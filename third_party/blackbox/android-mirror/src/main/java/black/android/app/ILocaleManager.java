package black.android.app;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

/**
 * android.app.ILocaleManager（API 33+）。
 *
 * 虚拟应用调 LocaleManager.setApplicationLocales() 时，最终会走这个 binder。
 * 系统侧会按包名去 PackageManager 里找，虚拟包在真实系统里查不到，
 * 直接抛 IllegalArgumentException("Unknown package: xxx for user 0")，
 * 调用方若在 <clinit> 里调（哔哩哔哩就是这样），就会升级成
 * ExceptionInInitializerError → NoClassDefFoundError → 进程崩溃。
 */
@BClassName("android.app.ILocaleManager")
public interface ILocaleManager {
    @BClassName("android.app.ILocaleManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
