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
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
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

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
        logDecor(activity, "onCreate");
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
        logDecor(activity, "onCreate");
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
        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                "onResume " + activity.getClass().getSimpleName());
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
        dumpMainStackIfStuck(app);
        checkHCallback();
        super.callApplicationOnCreate(app);
        top.niunaijun.blackbox.utils.DiagLog.log("Inst", "application onCreate RETURNED");
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
            java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            int dumped = 0;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                if (t == null || t == Thread.currentThread()) {
                    continue;
                }
                if (dumped++ >= 80) {
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

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                "newActivity " + className + " cl=" + (cl == null ? "null" : cl.getClass().getName()));
        try {
            Activity activity = super.newActivity(cl, className, intent);
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "newActivity ok -> " + activity.getClass().getName());
            return activity;
        } catch (ClassNotFoundException e) {
            top.niunaijun.blackbox.utils.DiagLog.log("Inst",
                    "newActivity CNFE " + className + " -> base");
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
