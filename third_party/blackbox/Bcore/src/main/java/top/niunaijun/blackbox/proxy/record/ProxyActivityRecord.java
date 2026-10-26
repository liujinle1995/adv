package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;

import top.niunaijun.blackbox.utils.compat.BundleCompat;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyActivityRecord {
    public int mUserId;
    public ActivityInfo mActivityInfo;
    public Intent mTarget;
    public IBinder mActivityRecord;

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord) {
        mUserId = userId;
        mActivityInfo = activityInfo;
        mTarget = target;
        mActivityRecord = activityRecord;
    }

    public static void saveStub(Intent shadow, Intent target, ActivityInfo activityInfo, IBinder activityRecord, int userId) {
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_activity_info_", activityInfo);
        shadow.putExtra("_B_|_target_", target);
        BundleCompat.putBinder(shadow, "_B_|_activity_record_v_", activityRecord);
    }

    public static ProxyActivityRecord create(Intent intent) {
        // 跨进程传过来的 extras 必须显式指定 ClassLoader。
        // Android 13 起 getParcelableExtra(String) 已废弃，内部走类型校验，
        // 对没有正确 ClassLoader 的 Intent 会直接返回 null（Android 17 上更严格）。
        // 一旦 mTarget 为 null，ProxyActivity 会什么都不做 → 虚拟应用整屏黑。
        intent.setExtrasClassLoader(ProxyActivityRecord.class.getClassLoader());
        int userId = intent.getIntExtra("_B_|_user_id_", 0);
        ActivityInfo activityInfo = getParcelable(intent, "_B_|_activity_info_", ActivityInfo.class);
        Intent target = getParcelable(intent, "_B_|_target_", Intent.class);
        IBinder activityRecord = BundleCompat.getBinder(intent, "_B_|_activity_record_v_");
        return new ProxyActivityRecord(userId, activityInfo, target, activityRecord);
    }

    @SuppressWarnings("deprecation")
    private static <T extends Parcelable> T getParcelable(Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, clazz);
        }
        return (T) intent.getParcelableExtra(key);
    }
}
