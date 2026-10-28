package top.niunaijun.blackbox.fake.service;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.LocaleList;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.DiagLog;

/**
 * locale 系统服务代理（API 33+）。
 *
 * 背景：`LocaleManager.setApplicationLocales()` 是「按包名」操作的，
 * 系统侧 LocaleManagerService 会先 verifyInstalledPackage(packageName, userId)，
 * 虚拟包的包名在真实 PackageManager 里根本不存在，于是抛
 *   IllegalArgumentException: Unknown package: tv.danmaku.bili for user 0
 *
 * 哔哩哔哩把这段逻辑放在了 `kntr.base.localization.Localization` 的静态初始化里，
 * 于是异常升级为 ExceptionInInitializerError → NoClassDefFoundError，
 * 进程在 Application 起来后几百毫秒内就崩了（闪退，界面上什么都看不到）。
 *
 * 处理原则：
 * - 写操作（set/override）不落到系统侧，只在沙箱内记一份，供 get 自洽；
 * - 读操作返回沙箱内记的值，没有则返回空列表（AOSP 契约：空 = 没有 per-app 覆盖）；
 * - 其余方法（如 getSystemLocales）不拦，直接转发真实服务 —— 系统级语言本来就该共用。
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public class ILocaleManagerProxy extends BinderInvocationStub {

    /** Context.LOCALE_SERVICE，API 33 才有这个常量，这里直接用字面量避免编译期依赖。 */
    private static final String SERVICE_NAME = "locale";

    /** 虚拟包 -> 它自己设置过的 per-app locale；系统侧不落任何东西。 */
    private static final ConcurrentHashMap<String, LocaleList> sLocales = new ConcurrentHashMap<>();

    private static final AtomicInteger sLogged = new AtomicInteger();

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(
                BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static void logOnce(String action, Object[] args) {
        // 只记前几条，避免应用高频调用时刷屏把日志写满
        if (sLogged.incrementAndGet() > 5) {
            return;
        }
        String pkg = (args != null && args.length > 0 && args[0] instanceof String)
                ? (String) args[0] : "?";
        DiagLog.log("Locale", action + " pkg=" + pkg + " (virtual, not forwarded)");
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String pkg = (String) args[0];
                LocaleList list = null;
                for (Object arg : args) {
                    if (arg instanceof LocaleList) {
                        list = (LocaleList) arg;
                        break;
                    }
                }
                if (list != null) {
                    sLocales.put(pkg, list);
                } else {
                    sLocales.remove(pkg);
                }
                logOnce("set", args);
                return null;
            }
            // 参数形态不符预期时交回真实服务，保持原行为
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                LocaleList list = sLocales.get((String) args[0]);
                return list != null ? list : LocaleList.getEmptyLocaleList();
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setOverrideLocaleConfig")
    public static class SetOverrideLocaleConfig extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            logOnce("setOverrideLocaleConfig", args);
            return null;
        }
    }

    @ProxyMethod("getOverrideLocaleConfig")
    public static class GetOverrideLocaleConfig extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // 覆盖配置只影响「系统设置里能给这个 App 选哪些语言」，沙箱内不需要
            return null;
        }
    }
}
