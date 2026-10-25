package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;

/**
 * hidden API 豁免器（Android 14+ 可用）。
 *
 * 背景：
 * 上游用 `free_reflection 3.0.1` 的 `Reflection.unseal()`。它的实现是
 * 「把 BootstrapClass 写成一个 dex 文件，再用 `DexFile` 打开，类因此以 boot class loader
 * 定义、落进 core-platform 域，从而能调用 `VMRuntime.setHiddenApiExemptions`」。
 *
 * Android 14 起 ART 拒绝打开**可写** dex 文件，free_reflection 的兜底路径直接抛
 * `SecurityException: Writable dex file ... is not allowed`，于是 unseal 返回 -1，
 * 所有 `MAX-TARGET-O` / `BLOCKED` 的隐藏成员全部不可读——而 BlackReflection 在反射失败时
 * 会静默返回 null，最终表现为主线程上一个莫名其妙的 `Integer.intValue()` NPE。
 *
 * 本类沿用同一原理，但换成两条在 Android 14+ 仍然成立的路径，且不依赖额外的 dex 产物：
 * 1. 直接 `new DexFile(apk)`（APK 本身是只读的，不触发可写限制），把 APK 里的
 *    [HiddenApiExempt] 以 boot class loader 再定义一份；
 * 2. 若多 dex 场景下 1 取不到类，则把 APK 内的 classesN.dex 复制到 code_cache，
 *    `chmod 0444` 去掉写位后再打开。
 *
 * 判定成功与否不信返回值，而是**实测**一个 `MAX-TARGET-O` 成员能否被反射读取。
 */
public final class HiddenApiUnsealer {

    public static final String TAG = "HiddenApiUnsealer";

    /** 在 core-platform 域执行的 helper；只能按名字引用，不能静态引用。 */
    private static final String HELPER_CLASS = "top.niunaijun.blackbox.utils.HiddenApiExempt";
    private static final String HELPER_METHOD = "exempt";
    private static final String EXEMPT_ALL_PREFIX = "L";

    /** 用于验证豁免是否生效的成员：Android 14+ 上是 MAX-TARGET-O。 */
    private static final String PROBE_CLASS = "android.app.ActivityThread$H";
    private static final String PROBE_FIELD = "EXECUTE_TRANSACTION";

    private static volatile boolean sDone;

    private HiddenApiUnsealer() {
    }

    /**
     * 幂等。每个进程调用一次即可，重复调用时只做一次验证。
     *
     * @return true 表示隐藏 API 已可访问
     */
    public static boolean unseal(Context context) {
        if (sDone) {
            return true;
        }
        synchronized (HiddenApiUnsealer.class) {
            if (sDone) {
                return true;
            }
            cleanOrphanDexFiles(context);
            if (isUnsealed()) {
                Slog.i(TAG, "hidden API 已可访问，无需豁免");
                sDone = true;
                return true;
            }

            Throwable first = tryExemptFromApkDex(context);
            if (isUnsealed()) {
                Slog.i(TAG, "豁免成功（直接加载 APK dex）");
                sDone = true;
                return true;
            }

            Throwable second = tryExemptFromReadOnlyDexCopy(context);
            if (isUnsealed()) {
                Slog.i(TAG, "豁免成功（只读 dex 副本）");
                sDone = true;
                return true;
            }

            // 兜底：仍走一次上游实现（Android 13 及以下通常就成功了）。
            // Android 14+ 不走这条：free_reflection 会把 dex 写进 code_cache 再加载，
            // ART 从 14 起拒绝加载可写 dex（日志刷 "Attempt to load writable dex file"），
            // Android 15/16 更是直接把 setHiddenApiExemptions 列进 blocked，
            // 跑一遍只会多丢一个可写 dex 垃圾，没有任何收益。
            Throwable third = null;
            if (Build.VERSION.SDK_INT < 34) {
                try {
                    me.weishu.reflection.Reflection.unseal(context);
                } catch (Throwable t) {
                    third = t;
                }
            } else {
                third = new UnsupportedOperationException(
                        "API " + Build.VERSION.SDK_INT + " 已拒绝 setHiddenApiExemptions，跳过 free_reflection 兜底（避免可写 dex）");
            }
            if (isUnsealed()) {
                Slog.i(TAG, "豁免成功（free_reflection）");
                sDone = true;
                return true;
            }

            Slog.e(TAG, "hidden API 豁免失败，引擎的反射 hook 会大面积失效"
                    + "（apkDex=" + brief(first)
                    + ", readonlyCopy=" + brief(second)
                    + ", freeReflection=" + brief(third) + "）");
            return false;
        }
    }

