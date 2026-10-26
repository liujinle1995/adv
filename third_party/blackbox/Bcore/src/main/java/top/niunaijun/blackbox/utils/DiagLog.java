package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * 引擎诊断日志出口。
 *
 * 背景：部分 ROM（实测荣耀平板 Android 17）把应用 logcat 限制到几乎为空，
 * 无法靠 `adb logcat` 看引擎内部异常。
 *
 * 关键约束：引擎代码跑在**虚拟应用进程**（:p0），它的 uid 与宿主不同，
 * 因此「宿主包名的目录」不保证可写，而 Context 推导出的目录又可能落在
 * 虚拟应用自己的私有数据里（外部看不到）。所以这里做的是**候选目录 + 探针写测试**：
 * 按优先级逐个试，第一个真正可写的就用它。
 */
public final class DiagLog {

    public interface Sink {
        void onLog(String tag, String message);
    }

    private static final String DIR_NAME = "logs";
    private static final String FILE_PREFIX = "engine-";

    private static volatile Sink sSink;
    private static volatile File sLogDir;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DiagLog() {
    }

    /** 宿主启动时可安装（仅本进程生效，可选）。 */
    public static void install(Sink sink) {
        sSink = sink;
    }

    public static void log(String tag, String message) {
        Log.d("adv/" + tag, message);
        Sink sink = sSink;
        if (sink != null) {
            try {
                sink.onLog(tag, message);
            } catch (Throwable ignored) {
                // 日志失败绝不能影响主流程
            }
        }
        writeToFile(tag, message);
    }

    /**
     * 拿 Context 的兜底链。
     *
     * 虚拟应用进程里 BlackBoxCore.sContext 不保证已经赋值（取决于初始化时机），
     * 而 ActivityThread.currentApplication() 在应用进程里总是可用的。
     */
    private static Context resolveContext() {
        try {
            Context ctx = BlackBoxCore.getContext();
            if (ctx != null) {
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Object app = clazz.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String hostPkg() {
        try {
            String pkg = BlackBoxCore.getHostPkg();
            return (pkg == null || pkg.isEmpty()) ? null : pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按优先级挑一个真正可写的日志目录。
     *
     * 优先级说明：
     * 1. 宿主外部私有目录 —— 主进程/:black 进程可写，`adb pull` 可直接取；
     * 2. 本进程外部私有目录 —— 虚拟进程下指向虚拟应用的外部目录，adb 同样可取；
     * 3. 本进程内部私有目录 —— 一定可写，但 adb 读不到（靠应用内诊断页导出）；
     * 4. 宿主内部私有目录绝对路径 —— 同 uid 时可用。
     */
    private static File pickDir() {
        Context ctx = resolveContext();
        String pkg = hostPkg();
        List<File> candidates = new ArrayList<>(4);

        if (pkg != null) {
            candidates.add(new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + pkg + "/files/" + DIR_NAME));
        }
        if (ctx != null) {
            File ext = null;
            try {
                ext = ctx.getExternalFilesDir(DIR_NAME);
            } catch (Throwable ignored) {
            }
            if (ext != null) {
                candidates.add(ext);
            }
            try {
                candidates.add(new File(ctx.getFilesDir(), DIR_NAME));
            } catch (Throwable ignored) {
            }
        }
        if (pkg != null) {
            candidates.add(new File("/data/user/0/" + pkg + "/files/" + DIR_NAME));
        }

        for (File dir : candidates) {
            if (isWritable(dir)) {
                return dir;
            }
        }
        return null;
    }

    /** 目录可写探针：真写一次，失败就换下一个（只 mkdirs 不足以判断权限）。 */
    private static boolean isWritable(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return false;
            }
            File probe = new File(dir, ".probe-" + Process.myPid());
            FileOutputStream fos = new FileOutputStream(probe, true);
            try {
                fos.write('x');
            } finally {
                fos.close();
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeToFile(String tag, String message) {
        try {
            File dir = sLogDir;
            if (dir == null) {
                dir = pickDir();
                if (dir == null) {
                    return;
                }
                sLogDir = dir;
            }

            Date now = new Date();
            File file = new File(dir, FILE_PREFIX + DAY.format(now) + ".log");
            String line = TIME.format(now) + " pid=" + Process.myPid()
                    + " [" + tag + "] " + message + "\n";

            FileOutputStream fos = new FileOutputStream(file, true);
            try {
                fos.write(line.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
            // 诊断日志写失败不影响业务
        }
    }
}
