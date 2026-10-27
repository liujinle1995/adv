package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.view.WindowManager;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


/**
 * Created by Milk on 4/6/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IWindowSessionProxy extends BinderInvocationStub {
    public static final String TAG = "WindowSessionStub";

    private IInterface mSession;

    public IWindowSessionProxy(IInterface session) {
        super(session.asBinder());
        mSession = session;
    }

    /**
     * 当前还在显示的 Dialog / Popup 窗口数量。
     *
     * 用途：判断「应用是不是正等用户操作弹窗」。
     * 典型场景：红果首启隐私弹窗 —— 启动页补救逻辑若无脑反射调 x2()，
     * 会在弹窗存在时把启动页 UI 抢着建出来，与弹窗状态互相覆盖，
     * 最终弹窗被自己 dismiss（走「不同意」分支）而应用直接退出。
     * 有弹窗时应当完全不干预，交给用户点。
     */
    public static final java.util.concurrent.atomic.AtomicInteger DIALOG_WINDOWS =
            new java.util.concurrent.atomic.AtomicInteger();

    public static boolean hasVisibleDialog() {
        return DIALOG_WINDOWS.get() > 0;
    }

    @Override
    protected Object getWho() {
        return mSession;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object getProxyInvocation() {
        return super.getProxyInvocation();
    }

    @ProxyMethod("addToDisplay")
    public static class AddToDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String desc = "";
            boolean isDialog = false;
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) arg;
                    desc = "type=" + lp.type + " w=" + lp.width + " h=" + lp.height
                            + " token=" + (lp.token != null) + " title=" + lp.getTitle();
                    // TYPE_APPLICATION(2) 及 2000–2999 的子窗口都是 Dialog/Popup 这一层；
                    // Activity 自己的窗口是 TYPE_BASE_APPLICATION(1)。
                    isDialog = lp.type == 2 || (lp.type >= 2000 && lp.type < 3000);
                    lp.packageName = BlackBoxCore.getHostPkg();
                }
            }
            // addToDisplay 的返回值就是 WMS 的状态码：0 表示窗口添加成功，负数表示被拒。
            // 这是判断"窗口为什么 0x0 / 没有 surface"最直接的证据。
            try {
                Object result = method.invoke(who, args);
                top.niunaijun.blackbox.utils.DiagLog.log(
                        "WMS", "addToDisplay -> " + result + " | " + desc);
                if (isDialog && Integer.valueOf(0).equals(result)) {
                    DIALOG_WINDOWS.incrementAndGet();
                }
                return result;
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log(
                        "WMS", "addToDisplay THROW " + t.getClass().getSimpleName()
                                + ":" + t.getMessage() + " | " + desc);
                throw t;
            }
        }
    }

    @ProxyMethod("addToDisplayAsUser")
    public static class AddToDisplayAsUser extends AddToDisplay {
    }

    /**
     * 记录窗口被移除的时刻与调用栈。
     *
     * 虚拟应用里的 Dialog（例如红果的首启隐私弹窗）加窗成功、却在一两秒后消失，
     * 应用就一直停在启动页等用户点「同意」。这里把移除时的调用栈打出来，
     * 才能区分是「应用自己 dismiss」还是「系统侧把窗口摘掉」。
     */
    @ProxyMethod("remove")
    public static class Remove extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            StringBuilder sb = new StringBuilder("remove <-");
            try {
                StackTraceElement[] st = new Throwable().getStackTrace();
                int shown = 0;
                for (StackTraceElement e : st) {
                    String cls = e.getClassName();
                    if (cls.startsWith("top.niunaijun.blackbox.fake")) {
                        continue;
                    }
                    // 跳过反射/代理自身的中间帧，否则真正的业务调用栈会被挤掉
                    if (cls.startsWith("java.lang.reflect.")
                            || cls.startsWith("com.android.internal.os")
                            || cls.startsWith("jdk.internal.reflect")) {
                        continue;
                    }
                    sb.append("\n    at ").append(e);
                    if (++shown >= 26) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            Object result = method.invoke(who, args);
            // remove 不携带窗口类型，只能按「有就减」处理；减到 0 为止。
            // 误差只可能让计数提前归零（Activity 窗口先被移除时），
            // 而那种时刻 Activity 本身正在销毁，已不需要「弹窗是否可见」的判断。
            if (DIALOG_WINDOWS.get() > 0) {
                DIALOG_WINDOWS.decrementAndGet();
            }
            top.niunaijun.blackbox.utils.DiagLog.log("WMS", sb.toString());
            return result;
        }
    }
}