    /**
     * 清理 free_reflection 遗留的孤儿 dex（形如 <毫秒时间戳>.dex）。
     * 该库在 API < 34 的兜底路径里把 dex 写进 code_cache 后不做清理，
     * 这些文件残留后，后续进程每次启动刷 "Attempt to load writable dex file" 警告。
     * 只匹配纯数字文件名，绝不碰本类自己的 adv-hiddenapi-*.dex。
     */
    private static void cleanOrphanDexFiles(Context context) {
        File cacheDir = context.getCodeCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".dex")
                    && name.substring(0, name.length() - 4).matches("\\d{10,}")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** 实测：能否通过反射读取一个被系统拦住的隐藏成员。 */
    public static boolean isUnsealed() {
        try {
            Class<?> clazz = Class.forName(PROBE_CLASS);
            Field field = clazz.getDeclaredField(PROBE_FIELD);
            field.setAccessible(true);
            field.getInt(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Throwable tryExemptFromApkDex(Context context) {
        DexFile dexFile = null;
        try {
            dexFile = new DexFile(new File(context.getApplicationInfo().sourceDir));
            return invokeHelper(dexFile.loadClass(HELPER_CLASS, null));
        } catch (Throwable t) {
            return t;
        } finally {
            closeQuietly(dexFile);
        }
    }

    private static Throwable tryExemptFromReadOnlyDexCopy(Context context) {
        File cacheDir = context.getCodeCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(context.getApplicationInfo().sourceDir);
            Throwable last = null;
            for (int index = 1; index <= MAX_DEX_ENTRIES; index++) {
                String entryName = index == 1 ? "classes.dex" : String.format(Locale.US, "classes%d.dex", index);
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    continue;
                }
                File target = new File(cacheDir, "adv-hiddenapi-" + index + ".dex");
                Throwable failure = loadFromReadOnlyCopy(zipFile, entry, target);
                if (failure == null) {
                    return null;
                }
                last = failure;
            }
            return last != null ? last : new IllegalStateException("APK 内没有 dex");
        } catch (Throwable t) {
            return t;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Throwable loadFromReadOnlyCopy(ZipFile zipFile, ZipEntry entry, File target) {
        DexFile dexFile = null;
        try {
            copy(zipFile, entry, target);
            // Android 14+ 只校验模式位：去掉写位即可加载
            try {
                Os.chmod(target.getAbsolutePath(), 0444);
            } catch (Throwable ignored) {
                // 部分 ROM 不允许 chmod，退回 File API
                //noinspection ResultOfMethodCallIgnored
                target.setReadOnly();
            }
            dexFile = new DexFile(target);
            Class<?> helper = dexFile.loadClass(HELPER_CLASS, null);
            if (helper == null) {
                return new ClassNotFoundException(HELPER_CLASS + " 不在 " + entry.getName());
            }
            return invokeHelper(helper);
        } catch (Throwable t) {
            return t;
        } finally {
            closeQuietly(dexFile);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static Throwable invokeHelper(Class<?> helper) {
        if (helper == null) {
            return new ClassNotFoundException(HELPER_CLASS + " 未能以 boot class loader 定义");
        }
        try {
            Method method = helper.getDeclaredMethod(HELPER_METHOD, String.class);
            Object result = method.invoke(null, EXEMPT_ALL_PREFIX);
            if (!Boolean.TRUE.equals(result)) {
                return new IllegalStateException("helper 返回 " + result);
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void copy(ZipFile zipFile, ZipEntry entry, File target) throws Exception {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = zipFile.getInputStream(entry);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void closeQuietly(DexFile dexFile) {
        if (dexFile == null) {
            return;
        }
        try {
            dexFile.close();
        } catch (Throwable ignored) {
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "ok";
        }
        String message = t.getCause() != null ? t.getCause().toString() : t.toString();
        return message;
    }

    private static final int MAX_DEX_ENTRIES = 32;
}
