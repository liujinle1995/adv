package top.niunaijun.blackbox.core.env;

import android.content.pm.ApplicationInfo;

import black.android.ddm.BRDdmHandleAppName;
import black.android.os.BRProcess;

public class VirtualRuntime {

    private static String sInitialPackageName;
    private static String sProcessName;

    public static String getProcessName() {
        return sProcessName;
    }

    public static String getInitialPackageName() {
        return sInitialPackageName;
    }

    public static void setupRuntime(String processName, ApplicationInfo appInfo) {
        if (sProcessName != null) {
            return;
        }
        sInitialPackageName = appInfo.packageName;
        sProcessName = processName;
        try {
            BRProcess.get().setArgV0(processName);
        } catch (Throwable ignored) {
        }
        renameKernelProcess(processName);
        try {
            BRDdmHandleAppName.get().setAppName(processName, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 真正改掉内核里的 /proc/self/cmdline。
     *
     * 为什么必须改：:p0 是宿主 fork 出来的，cmdline 天然是 "宿主:p0"。
     * 而 Application 判断「我是不是主进程」的常见写法就是比对
     * Process.myProcessName()（native 直读 cmdline）与自己的包名。
     * 两者不一致，应用就会走降级/安全模式分支 —— 红果实测会卡死在
     * AbsApplication.initSafeModeOtherTasks 里的 CountDownLatch 上。
     *
     * 注意这条路径改不了：Java 层读 /proc/self/cmdline 走的是
     * FileInputStream 的 native open，不经过 UnixFileSystem，IO 重定向规则对它无效。
     * 只能调用 Process.setArgV0 让 bionic 重写 argv[0] 内存。
     */
    private static void renameKernelProcess(String processName) {
        try {
            Class<?> processCls = Class.forName("android.os.Process");
            java.lang.reflect.Method setArgV0 =
                    processCls.getDeclaredMethod("setArgV0", String.class);
            setArgV0.setAccessible(true);
            setArgV0.invoke(null, processName);
            top.niunaijun.blackbox.utils.DiagLog.log("Runtime",
                    "setArgV0 ok cmdline=" + readCmdline());
        } catch (Throwable t) {
            top.niunaijun.blackbox.utils.DiagLog.log("Runtime",
                    "setArgV0 FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage()
                            + " cmdline=" + readCmdline());
        }
    }

    private static String readCmdline() {
        try {
            java.io.FileInputStream in =
                    new java.io.FileInputStream(new java.io.File("/proc/self/cmdline"));
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                if (n <= 0) {
                    return "<empty>";
                }
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                return new String(buf, 0, end, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "<err " + t.getClass().getSimpleName() + ">";
        }
    }
}
