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
            for (int i = 1; i <= 90; i++) {
                final int seconds = i * 2;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        int contentChildren = -1;
                        try {
                            android.view.View content = activity.findViewById(android.R.id.content);
                            if (content instanceof android.view.ViewGroup) {
                                contentChildren = ((android.view.ViewGroup) content).getChildCount();
                            }
                        } catch (Throwable ignored) {
                        }
                        top.niunaijun.blackbox.utils.DiagLog.log("Life",
                                "watch " + name + " contentChildren=" + contentChildren
                                        + " t=" + seconds + "s");
                    }
                }, seconds * 1000L);
            }
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
    private static void initHttpStack(Application app) {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> e = cl.loadClass("com.dragon.read.base.http.e");
            Class<?> providerBase = cl.loadClass("com.dragon.read.base.http.e$b");
            Class<?> m0 = cl.loadClass("com.dragon.read.app.launch.task.m0");
            Class<?> providerCls = cl.loadClass("com.dragon.read.app.launch.task.m0$a");
            Object outer = m0.getDeclaredConstructor().newInstance();
            Object provider = null;
            try {
                java.lang.reflect.Constructor<?> c = providerCls.getDeclaredConstructor(
                        m0, Application.class);
                c.setAccessible(true);
                provider = c.newInstance(outer, app);
            } catch (Throwable ignored) {
            }
            if (provider == null) {
                java.lang.reflect.Constructor<?> c = providerCls.getDeclaredConstructor(
                        Application.class);
                c.setAccessible(true);
                provider = c.newInstance(app);
            }

            java.lang.reflect.Field fa = e.getDeclaredField("a");
            fa.setAccessible(true);
            fa.set(null, provider);

            e.getMethod("f").invoke(null);
            e.getMethod("n", android.content.Context.class).invoke(null, app);
            e.getMethod("m", android.content.Context.class, Application.class).invoke(null, app, app);
            e.getMethod("l").invoke(null);

            java.lang.reflect.Field fb = e.getDeclaredField("b");
            fb.setAccessible(true);
            fb.setBoolean(null, true);

            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "http stack done (cronet skipped)");
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("Http",
                    "initHttpStack failed: " + cause.getClass().getName() + " " + cause.getMessage());
        }
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
