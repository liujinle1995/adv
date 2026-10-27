package top.niunaijun.blackbox.core.system.pm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.pm.installer.CopyExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.CreatePackageExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.CreateUserExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.Executor;
import top.niunaijun.blackbox.core.system.pm.installer.RemoveAppExecutor;
import top.niunaijun.blackbox.core.system.pm.installer.RemoveUserExecutor;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Created by Milk on 4/21/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageInstallerService extends IBPackageInstallerService.Stub implements ISystemService {
    private static final BPackageInstallerService sService = new BPackageInstallerService();

    public static BPackageInstallerService get() {
        return sService;
    }

    public static final String TAG = "BPackageInstallerService";

    @Override
    public int installPackageAsUser(BPackageSettings ps, int userId) {
        List<Executor> executors = new ArrayList<>();
        // 创建用户环境相关操作
        executors.add(new CreateUserExecutor());
        // 创建应用环境相关操作
        executors.add(new CreatePackageExecutor());
        // 拷贝应用相关文件
        executors.add(new CopyExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "installPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public int uninstallPackageAsUser(BPackageSettings ps, boolean removeApp, int userId) {
        List<Executor> executors = new ArrayList<>();
        if (removeApp) {
            // 移除App
            executors.add(new RemoveAppExecutor());
        }
        // 移除用户相关目录
        executors.add(new RemoveUserExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "uninstallPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public int clearPackage(BPackageSettings ps, int userId) {
        String packageName = ps.pkg.packageName;
        // 备份 shared_prefs —— 字节系的 device_id / install_id / openudid 与
        // Keva 键值都存这里，它们是「设备已注册」的凭据。
        // 一旦删掉，应用下次启动必须重新注册设备；沙箱环境过不了服务端风控，
        // 重新注册会持续失败，表现为主界面一直「网络出错，请点击重试」，
        // 点重试也恢复不了（真机实测：清数据后连续 3 次重启全部失败，
        // 清数据前一切正常；client_key 在两种情况下都为空，与此无关）。
        List<File> bakDirs = new ArrayList<>();
        List<File> bakBaks = new ArrayList<>();
        for (File dir : new File[]{
                BEnvironment.getDataDir(packageName, userId),
                BEnvironment.getDeDataDir(packageName, userId)}) {
            File prefs = new File(dir, "shared_prefs");
            if (!prefs.isDirectory()) continue;
            // 备份放在同级兄弟目录：同一分区内 renameTo 才可靠
            File bak = new File(dir.getParentFile(), dir.getName() + ".__prefs_bak");
            FileUtils.deleteDir(bak);
            if (prefs.renameTo(bak)) {
                bakDirs.add(dir);
                bakBaks.add(bak);
            }
        }

        List<Executor> executors = new ArrayList<>();
        // 移除用户相关目录
        executors.add(new RemoveUserExecutor());
        // 创建用户环境相关操作
        executors.add(new CreateUserExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, userId);
            Slog.d(TAG, "uninstallPackageAsUser: " + executor.getClass().getSimpleName() + " exec: " + exec);
            if (exec != 0) {
                return exec;
            }
        }

        // 把设备身份放回去
        for (int i = 0; i < bakDirs.size(); i++) {
            File dir = bakDirs.get(i);
            File bak = bakBaks.get(i);
            try {
                if (!dir.exists() && !dir.mkdirs()) continue;
                File prefs = new File(dir, "shared_prefs");
                FileUtils.deleteDir(prefs);
                bak.renameTo(prefs);
            } catch (Throwable t) {
                Slog.d(TAG, "restore shared_prefs failed: " + t.getMessage());
            } finally {
                FileUtils.deleteDir(bak);
            }
        }
        return 0;
    }

    @Override
    public int updatePackage(BPackageSettings ps) {
        List<Executor> executors = new ArrayList<>();
        executors.add(new CreatePackageExecutor());
        executors.add(new CopyExecutor());
        InstallOption option = ps.installOption;
        for (Executor executor : executors) {
            int exec = executor.exec(ps, option, -1);
            if (exec != 0) {
                return exec;
            }
        }
        return 0;
    }

    @Override
    public void systemReady() {

    }
}
