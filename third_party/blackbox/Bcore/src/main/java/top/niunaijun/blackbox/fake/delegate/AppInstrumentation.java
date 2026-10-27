package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    /** 便于后续诊断直接取 TTExecutors 的静态线程池做状态快照。 */
    private static volatile Class<?> sTTExecutors;
    private static volatile boolean diagPollerStarted;
    /**
     * 目标应用自己的 Application（`MuteApplicationStub` 之类）。
     *
     * 注意：同一个进程里 callApplicationOnCreate 会被调用两次——先宿主 AdvApp，
     * 再虚拟应用的 Application。诊断必须用后者（它的 ClassLoader 才是应用自己的
     * NewMiraClassloader），否则连应用自己的类都查不到。
     */
    private static volatile Application sTargetApp;
    /** View 树里最后看到的图片地址，供 TTNet 直连探针复用。 */
    private static volatile String sLastDraweeUri;
    /** 当前可见的虚拟 Activity，用来遍历它的 View 树做取证。 */
    private static volatile Activity sTopActivity;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "injectHook current=" + (mInstrumentation == null ? "null"
                            : mInstrumentation.getClass().getName())
                            + " pid=" + android.os.Process.myPid());
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) {
                return;
            }
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
            // 写入后立刻回读：字段被改名/不可写时 set 会静默失败，
            // 结果就是系统继续用默认 Instrumentation，虚拟 Activity 根本不会被创建。
            Instrumentation after = getCurrInstrumentation();
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "injectHook after=" + (after == null ? "null" : after.getClass().getName()));
        } catch (Throwable e) {
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "injectHook FAIL " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                "checkActivity " + activity.getClass().getName()
                        + " pid=" + android.os.Process.myPid());
        probeSafeMode(activity);
        startContentWatch(activity);
        autoRecoverSplash(activity);        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        // 按目标应用自己声明的方向设置（不要在大屏上强行覆盖：
        // 实测覆盖成 UNSPECIFIED 并不能解决黑屏，反而会让竖屏应用在平板上横屏显示）。
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);
        BActivityThread.currentActivityThread().loadXposed(context);
        return super.newApplication(cl, className, context);
    }

    /**
     * 内容视图观测。
     *
     * 启动页"白屏/只有主题背景"的判据是 `android.R.id.content` 有没有子 View：
     * 应用把 setContentView 放在启动页自己的异步分支里（红果是 SplashActivity.x2()），
     * 一旦那条分支被跳过或中途抛异常，content 就永远是 0 个子 View。
     *
     * 这里每 2 秒打一次，持续 60 秒：用户点完隐私弹窗后 content 是否从 0 变成 N，
     * 能直接区分"流程没跑"和"跑了但没画出来"。
     */
    private static void startContentWatch(final Activity activity) {
        try {
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final String name = activity.getClass().getSimpleName();
            // 只在「内容区还是空」时需要盯：一旦有子 View 再连续观察一次就收工，
            // 否则每个 Activity 90 次 × 多个 Activity 会把日志刷成几 MB（实测一天 5.4 MB）。
            handler.post(new Runnable() {
                private int tick;
                private int nonEmptyStreak;

                @Override
                public void run() {
                    tick++;
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        int contentChildren = -1;
                        try {
                            android.view.View content = activity.findViewById(android.R.id.content);
                            if (content instanceof android.view.ViewGroup) {
                                contentChildren = ((android.view.ViewGroup) content).getChildCount();
                            }
                        } catch (Throwable ignored) {
                        }
                        boolean report = contentChildren <= 0 || nonEmptyStreak < 1;
                        if (contentChildren > 0) {
                            nonEmptyStreak++;
                        }
                        if (report) {
                            top.niunaijun.blackbox.utils.DiagLog.log("Life",
                                    "watch " + name + " contentChildren=" + contentChildren
                                            + " t=" + (tick * 2) + "s");
                        }
                        if (nonEmptyStreak >= 2) {
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (tick < 30) {
                        handler.postDelayed(this, 2000L);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 启动页卡白屏的补救/取证。
     *
     * 红果（7.2.3.65）的 SplashActivity 只在两个时机做 UI 初始化：
     * `j2()` 里隐私已确认时直接调 `x2()`，或隐私弹窗点「同意」后的回调 `l2()` 里调 `x2()`。
     * 实测在沙箱中弹窗点「同意」只把窗口关掉、回调没有触发，于是 `x2()` 从未执行、
     * `setContentView` 从未被调用，界面永远停在主题背景（content 子 View 数为 0）。
     *
     * 这里在 Activity 创建 15 秒后（用户即便点了同意也早已完成）检查一次：
     * 内容区仍为空就反射调用 `x2()`，既能把界面救回来，也能把异常原样记进日志。
     */
    private static void autoRecoverSplash(final Activity activity) {
        try {
            final String cls = activity.getClass().getName();
            if (!cls.contains("SplashActivity")) return;
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

            // 第 1 步：轮询等 RPC 就绪后再补调 x2()。
            // 固定延时不可靠 —— 初始化可能 1 秒完成，也可能 10 秒；而且 RPC 没就绪时
            // x2() 必然抛 `had you called RpcService.init`，等于白跑一次。
            new SplashRecovery(activity, handler, cls).start();

            // 第 2 步（10s）：启动页还在（没有 finish，说明它不打算跳转）→
            // 直接启动应用的主界面 Activity。这类应用的主界面通常可以独立启动，
            // 启动页只是在等一个永远不会完成的初始化回调。
            // 10s 的依据：红果正常启动页 1–3s 就 finish，10s 后还在就是真卡住了；
            // 原先是 25s，用户要干等 25 秒才看到主界面（真机实测 16:46:26 才 fallback）。
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                                    "splash finished, skip fallback");
                            return;
                        }
                        String main = guessMainActivity(activity, cls);
                        if (main == null) {
                            top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                                    "no candidate main activity, give up");
                            return;
                        }
                        android.content.Intent intent = new android.content.Intent(
                                android.content.Intent.ACTION_MAIN);
                        intent.setClassName(activity.getPackageName(), main);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                        top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                                "fallback launched main activity: " + main);
                    } catch (Throwable t) {
                        logRecoverThrowable("fallback main", t);
                    }
                }
            }, 10000L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 启动页补救：每秒检查一次，等 RPC 就绪后再调 `x2()`，最多试 3 次。
     * 内容区一旦有子 View 就收工。
     */
    private static final class SplashRecovery implements Runnable {
        private final Activity activity;
        private final android.os.Handler handler;
        private final String cls;
        private int ticks;
        private int attempts;

        SplashRecovery(Activity activity, android.os.Handler handler, String cls) {
            this.activity = activity;
            this.handler = handler;
            this.cls = cls;
        }

        void start() {
            handler.postDelayed(this, 3000L);
        }

        @Override
        public void run() {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Recover", "splash gone, stop watching");
                    return;
                }
                int content = contentCount(activity);
                if (content > 0) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                            "splash rendered, content=" + content + " t=" + (ticks + 3) + "s");
                    return;
                }
                // 有 Dialog 正在显示（典型：首启隐私弹窗）→ 应用在等用户点，绝不干预。
                // 反例（真机实测）：清数据后弹窗已弹出，补救逻辑仍反射调 x2()，
                // 把启动页 UI 抢着建出来，与弹窗互相覆盖，最后弹窗自己 dismiss
                // 走的是「不同意」分支（栈 u0.T → AbsDialog.dismiss），应用直接退出。
                if (top.niunaijun.blackbox.fake.service.IWindowSessionProxy.hasVisibleDialog()) {
                    ticks++;
                    if (ticks <= 25) {
                        handler.postDelayed(this, 1000L);
                    } else {
                        top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                                "x2 recovery gave up: dialog still visible t=" + ticks + "s");
                    }
                    return;
                }
                boolean ready = isRpcReady(activity);
                if (ready && attempts < 3) {
                    attempts++;
                    invokeX2();
                }
                ticks++;
                if (ticks <= 25) {
                    handler.postDelayed(this, 1000L);
                } else {
                    top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                            "x2 recovery gave up, rpcReady=" + ready + " content=" + content);
                }
            } catch (Throwable ignored) {
            }
        }

        private void invokeX2() {
            try {
                top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                        "try x2() #" + attempts + " rpcReady=true content=" + contentCount(activity));
                Class<?> splash = activity.getClassLoader().loadClass(cls);
                java.lang.reflect.Method x2 = splash.getDeclaredMethod("x2");
                x2.setAccessible(true);
                x2.invoke(activity);
                top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                        "x2() OK, content=" + contentCount(activity));
            } catch (Throwable t) {
                logRecoverThrowable("x2()", t);
            }
        }
    }

    private static int contentCount(Activity activity) {
        try {
            android.view.View content = activity.findViewById(android.R.id.content);
            if (content instanceof android.view.ViewGroup) {
                return ((android.view.ViewGroup) content).getChildCount();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void logRecoverThrowable(String phase, Throwable t) {
        try {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            java.io.StringWriter sw = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(sw));
            top.niunaijun.blackbox.utils.DiagLog.log("Recover", phase + " THREW: " + sw);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 猜一个「主界面」Activity：排除启动页本身、只保留类名里带 Main 的。
     *
     * 不用 launcher 入口，因为启动页往往就是 launcher；也不猜别的名字，
     * 猜错的代价（起一个需要参数、会崩的页面）比"多等一会儿"大得多。
     */
    private static String guessMainActivity(Activity activity, String splashClass) {
        String pkg = activity.getPackageName();
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES);
            int total = pi.activities == null ? -1 : pi.activities.length;
            top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                    "pm activities=" + total);
            if (pi.activities != null) {
                String fallback = null;
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    String name = ai.name;
                    if (name == null || name.equals(splashClass)) continue;
                    if (name.contains("Splash") || name.contains("splash")) continue;
                    // 不强制要求 exported：沙箱内启动走的是引擎自己的 Activity 代理，
                    // 应用私有 Activity 同样可以拉起（红果的主界面就不是 exported）。
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    if (simple.startsWith("Main")) return name;
                    if (fallback == null && simple.contains("Main")) fallback = name;
                }
                if (fallback != null) return fallback;
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Recover",
                    "pm activities failed: " + t.getClass().getSimpleName());
        }
        return knownMainActivity(pkg);
    }

    /**
     * 已知应用的「主界面」兜底表。
     *
     * 沙箱里的 PackageManager 常常拿不到 GET_ACTIVITIES 的完整列表（红果实测返回空），
     * 但这类加固应用的启动页又偏偏卡在自家初始化上下不来。这里给已验证过的应用
     * 直接写死入口；没有命中的包名走通用规则，不猜。
     */
    private static String knownMainActivity(String pkg) {
        if (pkg == null) return null;
        if (pkg.startsWith("com.phoenix.read")) {
            return "com.dragon.read.pages.main.MainFragmentActivity";
        }
        return null;
    }

    /**
     * 唤醒字节系公共库的线程池（TTExecutors）。
     *
     * 实测该池在虚拟进程里"只收不做"：线程已创建、队列空、completed=0，
     * 提交的任务永远不执行。推论是它被置成了暂停态（这类池通常在 App 启动期间
     * 暂停后台任务，等某个初始化完成后再恢复），而恢复信号依赖的初始化本身
     * 又排在池里，于是互相等待。
     *
     * 这里在应用 onCreate 之前把池的 pause/resume 类开关拨回"运行"，
     * 并把池结构与静态字段打进日志，便于下一轮确诊。
     */
    private static void wakeExecutors(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            if (cl == null) return;
            Class<?> c;
            try {
                c = cl.loadClass("com.bytedance.common.utility.concurrent.TTExecutors");
            } catch (Throwable notFound) {
                return;
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "classloader=" + cl.getClass().getSimpleName() + " pkg=" + app.getPackageName());
            sTTExecutors = c;

            // 1) 无参静态方法里找 resume/start/wake 之类，逐个尝试
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                String n = m.getName().toLowerCase();
                if (!(n.contains("resume") || n.equals("start") || n.contains("wake")
                        || n.contains("enable") || n.contains("active"))) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(null);
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "invoke " + m.getName() + "() -> " + r);
                } catch (Throwable t) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "invoke " + m.getName() + " failed: " + t.getClass().getSimpleName());
                }
            }

            // 2) 打印静态字段，看池到底被谁持有、是否带暂停标志
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "field " + java.lang.reflect.Modifier.toString(f.getModifiers())
                                    + " " + f.getType().getSimpleName() + " " + f.getName()
                                    + " = " + (v == null ? "null" : v.getClass().getSimpleName()));
                } catch (Throwable ignored) {
                }
            }

            // 3) 关键：把"只收不做"的池换成真正会跑任务的池
            replacePool(c, "sNormalThreadPool", "adv-normal");
            replacePool(c, "sIOThreadPool", "adv-io");
            replacePool(c, "sBackgroundThreadPool", "adv-bg");
            replacePool(c, "sCPUThreadPool", "adv-cpu");
            // sSerialThreadPool 是字节自带的串行执行器（类型不是标准 ThreadPoolExecutor），
            // 图片/播放器那条链路经常把"最后一步"派到串行队列里；它不跑任务时表现为
            // socket 已建立、请求却永远发不出去。换成标准单线程串行执行器，语义一致。
            replaceSerialPool(c, "sSerialThreadPool", "adv-serial");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "wake skipped: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 用一个能正常执行任务的线程池替换掉指定静态字段。
     *
     * 原池在虚拟进程里的表现：线程数量正常、队列为空、完成任务数恒为 0，
     * 提交进去的任务永远不执行——应用所有异步初始化（含 RPC 框架）因此全部丢失，
     * 表现就是「启动页白屏 / 主界面一进来就抛 RpcService.init 未调用」。
     */
    private static void replacePool(Class<?> ttExecutors, String fieldName, String threadPrefix) {
        try {
            java.lang.reflect.Field f = ttExecutors.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object old = f.get(null);
            if (old == null) return;
            if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) return;

            final java.util.concurrent.atomic.AtomicInteger seq =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, threadPrefix + "-" + seq.incrementAndGet());
                            t.setDaemon(false);
                            return t;
                        }
                    });
            f.set(null, pool);
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "replaced " + fieldName + ": " + old.getClass().getSimpleName()
                            + " -> " + pool.getClass().getSimpleName());
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "replace " + fieldName + " failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 把字节自带的串行执行器换成标准单线程执行器（FIFO，语义等价）。
     *
     * 与 {@link #replacePool} 分开是因为这类字段的类型不是 ThreadPoolExecutor，
     * 直接用他家的实现时"任务进得去、不出来"，且从外部完全看不到队列长度。
     */
    private static void replaceSerialPool(Class<?> ttExecutors, String fieldName, String threadName) {
        try {
            java.lang.reflect.Field f = ttExecutors.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object old = f.get(null);
            if (old == null) {
                return;
            }
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadExecutor(
                            new java.util.concurrent.ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread t = new Thread(r, threadName);
                                    t.setDaemon(false);
                                    return t;
                                }
                            });
            f.set(null, pool);
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "replaced " + fieldName + ": " + old.getClass().getSimpleName()
                            + " -> single-serial");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "replace " + fieldName + " failed: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
        }
    }

    /**
     * 预初始化 Keva（字节的 KV 存储库）。
     *
     * 实测主线程在 Application.onCreate 里会走到
     * `KevaFuture.getBoolean → FutureTask.get`，等 Keva 的首次初始化。
     * 而 Keva 的初始化是懒加载 + 依赖共享线程池，在沙箱里经常迟迟不完成，
     * 主线程于是在这里死等（打断一次后会卡在下一个 Keva 调用上）。
     *
     * 做法：在**后台线程**里先触发一次初始化并等它完成。
     * 关键是必须在主线程之外触发——如果初始化任务需要主线程处理消息，
     * 主线程此时是空闲的，能正常处理；等它完成后主线程再进 onCreate 就不会阻塞。
     */
    private static void prewarmKeva(Application app) {
        try {
            final ClassLoader cl = app.getClassLoader();
            if (cl == null) return;
            // 红果不一定用 Keva；类不存在就跳过
            try {
                cl.loadClass("com.bytedance.keva.Keva");
            } catch (Throwable notFound) {
                return;
            }
            final long startedAt = System.currentTimeMillis();
            Thread warmer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> keva = cl.loadClass("com.bytedance.keva.Keva");
                        keva.getMethod("getRepo", String.class).invoke(null, "adv_prewarm");
                    } catch (Throwable t) {
                        Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                                && t.getCause() != null) ? t.getCause() : t;
                        top.niunaijun.blackbox.utils.DiagLog.log("Keva",
                                "prewarm failed: " + cause.getClass().getName() + " " + cause.getMessage());
                    }
                }
            }, "adv-keva-warm");
            warmer.setDaemon(true);
            warmer.start();
            warmer.join(4000L);
            top.niunaijun.blackbox.utils.DiagLog.log("Keva",
                    "prewarm joined alive=" + warmer.isAlive()
                            + " cost=" + (System.currentTimeMillis() - startedAt) + "ms");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Keva",
                    "prewarm skipped: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 检查并修复字节系公共库的线程池（TTExecutors）。
     *
     * 现象：红果第二次启动时主线程卡在
     * `lego.InitScheduler.startDispatchScopeTask → launch.r.h → CountDownLatch.await`，
     * 而它等的异步任务提交给 `TTExecutors.getNormalExecutor()`，线程 dump 里
     * 一个 TT 线程都没有——任务进了队列却没人消费，主线程于是永久等待。
     *
     * 这里在应用 onCreate 之前探测线程池的实际状态（核心线程数/池大小/队列长度），
     * 并在明显未工作时尝试把核心线程拉起来。只做保守修复，失败仅记录。
     */
    private static void probeAndFixExecutors(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            if (cl == null) return;
            Class<?> c = cl.loadClass("com.bytedance.common.utility.concurrent.TTExecutors");
            Object ex = c.getMethod("getNormalExecutor").invoke(null);
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "getNormalExecutor -> " + (ex == null ? "null" : ex.getClass().getName()));
            if (!(ex instanceof java.util.concurrent.ThreadPoolExecutor)) {
                return;
            }
            final java.util.concurrent.ThreadPoolExecutor pool =
                    (java.util.concurrent.ThreadPoolExecutor) ex;
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "pool core=" + pool.getCorePoolSize()
                            + " max=" + pool.getMaximumPoolSize()
                            + " size=" + pool.getPoolSize()
                            + " active=" + pool.getActiveCount()
                            + " queue=" + pool.getQueue().size()
                            + " factory=" + (pool.getThreadFactory() == null
                            ? "null" : pool.getThreadFactory().getClass().getSimpleName()));

            // 池里连一个线程都没有，说明线程工厂/线程创建环节出了问题：
            // 任务只会堆在队列里。主动预热线程，能救回来最好，救不回来也留证据。
            if (pool.getPoolSize() == 0) {
                try {
                    if (pool.getCorePoolSize() <= 0) pool.setCorePoolSize(2);
                    int started = pool.prestartAllCoreThreads();
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "prestartAllCoreThreads -> " + started
                                    + " size=" + pool.getPoolSize());
                } catch (Throwable t) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "prestart failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
                }
            }

            // 用真实任务验证消费链路：1.5 秒后还没执行就说明这个池对外"只收不做"
            final java.util.concurrent.atomic.AtomicBoolean ran =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        ran.set(true);
                        top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                                "probe task RAN on " + Thread.currentThread().getName());
                    }
                });
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log("Exec", "probe execute threw: " + t);
            }
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                            "probe task executed=" + ran.get()
                                    + " size=" + pool.getPoolSize()
                                    + " completed=" + pool.getCompletedTaskCount());
                }
            }, "adv-exec-probe");
            watcher.setDaemon(true);
            watcher.start();
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Exec",
                    "probe skipped: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 安全模式探针。
     *
     * 红果（字节系）在 Application 侧有一套 SafeModeController：
     * 连续「启动即崩」达到阈值后，启动页会直接跳安全模式页而**不做任何 UI 初始化**，
     * 表现就是一直停在主题背景。它的判据是私有实现，只能反射读回来。
     * 类名/方法名按 7.2.3.65 反编译结果写死，其他包名一律跳过。
     */
    private static void probeSafeMode(android.content.Context ctx) {
        try {
            String pkg = ctx.getPackageName();
            if (pkg == null || !pkg.startsWith("com.phoenix.read")) return;
            // 必须用目标应用自己的 ClassLoader：虚拟应用的类不在宿主的 ClassLoader 里
            ClassLoader cl = ctx.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            Class<?> c = cl.loadClass("com.dragon.read.app.h1");
            Object inst = c.getMethod("f").invoke(null);
            Object needSafeMode = c.getMethod("n").invoke(inst);
            top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                    "h1.n()(needSafeMode)=" + needSafeMode);
            try {
                java.lang.reflect.Method d = c.getDeclaredMethod("d");
                d.setAccessible(true);
                top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                        "comboCrashCount=" + d.invoke(inst));
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                        "count read failed: " + t.getClass().getSimpleName());
            }
            try {
                // 隐私是否真的被记录：false 说明启动页永远停在弹窗前，
                // true 则说明「同意」已生效，问题在同意之后的初始化分支。
                Class<?> pm = cl.loadClass("com.dragon.read.app.PrivacyMgr");
                Object pmInst = pm.getMethod("inst").invoke(null);
                Object confirmed = pm.getMethod("hasConfirmedOrBasicFunctionEnabled").invoke(pmInst);
                top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                        "privacyConfirmed=" + confirmed);
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                        "privacy probe failed: " + t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                    "probe skipped: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        probeShortSeries(activity, activity == null ? null : activity.getIntent(), "onCreate");
        try {
            super.callActivityOnCreate(activity, icicle, persistentState);
        } catch (Throwable t) {
            logLifecycleCrash(activity, "onCreate", t);
            throw t;
        }
        logDecor(activity, "onCreate");
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        probeShortSeries(activity, activity == null ? null : activity.getIntent(), "onCreate");
        try {
            super.callActivityOnCreate(activity, icicle);
        } catch (Throwable t) {
            logLifecycleCrash(activity, "onCreate", t);
            throw t;
        }
        logDecor(activity, "onCreate");
    }

    /**
     * 把 Activity 生命周期里抛出的异常写进诊断日志再原样抛出。
     *
     * ROM 把 logcat 屏蔽了，未捕获异常在设备上完全不可见；而这类异常
     * （例如启动页在 setContentView 之前崩）恰恰是"白屏不跳转"的真正原因。
     * 只记录、不改变行为：异常照旧向上抛，崩溃语义不变。
     */
    private static void logLifecycleCrash(Activity activity, String phase, Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            top.niunaijun.blackbox.utils.DiagLog.log("Crash",
                    phase + " THREW in " + activity.getClass().getName() + "\n" + sw);
        } catch (Throwable ignored) {
        }
    }

    // 启动页卡死时，「应用到底收到了哪些生命周期回调」是最直接的判据：
    // 只有 onCreate 没有 onResume，说明跳转条件（首帧/resume 回调）永远不成立。
    @Override
    public void callActivityOnStart(Activity activity) {
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onStart " + activity.getClass().getSimpleName());
        super.callActivityOnStart(activity);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        probeShortSeriesState(activity);
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onResume " + activity.getClass().getSimpleName());
        sTopActivity = activity;
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPostCreate(Activity activity, Bundle savedInstanceState) {
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onPostCreate " + activity.getClass().getSimpleName());
        super.callActivityOnPostCreate(activity, savedInstanceState);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onPause " + activity.getClass().getSimpleName());
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onStop " + activity.getClass().getSimpleName());
        super.callActivityOnStop(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onDestroy " + activity.getClass().getSimpleName());
        super.callActivityOnDestroy(activity);
    }

    /** 记下内容视图的规模：启动页若只有主题背景、内容区为空，说明它还在等初始化。 */
    private static void logDecor(Activity activity, String phase) {
        try {
            android.view.View decor = activity.getWindow() == null
                    ? null : activity.getWindow().peekDecorView();
            int decorChildren = decor instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) decor).getChildCount() : -1;
            int contentChildren = -1;
            try {
                android.view.View content = activity.findViewById(android.R.id.content);
                if (content instanceof android.view.ViewGroup) {
                    contentChildren = ((android.view.ViewGroup) content).getChildCount();
                }
            } catch (Throwable ignored) {
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Life",
                    phase + " done " + activity.getClass().getSimpleName()
                            + " decorChildren=" + decorChildren
                            + " contentChildren=" + contentChildren);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                "callApplicationOnCreate " + (app == null ? "null" : app.getClass().getName())
                        + " pid=" + android.os.Process.myPid());
        // 必须早于应用自己的 onCreate：安全模式计数决定它走哪条初始化分支，
        // 一旦进了「安全模式」分支，主线程会 await 一个永远不完成的 latch（实测卡死）。
        if (app != null) {
            probeSafeMode(app);
            clearSafeModeIfRequested(app);
            wakeExecutors(app);
            // 必须在应用自己的 onCreate 之前配好 Keva 的执行器：
            // KevaImpl.<clinit> 首次执行时若 KevaBuilder.mExecutor 非空会直接采用，
            // 否则兜底建一个在沙箱里不消费任务的 PThreadPoolExecutorDelegate，
            // 导致 KevaFuture.get() 永久阻塞（ttnet 的 ClientKeyManager 卡死）。
            preConfigureKeva(app);
        }
        dumpMainStackIfStuck(app);
        checkHCallback();
        startUnstick(mainThread());
        if (app != null) {
            // 同一个进程会先拿到宿主 AdvApp、再拿到目标应用的 Application；
            // 诊断一律用后者（ClassLoader 才对）。
            String cn = app.getClass().getName();
            if (!"com.adv.runner.AdvApp".equals(cn)) {
                sTargetApp = app;
            }
            startDiagPoller(app);
        }
        try {
            super.callApplicationOnCreate(app);
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                top.niunaijun.blackbox.utils.DiagLog.log("Crash",
                        "Application.onCreate THREW\n" + sw);
            } catch (Throwable ignored) {
            }
            throw t;
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Inst", "application onCreate RETURNED");
        advAppCreateDone = true;
        if (app != null) ensureRpcInit(app);
    }

    /**
     * 在应用 onCreate 之前，给 KevaBuilder 塞一个能跑任务的 executor。
     *
     * KevaImpl.<clinit>（字节的 KV 库，首次使用时才初始化）会：
     *   getInstance() 拿 builder → clearInstance() 清空静态实例 →
     *   读 builder.mExecutor，非空则赋给 sExecutor，否则兜底建 PThreadPoolExecutorDelegate。
     *
     * 那个兜底线程池在沙箱里不消费任务，会让 KevaFuture.get() 永久阻塞。所以这里
     * 抢在应用代码触发 KevaImpl 之前，先把 builder 配好。
     */
    private static void preConfigureKeva(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> kb = Class.forName("com.bytedance.keva.KevaBuilder", false, cl);
            Object builder = kb.getMethod("getInstance").invoke(null);
            if (builder == null) {
                builder = kb.getDeclaredConstructor().newInstance();
            }
            java.util.concurrent.ExecutorService ex =
                    java.util.concurrent.Executors.newCachedThreadPool();
            try {
                kb.getMethod("setExecutor", java.util.concurrent.Executor.class)
                        .invoke(builder, ex);
            } catch (Throwable ignored) {
                java.lang.reflect.Field mf = kb.getDeclaredField("mExecutor");
                mf.setAccessible(true);
                mf.set(builder, ex);
            }
            // 塞回静态实例，供 KevaImpl.<clinit> 的 getInstance() 取用
            java.lang.reflect.Field mi = kb.getDeclaredField("mInstance");
            mi.setAccessible(true);
            mi.set(null, builder);
            top.niunaijun.blackbox.utils.DiagLog.log("Keva",
                    "pre-configured builder executor");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Keva",
                    "pre-config failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 补一次字节 RPC 框架的初始化。
     *
     * 反编译（classes26.dex）确认：真正被混淆掉的入口是
     * <pre>
     *   com.bytedance.rpc.m.i(Landroid/app/Application;Lcom/bytedance/rpc/c;)V
     * </pre>
     * 初始化完成后 `m.a`（com.bytedance.rpc.d）非空；`m.j()` 返回 true。
     * 未初始化时所有门面方法都会走 `m.n()` 抛
     * `had you called RpcService.init(Application,RpcConfig)`。
     *
     * 应用自己的初始化在 `com.dragon.read.app.launch.task.p2.e(Application)`，
     * 由 `m0.n()` 通过 `launch.n.x("RpcInitializer", ...)` 投递到
     * TTExecutors 的普通线程池；沙箱里这个池只收不做，所以永远没跑。
     * 这里直接按原顺序补调一次，失败再降级到最小初始化。
     */
    private static void ensureRpcInit(final Application app) {
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    // 顺序很重要：先网络层，再装 HTTP 客户端，再账号/图片/RPC
                    initHttpStack(app);
                    initRpcNow(app);
                    installRequestQueue(app);
                }
            }, "adv-boot-init");
            t.setDaemon(true);
            t.start();
            // 必须等它做完再让 Application.onCreate 返回：应用自己的首个网络请求
            // 就在启动页/主界面上，早于这里就会失败并把界面钉死在「网络出错」。
            // 实测整段 <100ms；给 3 秒上限，万一卡住也不至于卡死启动。
            t.join(3000L);
            top.niunaijun.blackbox.utils.DiagLog.log("Boot",
                    "init joined=" + !t.isAlive());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 手工完成 `com.dragon.read.base.http.e.h(provider)` 里除 Cronet 之外的全部步骤。
     *
     * 原流程 d(provider) 的顺序是：
     * <pre>
     *   a = provider → f() → n(app) → setCronetDepend → m(app, app) → l() → forceInitCronetKernel()
     * </pre>
     * 最后一步在沙箱里会永久 BLOCKED（chromium 内核起不来），而且它握着 ttnet 的锁，
     * 一旦卡住所有网络请求都会跟着挂，界面就只显示「网络出错」。
     * 所以这里跳过 Cronet，只做前面的必需步骤（公共参数 / TTNetDepend / tryInitTTNet）。
     */
    /**
     * `e$b` 的实现类在各版本里混淆名不同：7.1.8.32 是 `p0$a`，7.2.3.65 是 `m0$a`。
     * 所以这里只把它当候选，真正判据是「谁能被 e$b 赋值」。
     */
    private static final String[] HTTP_PROVIDER_CANDIDATES = {
            "com.dragon.read.app.launch.task.p0$a",
            "com.dragon.read.app.launch.task.m0$a",
            "com.dragon.read.app.launch.task.n0$a",
            "com.dragon.read.app.launch.task.o0$a",
            "com.dragon.read.app.launch.task.q0$a",
            "com.dragon.read.app.launch.task.r0$a",
            "com.dragon.read.app.launch.task.k0$a",
            "com.dragon.read.app.launch.task.j0$a",
    };

    private static void initHttpStack(Application app) {
        final Class<?> e;
        final Class<?> providerBase;
        try {
            ClassLoader cl = app.getClassLoader();
            e = cl.loadClass("com.dragon.read.base.http.e");
            providerBase = cl.loadClass("com.dragon.read.base.http.e$b");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "http classes missing: " + t.getClass().getSimpleName());
            return;
        }
        ClassLoader cl = app.getClassLoader();

        logHttpState(e, "before");

        // 1) provider：应用自己的启动链可能已经设过，先用现成的。
        Object provider = getStatic(e, "a");
        if (provider == null) {
            Class<?> providerCls = findHttpProvider(cl, providerBase);
            if (providerCls == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "provider NOT FOUND, tried=" + java.util.Arrays.toString(HTTP_PROVIDER_CANDIDATES));
                return;
            }
            provider = newHttpProvider(providerCls, app);
            if (provider == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "provider ctor failed: " + providerCls.getName());
                return;
            }
            setStatic(e, "a", provider);
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "provider = " + providerCls.getName());
        } else {
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "provider pre-set: " + provider.getClass().getName());
        }

        // 2) 按 e.d(b) 的原顺序补跑，只跳过最后一步 Cronet。
        //    每一步单独容错——旧版整段包在一个 try 里，第一行 ClassNotFound
        //    就把后面所有步骤（包括 tryInitTTNet）全废了。
        step(e, "f", new Class<?>[0], new Object[0]);
        step(e, "n", new Class<?>[]{android.content.Context.class}, new Object[]{app});
        setCronetDepend(cl);
        step(e, "m", new Class<?>[]{android.content.Context.class, Application.class},
                new Object[]{app, app});
        step(e, "l", new Class<?>[0], new Object[0]);

        // 3) d() 结尾会把就绪标志置 true 并回调观察者（这里只置标志，不回调，
        //    避免应用在 onActivityCreated 之前就跑业务逻辑）。
        setStatic(e, "b", Boolean.TRUE);

        logHttpState(e, "after");
    }

    private static Class<?> findHttpProvider(ClassLoader cl, Class<?> base) {
        for (String name : HTTP_PROVIDER_CANDIDATES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (!base.isAssignableFrom(c)) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    continue;
                }
                return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 内部类形如 `p0$a(p0, Application)`，外层自己 new 一个再传进去。 */
    private static Object newHttpProvider(Class<?> providerCls, Application app) {
        Class<?> outerCls = providerCls.getDeclaringClass();
        if (outerCls == null) {
            outerCls = providerCls.getEnclosingClass();
        }
        Object outer = null;
        if (outerCls != null) {
            try {
                java.lang.reflect.Constructor<?> oc = outerCls.getDeclaredConstructor();
                oc.setAccessible(true);
                outer = oc.newInstance();
            } catch (Throwable ignored) {
            }
        }
        if (outer != null) {
            try {
                java.lang.reflect.Constructor<?> c =
                        providerCls.getDeclaredConstructor(outerCls, Application.class);
                c.setAccessible(true);
                return c.newInstance(outer, app);
            } catch (Throwable ignored) {
            }
        }
        try {
            java.lang.reflect.Constructor<?> c = providerCls.getDeclaredConstructor(Application.class);
            c.setAccessible(true);
            return c.newInstance(app);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Constructor<?> c = providerCls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** `TTNetInit.setCronetDepend(com.dragon.read.base.http.b.c)`，慢不了也不联网，照样补上。 */
    private static void setCronetDepend(ClassLoader cl) {
        try {
            Class<?> b = cl.loadClass("com.dragon.read.base.http.b");
            Object depend = b.getField("c").get(null);
            if (depend == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Http", "step cronetDepend skipped (null)");
                return;
            }
            Class<?> ttnetInit = cl.loadClass("com.bytedance.ttnet.TTNetInit");
            for (java.lang.reflect.Method m : ttnetInit.getMethods()) {
                if (!m.getName().equals("setCronetDepend") || m.getParameterCount() != 1) {
                    continue;
                }
                m.setAccessible(true);
                m.invoke(null, depend);
                top.niunaijun.blackbox.utils.DiagLog.log("Http", "step cronetDepend ok");
                return;
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Http", "step cronetDepend: no method");
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "step cronetDepend FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    private static void step(Class<?> cls, String name, Class<?>[] types, Object[] args) {
        long t0 = System.currentTimeMillis();
        try {
            java.lang.reflect.Method m = cls.getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(null, args);
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "step " + name + " ok " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "step " + name + " FAIL " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    private static Object getStatic(Class<?> cls, String name) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setStatic(Class<?> cls, String name, Object value) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    /** a=provider / b=就绪标志 / d=TTNetDepend / e=tryInitTTNet 已执行。 */
    private static void logHttpState(Class<?> e, String tag) {
        Object a = getStatic(e, "a");
        Object d = getStatic(e, "d");
        Object c = getStatic(e, "c");
        int cbs = -1;
        if (c instanceof java.util.Collection) {
            cbs = ((java.util.Collection<?>) c).size();
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Http",
                "e[" + tag + "] provider=" + (a == null ? "null" : a.getClass().getSimpleName())
                        + " ttnetDepend=" + (d == null ? "null" : "set")
                        + " ready=" + getStatic(e, "b")
                        + " ttnetInit=" + getStatic(e, "e")
                        + " callbacks=" + cbs);
    }

    /** RPC 框架是否已完成初始化（`com.bytedance.rpc.m.j()`）。 */
    private static boolean isRpcReady(android.content.Context ctx) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            if (cl == null) return false;
            Class<?> m = cl.loadClass("com.bytedance.rpc.m");
            Object r = m.getMethod("j").invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void initRpcNow(Application app) {
        try {
            // 宿主自己的 Application 也会走这里（:black 等进程），它的 ClassLoader 里
            // 没有目标应用的类，先静默跳过，避免日志被 ClassNotFound 刷满。
            try {
                app.getClassLoader().loadClass("com.bytedance.rpc.m");
            } catch (Throwable notTarget) {
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc", "skip: target class not in loader");
                return;
            }
            // 0.2) 安装全局 HTTP 客户端。
            //      m0.n() 里这一步排在 cronet 之后，而 cronet 在沙箱里永远卡住，
            //      于是 NetworkClient 一直是空的 —— 所有请求都会立刻失败，
            //      主界面直接显示「网络出错，请点击重试」。
            try {
                ClassLoader cl = app.getClassLoader();
                Class<?> nc = cl.loadClass("com.bytedance.common.utility.NetworkClient");
                Object client = cl.loadClass("com.dragon.read.app.XsSSNetworkClient")
                        .getDeclaredConstructor().newInstance();
                // 请求级埋点：NetworkClient 是接口时，套一层代理，把每次请求的
                // URL / 结果长度 / 异常全部记下来。图片、播放器、接口都会经过它，
                // 这是唯一能看到"到底有没有发请求"的抓手。
                if (nc.isInterface()) {
                    try {
                        client = java.lang.reflect.Proxy.newProxyInstance(
                                cl, new Class<?>[]{nc}, new HttpTraceHandler(client));
                        top.niunaijun.blackbox.utils.DiagLog.log("Http", "NetworkClient wrapped (trace)");
                    } catch (Throwable t) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Http",
                                "wrap failed: " + t.getClass().getSimpleName());
                    }
                } else {
                    top.niunaijun.blackbox.utils.DiagLog.log("Http",
                            "NetworkClient is class, no trace wrapper");
                }
                boolean ok = false;
                for (java.lang.reflect.Method m : nc.getMethods()) {
                    if (!m.getName().equals("setDefault") || m.getParameterCount() != 1) continue;
                    m.setAccessible(true);
                    m.invoke(null, client);
                    ok = true;
                    break;
                }
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "NetworkClient.setDefault ok=" + ok + " client=" + client.getClass().getSimpleName());
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null) ? t.getCause() : t;
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "NetworkClient.setDefault failed: " + cause.getClass().getName()
                                + " " + cause.getMessage());
            }

            // 0) 补跑「账号」初始化。
            //    m0.n(Application) 里依次做 HttpServiceInit → Timon → TTAccount → Rpc，
            //    但实测它会 BLOCKED 在 TTNetInit.forceInitCronetKernel（Cronet 内核在
            //    沙箱里起不来），所以不能整段调用，只挑后续真正会崩的几步。
            if (!accountInitDone) {
                accountInitDone = true;
                try {
                    ClassLoader cl = app.getClassLoader();
                    Class<?> r3 = cl.loadClass("com.dragon.read.app.launch.task.r3");
                    Object inst = r3.getDeclaredConstructor().newInstance();
                    r3.getMethod("h", Application.class).invoke(inst, app);
                    top.niunaijun.blackbox.utils.DiagLog.log("Rpc", "r3.h() done (TTAccount)");
                } catch (Throwable t) {
                    Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                            && t.getCause() != null) ? t.getCause() : t;
                    top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                            "r3.h() failed: " + cause.getClass().getName() + " " + cause.getMessage());
                }
            }

            // 0.5) 图片加载（Fresco）。启动页/主界面都是 Compose + Fresco 套头像，
            //      未初始化时 Fresco.newDraweeControllerBuilder 直接 NPE 崩进程。
            try {
                ClassLoader cl = app.getClassLoader();
                Class<?> fresco = cl.loadClass("com.facebook.drawee.backends.pipeline.Fresco");
                if (!Boolean.TRUE.equals(fresco.getMethod("hasBeenInitialized").invoke(null))) {
                    Class<?> y = cl.loadClass("com.dragon.read.app.launch.task.y");
                    Object inst = y.getDeclaredConstructor().newInstance();
                    y.getMethod("k", Application.class).invoke(inst, app);
                    top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                            "y.k() done (Fresco), inited="
                                    + fresco.getMethod("hasBeenInitialized").invoke(null));
                }
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null) ? t.getCause() : t;
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                        "y.k() failed: " + cause.getClass().getName() + " " + cause.getMessage());
            }
            if (isRpcReady(app)) return;

            // 1) RPC 初始化任务
            try {
                ClassLoader cl = app.getClassLoader();
                Class<?> p2 = cl.loadClass("com.dragon.read.app.launch.task.p2");
                Object inst = p2.getDeclaredConstructor().newInstance();
                p2.getMethod("e", Application.class).invoke(inst, app);
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                        "p2.e() invoked, ready=" + isRpcReady(app));
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null) ? t.getCause() : t;
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                        "p2.e() failed: " + cause.getClass().getName() + " " + cause.getMessage());
            }

            // 探针：读 client_key_config 仓库，判断 KMS 密钥是否为空（空的请求头会被服务端拒）
            probeClientKey(app);
            if (isRpcReady(app)) return;

            // 2) 最后兜底：最小初始化，至少让门面方法不再抛「未初始化」
            try {
                ClassLoader cl = app.getClassLoader();
                Class<?> m = cl.loadClass("com.bytedance.rpc.m");
                Class<?> cfgCls = cl.loadClass("com.bytedance.rpc.c");
                Class<?> cfgBuilder = cl.loadClass("com.bytedance.rpc.c$b");

                Object b = m.getMethod("e").invoke(null);
                b = cfgBuilder.getMethod("m", String.class).invoke(b, "https://reading.snssdk.com/");
                b = cfgBuilder.getMethod("n", boolean.class).invoke(b, Boolean.TRUE);
                b = cfgBuilder.getMethod("h", String.class, String.class)
                        .invoke(b, "X-Xs-From-Web", "0");
                Object empty = java.lang.reflect.Array.newInstance(cfgCls, 0);
                Object cfg = cfgBuilder.getMethod("i", empty.getClass()).invoke(b, empty);

                m.getMethod("i", Application.class, cfgCls).invoke(null, app, cfg);
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                        "minimal init done, ready=" + isRpcReady(app));
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null) ? t.getCause() : t;
                top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                        "minimal init failed: " + cause.getClass().getName() + " " + cause.getMessage());
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Rpc",
                    "init crashed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** 探针：读 ttnet 的 client_key_config 仓库，看 KMS 密钥是否为空。 */
    private static void probeClientKey(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> keva = cl.loadClass("com.bytedance.keva.Keva");
            Object repo = keva.getMethod("getRepo", String.class, int.class)
                    .invoke(null, "ttnet_client_key_config", 1);
            if (repo == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Key", "repo null");
                return;
            }
            String ck = String.valueOf(repo.getClass().getMethod("getString",
                    String.class, String.class).invoke(repo, "client_key", ""));
            String kv = String.valueOf(repo.getClass().getMethod("getString",
                    String.class, String.class).invoke(repo, "kms_version", ""));
            String sid = String.valueOf(repo.getClass().getMethod("getString",
                    String.class, String.class).invoke(repo, "session_id", ""));
            top.niunaijun.blackbox.utils.DiagLog.log("Key",
                    "client_key.len=" + ck.length() + " kms=" + kv + " sid=" + sid);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Key",
                    "probe failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** 应用 Application.onCreate 是否已返回；打断守护据此收工。 */
    private static volatile boolean advAppCreateDone = false;

    /** TTAccount 只初始化一次（每个进程）。 */
    private static volatile boolean accountInitDone = false;

    private static Thread mainThread() {
        return android.os.Looper.getMainLooper().getThread();
    }

    /**
     * 「解锁守护」：应用程序初始化时，主线程会多次阻塞在应用自己的等待点上，
     * 这些等待依赖在沙箱里永远不完成的任务（Keva 初始化、TTExecutors 任务、
     * lego 的 CountDownLatch），实测就是卡死。
     *
     * 做法：主线程处于 WAITING 且栈停在这几类等待上时，反复 interrupt。
     * 这类等待点（FutureTask.get / CountDownLatch.await）都是可中断的，
     * 被中断后应用会走自己的 catch 分支继续往下跑——丢掉的只是那批异步初始化，
     * 换来的是界面能起来。onCreate 返回后守护线程立即收工，不影响后续运行。
     */
    private static void startUnstick(final Thread main) {
        if (main == null) return;
        Thread unstick = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 80; i++) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (advAppCreateDone) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                                "unstick 结束（onCreate 已返回，打断 " + i + " 次轮询）");
                        return;
                    }
                    Thread.State state = main.getState();
                    if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                        continue;
                    }
                    String point = stuckPoint(main.getStackTrace());
                    if (point == null) continue;
                    main.interrupt();
                    top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                            "unstick #" + (i + 1) + " 打断主线程，阻塞点=" + point);
                }
            }
        }, "adv-unstick");
        unstick.setDaemon(true);
        unstick.start();
    }

    /** 判断主线程是不是停在我们已知的、可安全打断的等待点上。 */
    private static String stuckPoint(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String cn = e.getClassName();
            if (cn == null) continue;
            if (cn.startsWith("com.bytedance.keva.")) return "keva." + e.getMethodName();
            if ("java.util.concurrent.CountDownLatch".equals(cn)) return "CountDownLatch.await";
            if (cn.startsWith("com.dragon.read.app.launch")) return "launch." + e.getMethodName();
            if (cn.startsWith("com.dragon.read.base.http")) return "http." + e.getMethodName();
            if (cn.startsWith("com.bytedance.ttnet")) return "ttnet." + e.getMethodName();
        }
        return null;
    }

    /**
     * 按宿主要求清除目标应用的「连续崩溃计数」（安全模式计数）。
     *
     * 宿主的 debug_reset 会往实例数据目录写 `.adv_flags/clear_safemode`；
     * 本方法在应用 onCreate 之前读到标记，就把计数归零并消费掉标记。
     *
     * 背景：红果把「启动即被杀」也算作异常启动，累计到阈值后，
     * Application 会走 SafeMode 分支等待一个不会完成的任务，形成
     * 「卡死 → 被杀 → 计数+1 → 更卡」的死循环，只能从 App 内部清计数来打破。
     */
    private static void clearSafeModeIfRequested(Application app) {
        try {
            String pkg = app.getPackageName();
            if (pkg == null || !pkg.startsWith("com.phoenix.read")) return;
            java.io.File dataDir = app.getFilesDir() == null ? null : app.getFilesDir().getParentFile();
            if (dataDir == null) return;
            java.io.File flag = new java.io.File(dataDir, ".adv_flags/clear_safemode");
            if (!flag.isFile()) return;

            ClassLoader cl = app.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            Class<?> c = cl.loadClass("com.dragon.read.app.h1");
            Object inst = c.getMethod("f").invoke(null);
            java.lang.reflect.Method reset = c.getMethod("s", boolean.class, String.class);
            reset.invoke(inst, false, "adv_reset");
            boolean deleted = flag.delete();
            top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                    "clearSafeMode OK, flagDeleted=" + deleted);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Safe",
                    "clearSafeMode failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 加固壳（如红果的 MuteApplicationStub）或大型框架（ByteDance lego 初始化）
     * 在 onCreate 里会阻塞等待子任务，此时没有 root 拿不到 tombstone、logcat 又被
     * ROM 屏蔽，只能从进程内部自行上报。
     *
     * 起守护线程，12 秒后把**所有线程**的栈写进诊断日志：主线程往往只是
     * 在 CountDownLatch.await 上等，真正卡住的子任务线程必须一起看才定位得到。
     */
    private static void dumpMainStackIfStuck(Application app) {
        try {
            final Thread main = android.os.Looper.getMainLooper().getThread();
            final String name = app == null ? "null" : app.getClass().getName();
            Thread watcher = new Thread(() -> {
                // 分多个时间点各抓一次：onCreate 卡住时第一份就够定位；
                // 若 onCreate 已返回但界面还停在启动页（子任务卡住），
                // 也要能拿到一份 onCreate 之后的线程快照，否则无从下手。
                for (int round = 0; round < 3; round++) {
                    try {
                        Thread.sleep(round == 0 ? 12000 : 25000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    dumpOnce(name, main, round);
                }
            }, "adv-stack-watch");
            watcher.setDaemon(true);
            watcher.start();
        } catch (Throwable ignored) {
        }
    }

    private static void dumpOnce(String name, Thread main, int round) {
        try {
            // 每轮都尝试修一次进程名缓存：首次读取如果发生在改名之前，
            // 缓存里就是宿主名，越早改写越有机会让应用拿到正确身份。
            try {
                String virtualName = top.niunaijun.blackbox.core.env.VirtualRuntime
                        .getInitialPackageName();
                if (virtualName != null) {
                    top.niunaijun.blackbox.app.BActivityThread
                            .fixProcessNameCaches(virtualName, "probe#" + round);
                }
            } catch (Throwable ignored) {
            }
            StringBuilder sb = new StringBuilder("STACK DUMP#").append(round)
                    .append(" (onCreate of ").append(name)
                    .append(") progName=").append(android.os.Process.myProcessName())
                    .append(" mainState=").append(main.getState());
            // 第二轮（约 37s）还卡在「主线程等一个应用自己的任务锁」时，主动打断一次：
            // 红果的 com.dragon.read.app.launch.r.h 用 CountDownLatch.await 等异步任务，
            // 而任务池在沙箱里不消费任务，主线程会永远等下去（线程栈已多次验证）。
            // 打断只会让它走 catch(InterruptedException) 分支继续往下跑，
            // 丢掉的是那批异步初始化，换来整个界面能用。
            if (round >= 1 && main.getState() == Thread.State.WAITING) {
                boolean stuckOnLatch = false;
                for (StackTraceElement ste : main.getStackTrace()) {
                    String cn = ste.getClassName();
                    if (cn != null && cn.startsWith("com.dragon.read.app.launch")
                            && "h".equals(ste.getMethodName())) {
                        stuckOnLatch = true;
                        break;
                    }
                }
                if (stuckOnLatch) {
                    main.interrupt();
                    sb.append(" [interrupted: stuck in launch.r.h latch]");
                    top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                            "打破卡死：主线程仍在 launch.r.h 的 await 上，已 interrupt");
                }
            }
            java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            int dumped = 0;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                if (t == null || t == Thread.currentThread()) {
                    continue;
                }
                if (dumped++ >= 400) {
                    break;
                }
                sb.append("\n  [").append(t.getName()).append("] ").append(t.getState())
                        .append(t == main ? " *MAIN*" : "");
                StackTraceElement[] st = e.getValue();
                int limit = Math.min(st.length, 18);
                for (int i = 0; i < limit; i++) {
                    sb.append("\n      at ").append(st[i]);
                }
                if (st.length > limit) {
                    sb.append("\n      ... ").append(st.length - limit).append(" more");
                }
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Inst", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 按需诊断（线上取证）
    //
    // 现象：平板 Android 17 上首页能出内容，但「加载更多」永远转圈、封面不加载、
    // 视频点不开。此时进程里所有线程池都是空闲的、没有任何新 socket，
    // 说明应用**根本没有发起请求**，而不是请求发出去失败了。
    //
    // 这种"安静卡住"从外部完全看不见（ROM 把 logcat 加密了），所以提供一个
    // 外部触发点：`adb shell touch /sdcard/Android/data/<宿主包>/files/.diag/dump`
    // 引擎会立刻把「线程池真实状态 + 全网线程栈 + 网络连通性」写进诊断日志。
    // ------------------------------------------------------------------

    /** 起一个轮询线程，等外部触发文件出现。 */
    private static void startDiagPoller(final Application app) {
        if (diagPollerStarted) {
            return;
        }
        diagPollerStarted = true;
        final Application hostApp = app;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File trigger = null;
                long lastMtime = 0L;
                for (int i = 0; i < 7200; i++) {
                    try {
                        if (trigger == null) {
                            java.io.File base = top.niunaijun.blackbox.utils.DiagLog.externalFilesDir();
                            if (base != null) {
                                trigger = new java.io.File(new java.io.File(base, ".diag"), "dump");
                            }
                        }
                        // 按 mtime 触发（不删文件）：宿主主进程/:push/虚拟应用进程
                        // 各自轮询，删文件会让只有一个进程拿到这次触发。
                        if (trigger != null && trigger.exists()) {
                            long mtime = trigger.lastModified();
                            if (mtime != 0L && mtime != lastMtime) {
                                lastMtime = mtime;
                                Application target = sTargetApp != null ? sTargetApp : hostApp;
                                dumpDiagnostics(target, "trigger");
                            }
                        }
                        Thread.sleep(700L);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "adv-diag-poll");
        t.setDaemon(true);
        t.start();
        top.niunaijun.blackbox.utils.DiagLog.log("Diag", "poller started");
    }

    /** 一次性把关键运行时状态打进日志。 */
    /**
     * 找出「请求进去了但没被执行」的那个队列。
     *
     * 图片/播放的链路是 FrescoTTNetFetcher → RetrofitUtils → SsHttpExecutor.execute，
     * 而 SsHttpExecutor 把任务交给 dispatcher.e，dispatcher.e 再转给 dispatcher.d 里的
     * 5 个线程池；此外 bytex 的 taskmonitor 还会把带节流标注的任务改派到
     * ThrottleExecutors 的 ScheduledExecutorService（延迟队列）。
     *
     * 任何一层堵住，现象都是「没有 socket、没有异常、线程全空闲」，
     * 所以必须逐个看队列长度和队首延迟，否则永远猜不到。
     */
    private static void dumpNetworkQueues(Application app) {
        ClassLoader cl = app.getClassLoader();
        try {
            Class<?> sse = cl.loadClass(
                    "com.bytedance.frameworks.baselib.network.http.retrofit.SsHttpExecutor");
            java.lang.reflect.Field f = sse.getDeclaredField("sRequestQueue");
            f.setAccessible(true);
            Object q = f.get(null);
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    "SsHttpExecutor.sRequestQueue=" + (q == null ? "null" : q.getClass().getName()));
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    "SsHttpExecutor failed: " + t.getClass().getSimpleName());
        }

        try {
            Class<?> d = cl.loadClass("com.bytedance.frameworks.baselib.network.dispatcher.d");
            java.lang.reflect.Field hf = d.getDeclaredField("h");
            hf.setAccessible(true);
            dumpPoolFields(d, hf.get(null), "dispatcher.d");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    "dispatcher.d failed: " + t.getClass().getSimpleName());
        }

        try {
            Class<?> te = cl.loadClass("com.ss.android.ugc.bytex.taskmonitor.ThrottleExecutors");
            dumpPoolFields(te, null, "ThrottleExecutors");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    "ThrottleExecutors failed: " + t.getClass().getSimpleName());
        }

        try {
            Class<?> tm = cl.loadClass("com.ss.android.ugc.bytex.taskmonitor.TaskMonitor");
            Object r = tm.getMethod("isEnableThrottle").invoke(null);
            top.niunaijun.blackbox.utils.DiagLog.log("Queue", "TaskMonitor.isEnableThrottle=" + r);
            dumpPoolFields(tm, null, "TaskMonitor");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    "TaskMonitor failed: " + t.getClass().getSimpleName());
        }

        // 池里说 size=8 active=8，线程转储里却找不到 Net*，两者必须能对上
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<Thread, StackTraceElement[]> en
                    : Thread.getAllStackTraces().entrySet()) {
                String n = en.getKey().getName();
                if (n == null || !n.startsWith("Net")) {
                    continue;
                }
                sb.append(n).append('=').append(en.getKey().getState()).append(' ');
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Queue", "NetThreads " + sb);
        } catch (Throwable ignored) {
        }
    }

    private static void dumpPoolFields(Class<?> cls, Object target, String label) {
        java.lang.reflect.Field[] fs;
        try {
            fs = cls.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }
        for (java.lang.reflect.Field f : fs) {
            if (!java.util.concurrent.Executor.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object v;
            try {
                f.setAccessible(true);
                v = f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : target);
            } catch (Throwable t) {
                continue;
            }
            if (v == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                        label + "." + f.getName() + "=null");
                continue;
            }
            logPool(label + "." + f.getName(), v);
        }
    }

    private static void logPool(String name, Object v) {
        if (!(v instanceof java.util.concurrent.ThreadPoolExecutor)) {
            top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                    name + "=" + v.getClass().getSimpleName());
            return;
        }
        java.util.concurrent.ThreadPoolExecutor p = (java.util.concurrent.ThreadPoolExecutor) v;
        int queued = -1;
        long headDelay = -1L;
        try {
            java.util.concurrent.BlockingQueue<Runnable> bq = p.getQueue();
            queued = bq.size();
            // 走接口反射：实现类往往是 private 内部类，直接 getMethod 会拿不到
            Object head = java.util.concurrent.BlockingQueue.class.getMethod("peek").invoke(bq);
            if (head != null) {
                Object d = java.util.concurrent.Delayed.class
                        .getMethod("getDelay", java.util.concurrent.TimeUnit.class)
                        .invoke(head, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (d instanceof Number) {
                    headDelay = ((Number) d).longValue();
                }
            }
        } catch (Throwable ignored) {
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Queue",
                name + " size=" + p.getPoolSize() + " active=" + p.getActiveCount()
                        + " queued=" + queued + " headDelayMs=" + headDelay
                        + " done=" + p.getCompletedTaskCount() + " shutdown=" + p.isShutdown());
    }

    /**
     * 用应用自己的 TTNet 通道（TTNetDepend.executeGet）发一次同步 GET。
     *
     * 这是绕开 Fresco、绕开 Retrofit 的最小复现：
     * - 能返回 -> TTNet 本身没问题，卡点在 Fresco/Retrofit 那一层；
     * - 一直不返回 -> TTNet 底层不通，再看具体是哪个域名。
     */
    /**
     * 接管字节的请求队列。
     *
     * 链路：FrescoTTNetFetcher → RetrofitUtils.createSsService → SsCall.enqueue
     *      → SsHttpExecutor.execute → sRequestQueue.b(task)
     * 实测沙箱里 sRequestQueue 指向的 dispatcher 池「8 个线程全 active、完成数 0」，
     * 任务进去就再也不出来（队列里堆着，既不建 socket 也不报错）。
     *
     * 所以把 sRequestQueue 换成一个只做一件事的队列：拿到 task 立刻丢到普通线程池执行。
     * 丢失的是优先级排序和延迟调度，换来的是请求真的能发出去。
     *
     * sRequestQueue 的声明类型是接口 jb0.a，所以可以用 Proxy 顶上。
     */
    private static void installRequestQueue(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            // 注意：接口的真实类名就是顶层包 jb0.a（之前误加了 dispatcher 前缀，导致 CNFE）
            Class<?> queueItf = cl.loadClass("jb0.a");
            Class<?> sse = cl.loadClass(
                    "com.bytedance.frameworks.baselib.network.http.retrofit.SsHttpExecutor");
            java.lang.reflect.Field f = sse.getDeclaredField("sRequestQueue");
            f.setAccessible(true);
            Object cur = f.get(null);
            if (cur != null && cur.getClass().getName().contains("$Proxy")) {
                replaceDispatcherPools(app);
                installHttpTrace(app);
                return;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{queueItf}, new RequestQueueHandler());
            f.set(null, proxy);
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "request queue -> adv-net (was "
                            + (cur == null ? "null" : cur.getClass().getSimpleName()) + ")");
            // 图片走的是 SsHttpExecutor（已接管），但详情/播放这类请求可能直接走
            // dispatcher 的线程池，那边同样是 done=0 的「只收不做」，一并换掉。
            replaceDispatcherPools(app);
            installHttpTrace(app);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "installRequestQueue failed: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
            replaceDispatcherPools(app);
            installHttpTrace(app);
        }
    }

    private static volatile boolean sPoolsReplaced;

    /**
     * 兜底：把 dispatcher.d 里那 5 个「收任务不干活」的池换成普通线程池。
     * 实测它们 size=8 active=8 done=0，队列里有任务但永远不完成。
     */
    /** 记录每一条接口请求的 URL / 结果 / 耗时（字节拦截器是接口，可用 Proxy 顶上）。 */
    private static void installHttpTrace(final Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> itf = cl.loadClass("com.bytedance.retrofit2.intercept.Interceptor");
            Class<?> ru = cl.loadClass("com.bytedance.ttnet.utils.RetrofitUtils");
            java.lang.reflect.Field lf = ru.getDeclaredField("sInterceptors");
            lf.setAccessible(true);
            Object list = lf.get(null);
            if (list instanceof java.util.List) {
                for (Object o : (java.util.List<?>) list) {
                    if (o != null && o.getClass().getName().contains("$Proxy")) {
                        return;
                    }
                }
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{itf}, new SsInterceptorTrace(cl));
            boolean ok = false;
            for (java.lang.reflect.Method m : ru.getMethods()) {
                if (!m.getName().equals("addInterceptor") || m.getParameterCount() != 1) {
                    continue;
                }
                m.setAccessible(true);
                m.invoke(null, proxy);
                ok = true;
                break;
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Trace", "interceptor installed=" + ok);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Trace",
                    "install failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static final class SsInterceptorTrace implements java.lang.reflect.InvocationHandler {
        private final ClassLoader cl;

        SsInterceptorTrace(ClassLoader cl) {
            this.cl = cl;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) {
                return "adv-http-trace";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            if (args == null || args.length == 0) {
                return null;
            }
            Object chain = args[0];
            long t0 = System.currentTimeMillis();
            String url = "?";
            Object req = null;
            try {
                Class<?> chainItf = cl.loadClass(
                        "com.bytedance.retrofit2.intercept.Interceptor$Chain");
                req = chainItf.getMethod("request").invoke(chain);
                if (req != null) {
                    try {
                        url = String.valueOf(req.getClass().getMethod("getUrl").invoke(req));
                    } catch (Throwable e1) {
                        try {
                            url = String.valueOf(req.getClass().getMethod("url").invoke(req));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                Class<?> reqCls = cl.loadClass("com.bytedance.retrofit2.client.Request");
                Object resp = chainItf.getMethod("proceed", reqCls).invoke(chain, req);
                String code = "?";
                if (resp != null) {
                    try {
                        code = String.valueOf(resp.getClass().getMethod("code").invoke(resp));
                    } catch (Throwable ignored) {
                    }
                }
                top.niunaijun.blackbox.utils.DiagLog.log("Trace",
                        shortUrl(url) + " -> code=" + code
                                + " " + (System.currentTimeMillis() - t0) + "ms");
                return resp;
            } catch (Throwable t) {
                Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null) ? t.getCause() : t;
                top.niunaijun.blackbox.utils.DiagLog.log("Trace",
                        shortUrl(url) + " FAIL " + c.getClass().getSimpleName()
                                + ": " + c.getMessage()
                                + " " + (System.currentTimeMillis() - t0) + "ms");
                if (c instanceof RuntimeException) {
                    throw (RuntimeException) c;
                }
                if (c instanceof Error) {
                    throw (Error) c;
                }
                throw new RuntimeException(c);
            }
        }
    }

    private static void replaceDispatcherPools(Application app) {
        if (sPoolsReplaced) {
            return;
        }
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> dCls = cl.loadClass("com.bytedance.frameworks.baselib.network.dispatcher.d");
            java.lang.reflect.Field hf = dCls.getDeclaredField("h");
            hf.setAccessible(true);
            Object d = hf.get(null);
            if (d == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Http", "dispatcher.d.h is null");
                return;
            }
            int n = 0;
            for (java.lang.reflect.Field f : dCls.getDeclaredFields()) {
                if (f.getType() != java.util.concurrent.ThreadPoolExecutor.class) {
                    continue;
                }
                f.setAccessible(true);
                Object old = f.get(d);
                if (old instanceof java.util.concurrent.ThreadPoolExecutor) {
                    java.util.concurrent.ThreadPoolExecutor op =
                            (java.util.concurrent.ThreadPoolExecutor) old;
                    top.niunaijun.blackbox.utils.DiagLog.log("Http",
                            "old pool " + f.getName() + " size=" + op.getPoolSize()
                                    + " active=" + op.getActiveCount()
                                    + " queued=" + op.getQueue().size()
                                    + " done=" + op.getCompletedTaskCount());
                }
                java.util.concurrent.ThreadPoolExecutor pool =
                        new java.util.concurrent.ThreadPoolExecutor(
                                2, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
                                new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                                new java.util.concurrent.ThreadFactory() {
                                    private final java.util.concurrent.atomic.AtomicInteger seq =
                                            new java.util.concurrent.atomic.AtomicInteger(1);

                                    @Override
                                    public Thread newThread(Runnable r) {
                                        Thread t = new Thread(r, "adv-disp-" + seq.getAndIncrement());
                                        t.setDaemon(true);
                                        return t;
                                    }
                                });
                pool.allowCoreThreadTimeOut(true);
                f.set(d, pool);
                n++;
            }
            sPoolsReplaced = true;
            top.niunaijun.blackbox.utils.DiagLog.log("Http", "dispatcher pools replaced: " + n);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "replaceDispatcherPools failed: " + t.getClass().getSimpleName()
                            + " " + t.getMessage());
        }
    }

    private static final class RequestQueueHandler implements java.lang.reflect.InvocationHandler {
        static final java.util.concurrent.ExecutorService POOL =
                java.util.concurrent.Executors.newCachedThreadPool(
                        new java.util.concurrent.ThreadFactory() {
                            private final java.util.concurrent.atomic.AtomicInteger seq =
                                    new java.util.concurrent.atomic.AtomicInteger(1);

                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "adv-net-" + seq.getAndIncrement());
                                t.setDaemon(true);
                                return t;
                            }
                        });

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) {
                return "adv-net-queue";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof Runnable) {
                        POOL.execute((Runnable) a);
                        break;
                    }
                }
            }
            return null;
        }
    }

    /** dispatcher 的线程一启动就调 setThreadPriority(10)，确认它在沙箱里不会卡住。 */
    private static void probeThreadPriority() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    android.os.Process.setThreadPriority(10);
                    top.niunaijun.blackbox.utils.DiagLog.log("Prio",
                            "setThreadPriority(10) ok " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable e) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Prio",
                            "setThreadPriority(10) FAIL " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage());
                }
            }
        }, "adv-prio-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(3000L);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            top.niunaijun.blackbox.utils.DiagLog.log("Prio",
                    "setThreadPriority(10) STILL BLOCKED after 3s");
        }
    }

    private static void probeTtnetSync(final Application app, final String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        final Object depend;
        try {
            Class<?> e = app.getClassLoader().loadClass("com.dragon.read.base.http.e");
            depend = getStatic(e, "d");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Ttnet",
                    "skip " + url + ": " + t.getClass().getSimpleName());
            return;
        }
        if (depend == null) {
            top.niunaijun.blackbox.utils.DiagLog.log("Ttnet", "skip " + url + ": no TTNetDepend");
            return;
        }
        final String shortUrl = url.length() > 60 ? url.substring(0, 60) : url;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    Object r = null;
                    boolean called = false;
                    for (java.lang.reflect.Method m : depend.getClass().getMethods()) {
                        if (!m.getName().equals("executeGet") || m.getParameterCount() != 2) {
                            continue;
                        }
                        if (m.getParameterTypes()[1] != String.class) {
                            continue;
                        }
                        m.setAccessible(true);
                        r = m.invoke(depend, 0, url);
                        called = true;
                        break;
                    }
                    if (!called) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Ttnet",
                                "no executeGet(int,String) on " + depend.getClass().getName());
                        return;
                    }
                    top.niunaijun.blackbox.utils.DiagLog.log("Ttnet",
                            "GET " + shortUrl + " -> "
                                    + (r instanceof String ? "len=" + ((String) r).length() : String.valueOf(r))
                                    + " in " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable th) {
                    Throwable c = (th instanceof java.lang.reflect.InvocationTargetException
                            && th.getCause() != null) ? th.getCause() : th;
                    top.niunaijun.blackbox.utils.DiagLog.log("Ttnet",
                            "GET " + shortUrl + " FAIL " + c.getClass().getSimpleName()
                                    + ": " + c.getMessage()
                                    + " in " + (System.currentTimeMillis() - t0) + "ms");
                }
            }
        }, "adv-ttnet-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join(8000L);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            top.niunaijun.blackbox.utils.DiagLog.log("Ttnet",
                    "GET " + shortUrl + " STILL BLOCKED after 8s");
        }
    }

    /**
     * 列出启动任务链的完成情况。
     *
     * com.dragon.read.app.launch.m 用 Map<String, m$c> 记录每个启动任务：
     * m$c.a=名称, b=开始时间, c=结束时间(0 表示没跑完), d=是否完成。
     * 沙箱里很多模块初始化是"投递到线程池后就不再管"，任务没跑完就静默失效，
     * 界面上表现为某个页面永远停在骨架屏——这张表能直接指出来是谁没跑。
     */
    /**
     * 播放页（ShortSeriesActivity）的数据是调用方通过静态字段 S 传进来的，
     * 页面 onCreate 里 `this.G = S; S = null;`。所以只要在 newActivity 这一刻
     * 看 S 是不是空，就能区分「调用方没给数据」和「有数据但没发请求」。
     */
    private static void probeShortSeries(Activity activity, Intent intent, String phase) {
        try {
            if (activity == null) {
                return;
            }
            String cls = activity.getClass().getName();
            if (!cls.contains("ShortSeries")) {
                return;
            }
            StringBuilder sb = new StringBuilder(phase).append(' ').append(cls);
            try {
                android.os.Bundle b = intent == null ? null : intent.getExtras();
                sb.append(" extras=").append(b == null ? "null" : b.keySet().toString());
            } catch (Throwable t) {
                sb.append(" extrasErr=").append(t.getClass().getSimpleName());
            }
            java.lang.reflect.Field f = null;
            Class<?> c = activity.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("S");
                } catch (Throwable ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                sb.append(" S=<no field>");
            } else {
                f.setAccessible(true);
                Object v = f.get(null);
                sb.append(" S=").append(v == null ? "null" : v.getClass().getSimpleName());
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Play", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** onResume 时再看一眼：数据 G、播放管理器 w、播放容器 i 是否存在。 */
    private static void probeShortSeriesState(Activity a) {
        try {
            if (a == null || !a.getClass().getName().contains("ShortSeries")) {
                return;
            }
            StringBuilder sb = new StringBuilder("resume ").append(a.getClass().getSimpleName());
            for (String fn : new String[]{"G", "w", "i"}) {
                Object v = readInstanceField(a, fn);
                sb.append(' ').append(fn).append('=')
                        .append(v == null ? "null" : v.getClass().getSimpleName());
                if (v instanceof android.view.ViewGroup) {
                    sb.append("(children=").append(((android.view.ViewGroup) v).getChildCount()).append(')');
                }
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Play", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static Object readInstanceField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void dumpLaunchTasks(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> m = cl.loadClass("com.dragon.read.app.launch.m");
            // f: CopyOnWriteArrayList<m$c>  —— 任务记录本体
            try {
                java.lang.reflect.Field ff = m.getDeclaredField("f");
                ff.setAccessible(true);
                Object v = ff.get(null);
                if (v instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) v;
                    top.niunaijun.blackbox.utils.DiagLog.log("Launch", "m.f size=" + list.size());
                    int n = 0;
                    for (Object o : list) {
                        logLaunchRecord("f", String.valueOf(n++), o);
                        if (n >= 300) {
                            break;
                        }
                    }
                } else {
                    top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                            "m.f = " + (v == null ? "null" : v.getClass().getName()));
                }
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                        "m.f failed: " + t.getClass().getSimpleName());
            }
            // g: SparseArrayCompat
            try {
                java.lang.reflect.Field fg = m.getDeclaredField("g");
                fg.setAccessible(true);
                Object g = fg.get(null);
                if (g != null) {
                    java.lang.reflect.Method size = g.getClass().getMethod("size");
                    int sz = ((Number) size.invoke(g)).intValue();
                    top.niunaijun.blackbox.utils.DiagLog.log("Launch", "m.g size=" + sz);
                    java.lang.reflect.Method valueAt = g.getClass().getMethod("valueAt", int.class);
                    for (int i = 0; i < Math.min(sz, 200); i++) {
                        logLaunchRecord("g", String.valueOf(i), valueAt.invoke(g, i));
                    }
                }
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                        "m.g failed: " + t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                    "dump failed: " + t.getClass().getSimpleName());
        }
    }

    private static void logLaunchRecord(String prefix, Object key, Object val) {
        String name = String.valueOf(key);
        long start = -1L;
        long end = -1L;
        boolean done = false;
        try {
            if (val == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Launch", prefix + " " + name + " = null");
                return;
            }
            java.lang.reflect.Field fa = val.getClass().getDeclaredField("a");
            fa.setAccessible(true);
            name = String.valueOf(fa.get(val));
            java.lang.reflect.Field fb = val.getClass().getDeclaredField("b");
            fb.setAccessible(true);
            start = ((Number) fb.get(val)).longValue();
            java.lang.reflect.Field fc = val.getClass().getDeclaredField("c");
            fc.setAccessible(true);
            end = ((Number) fc.get(val)).longValue();
            java.lang.reflect.Field fd = val.getClass().getDeclaredField("d");
            fd.setAccessible(true);
            done = Boolean.TRUE.equals(fd.get(val));
        } catch (Throwable ignored) {
        }
        if (end <= 0L) {
            top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                    "UNFINISHED " + name + " started=" + (start > 0) + " done=" + done);
        } else {
            top.niunaijun.blackbox.utils.DiagLog.log("Launch",
                    "ok " + name + " " + (end - start) + "ms");
        }
    }

    private static void dumpDiagnostics(Application app, String reason) {
        top.niunaijun.blackbox.utils.DiagLog.log("Diag", "=== dump begin (" + reason + ") ===");
        installRequestQueue(app);
        probeThreadPriority();
        dumpLaunchTasks(app);
        logExecutorState(app);
        logNetworkState(app);
        probeAppHttp(app);
        logFrescoState(app);
        probeFrescoFetch(app);
        dumpNetworkQueues(app);
        dumpViewTree();
        probeTtnetSync(app, "https://www.baidu.com/");
        probeTtnetSync(app, sLastDraweeUri);
        logMappedLibs();
        logNativeThreads();
        logAllThreads(app);
        exportAppSelfLogs(app);
        top.niunaijun.blackbox.utils.DiagLog.log("Diag", "=== dump end ===");
    }

    /**
     * 遍历当前虚拟 Activity 的 View 树，逐个检查图片控件。
     *
     * 这一步用来区分两种截然不同的原因：
     *   1. 控件压根没拿到图片（controller=null）→ UI 根本没发起请求；
     *   2. 拿到了 controller 但 URL 是空的（uri=""）→ 响应里就没有图片地址；
     *   3. URL 正常（uri=https://p3-novel.byteimg.com/...）却没图 → 加载被丢弃。
     *
     * 同时打印 `isShown()`：列表图片普遍"可见才加载"，虚拟窗口的可见性判断
     * 一旦恒为 false，图片就永远不请求——这是最可疑的一条。
     */
    private static void dumpViewTree() {
        Activity cur = sTopActivity;
        if (cur == null) {
            try {
                cur = top.niunaijun.blackbox.app.BActivityThread.sTopActivity;
            } catch (Throwable ignored) {
            }
        }
        final Activity activity = cur;
        if (activity == null) {
            top.niunaijun.blackbox.utils.DiagLog.log("View", "no top activity");
            return;
        }
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final StringBuilder out = new StringBuilder();
        final int[] stats = new int[]{0, 0, 0};   // 总控件数 / Drawee 数 / 有 URL 的
        final java.util.List<Object> caught = new java.util.ArrayList<>();
        Runnable walker = new Runnable() {
            @Override
            public void run() {
                try {
                    android.view.View decor = activity.getWindow() == null
                            ? null : activity.getWindow().peekDecorView();
                    if (decor == null) {
                        decor = activity.findViewById(android.R.id.content);
                    }
                    walkViews(decor, 0, out, stats, caught);
                    if (decor != null) {
                        out.append("decor ").append(decor.getWidth()).append('x').append(decor.getHeight())
                                .append(" attached=").append(decor.isAttachedToWindow())
                                .append(" activity=")
                                .append(activity.getClass().getName())
                                .append('\n');
                    }
                } catch (Throwable t) {
                    out.append("walk err ").append(t.getClass().getSimpleName()).append('\n');
                } finally {
                    latch.countDown();
                }
            }
        };
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                walker.run();
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(walker);
                latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {
        }
        top.niunaijun.blackbox.utils.DiagLog.log("View",
                "activity=" + activity.getClass().getSimpleName()
                        + " views=" + stats[0] + " drawee=" + stats[1]
                        + " withUrl=" + stats[2]);
        for (String line : out.toString().split("\n")) {
            if (line.length() > 0) {
                top.niunaijun.blackbox.utils.DiagLog.log("View", line);
            }
        }
        // 把从界面上抓到的真实 ImageRequest 原样重放一次：失败原因会被 Fresco
        // 完整抛出来；成功则说明这条请求本身没问题（问题在"提交后没执行"）。
        if (!caught.isEmpty()) {
            replayImageRequests(caught);
        }
    }

    private static void replayImageRequests(java.util.List<Object> requests) {
        try {
            Application app = sTargetApp;
            if (app == null) {
                return;
            }
            ClassLoader cl = app.getClassLoader();
            Class<?> fresco = cl.loadClass("com.facebook.drawee.backends.pipeline.Fresco");
            Object pipe = fresco.getMethod("getImagePipeline").invoke(null);
            java.lang.reflect.Method fetch = null;
            for (java.lang.reflect.Method m : pipe.getClass().getMethods()) {
                if (!m.getName().equals("fetchDecodedImage")) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0].isInstance(requests.get(0))) {
                    fetch = m;
                    break;
                }
            }
            if (fetch == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("Replay", "no fetch method");
                return;
            }
            int n = 0;
            for (Object req : requests) {
                if (n++ >= 2) {
                    break;
                }
                String uri = "-";
                try {
                    Object u = req.getClass().getMethod("getSourceUri").invoke(req);
                    uri = shortUrl(String.valueOf(u));
                } catch (Throwable ignored) {
                }
                long t0 = System.currentTimeMillis();
                Object ds = fetch.invoke(pipe, req, app);
                boolean finished = false;
                while (System.currentTimeMillis() - t0 < 8000L) {
                    if (Boolean.TRUE.equals(ds.getClass().getMethod("isFinished").invoke(ds))) {
                        finished = true;
                        break;
                    }
                    Thread.sleep(100L);
                }
                long cost = System.currentTimeMillis() - t0;
                if (!finished) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Replay",
                            uri + " TIMEOUT 8s (排进管线但没人执行)");
                    continue;
                }
                Object fail = ds.getClass().getMethod("getFailureCause").invoke(ds);
                Object res = ds.getClass().getMethod("getResult").invoke(ds);
                if (fail != null) {
                    Throwable c = (Throwable) fail;
                    top.niunaijun.blackbox.utils.DiagLog.log("Replay",
                            uri + " FAIL " + cost + "ms " + c.getClass().getName()
                                    + " " + c.getMessage());
                    StackTraceElement[] st = c.getStackTrace();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(14, st.length); i++) {
                        sb.append("\n      at ").append(st[i]);
                    }
                    top.niunaijun.blackbox.utils.DiagLog.log("Replay", "cause:" + sb);
                } else {
                    top.niunaijun.blackbox.utils.DiagLog.log("Replay",
                            uri + " OK " + cost + "ms result="
                                    + (res == null ? "null" : res.getClass().getSimpleName()));
                }
                try {
                    if (res instanceof java.io.Closeable) {
                        ((java.io.Closeable) res).close();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Replay",
                    "failed " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void walkViews(android.view.View v, int depth,
                                  StringBuilder out, int[] stats, java.util.List<Object> caught) {
        if (v == null || depth > 30 || stats[0] > 400) {
            return;
        }
        stats[0]++;
        String cls = v.getClass().getName();
        if (cls.contains("Drawee") || cls.contains("drawee")) {
            stats[1]++;
            String ctl = "null";
            String uri = "-";
            try {
                Object c = v.getClass().getMethod("getController").invoke(v);
                if (c != null) {
                    ctl = c.getClass().getSimpleName();
                    try {
                        Object req = c.getClass().getMethod("getImageRequest").invoke(c);
                        if (req != null) {
                            Object u = req.getClass().getMethod("getSourceUri").invoke(req);
                            if (u != null) {
                                uri = String.valueOf(u);
                                stats[2]++;
                                sLastDraweeUri = uri;
                                if (caught.size() < 3 && v.isShown()) {
                                    caught.add(req);
                                }
                            } else {
                                uri = "null";
                            }
                        }
                    } catch (Throwable t) {
                        uri = "?" + t.getClass().getSimpleName();
                    }
                }
            } catch (Throwable t) {
                ctl = "err:" + t.getClass().getSimpleName();
            }
            out.append(cls.substring(cls.lastIndexOf('.') + 1))
                    .append(" shown=").append(v.isShown())
                    .append(" vis=").append(v.getVisibility())
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" ctl=").append(ctl)
                    .append(" uri=").append(shortUrl(uri))
                    .append('\n');
            return;
        }
        // 播放器只在 Surface 有效后才开始拉流，所以这块必须单独记
        if (v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) {
            String surf;
            try {
                if (v instanceof android.view.SurfaceView) {
                    android.view.SurfaceHolder h = ((android.view.SurfaceView) v).getHolder();
                    android.view.Surface s = h == null ? null : h.getSurface();
                    surf = s == null ? "surface=null" : "valid=" + s.isValid();
                } else {
                    android.view.TextureView tv = (android.view.TextureView) v;
                    surf = "available=" + tv.isAvailable()
                            + " st=" + (tv.getSurfaceTexture() != null)
                            + " attached=" + tv.isAttachedToWindow();
                }
            } catch (Throwable t) {
                surf = "err:" + t.getClass().getSimpleName();
            }
            out.append(cls.substring(cls.lastIndexOf('.') + 1))
                    .append(" shown=").append(v.isShown())
                    .append(" vis=").append(v.getVisibility())
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" [").append(surf).append(']')
                    .append('\n');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                walkViews(g.getChildAt(i), depth + 1, out, stats, caught);
            }
        }
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() > 95 ? s.substring(0, 95) + "…" : s;
    }

    /**
     * 决定性实验：用**应用自己的图片管线**去拉一张图。
     *
     * 走的是 Fresco 的 `ImagePipeline.fetchDecodedImage`，也就是界面加载封面时走的
     * 那条完全相同的链路（同样的解码器、缓存、网络 fetcher）。三种结果分别对应：
     *   - 成功        → 管线没坏，是界面根本没发起请求（问题在上层 UI/数据绑定）
     *   - 失败 + 原因 → 直接拿到根因（网络 fetcher / 解码器 / 缓存）
     *   - 8 秒不结束  → 任务进了管线但没人执行（调度层问题）
     */
    private static void probeFrescoFetch(final Application app) {
        if (app == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final String url = "https://www.baidu.com/img/flexible/logo/pc/result.png";
                Object resultRef = null;
                try {
                    ClassLoader cl = app.getClassLoader();
                    Class<?> fresco = cl.loadClass("com.facebook.drawee.backends.pipeline.Fresco");
                    Object pipe = fresco.getMethod("getImagePipeline").invoke(null);
                    Class<?> uriCls = Class.forName("android.net.Uri");
                    Object uri = uriCls.getMethod("parse", String.class).invoke(null, url);
                    Class<?> builderCls = cl.loadClass(
                            "com.facebook.imagepipeline.request.ImageRequestBuilder");
                    Object builder = builderCls
                            .getMethod("newBuilderWithSource", uriCls).invoke(null, uri);
                    Object request = builderCls.getMethod("build").invoke(builder);
                    Class<?> reqCls = cl.loadClass(
                            "com.facebook.imagepipeline.request.ImageRequest");

                    java.lang.reflect.Method fetch = null;
                    for (java.lang.reflect.Method m : pipe.getClass().getMethods()) {
                        if (!m.getName().equals("fetchDecodedImage")) {
                            continue;
                        }
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 2 && p[0].isAssignableFrom(reqCls)) {
                            fetch = m;
                            break;
                        }
                    }
                    if (fetch == null) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                                "fetchDecodedImage not found");
                        return;
                    }
                    long t0 = System.currentTimeMillis();
                    Object ds = fetch.invoke(pipe, request, app);
                    if (ds == null) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco", "fetch returned null ds");
                        return;
                    }
                    boolean finished = false;
                    while (System.currentTimeMillis() - t0 < 8000L) {
                        Object fin = ds.getClass().getMethod("isFinished").invoke(ds);
                        if (Boolean.TRUE.equals(fin)) {
                            finished = true;
                            break;
                        }
                        Thread.sleep(100L);
                    }
                    long cost = System.currentTimeMillis() - t0;
                    if (!finished) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                                "fetch TIMEOUT 8s, still running (任务进了管线没人执行)");
                        return;
                    }
                    Object fail = ds.getClass().getMethod("getFailureCause").invoke(ds);
                    resultRef = ds.getClass().getMethod("getResult").invoke(ds);
                    if (fail != null) {
                        Throwable c = (Throwable) fail;
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                                "fetch FAIL in " + cost + "ms " + c.getClass().getName()
                                        + " " + c.getMessage());
                        StackTraceElement[] st = c.getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(12, st.length); i++) {
                            sb.append("\n      at ").append(st[i]);
                        }
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco", "cause:" + sb);
                    } else {
                        top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                                "fetch OK in " + cost + "ms result="
                                        + (resultRef == null ? "null"
                                        : resultRef.getClass().getSimpleName()));
                    }
                } catch (Throwable e) {
                    Throwable c = (e instanceof java.lang.reflect.InvocationTargetException
                            && e.getCause() != null) ? e.getCause() : e;
                    top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                            "probe err " + c.getClass().getName() + " " + c.getMessage());
                } finally {
                    try {
                        if (resultRef instanceof java.io.Closeable) {
                            ((java.io.Closeable) resultRef).close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "adv-fresco-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 看 Fresco 图片管线是不是被暂停了。
     *
     * `ImagePipeline.pause()` 一旦被调用且没有 resume（例如应用监听前后台、
     * 省流模式、低内存时暂停），之后所有图片请求只会排队不入队执行 ——
     * 表现就是封面全空、线程全空闲、也不产生网络请求。这条日志直接给出答案。
     */
    private static void logFrescoState(Application app) {
        if (app == null) {
            return;
        }
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> fresco = cl.loadClass("com.facebook.drawee.backends.pipeline.Fresco");
            Object inited = fresco.getMethod("hasBeenInitialized").invoke(null);
            top.niunaijun.blackbox.utils.DiagLog.log("Fresco", "initialized=" + inited);
            if (!Boolean.TRUE.equals(inited)) {
                return;
            }
            Object pipe = fresco.getMethod("getImagePipeline").invoke(null);
            if (pipe == null) {
                return;
            }
            for (java.lang.reflect.Method m : pipe.getClass().getMethods()) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                if (m.getName().equals("isPaused")) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                            "isPaused=" + m.invoke(pipe));
                } else if (m.getName().contains("CacheStats")) {
                    Object st = m.invoke(pipe);
                    top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                            m.getName() + "=" + st);
                }
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Fresco",
                    "failed " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 看关键 native 库到底有没有被映射进进程。
     *
     * 图片解码（webp/heif）和视频播放（ttmplayer/ffmpeg）都靠 .so；如果这些库
     * 在沙箱里没加载成功，表现就是「图片全是占位、视频一直转圈」，而纯 Java
     * 的接口请求完全正常。UnsatisfiedLinkError 通常被应用吞掉，不会显式报错。
     */
    private static void logMappedLibs() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/proc/self/maps"));
            StringBuilder sb = new StringBuilder();
            try {
                String line;
                int total = 0;
                java.util.Set<String> libs = new java.util.LinkedHashSet<>();
                while ((line = r.readLine()) != null) {
                    total++;
                    int i = line.indexOf(".so");
                    if (i < 0) {
                        continue;
                    }
                    int slash = line.lastIndexOf('/', i);
                    if (slash >= 0) {
                        libs.add(line.substring(slash + 1, i + 3));
                    }
                }
                sb.append("maps lines=").append(total).append(" libs=").append(libs.size());
                top.niunaijun.blackbox.utils.DiagLog.log("Lib", sb.toString());
                String[] keys = {"webp", "heif", "heic", "jpeg", "imagepipeline", "ttmplayer",
                        "avcodec", "ffmpeg", "ttboringssl", "ttcrypto", "cronet", "ttnet",
                        "mdl", "bda", "ttm", "bytebench"};
                for (String k : keys) {
                    StringBuilder hit = new StringBuilder();
                    for (String lib : libs) {
                        if (lib.toLowerCase().contains(k)) {
                            hit.append(lib).append(' ');
                        }
                    }
                    top.niunaijun.blackbox.utils.DiagLog.log("Lib",
                            k + ": " + (hit.length() == 0 ? "-" : hit.toString().trim()));
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Lib",
                    "failed " + t.getClass().getSimpleName());
        }
    }

    /** 包在 NetworkClient 外面的埋点代理：每个请求一行日志。 */
    private static final class HttpTraceHandler implements java.lang.reflect.InvocationHandler {
        private final Object target;

        HttpTraceHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // 只记真正发请求的方法，避免 getClass/hashCode 之类刷屏
            boolean looksHttp = args != null && args.length >= 1
                    && args[0] instanceof String
                    && ((String) args[0]).startsWith("http");
            if (!looksHttp) {
                try {
                    return method.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            String url = (String) args[0];
            long t0 = System.currentTimeMillis();
            try {
                Object r = method.invoke(target, args);
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "req " + name + " " + trim(url) + " -> "
                                + (r == null ? "null"
                                : (r instanceof String ? "len=" + ((String) r).length() : "obj"))
                                + " " + (System.currentTimeMillis() - t0) + "ms");
                return r;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                top.niunaijun.blackbox.utils.DiagLog.log("Http",
                        "req " + name + " " + trim(url) + " FAIL "
                                + (System.currentTimeMillis() - t0) + "ms "
                                + c.getClass().getName() + " " + c.getMessage());
                throw c;
            }
        }

        private static String trim(String url) {
            int q = url.indexOf('?');
            String s = q > 0 ? url.substring(0, q) : url;
            return s.length() > 110 ? s.substring(0, 110) + "…" : s;
        }
    }

    /**
     * 打印**每个线程当前的内核系统调用**。
     *
     * 关键：Chromium/Cronet、TTNet、MDL 这些线程是纯 native 的，Java 栈是空的
     * （`Thread.getStackTrace()` 返回 0 帧），从 Java 侧完全看不出它们在干什么。
     * 但 `/proc/self/task/<tid>/syscall` 对同进程可读，能直接看到
     * epoll_pwait（空闲）、futex（等锁）、connect（连不出去）、recvfrom（等响应）。
     *
     * 这决定了「请求没发出去」还是「发出去了在等响应」。
     */
    private static void logNativeThreads() {
        final String[] names = {
                "0 io_setup", "22 epoll_pwait", "21 epoll_ctl", "63 read", "64 write",
                "56 openat", "57 close", "73 ppoll", "98 futex", "101 nanosleep",
                "198 socket", "200 bind", "201 listen", "202 accept", "203 connect",
                "206 sendto", "207 recvfrom", "208 sendmsg", "209 recvmsg", "210 shutdown",
                "242 accept4", "172 getpid"};
        java.util.Map<String, String> byNum = new java.util.HashMap<>();
        for (String s : names) {
            int sp = s.indexOf(' ');
            byNum.put(s.substring(0, sp), s.substring(sp + 1));
        }
        try {
            java.io.File dir = new java.io.File("/proc/self/task");
            java.io.File[] tasks = dir.listFiles();
            if (tasks == null) {
                return;
            }
            for (java.io.File t : tasks) {
                String tid = t.getName();
                String comm = readShort(new java.io.File(t, "comm"));
                String stat = readShort(new java.io.File(t, "stat"));
                String syscall = readShort(new java.io.File(t, "syscall"));
                String state = "?";
                if (stat != null) {
                    int rp = stat.lastIndexOf(')');
                    if (rp > 0 && rp + 2 < stat.length()) {
                        state = stat.substring(rp + 2, rp + 3);
                    }
                }
                String sys = "?";
                if (syscall != null && !syscall.startsWith("-")) {
                    int sp = syscall.indexOf(' ');
                    String num = sp > 0 ? syscall.substring(0, sp) : syscall;
                    String nm = byNum.get(num);
                    sys = nm == null ? ("sys#" + num) : nm;
                    if ("epoll_pwait".equals(nm) || "ppoll".equals(nm)) {
                        sys = sys + "(idle-poll)";
                    }
                } else if (syscall != null && syscall.startsWith("running")) {
                    sys = "running";
                }
                top.niunaijun.blackbox.utils.DiagLog.log("NThread",
                        tid + " " + state + " " + comm + " -> " + sys);
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("NThread",
                    "failed " + t.getClass().getSimpleName());
        }
    }

    private static String readShort(java.io.File f) {
        try {
            byte[] buf = new byte[512];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(buf);
                if (n <= 0) {
                    return "";
                }
                return new String(buf, 0, n, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用应用自己的 HTTP 客户端（字节 NetworkClient，底层是 TTNet）去取一个 URL。
     *
     * 为什么要这么做：实测接口 JSON 走 OkHttp 正常，而图片/视频（走 TTNet）完全
     * 不动。这里直接拿 TTNet 打一发，把结果或异常原样打出来，就能区分
     * 「TTNet 这条链路坏了」和「上层根本没调用」。
     */
    private static void probeAppHttp(final Application app) {
        if (app == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String url = "https://www.baidu.com/";
                // 1) 应用自己的 NetworkClient（TTNet）
                ttnet_probe:
                try {
                    ClassLoader cl = app.getClassLoader();
                    Class<?> nc = null;
                    String lastErr = "";
                    ClassLoader[] loaders = {cl,
                            Thread.currentThread().getContextClassLoader(),
                            AppInstrumentation.class.getClassLoader()};
                    for (ClassLoader l : loaders) {
                        if (l == null) {
                            continue;
                        }
                        try {
                            // 必须用 loadClass(String)：红果的 NewMiraClassloader 重写了
                            // loadClass(String, boolean)，Class.forName(…, false, cl) 会走
                            // 到那条分支里查不到类（引擎自己一直用 loadClass 才正常）。
                            nc = l.loadClass("com.bytedance.common.utility.NetworkClient");
                            top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                                    "NetworkClient loaded by " + l.getClass().getName()
                                            + " appCl=" + (cl == null ? "null"
                                            : cl.getClass().getName()));
                            break;
                        } catch (Throwable e) {
                            lastErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }
                    if (nc == null) {
                        top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                                "NetworkClient CNFE " + lastErr);
                        break ttnet_probe;
                    }
                    Object client = null;
                    for (java.lang.reflect.Method m : nc.getMethods()) {
                        if (m.getParameterCount() == 0
                                && nc.isAssignableFrom(m.getReturnType())
                                && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                            client = m.invoke(null);
                            break;
                        }
                    }
                    if (client == null) {
                        for (java.lang.reflect.Field f : nc.getDeclaredFields()) {
                            if (nc.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                client = f.get(null);
                                if (client != null) {
                                    break;
                                }
                            }
                        }
                    }
                    top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                            "NetworkClient instance=" + (client == null ? "null"
                                    : client.getClass().getName()));
                    if (client != null) {
                        java.lang.reflect.Method target = null;
                        StringBuilder sigs = new StringBuilder();
                        for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                            if (m.getParameterCount() == 1
                                    && m.getParameterTypes()[0] == String.class) {
                                sigs.append(m.getName()).append("->")
                                        .append(m.getReturnType().getSimpleName()).append(' ');
                                if (target == null && m.getReturnType() == String.class) {
                                    target = m;
                                }
                            }
                        }
                        top.niunaijun.blackbox.utils.DiagLog.log("Probe", "methods: " + sigs);
                        if (target != null) {
                            long t0 = System.currentTimeMillis();
                            try {
                                Object r = target.invoke(client, url);
                                String head = "";
                                if (r instanceof String) {
                                    String s = (String) r;
                                    head = " head=" + s.substring(0, Math.min(60, s.length()))
                                            .replace('\n', ' ');
                                }
                                top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                                        "ttnet " + target.getName() + "(" + url + ") -> "
                                                + (r == null ? "null"
                                                : (r instanceof String
                                                ? "len=" + ((String) r).length() : String.valueOf(r)))
                                                + head
                                                + " in " + (System.currentTimeMillis() - t0) + "ms");
                            } catch (Throwable e) {
                                Throwable c = (e instanceof java.lang.reflect.InvocationTargetException
                                        && e.getCause() != null) ? e.getCause() : e;
                                top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                                        "ttnet " + target.getName() + " FAIL in "
                                                + (System.currentTimeMillis() - t0) + "ms "
                                                + c.getClass().getName() + " " + c.getMessage());
                            }
                        }
                    }
                } catch (Throwable e) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                            "ttnet probe err " + e.getClass().getSimpleName());
                }
                // 2) 对照：Java 侧 HttpURLConnection
                long t1 = System.currentTimeMillis();
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    int code = c.getResponseCode();
                    top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                            "hurl code=" + code + " in " + (System.currentTimeMillis() - t1) + "ms");
                    c.disconnect();
                } catch (Throwable e) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Probe",
                            "hurl FAIL in " + (System.currentTimeMillis() - t1) + "ms "
                                    + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }, "adv-http-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把虚拟应用自己写的日志（字节的 alog / TTNet / 播放器 mdl）搬到外部可读目录。
     *
     * 这是排查「图片不加载、视频不播放」最直接的证据来源：这类失败只在应用
     * 自己的日志里有错误码（TTNet error / Fresco onFailure），而进程数据目录
     * adb 读不到、ROM 又把 logcat 加密了。
     *
     * 只搬文本类小文件（单文件 < 3 MB），总量上限 20 MB，避免把整个 data 拖出来。
     */
    private static void exportAppSelfLogs(Application app) {
        if (app == null) {
            return;
        }
        try {
            java.io.File base = top.niunaijun.blackbox.utils.DiagLog.externalFilesDir();
            if (base == null) {
                return;
            }
            java.io.File outDir = new java.io.File(new java.io.File(base, ".diag"),
                    "app-" + android.os.Process.myPid());
            java.io.File src = app.getFilesDir();
            if (src == null || !src.isDirectory()) {
                top.niunaijun.blackbox.utils.DiagLog.log("Export", "no files dir");
                return;
            }
            // 先打一份目录树（应用自己的日志不一定在 files/ 下，字节系常在
            // data 根目录的 app_alog / alog 里），再按文件类型复制。
            java.io.File dataDir = null;
            try {
                dataDir = app.getDataDir();
            } catch (Throwable ignored) {
            }
            if (dataDir != null) {
                listTree(dataDir, 0);
            }
            long[] budget = {20L * 1024 * 1024};
            int[] count = {0};
            copyTree(src, outDir, budget, count, 0);
            if (dataDir != null) {
                // 虚拟应用真正的数据目录：context 的 getDataDir() 拿到的是宿主路径，
                // 目标应用的私有目录在 blackbox/data/user/<userId>/<pkg> 下。
                java.io.File vroot = new java.io.File(dataDir, "blackbox/data/user");
                java.io.File[] users = vroot.listFiles();
                if (users != null) {
                    for (java.io.File u : users) {
                        java.io.File[] pkgs = u.listFiles();
                        if (pkgs == null) {
                            continue;
                        }
                        for (java.io.File p : pkgs) {
                            listTree(p, 0);
                            copyTree(p, new java.io.File(outDir,
                                    "_v/" + u.getName() + "/" + p.getName()), budget, count, 0);
                        }
                    }
                }
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Export",
                    "copied " + count[0] + " files to " + outDir
                            + " left=" + (budget[0] / 1024) + "KB");
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Export",
                    "failed " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static final String[] EXPORT_SKIP_DIRS = {
            "cache", "code_cache", "lib", "oat", ".tmp", ".adv_flags"};

    /** 打目录树：只看 3 层，输出「目录 → 文件数/总字节」，用来定位应用日志到底写在哪。 */
    private static void listTree(java.io.File dir, int depth) {
        if (depth > 2) {
            return;
        }
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        int files = 0;
        long bytes = 0L;
        for (java.io.File f : children) {
            if (f.isFile()) {
                files++;
                bytes += f.length();
            }
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Tree",
                dir.getAbsolutePath() + " files=" + files + " bytes=" + bytes);
        for (java.io.File f : children) {
            if (f.isDirectory()) {
                listTree(f, depth + 1);
            }
        }
    }

    private static void copyTree(java.io.File src, java.io.File dst,
                                 long[] budget, int[] count, int depth) {
        if (depth > 6 || budget[0] <= 0) {
            return;
        }
        String name = src.getName();
        for (String skip : EXPORT_SKIP_DIRS) {
            if (skip.equals(name)) {
                return;
            }
        }
        java.io.File[] children = src.listFiles();
        if (children == null) {
            return;
        }
        for (java.io.File f : children) {
            if (budget[0] <= 0) {
                return;
            }
            if (f.isDirectory()) {
                copyTree(f, new java.io.File(dst, f.getName()), budget, count, depth + 1);
                continue;
            }
            long len = f.length();
            if (len <= 0 || len > 3L * 1024 * 1024) {
                continue;
            }
            String ln = f.getName().toLowerCase();
            // 字节的 alog 经常没有扩展名，所以用"排除二进制"而不是"只收 .log"
            boolean binary = ln.endsWith(".so") || ln.endsWith(".db") || ln.endsWith(".dex")
                    || ln.endsWith(".apk") || ln.endsWith(".png") || ln.endsWith(".jpg")
                    || ln.endsWith(".jpeg") || ln.endsWith(".webp") || ln.endsWith(".ttf")
                    || ln.endsWith(".oat") || ln.endsWith(".vdex") || ln.endsWith(".zip");
            if (binary) {
                continue;
            }
            try {
                //noinspection ResultOfMethodCallIgnored
                dst.mkdirs();
                java.io.File out = new java.io.File(dst, f.getName());
                java.io.InputStream in = new java.io.FileInputStream(f);
                try {
                    java.io.OutputStream os = new java.io.FileOutputStream(out);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            budget[0] -= n;
                        }
                    } finally {
                        os.close();
                    }
                } finally {
                    in.close();
                }
                count[0]++;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 打印 TTExecutors 各线程池的真实状态。
     *
     * 判据：`queue` 一直涨而 `completed` 不涨 = 任务进得去、没人跑；
     * `queue` 为 0 且 `completed` 为 0 = 应用压根没提交任务（问题在更上层）。
     */
    private static void logExecutorState(Application app) {
        try {
            Class<?> c = sTTExecutors;
            if (c == null && app != null) {
                c = Class.forName("com.bytedance.common.utility.concurrent.TTExecutors",
                        false, app.getClassLoader());
                sTTExecutors = c;
            }
            if (c == null) {
                return;
            }
            String[] fields = {"sNormalThreadPool", "sIOThreadPool", "sBackgroundThreadPool",
                    "sCPUThreadPool", "sSerialThreadPool", "sScheduledThreadPool"};
            for (String name : fields) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    top.niunaijun.blackbox.utils.DiagLog.log("Pool", name + " " + describePool(v));
                } catch (Throwable t) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Pool",
                            name + " err " + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Pool", "failed " + t.getClass().getSimpleName());
        }
    }

    private static String describePool(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor p = (java.util.concurrent.ThreadPoolExecutor) v;
            try {
                return p.getClass().getSimpleName()
                        + " size=" + p.getPoolSize()
                        + " active=" + p.getActiveCount()
                        + " queue=" + p.getQueue().size()
                        + " completed=" + p.getCompletedTaskCount();
            } catch (Throwable ignored) {
            }
        }
        return v.getClass().getSimpleName() + " (not TPE)";
    }

    /**
     * 网络状态 + 真实连通性探针。
     *
     * 应用侧如果认为"没有网络"，会停在加载态而不发请求——这是"安静卡住"的
     * 另一种解释。这里同时看系统给的能力位和真实 TCP 能不能连出去。
     */
    private static void logNetworkState(final Application app) {
        try {
            final android.content.Context ctx = app == null
                    ? top.niunaijun.blackbox.BlackBoxCore.getContext() : app;
            if (ctx == null) {
                return;
            }
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network n = cm.getActiveNetwork();
                android.net.NetworkCapabilities nc =
                        n == null ? null : cm.getNetworkCapabilities(n);
                top.niunaijun.blackbox.utils.DiagLog.log("Net",
                        "active=" + n + " caps=" + (nc == null ? "null" : nc.toString()));
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Net", "state failed: "
                    + t.getClass().getSimpleName());
        }
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                String host = "www.baidu.com";
                int port = 443;
                long t0 = System.currentTimeMillis();
                java.net.Socket s = new java.net.Socket();
                try {
                    s.connect(new java.net.InetSocketAddress(host, port), 4000);
                    top.niunaijun.blackbox.utils.DiagLog.log("Net",
                            "tcp " + host + ":" + port + " OK in "
                                    + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable e) {
                    top.niunaijun.blackbox.utils.DiagLog.log("Net",
                            "tcp " + host + ":" + port + " FAIL in "
                                    + (System.currentTimeMillis() - t0) + "ms "
                                    + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "adv-net-probe");
        probe.setDaemon(true);
        probe.start();
    }

    /** 每个线程单独一条日志，方便 grep；关键线程给更多栈帧。 */
    private static void logAllThreads(Application app) {
        try {
            java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            top.niunaijun.blackbox.utils.DiagLog.log("Dump", "threads=" + all.size());
            Thread main = android.os.Looper.getMainLooper().getThread();
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                if (t == null) {
                    continue;
                }
                StackTraceElement[] st = e.getValue();
                String nm = t.getName();
                boolean focus = nm != null && (nm.contains("TTNet") || nm.contains("Fresco")
                        || nm.contains("adv-") || nm.contains("Chromium") || nm.contains("Cronet")
                        || nm.contains("player") || nm.contains("mdl") || nm.contains("MediaLoad")
                        || nm.contains("Serial") || nm.contains("okhttp") || nm.contains("OkHttp"));
                int limit = Math.min(st.length, focus ? 40 : 8);
                StringBuilder sb = new StringBuilder(256);
                sb.append('[').append(nm).append("] ").append(t.getState())
                        .append(t == main ? " *MAIN*" : "");
                for (int i = 0; i < limit; i++) {
                    sb.append("\n    at ").append(st[i]);
                }
                if (st.length > limit) {
                    sb.append("\n    ... ").append(st.length - limit).append(" more");
                }
                top.niunaijun.blackbox.utils.DiagLog.log("Dump", sb.toString());
            }
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Dump",
                    "failed " + t.getClass().getSimpleName());
        }
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                "newActivity " + className + " cl=" + (cl == null ? "null" : cl.getClass().getName()));
        try {
            Activity activity = super.newActivity(cl, className, intent);
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "newActivity ok -> " + activity.getClass().getName());
            probeShortSeries(activity, intent, "newActivity");
            return activity;
        } catch (ClassNotFoundException e) {
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "newActivity CNFE " + className + " -> base");
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
