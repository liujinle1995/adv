package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;
import top.canyie.pine.xposed.PineXposed;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.core.CrashHandler;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.VirtualRuntime;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.am.ReceiverData;
import top.niunaijun.blackbox.entity.pm.InstalledModule;
import top.niunaijun.blackbox.fake.delegate.AppInstrumentation;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;
import top.niunaijun.blackbox.fake.frameworks.BXposedManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.utils.compat.StrictModeCompat;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    /** 最近一次 onResume 的虚拟 Activity（诊断遍历 View 树用）。 */
    public static volatile android.app.Activity sTopActivity;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = BlackBoxCore.get().getHandler();
    private static final Object mConfigLock = new Object();

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? BlackBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                // 该进程已被attach
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mConfigLock) {
                            try {
                                iBinder.linkToDeath(this, 0);
                            } catch (RemoteException ignored) {
                            }
                            mAppConfig = null;
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /** 供诊断使用：当前进程里加载虚拟应用类的 ClassLoader（取不到返回 null）。 */
    public ClassLoader getClassLoaderForApp() {
        try {
            if (mBoundApplication == null || mBoundApplication.info == null) {
                return null;
            }
            return BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        Service service;
        try {
            service = (Service) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create service " + serviceInfo.name
                            + ": " + e.toString(), e);
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        JobService service;
        try {
            service = (JobService) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    BActivityThread.currentActivityThread().getActivityThread(),
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create JobService " + serviceInfo.name
                            + ": " + e.toString(), e);
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            BlackBoxCore.get().getHandler().post(() -> {
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        // hidden API 豁免必须尽早做：后面改进程名要反射写 android.os.Process 的
        // 隐藏字段，而 Android 9+ 默认把隐藏成员从 getDeclaredFields 里过滤掉、
        // setAccessible 直接抛异常（实测 bindApplication 阶段改不动，要等到应用
        // 自己初始化之后才行，那时应用早已把错误的进程名缓存下来了）。
        try {
            boolean ok = top.niunaijun.blackbox.utils.HiddenApiUnsealer
                    .unseal(BlackBoxCore.getContext());
            step("unseal=" + ok + " probe="
                    + top.niunaijun.blackbox.utils.HiddenApiUnsealer.isUnsealed());
        } catch (Throwable t) {
            step("unseal FAIL " + t.getClass().getSimpleName());
        }
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }

        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        // Android 14+ 的 ART 拒绝加载可写 apk；已经安装过的实例可能还留着可写状态，
        // 每次启动前统一去掉写位，否则 LoadedApk.makeApplication 直接失败。
        makeApkReadOnly(applicationInfo);
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));

        Object boundApplication = BRActivityThread.get(BlackBoxCore.mainThread()).mBoundApplication();

        Context packageContext = createPackageContext(applicationInfo);
        Object loadedApk = resolveLoadedApk(applicationInfo, packageContext);
        top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                "pkg=" + packageName
                        + " ctxPkg=" + (packageContext == null ? "null" : packageContext.getPackageName())
                        + " apkPkg=" + loadedApkPackageName(loadedApk));
        diagLoadedApk(loadedApk, applicationInfo);
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        // fix applicationInfo
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);
        step("fixed appInfo");

        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(getUserId() + ":" + packageName + ":" + processName);
        }

        VirtualRuntime.setupRuntime(processName, applicationInfo);
        step("VirtualRuntime");

        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }

        NativeCore.init(Build.VERSION.SDK_INT);
        step("NativeCore");
        try {
            String status = NativeCore.hookProcessName(processName);
            step("hookProcessName " + status);
            // 内核 argv 已改，但 Java 侧 myProcessName() 读的是静态缓存，
            // 这里用原生方式再写一次（Java 反射在此阶段被 hidden-api 挡住）。
            Class<?> processCls = Class.forName("android.os.Process");
            step("Process fields: " + NativeCore.dumpStaticFields(processCls));
            step("setArgV0: " + NativeCore.setStaticStringField(
                    processCls, "sArgV0", processName)
                    + " | now=" + android.os.Process.myProcessName());
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                    "hookProcessName FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        // 顺序很重要：进程名要先在内核里改正，再让任何代码去读它。
        // Process.myProcessName() 会把读到的值缓存下来，一旦在改名之前被读过
        // （旧版本的诊断日志就踩过这个坑），应用后面永远拿到 "宿主:p0"，
        // 于是判定「我不是主进程」，启动页不干活、初始化走安全模式分支。
        fixProcessNameCaches(processName, "after-hook");
        diagProcessIdentity(processName);
        assert packageContext != null;
        IOCore.get().enableRedirect(packageContext);
        step("IOCore");

        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;

        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);

        mBoundApplication = bindData;
        step("appBindData");

        //ssl适配
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);
            step("onBeforeCreateApplication");
            // 用 Reflector 而不是 BlackReflection：后者的 get() 会把异常吞成 null，
            // makeApplication 一旦失败只会得到一个莫名的 NPE（ContextCompat.fix(null)），
            // 真实原因（LoadedApk/Instrumentation 在 Android 14+ 的变化）完全看不到。
            application = Reflector.with(loadedApk)
                    .method("makeApplication", boolean.class, Instrumentation.class)
                    .call(false, null);
            step("makeApplication -> " + (application == null ? "null" : application.getClass().getName()));
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
            ContextCompat.fix((Context) BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext());
            ContextCompat.fix(mInitialApplication);
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            step("installProviders");

            onBeforeApplicationOnCreate(packageName, processName, application);
            AppInstrumentation.get().callApplicationOnCreate(application);
            step("callApplicationOnCreate");
            onAfterApplicationOnCreate(packageName, processName, application);

            // Instrumentation 那条路是否真的被系统走到并不确定（不同版本差异较大），
            // 这里用最权威的 Application 级回调再记一遍：Activity 到底收到了哪些
            // 生命周期。启动页卡死这类问题，这一份日志就能定性。
            installLifecycleProbe(application);

            HookManager.get().checkEnv(HCallbackProxy.class);
            step("checkEnv HCallbackProxy");
        } catch (Throwable e) {
            top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                    "EXCEPTION " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }

    private static void step(String name) {
        top.niunaijun.blackbox.utils.DiagLog.log("Bind", "step " + name);
    }

    // 注：曾尝试用 Pine 直接替换 Process.myProcessName() / ActivityThread.currentProcessName()
    // 来伪造进程名，实测在 Android 17 上会让虚拟进程立刻 native 崩溃
    // （dropbox: data_app_native_crash），所以放弃这条路径。
    // 当前结论：进程名在 bindApplication 阶段既写不进去（隐藏字段被过滤，
    // 反射与 JNI 都拿不到），也不能改方法体；应用侧看到的是 "宿主:p0"。

    /**
     * 用 Application.ActivityLifecycleCallbacks 记录虚拟 Activity 的真实生命周期。
     *
     * 为什么不用 Instrumentation 的打点做唯一判据：不同系统版本上 ActivityThread
     * 走哪条 Instrumentation 实例并不一致（实测 :p0 里挂的是 RouteInstrumentation，
     * 而不是我们注入的 AppInstrumentation），所以那里没日志并不能证明回调没到。
     * Application 层的回调是最终事实。
     */
    private static void installLifecycleProbe(Application application) {
        try {
            application.registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        private void log(String phase, Activity a) {
                            try {
                                String extra = "";
                                if ("onActivityResumed".equals(phase)
                                        || "onActivityCreated".equals(phase)) {
                                    android.view.View decor = a.getWindow() == null
                                            ? null : a.getWindow().peekDecorView();
                                    extra = " decorChildren="
                                            + (decor instanceof android.view.ViewGroup
                                            ? ((android.view.ViewGroup) decor).getChildCount() : -1)
                                            + " attached=" + (decor != null && decor.isAttachedToWindow());
                                }
                                top.niunaijun.blackbox.utils.DiagLog.log("ALife",
                                        phase + " " + a.getClass().getSimpleName() + extra);
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        public void onActivityCreated(Activity a, Bundle b) {
                            log("onActivityCreated", a);
                        }

                        @Override
                        public void onActivityStarted(Activity a) {
                            log("onActivityStarted", a);
                        }

                        @Override
                        public void onActivityResumed(Activity a) {
                            log("onActivityResumed", a);
                            sTopActivity = a;
                        }

                        @Override
                        public void onActivityPaused(Activity a) {
                            log("onActivityPaused", a);
                        }

                        @Override
                        public void onActivityStopped(Activity a) {
                            log("onActivityStopped", a);
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity a, Bundle b) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity a) {
                            log("onActivityDestroyed", a);
                        }
                    });
            step("lifecycleProbe installed");
        } catch (Throwable t) {
            step("lifecycleProbe FAIL " + t.getClass().getSimpleName());
        }
    }

    /**
     * 把「进程身份在三处不同来源里的实际值」一次性记下来。
     *
     * 应用判断「我是不是主进程」时会用到不同来源，而它们在沙箱里的表现并不一致：
     *   - ActivityThread.currentProcessName()：系统静态缓存，可能还是宿主 :p0
     *   - Process.myProcessName()：读 /proc/self/cmdline，走 IO 重定向后应为虚拟应用
     *   - /proc/self/cmdline 原文：验证重定向是否真的生效
     * 排查这类兼容问题时如果不把三处对齐看，很容易改错地方。
     */
    private static void diagProcessIdentity(String expected) {
        try {
            StringBuilder sb = new StringBuilder("expected=").append(expected);
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            try {
                sb.append(" currentProcessName=").append(
                        atCls.getMethod("currentProcessName").invoke(null));
            } catch (Throwable t) {
                sb.append(" currentProcessName=ERR(").append(t.getClass().getSimpleName()).append(")");
            }
            try {
                sb.append(" myProcessName=").append(android.os.Process.myProcessName());
            } catch (Throwable t) {
                sb.append(" myProcessName=ERR(").append(t.getClass().getSimpleName()).append(")");
            }
            sb.append(" cmdline=").append(readCmdline());
            // 顺便把 ActivityThread 里所有非空 String 静态字段打出来，
            // 用来确认新版本把这个值挪到了哪个字段上。
            try {
                for (java.lang.reflect.Field f : atCls.getDeclaredFields()) {
                    if (f.getType() != String.class
                            || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v != null && ((String) v).contains("com.adv")) {
                            sb.append(" field.").append(f.getName()).append("=").append(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Ident", sb.toString());
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Ident", "diag failed " + t);
        }
    }

    private static String readCmdline() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File("/proc/self/cmdline"));
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                if (n <= 0) {
                    return "<empty>";
                }
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                return new String(buf, 0, end, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "<err " + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 尝试让 ActivityThread.currentProcessName() 返回虚拟应用的进程名。
     *
     * 动机：:p0 进程是宿主 fork 出来的，系统在进程启动时把进程名设成了
     * "com.adv.runner:p0"。部分应用靠这个值判断「我是不是主进程」来决定要不要
     * 初始化主进程任务，判定为否就可能跳过，而主线程仍在等那批任务完成 ——
     * 表现为进程活着、CPU 0%、Application.onCreate 永不返回。
     *
     * 注意：Android 17 上 ActivityThread 已经没有 sCurrentProcessName 字段
     * （此处会打印 field missing 后跳过）。系统实际走的是
     * mBoundApplication.processName，我们在 bindData 里已经设对了，
     * 所以新系统上不依赖这里。保留是为了兼容仍使用该静态字段的旧版本。
     */
    private static void fixCurrentProcessName(String processName) {
        String actual = readCmdline();
        StringBuilder hit = new StringBuilder();
        for (String cls : new String[]{"android.os.Process", "android.app.ActivityThread"}) {
            try {
                Class<?> c = Class.forName(cls);
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (f.getType() != String.class
                                || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (!(v instanceof String)) {
                                continue;
                            }
                            // 只改「确实是本进程名」的字段，避免误伤别的常量
                            if (!v.equals(actual) && !"com.adv.runner:p0".equals(v)) {
                                continue;
                            }
                            f.set(null, processName);
                            if (hit.length() > 0) {
                                hit.append(',');
                            }
                            hit.append(c.getSimpleName()).append('.').append(f.getName())
                                    .append("=").append(v);
                        } catch (Throwable ignored) {
                        }
                    }
                    c = c.getSuperclass();
                }
            } catch (Throwable ignored) {
            }
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                "processName fix -> " + (hit.length() == 0 ? "no field matched" : hit.toString())
                        + " | currentProcessName=" + currentProcessNameSafe()
                        + " myProcessName=" + android.os.Process.myProcessName());
    }

    private static String currentProcessNameSafe() {
        try {
            return String.valueOf(Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName").invoke(null));
        } catch (Throwable t) {
            return "ERR(" + t.getClass().getSimpleName() + ")";
        }
    }

    // 诊断自己的类加载器里不存在的旧值，避免写死字符串。
    private static final String[] OLD_PROCESS_NAMES = {
            "com.adv.runner", "com.adv.runner:p0", "com.adv.runner:black"
    };

    /**
     * 把「已经缓存了宿主进程名」的静态字段改写掉。
     *
     * Process.myProcessName() / ActivityThread.currentProcessName() 在部分系统版本上
     * 会把第一次读到的值存进静态字段。只要有任何代码在改内核 cmdline 之前读过一次
     * （诊断日志最容易踩），应用之后拿到的永远是 "宿主:p0"，于是把自己当成子进程：
     * 启动页不执行跳转逻辑、初始化框架走安全模式分支并死等。
     *
     * 这里不猜字段名，直接扫这几个系统类里的静态 String，命中旧值就改写并记下来。
     */
    public static void fixProcessNameCaches(String virtualName, String tag) {
        StringBuilder hit = new StringBuilder();
        // 已确认（Android 17 实测）：Process.myProcessName() 与
        // ActivityThread.currentProcessName() 都走 android.os.Process.sArgV0。
        // 这个字段一旦被填成 "宿主:p0"，后面怎么改内核 cmdline 都没用，
        // 所以这里无条件覆盖，而不是等它变成旧值再改。
        try {
            Class<?> processCls = Class.forName("android.os.Process");
            java.lang.reflect.Field f = processCls.getDeclaredField("sArgV0");
            f.setAccessible(true);
            Object old = f.get(null);
            f.set(null, virtualName);
            hit.append("sArgV0=").append(old).append("->").append(virtualName);
        } catch (Throwable ignored) {
        }
        for (String cls : new String[]{
                "android.os.Process", "android.app.ActivityThread",
                "android.ddm.DdmHandleAppName", "android.app.Application"}) {
            try {
                Class<?> c = Class.forName(cls);
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (f.getType() != String.class
                                || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (!(v instanceof String)) {
                                continue;
                            }
                            String s = (String) v;
                            boolean stale = false;
                            for (String old : OLD_PROCESS_NAMES) {
                                if (old.equals(s)) {
                                    stale = true;
                                    break;
                                }
                            }
                            if (!stale || s.equals(virtualName)) {
                                continue;
                            }
                            f.set(null, virtualName);
                            if (hit.length() > 0) {
                                hit.append(',');
                            }
                            hit.append(c.getSimpleName()).append('.').append(f.getName())
                                    .append(':').append(s);
                        } catch (Throwable ignored) {
                        }
                    }
                    c = c.getSuperclass();
                }
            } catch (Throwable ignored) {
            }
        }
        top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                "nameCache[" + tag + "] "
                        + (hit.length() == 0 ? "none" : hit.toString())
                        + " | now=" + android.os.Process.myProcessName()
                        + " cur=" + currentProcessNameSafe());
    }

    /** 确认 LoadedApk 的 ClassLoader 能否真正加载虚拟应用的 Application 类。 */
    private static void diagLoadedApk(Object loadedApk, ApplicationInfo applicationInfo) {
        try {
            ClassLoader cl = BRLoadedApk.get(loadedApk).getClassLoader();
            StringBuilder sb = new StringBuilder("ldr=")
                    .append(cl == null ? "null" : cl.getClass().getName())
                    .append(" appCls=").append(applicationInfo.className);
            if (cl != null && applicationInfo.className != null) {
                try {
                    cl.loadClass(applicationInfo.className);
                    sb.append(" load=OK");
                } catch (Throwable t) {
                    sb.append(" load=FAIL ").append(t.getClass().getSimpleName());
                }
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Bind", sb.toString());
            // 加固壳几乎都要 System.loadLibrary，so 没解压出来就会在 onCreate 里
            // 反复重试或直接挂住，把目录实际情况记下来省掉一轮猜测。
            java.io.File libDir = new java.io.File(applicationInfo.nativeLibraryDir);
            String[] libs = libDir.isDirectory() ? libDir.list() : null;
            top.niunaijun.blackbox.utils.DiagLog.log("Bind", "libDir=" + applicationInfo.nativeLibraryDir
                    + " isDir=" + libDir.isDirectory()
                    + " libs=" + (libs == null ? "n/a" : java.util.Arrays.toString(libs)));
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Bind", "diagLoadedApk ERR " + t);
        }
    }

    private static void makeApkReadOnly(ApplicationInfo info) {
        if (info == null) {
            return;
        }
        FileUtils.makeReadOnly(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) {
                FileUtils.makeReadOnly(split);
            }
        }
    }

    /**
     * 取虚拟应用自己的 LoadedApk。
     *
     * 不能直接用 createPackageContext 拿到的那个：宿主里没有虚拟应用的包记录时，
     * 它返回的是**宿主自己的 LoadedApk**，其 mApplication 已经是宿主的 Application。
     * 这样 makeApplication() 会直接返回缓存的宿主 Application，
     * 虚拟应用的 Application 永远不会被创建 —— 后续系统创建界面时拿不到应用上下文，
     * 界面就一直出不来。
     *
     * 改用系统自己在 performLaunchActivity 里用的入口 getPackageInfoNoCheck：
     * 它以 ApplicationInfo.packageName 为 key 建/取 LoadedApk，
     * 拿到的是加载虚拟 APK 的那一份。
     */
    private static Object resolveLoadedApk(ApplicationInfo applicationInfo, Context packageContext) {
        Object fromSystem = null;
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Class<?> compatCls = Class.forName("android.content.res.CompatibilityInfo");
            Object compat = compatCls.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
            java.lang.reflect.Method m = atCls.getDeclaredMethod(
                    "getPackageInfoNoCheck", ApplicationInfo.class, compatCls);
            m.setAccessible(true);
            Object receiver = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    ? null : BlackBoxCore.mainThread();
            fromSystem = m.invoke(receiver, applicationInfo, compat);
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Bind",
                    "getPackageInfoNoCheck FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        if (fromSystem != null && applicationInfo.packageName.equals(loadedApkPackageName(fromSystem))) {
            return fromSystem;
        }
        // 回退：老路子（可能拿到宿主的 LoadedApk，仅作兜底）
        top.niunaijun.blackbox.utils.DiagLog.log("Bind", "fallback to context loadedApk");
        return BRContextImpl.get(packageContext).mPackageInfo();
    }

    /** 读取 LoadedApk 的包名，用于确认拿到的到底是谁的那一份。 */
    private static String loadedApkPackageName(Object loadedApk) {
        if (loadedApk == null) {
            return "null";
        }
        try {
            Object info = loadedApk.getClass().getMethod("getApplicationInfo").invoke(loadedApk);
            return info == null ? "unknown" : ((ApplicationInfo) info).packageName;
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public Object getPackageInfo() {
        return mBoundApplication.info;
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }

    public void loadXposed(Context context) {
        String vPackageName = getAppPackageName();
        String vProcessName = getAppProcessName();
        if (!TextUtils.isEmpty(vPackageName) && !TextUtils.isEmpty(vProcessName) && BXposedManager.get().isXPEnable()) {
            assert vPackageName != null;
            assert vProcessName != null;

            boolean isFirstApplication = vPackageName.equals(vProcessName);

            List<InstalledModule> installedModules = BXposedManager.get().getInstalledModules();
            for (InstalledModule installedModule : installedModules) {
                if (!installedModule.enable) {
                    continue;
                }
                try {
                    PineXposed.loadModule(new File(installedModule.getApplication().sourceDir));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            try {
                PineXposed.onPackageLoad(vPackageName, vProcessName, context.getApplicationInfo(), isFirstApplication, context.getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        if (BlackBoxCore.get().isHideXposed()) {
            NativeCore.hideXposed();
        }
    }

    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(BlackBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) throws RemoteException {

    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (!isInit()) {
            bindApplication(BActivityThread.getAppConfig().packageName, BActivityThread.getAppConfig().processName);
        }
        String[] split = providerInfo.authority.split(";");
        for (String auth : split) {
            ContentProviderClient contentProviderClient = BlackBoxCore.getContext()
                    .getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(contentProviderClient).mContentProvider();
            if (iInterface == null)
                continue;
            return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
            if (activities.isEmpty())
                return;
            Object clientRecord = activities.get(token);
            if (clientRecord == null)
                return;
            Activity activity = getActivityByToken(token);

            while (activity.getParent() != null) {
                activity = activity.getParent();
            }

            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                newIntent = BRReferrerIntent.get()._new(intent, BlackBoxCore.getHostPkg());
            } else {
                newIntent = intent;
            }
            Object mainThread = BlackBoxCore.mainThread();
            if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent)
                );
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent),
                        true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(ReceiverData data) throws RemoteException {
        if (!isInit()) {
            bindApplication();
        }
        mH.post(() -> {
            BroadcastReceiver mReceiver = null;
            Intent intent = data.intent;
            ActivityInfo activityInfo = data.activityInfo;
            BroadcastReceiver.PendingResult pendingResult = data.data.build();

            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader classLoader = baseContext.getClassLoader();
                intent.setExtrasClassLoader(classLoader);

                mReceiver = (BroadcastReceiver) classLoader.loadClass(activityInfo.name).newInstance();
                BRBroadcastReceiver.get(mReceiver).setPendingResult(pendingResult);
                mReceiver.onReceive(baseContext, intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(mReceiver).getPendingResult();
                if (finish != null) {
                    finish.finish();
                }
                BlackBoxCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                Slog.e(TAG,
                        "Error receiving broadcast " + intent
                                + " in " + mReceiver);
            }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> iBinderObjectMap =
                BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(iBinderObjectMap.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeCreateApplication(packageName, processName, context, BActivityThread.getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}
