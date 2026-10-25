package top.niunaijun.blackbox.core.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import top.niunaijun.blackbox.BlackBoxCore;


/**
 * 引擎服务进程（`:black`）的守护服务。
 *
 * 原始实现（2021）只 `startForeground` 了一个没有渠道、没有图标、没有文案的空通知，
 * 在 Android 8+ 上渠道不存在 → 通知被丢弃；Android 14+ 还要求声明
 * `foregroundServiceType`，因此 `startForeground` 直接抛异常、服务启动即失败。
 * 结果是 `:black` 始终是普通后台进程，被 ColorOS 冻结器当缓存进程回收
 * （实测每 45–60 秒一次 `Sync transaction while frozen`）。
 *
 * 现在按规范建渠道 + 图标 + 文案，把 `:black` 提为前台服务（adj 从 905 降到前台级别），
 * 减少被冻结/回收导致的引擎全量重建。
 *
 * 兼容策略：任何一步失败都 `stopSelf()` 静默退出，保持"没有守护服务"的旧行为，
 * 绝不让 `:black` 因前台服务失败而崩溃或 ANR。
 */
public class DaemonService extends Service {
    public static final String TAG = "DaemonService";
    private static final int NOTIFY_ID = BlackBoxCore.getHostPkg().hashCode();
    private static final String CHANNEL_ID = "blackbox_core";
    private static final String CHANNEL_NAME = "引擎运行状态";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!showNotification()) {
            // 前台化失败：直接退出，避免 startForegroundService 的 5 秒超时 ANR
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    public static void start(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClass(context, DaemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务可能被拒；退化成普通服务，不影响引擎功能
            Log.w(TAG, "startForegroundService rejected, fallback to startService: " + t);
            try {
                context.startService(new Intent().setClass(context, DaemonService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    /** @return true 表示已成为前台服务 */
    private boolean showNotification() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("引擎服务运行中")
                    .setContentText("虚拟应用运行时保持引擎在线")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setSilent(true)
                    .build();

            startForeground(NOTIFY_ID, notification);
            Log.i(TAG, "foreground started id=" + NOTIFY_ID);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

    /**
     * 历史遗留的内部服务，仅用于维持进程存在感。
     * 注意：不再 cancel 前台通知（旧实现会把守护通知清掉，等于自废前台身份）。
     */
    public static class DaemonInnerService extends Service {
        @Override
        public void onCreate() {
            Log.i(TAG, "DaemonInnerService -> onCreate");
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.i(TAG, "DaemonInnerService -> onStartCommand");
            stopSelf();
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            Log.i(TAG, "DaemonInnerService -> onDestroy");
            super.onDestroy();
        }
    }
}
