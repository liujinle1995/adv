package top.niunaijun.blackbox.utils;

import android.app.Application;
import android.content.Intent;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * 给 Intent 的 extras 设置 ClassLoader。
 *
 * 原来各处都是直接写 `BActivityThread.getApplication().getClassLoader()`。
 * 这个写法默认「发广播/收广播时 Application 一定已经绑定好」，实际不成立：
 * 哔哩哔哩的 gripper（启动任务框架）会在 Application.onCreate 里就用协程
 * 发一次广播，那一刻 BActivityThread 的 Application 还是 null，
 * 于是 hook 里抛 NPE，直接杀掉整个进程——表现为「点启动就闪退，没有任何日志」。
 *
 * extras 的 ClassLoader 只影响自定义 Parcelable 的反序列化，拿不到应用 loader 时
 * 退化成引擎自己的 loader 不会崩，最多是某些自定义 extras 取不到类；
 * 这比直接 NPE 崩进程好得多。
 */
public final class AppClassLoader {

    private AppClassLoader() {
    }

    public static ClassLoader of() {
        try {
            Application app = BActivityThread.getApplication();
            if (app != null) {
                ClassLoader cl = app.getClassLoader();
                if (cl != null) {
                    return cl;
                }
            }
        } catch (Throwable ignored) {
        }
        return AppClassLoader.class.getClassLoader();
    }

    public static void applyTo(Intent intent) {
        if (intent == null) {
            return;
        }
        ClassLoader cl = of();
        if (cl != null) {
            intent.setExtrasClassLoader(cl);
        }
    }
}
