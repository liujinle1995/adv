package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.TrieTree;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    // /data/data/com.google/  ----->  /data/data/com.virtual/data/com.google/
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        //Add the key to TrieTree
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "mkdirs " + redirectPath);
            FileUtils.mkdirs(redirectPath);
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "mkdirs done");
        }
        top.niunaijun.blackbox.utils.DiagLog.log("IO", "addIORule " + origPath);
        NativeCore.addIORule(origPath, redirectPath);
        top.niunaijun.blackbox.utils.DiagLog.log("IO", "addIORule done");
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.contains("/blackbox/")) {
            return path;
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return search;

        //Search the key from TrieTree
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(mRedirectMap.get(key)));

        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        //Search the key from TrieTree
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    // 由于正常情况Application已完成重定向，以下重定向是怕代码写死。
    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();
        top.niunaijun.blackbox.utils.DiagLog.log("IO", "enter pkg=" + packageName);

        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BActivityThread.getUserId());
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "appInfo ok dataDir=" + packageInfo.dataDir
                    + " libDir=" + packageInfo.nativeLibraryDir);
            int systemUserId = BlackBoxCore.getHostUserId();
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "hostUserId=" + systemUserId);
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "data rule ok");

            // 这里**不能**调 context.getExternalCacheDir() / getExternalStorageDirectory()：
            // :p0 进程跑在虚拟 uid 下，这类调用会走 StorageManager 的 binder，
            // 而调用方身份此时还没切换到虚拟用户，实测在主线程上永久挂住
            // （进程存活、CPU 0%、后续启动流程再也不动）。
            //
            // 同理，把 /sdcard 重定向到外部沙箱目录也会挂：规则注册前要先对目标路径
            // 做 exists()/mkdirs()，而这条外部路径在虚拟进程里同样要走 FUSE 授权。
            // 结论：外部存储重定向整体推迟到「进程身份完全就绪」之后再单独处理，
            // 启动链路优先——只有先跑起来，才有机会处理这些细节。
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "skip external rules (virtual uid storage)");
            boolean hideRoot = BlackBoxCore.get().isHideRoot();
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "rules=" + rule.size()
                    + " black=" + blackRule.size() + " hideRoot=" + hideRoot);
            if (hideRoot) {
                hideRoot(rule);
                top.niunaijun.blackbox.utils.DiagLog.log("IO", "hideRoot ok");
            }
            proc(rule);
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "proc ok");
        } catch (Throwable e) {
            top.niunaijun.blackbox.utils.DiagLog.log("IO", "EXCEPTION " + e.getClass().getName()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        top.niunaijun.blackbox.utils.DiagLog.log("IO", "before enableIO");
        NativeCore.enableIO();
        top.niunaijun.blackbox.utils.DiagLog.log("IO", "enableIO ok");
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = BActivityThread.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);
    }
}
