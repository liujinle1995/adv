package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";

    /*
     * ActivityThread$H 的消息常量。
     *
     * 这些字段在 framework 里被标记为 hiddenapi（EXECUTE_TRANSACTION 是 MAX-TARGET-O，
     * 对 targetSdk > 26 的应用等同 BLOCKED），一旦宿主进程的 hidden API 豁免没生效，
     * 反射取值为 null。BlackReflection 的 get() 会把异常吞掉返回 null，
     * 直接拆箱就会在主线程 NPE 把进程打死。
     *
     * 因此这里一律用 "反射取值 + AOSP 字面量兜底"，并且失败只记日志不抛异常。
     */
    private static final int WHAT_LAUNCH_ACTIVITY_FALLBACK = 100;
    private static final int WHAT_EXECUTE_TRANSACTION_FALLBACK = 159;
    private static final int WHAT_CREATE_SERVICE_FALLBACK = 114;

    private static volatile boolean sWhatsResolved;
    private static volatile int sWhatExecuteTransaction = WHAT_EXECUTE_TRANSACTION_FALLBACK;
    private static volatile int sWhatLaunchActivity = WHAT_LAUNCH_ACTIVITY_FALLBACK;
    private static volatile int sWhatCreateService = WHAT_CREATE_SERVICE_FALLBACK;

    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private static void resolveWhats() {
        if (sWhatsResolved) {
            return;
        }
        synchronized (HCallbackProxy.class) {
            if (sWhatsResolved) {
                return;
            }
            // 只解析当前系统分支真正会用到的常量：
            // Android 14 起 ActivityThread$H 已经删掉 LAUNCH_ACTIVITY（统一走 EXECUTE_TRANSACTION），
            // 无脑全量解析会刷出一堆"字段不存在"的假告警。
            if (BuildCompat.isPie()) {
                sWhatExecuteTransaction = readHConstant("EXECUTE_TRANSACTION",
                        WHAT_EXECUTE_TRANSACTION_FALLBACK, true);
            } else {
                sWhatLaunchActivity = readHConstant("LAUNCH_ACTIVITY",
                        WHAT_LAUNCH_ACTIVITY_FALLBACK, true);
            }
            sWhatCreateService = readHConstant("CREATE_SERVICE",
                    WHAT_CREATE_SERVICE_FALLBACK, false);
            sWhatsResolved = true;
        }
    }

    private static int readHConstant(String name, int fallback, boolean critical) {
        try {
            // getWithException：反射失败时抛出真实原因，而不是静默返回 null
            Integer value;
            switch (name) {
                case "EXECUTE_TRANSACTION":
                    value = BRActivityThreadH.getWithException().EXECUTE_TRANSACTION();
                    break;
                case "LAUNCH_ACTIVITY":
                    value = BRActivityThreadH.getWithException().LAUNCH_ACTIVITY();
                    break;
                case "CREATE_SERVICE":
                    value = BRActivityThreadH.getWithException().CREATE_SERVICE();
                    break;
                default:
                    value = null;
                    break;
            }
            if (value != null) {
                return value;
            }
            Slog.w(TAG, "ActivityThread$H." + name + " 反射返回 null，回退字面量 " + fallback);
        } catch (Throwable t) {
            if (critical) {
                // 关键常量取不到说明 hidden API 豁免没生效，带堆栈便于定位
                Slog.w(TAG, "ActivityThread$H." + name + " 反射失败，回退字面量 " + fallback, t);
            } else {
                Slog.w(TAG, "ActivityThread$H." + name + " 不存在，回退字面量 " + fallback);
            }
        }
        return fallback;
    }

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private static java.lang.reflect.Method sExecTransaction;

    /** 直接调用 ActivityThread.executeTransaction，绕开 Handler 分发。 */
    private void executeTransactionDirectly(Object transaction) {
        if (transaction == null) {
            return;
        }
        try {
            if (sExecTransaction == null) {
                Class<?> txCls = Class.forName("android.app.servertransaction.ClientTransaction");
                sExecTransaction = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("executeTransaction", txCls);
                sExecTransaction.setAccessible(true);
            }
            sExecTransaction.invoke(top.niunaijun.blackbox.BlackBoxCore.mainThread(), transaction);
            top.niunaijun.blackbox.utils.DiagLog.log("H", "executeTransaction done");
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("H",
                    "executeTransaction FAIL " + cause.getClass().getName() + ": " + cause.getMessage()
                            + "\n" + android.util.Log.getStackTraceString(cause));
        }
    }

    private static java.lang.reflect.Method sHHandleMessage;

    /** 绕过 Handler 的分发链，直接调用系统 H 的 handleMessage。 */
    private void dispatchToSystemH(Message msg) {
        try {
            if (sHHandleMessage == null) {
                sHHandleMessage = getH().getClass()
                        .getDeclaredMethod("handleMessage", Message.class);
                sHHandleMessage.setAccessible(true);
            }
            sHHandleMessage.invoke(getH(), msg);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("H",
                    "dispatchToSystemH FAIL " + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    private Handler getH() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    private static final java.util.concurrent.atomic.AtomicInteger sMsgSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        // 只在前 40 条消息时记录，用于确认 launch 事务是否真的到达 H。
        if (sMsgSeq.getAndIncrement() < 40) {
            try {
                Object inst = BRActivityThread.get(
                        top.niunaijun.blackbox.BlackBoxCore.mainThread()).mInstrumentation();
                top.niunaijun.blackbox.utils.DiagLog.log("Hmsg",
                        "what=" + msg.what + " execTx=" + sWhatExecuteTransaction
                                + " inst=" + (inst == null ? "null" : inst.getClass().getSimpleName()));
            } catch (Throwable ignored) {
            }
        }
        if (!mBeing.getAndSet(true)) {
            try {
                resolveWhats();
                if (BuildCompat.isPie()) {
                    if (msg.what == sWhatExecuteTransaction) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                        // 交回系统正常分发。
                        // 注意：不要在这里主动 executeTransaction —— 实测会触发
                        // appConfig=null → restartProcess 的进程重启循环。
                        return false;
                    }
                } else {
                    if (msg.what == sWhatLaunchActivity) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (msg.what == sWhatCreateService) {
                    return handleCreateService(msg.obj);
                }
                // launch 相关的消息必须交回系统 H 处理：
                // 如果交给 mOtherCallback（可能是第三方 SDK 设置的），它返回 true 就会把
                // EXECUTE_TRANSACTION 吞掉 → 系统永远不创建 Activity，
                // 表现就是「ActivityRecord 是 RESUMED 但界面从没出现」。
                boolean isLaunchTransaction = BuildCompat.isPie()
                        ? msg.what == sWhatExecuteTransaction
                        : msg.what == sWhatLaunchActivity;
                if (mOtherCallback != null) {
                    boolean handled = mOtherCallback.handleMessage(msg);
                    if (isLaunchTransaction) {
                        top.niunaijun.blackbox.utils.DiagLog.log("H",
                                "otherCallback=" + mOtherCallback.getClass().getName()
                                        + " handled=" + handled + " (launch, 强制交给系统)");
                        return false;
                    }
                    return handled;
                }
                return false;
            } catch (Throwable t) {
                // 这里是主线程 Looper 回调，hook 层任何异常都不允许把宿主进程打死，
                // 交给原始 H.handleMessage 继续处理即可。
                Slog.e(TAG, "handleMessage what=" + msg.what + " 处理失败", t);
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();
        // Android 16：ClientTransaction.mActivityCallbacks 只有 addCallback 后才会初始化，
        // 只带 lifecycle request 的事务这里是 null，直接遍历会 NPE。
        if (mActivityCallbacks == null) {
            return null;
        }

        for (Object obj : mActivityCallbacks) {
            if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                return obj;
            }
        }
        return null;
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            // ClientTransaction
            r = getLaunchActivityItem(client);
        } else {
            // ActivityClientRecord
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        // 这条路径是虚拟 Activity 真正被创建的唯一入口：
        // 只要 activityInfo 为 null，下面整段都不执行，壳会变成空白窗口 → 用户看到黑屏。
        try {
            top.niunaijun.blackbox.utils.DiagLog.log("H",
                    "launch info=" + (activityInfo != null)
                            + " target=" + (stubRecord.mTarget != null)
                            + " appConfig=" + (BActivityThread.getAppConfig() != null)
                            + " pid=" + android.os.Process.myPid()
                            + " intent=" + intent.getComponent());
            diagActivityInfo(activityInfo, stubRecord.mTarget);
            probeWindowSession();
            // 注意：不要再调用 probePackageInfo。
            // 它会真的创建 LoadedApk，而创建之后系统的启动流程会进入
            // appConfig=null → restartProcess 的进程重启循环（实测 launch 32 次）。
        } catch (Throwable ignored) {
        }
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("H",
                        "appConfig null -> restartProcess " + activityInfo.packageName);
                BlackBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                Intent launchIntentForPackage = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo, stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            // bind
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            BlackBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            if (BuildCompat.isPie()) {
                // 把启动参数换成虚拟应用的目标界面，系统随后按它创建虚拟 Activity。
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            }
            if (BuildCompat.isS()) {
                // 部分版本 performLaunchActivity 读的是 mLaunchingActivities 里的 record，
                // 这里能取到就一并改；Android 16 上取不到（返回 null）也不影响上面的 item 修改。
                Object record = BRActivityThread.get(BlackBoxCore.mainThread()).getLaunchingActivity(token);
                if (record != null) {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                    clientRecordContext._set_intent(stubRecord.mTarget);
                    clientRecordContext._set_activityInfo(activityInfo);
                    clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());
                }
                checkActivityClient();
            } else if (!BuildCompat.isPie()) {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }

    /**
     * 打印 ActivityThread 的关键成员是否已初始化。
     *
     * getPackageInfoNoCheck 抛 "null receiver" 说明 ActivityThread 实例上有成员是 null，
     * 而这些成员是系统创建 Activity 的必要条件。
     */
    private void dumpActivityThreadFields() {
        String[] names = {
                "mResourcesManager", "mPackages", "mResourcePackages",
                "mBoundApplication", "mInitialApplication", "mInstrumentation",
                "mSystemThread", "mSystemContext"
        };
        Object at = top.niunaijun.blackbox.BlackBoxCore.mainThread();
        StringBuilder sb = new StringBuilder("ActivityThread.");
        for (String name : names) {
            String value;
            try {
                Field f = at.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(at);
                value = (v == null ? "null" : v.getClass().getSimpleName());
            } catch (Throwable t) {
                value = "err:" + t.getClass().getSimpleName();
            }
            sb.append(name).append('=').append(value).append(' ');
        }
        top.niunaijun.blackbox.utils.DiagLog.log("AT", sb.toString());
    }

    /**
     * 用系统自己的入口复现 Activity 创建的前置步骤。
     *
     * ActivityThread.performLaunchActivity 的第一步就是
     * getPackageInfoNoCheck(activityInfo.applicationInfo, compat)，
     * 再用返回的 LoadedApk.getClassLoader() 去加载目标类。
     * 这两步任何一步失败，异常都会被系统事务执行器吞掉，界面永远不出现。
     * 这里主动走一遍并把结果写进日志。
     */
    private void probePackageInfo(ActivityInfo info, Intent target) {
        try {
            if (info == null || info.applicationInfo == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("P", "appInfo=null，跳过探测");
                return;
            }
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Class<?> compatCls = Class.forName("android.content.res.CompatibilityInfo");
            Object compat = compatCls.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
            java.lang.reflect.Method m = atCls.getDeclaredMethod(
                    "getPackageInfoNoCheck", android.content.pm.ApplicationInfo.class, compatCls);
            m.setAccessible(true);
            // Android 17 上它可能是实例方法：传 null 会得到 "null receiver"，
            // 那是探测方式的问题，不是系统的真实故障，必须按实际签名区分。
            Object receiver = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    ? null : top.niunaijun.blackbox.BlackBoxCore.mainThread();
            Object loadedApk = m.invoke(receiver, info.applicationInfo, compat);
            if (loadedApk == null) {
                top.niunaijun.blackbox.utils.DiagLog.log("P", "getPackageInfoNoCheck -> null");
                return;
            }
            ClassLoader cl = (ClassLoader) loadedApk.getClass()
                    .getMethod("getClassLoader").invoke(loadedApk);
            String targetCls = target != null && target.getComponent() != null
                    ? target.getComponent().getClassName() : null;
            String loadResult = "n/a";
            if (targetCls != null) {
                try {
                    Class.forName(targetCls, false, cl);
                    loadResult = "OK";
                } catch (Throwable t) {
                    loadResult = t.getClass().getSimpleName();
                }
            }
            top.niunaijun.blackbox.utils.DiagLog.log("P",
                    "loadedApk ok, cl=" + (cl == null ? "null" : cl.getClass().getName())
                            + " target=" + targetCls + " load=" + loadResult);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("P",
                    "getPackageInfoNoCheck FAIL " + cause.getClass().getName()
                            + ": " + cause.getMessage());
            top.niunaijun.blackbox.utils.DiagLog.log("P",
                    "stack:" + android.util.Log.getStackTraceString(cause));
            dumpActivityThreadFields();
        }
    }

    /**
     * 主动探测窗口会话能否建立。
     *
     * ActivityThread 在创建 Activity 之前会先 WindowManagerGlobal.initialize()，
     * 它内部要走 WMS 打开 IWindowSession。沙箱进程的 uid 与宿主不同，
     * 一旦这一步失败，异常会被系统事务执行器吞掉 —— Activity 永远不创建，
     * 也没有任何窗口，用户看到的就是整屏黑。
     */
    private void probeWindowSession() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            java.lang.reflect.Method init = wmg.getDeclaredMethod("initialize");
            init.setAccessible(true);
            init.invoke(null);
            java.lang.reflect.Method getSession = wmg.getDeclaredMethod("getWindowSession");
            getSession.setAccessible(true);
            Object session = getSession.invoke(null);
            top.niunaijun.blackbox.utils.DiagLog.log("H",
                    "WMG initialize ok, session=" + (session == null ? "null"
                            : session.getClass().getName()));
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    ? t.getCause() : t;
            top.niunaijun.blackbox.utils.DiagLog.log("H",
                    "WMG initialize FAIL " + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    /**
     * 记录创建虚拟 Activity 所依赖的关键信息。
     *
     * 系统侧 performLaunchActivity 会用 activityInfo.applicationInfo 去解析 APK，
     * 用 Intent 里的类名去加载类；任何一项不成立都会在系统内部抛异常并被吞掉，
     * 表现为「ActivityRecord 是 RESUMED，但界面从没被创建」。
     */
    private void diagActivityInfo(ActivityInfo info, Intent target) {
        try {
            StringBuilder sb = new StringBuilder();
            if (info == null) {
                sb.append(" info=null");
            } else {
                sb.append(" pkg=").append(info.packageName)
                        .append(" cls=").append(info.name)
                        .append(" proc=").append(info.processName);
                if (info.applicationInfo == null) {
                    sb.append(" appInfo=null");
                } else {
                    String src = info.applicationInfo.sourceDir;
                    sb.append(" src=").append(src)
                            .append(" exists=").append(src != null && new java.io.File(src).isFile())
                            .append(" dataDir=").append(info.applicationInfo.dataDir);
                }
            }
            if (target != null && target.getComponent() != null) {
                String targetCls = target.getComponent().getClassName();
                boolean loadable;
                try {
                    // 优先用当前进程真实的 App ClassLoader；取不到就退回 Context 的
                    ClassLoader appLoader = BActivityThread.currentActivityThread()
                            .getClassLoaderForApp();
                    if (appLoader == null) {
                        appLoader = top.niunaijun.blackbox.BlackBoxCore.getContext().getClassLoader();
                    }
                    Class.forName(targetCls, false, appLoader);
                    loadable = true;
                } catch (Throwable t) {
                    loadable = false;
                }
                sb.append(" | targetCls=").append(targetCls).append(" loadable=").append(loadable);
            }
            top.niunaijun.blackbox.utils.DiagLog.log("H", sb.toString());
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("H", "diag failed " + t);
        }
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                BlackBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
