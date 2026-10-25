package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ApplicationThreadCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;

/**
 * Created by Milk on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);

            if (bProcess == null) {
                bProcess = new HashMap<>();
            }
            boolean reboundFromDisk = false;
            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.initLock != null) {
                        app.initLock.block();
                    }
                    if (app.bActivityThread != null) {
                        return app;
                    }
                }
                // :black 重启后内存注册表丢失，但虚拟进程的宿主进程可能仍在运行。
                // 先按磁盘 proc 目录恢复：若某 bpid 的 cmdline 与目标进程名一致且宿主进程
                // 存活，复用该 bpid 重绑（initAppProcessL 的 provider 调用在客户端是幂等的，
                // 同 package 重复 init 只会刷新 AppConfig），避免两个宿主进程跑同一虚拟进程
                // ——两者 WebView 数据目录后缀相同会触发 AwDataDirLock FATAL（真机实测）。
                int diskBpid = findLiveBPidOnDisk(processName);
                if (diskBpid != -1) {
                    bpid = diskBpid;
                    reboundFromDisk = true;
                    Slog.d(TAG, "rebind live process: " + processName + ", bpid = " + bpid);
                } else {
                    bpid = getUsingBPidL();
                }
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }
            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            app.buid = BPackageManagerService.get().getAppId(packageName);
            app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
            app.userId = userId;

            bProcess.put(processName, app);
            mPidsSelfLocked.add(app);

            mProcessMap.put(buid, bProcess);
            if (!initAppProcessL(app)) {
                //init process fail
                int failedBpid = app.bpid;
                bProcess.remove(processName);
                mPidsSelfLocked.remove(app);
                app = null;
                if (reboundFromDisk) {
                    // 复用磁盘 bpid 重绑失败（宿主进程可能已被其他虚拟进程复用），
                    // 清理残留 proc 目录，下次调用按常规流程分配新 bpid
                    FileUtils.deleteDir(BEnvironment.getProcDir(failedBpid));
                }
            } else {
                app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            }
        }
        return app;
    }

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
            int i = parseBPid(runningAppProcess.processName);
            usingPs.add(i);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            ProcessRecord app;
            synchronized (mProcessLock) {
                app = findProcessByPid(callingPid);
            }
            if (app == null) {
                String stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
                int bpid = parseBPid(stubProcessName);
                startProcessLocked(packageName, processName, userId, bpid, callingPid);
            }
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = BlackBoxCore.getHostPkg() + ":p";
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }

    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        AppConfig appConfig = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, appConfig);
        Bundle init = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null, bundle);
        IBinder appThread = BundleCompat.getBinder(init, "_Black_|_client_");
        if (appThread == null || !appThread.isBinderAlive()) {
            return false;
        }
        attachClientL(record, appThread);

        createProc(record);
        return true;
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    appThread.unlinkToDeath(this, 0);
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);

            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
                int appId = BPackageManagerService.get().getAppId(packageName);
                for (ProcessRecord processRecord : mPidsSelfLocked) {
                    int appId1 = BUserHandle.getAppId(processRecord.buid);
                    if (appId == appId1) {
                        mProcessMap.remove(processRecord.buid);
                        tmp.remove(processRecord);
                        processRecord.kill();
                    }
                }
                mPidsSelfLocked.clear();
                mPidsSelfLocked.addAll(tmp);
            }
            // 同 killPackageAsUser：注册表丢失时按磁盘 proc 目录补杀，避免卸载后残留进程
            killByDiskRecords(packageName);
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process != null) {
                for (ProcessRecord value : process.values()) {
                    value.kill();
                    mPidsSelfLocked.remove(value);
                }
                mProcessMap.remove(buid);
            }
            // :black 被系统冻结器（Sync transaction while frozen）杀死重启后，内存注册表
            // mProcessMap 丢失，仅凭注册表会静默跳过击杀——用户点悬浮按钮 stop 后虚拟 App
            // 继续运行（真机实测持续 2 分钟以上，表现为"点了没反应"）。
            // proc 目录由 createProc 写入、不随 :black 重启丢失，是注册表丢失后的恢复源，
            // 此处按磁盘记录补杀目标 package 的所有宿主进程（主进程及 :push 等子进程）。
            killByDiskRecords(packageName);
        }
    }

    /**
     * 按磁盘 proc 目录（{@link BEnvironment#getProcDir()}）恢复 bpid -> 虚拟进程名映射，
     * 击杀目标 package 的所有仍在运行的宿主进程。注意：cmdline 不记录 userId，同一 package
     * 多用户同时运行的极端场景会一并击杀（当前引擎单用户虚拟场景无此问题）。
     */
    private void killByDiskRecords(String packageName) {
        File[] bpidDirs = BEnvironment.getProcDir().listFiles();
        if (bpidDirs == null)
            return;
        for (File dir : bpidDirs) {
            if (!dir.isDirectory())
                continue;
            String processName = readCmdline(dir);
            if (processName == null)
                continue;
            if (!processName.equals(packageName) && !processName.startsWith(packageName + ":"))
                continue;
            int bpid = parseBpid(dir.getName());
            if (bpid == -1)
                continue;
            int pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid));
            Slog.d(TAG, "killByDiskRecords: " + processName + ", bpid = " + bpid + ", pid = " + pid);
            if (pid > 0) {
                try {
                    Process.killProcess(pid);
                } catch (Throwable ignored) {
                }
            }
            FileUtils.deleteDir(dir);
        }
    }

    /** 读取 proc 目录下的 cmdline（虚拟进程名），失败返回 null。 */
    private static String readCmdline(File bpidDir) {
        try {
            return FileUtils.readToString(new File(bpidDir, "cmdline").getAbsolutePath()).trim();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int parseBpid(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 按磁盘 proc 目录查找已绑定同一虚拟进程名、且宿主进程仍存活的 bpid；
     * 顺手清理宿主进程已死的残留 proc 目录。找不到返回 -1。
     */
    private int findLiveBPidOnDisk(String processName) {
        File[] bpidDirs = BEnvironment.getProcDir().listFiles();
        if (bpidDirs == null)
            return -1;
        for (File dir : bpidDirs) {
            if (!dir.isDirectory())
                continue;
            String name = readCmdline(dir);
            if (name == null || !name.equals(processName))
                continue;
            int bpid = parseBpid(dir.getName());
            if (bpid == -1)
                continue;
            int pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid));
            if (pid > 0) {
                return bpid;
            }
            // 宿主进程已死，清理残留，避免后续误判
            FileUtils.deleteDir(dir);
        }
        return -1;
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null || process.isEmpty()) {
                // :black 重启后注册表丢失，按磁盘 proc 目录合成记录。若直接返回空列表，
                // 上层 queryState 会误报进程已停止——悬浮按钮轮询据此自动隐藏按钮，
                // 用户再也点不到返回按钮（真机实测）。
                return synthRecordsFromDisk(packageName, userId);
            }
            return new ArrayList<>(process.values());
        }
    }

    /**
     * 按磁盘 proc 目录合成目标 package 的进程记录（仅 pid/processName/bpid 等字段有效，
     * bActivityThread 为 null）。仅用于查询类路径，发起 IPC 前需先重绑。
     */
    private List<ProcessRecord> synthRecordsFromDisk(String packageName, int userId) {
        List<ProcessRecord> result = new ArrayList<>();
        File[] bpidDirs = BEnvironment.getProcDir().listFiles();
        if (bpidDirs == null)
            return result;
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return result;
        int appId = BPackageManagerService.get().getAppId(packageName);
        for (File dir : bpidDirs) {
            if (!dir.isDirectory())
                continue;
            String processName = readCmdline(dir);
            if (processName == null)
                continue;
            if (!processName.equals(packageName) && !processName.startsWith(packageName + ":"))
                continue;
            int bpid = parseBpid(dir.getName());
            if (bpid == -1)
                continue;
            int pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid));
            if (pid <= 0)
                continue;
            ProcessRecord record = new ProcessRecord(info, processName);
            record.bpid = bpid;
            record.pid = pid;
            record.userId = userId;
            record.buid = BUserHandle.getUid(userId, appId);
            record.uid = Process.myUid();
            result.add(record);
        }
        return result;
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        synchronized (mProcessLock) {
            ProcessRecord callingProcess = BProcessManagerService.get().findProcessByPid(pid);
            if (callingProcess == null) {
                return BPackageManagerService.get().getAppId(packageName);
            }
            return BUserHandle.getAppId(callingProcess.buid);
        }
    }

    public int getUserIdByCallingPid(int callingPid) {
        synchronized (mProcessLock) {
            ProcessRecord callingProcess = BProcessManagerService.get().findProcessByPid(callingPid);
            if (callingProcess == null) {
                return 0;
            }
            return callingProcess.userId;
        }
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }

    @Override
    public void systemReady() {
        // :black 每次被冻结器杀死重启都会走到这里。旧实现直接 deleteDir(procDir)，
        // 会把仍在运行的虚拟进程的磁盘映射一并清掉，导致重启后内存注册表与磁盘
        // 双丢失：stop 静默无效、queryState 误报 STOPPED（悬浮按钮被自动隐藏）、
        // initProcess 重复绑定同一虚拟进程（真机实测最多三进程并存）。
        // 现在只清理宿主进程已死的残留记录，保留存活进程的映射作为恢复源。
        File[] bpidDirs = BEnvironment.getProcDir().listFiles();
        if (bpidDirs == null)
            return;
        for (File dir : bpidDirs) {
            if (!dir.isDirectory()) {
                FileUtils.deleteDir(dir);
                continue;
            }
            int bpid = parseBpid(dir.getName());
            if (bpid == -1) {
                FileUtils.deleteDir(dir);
                continue;
            }
            int pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(bpid));
            if (pid <= 0) {
                // 宿主进程已死，清理残留
                FileUtils.deleteDir(dir);
            }
        }
    }
}
