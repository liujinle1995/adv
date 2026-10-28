package top.niunaijun.blackbox.core;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Created by Milk on 4/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        logThrowable(t, e);
        if (BlackBoxCore.get().getExceptionHandler() != null) {
            BlackBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        mDefaultHandler.uncaughtException(t, e);
    }

    /**
     * 在转交系统处理之前，先把堆栈写进诊断日志。
     *
     * 必要性：哔哩哔哩这类应用会自己装 UncaughtExceptionHandler
     * （崩溃元数据里能看到 Crash-Handler: com.bilibili.lib.blcrash.k），
     * 它上报完之后不再 rethrow，进程直接消失——dropbox 里没有记录、
     * logcat 又被厂商 ROM 屏蔽，等于一点线索都不剩。
     * 这里自己留一份，至少能知道「是谁在什么条件下崩的」。
     */
    public static void logThrowable(Thread t, Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            sw.write("thread=" + (t == null ? "?" : t.getName())
                    + " pkg=" + currentPackage() + "\n");
            e.printStackTrace(pw);
            pw.flush();
            String text = sw.toString();
            // 限制长度，避免把诊断日志写爆
            if (text.length() > 12000) {
                text = text.substring(0, 12000) + "\n...(truncated)";
            }
            top.niunaijun.blackbox.utils.DiagLog.log("Crash", "uncaught\n" + text);
        } catch (Throwable ignored) {
        }
    }

    private static String currentPackage() {
        try {
            String p = top.niunaijun.blackbox.core.env.VirtualRuntime.getInitialPackageName();
            if (p != null && p.length() > 0) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        try {
            return android.os.Process.myProcessName();
        } catch (Throwable ignored) {
        }
        return "?";
    }
}
