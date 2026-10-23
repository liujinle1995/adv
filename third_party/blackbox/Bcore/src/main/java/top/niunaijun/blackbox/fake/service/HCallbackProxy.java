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

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                resolveWhats();
                if (BuildCompat.isPie()) {
                    if (msg.what == sWhatExecuteTransaction) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
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
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
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
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
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
                // Android 16 的 LaunchActivityItem.execute 会用它自己的 mIntent/mInfo
                // 构造出 ActivityClientRecord，再进 performLaunchActivity，
                // 所以 item 上的 intent 必须改（否则 proxy 只是在起自己，形成死循环）。
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
