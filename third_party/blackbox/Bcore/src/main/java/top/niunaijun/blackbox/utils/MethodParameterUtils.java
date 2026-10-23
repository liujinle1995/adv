package top.niunaijun.blackbox.utils;

import java.util.Arrays;
import java.util.HashSet;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

public class MethodParameterUtils {

    /**
     * 安全读取 int 型 Binder 入参。
     *
     * Android 13/14 起不少系统接口把 `flags` 从 int 改成了 long，
     * 例如 `IPackageManager.getApplicationInfo(String, long flags, int userId)`、
     * `getPackageInfo`、`resolveIntent` 等。hook 里若沿用 `(int) args[i]`，
     * 在虚拟进程里会直接抛 `ClassCastException: java.lang.Long cannot be cast to java.lang.Integer`
     * 并把进程打死。这里统一按 Number 取值，兼容 int/long/short 各种装箱类型。
     */
    public static int getInt(Object[] args, int index) {
        long value = getLong(args, index);
        return (int) value;
    }

    public static long getLong(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return 0L;
        }
        Object value = args[index];
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public static boolean getBoolean(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return false;
        }
        Object value = args[index];
        return value instanceof Boolean && (Boolean) value;
    }

    public static <T> T getFirstParam(Object[] args, Class<T> tClass) {
        if (args == null) {
            return null;
        }
        int index = ArrayUtils.indexOfFirst(args, tClass);
        if (index != -1) {
            return (T) args[index];
        }
        return null;
    }

    public static String replaceFirstAppPkg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                    return value;
                }
            }
        }
        return null;
    }

    public static void replaceAllAppPkg(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null)
                continue;
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                }
            }
        }
    }

    public static void replaceFirstUid(Object[] args) {
        if (args == null)
            return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int uid = (int) args[i];
                if (uid == BActivityThread.getBUid()) {
                    args[i] = BlackBoxCore.getHostUid();
                }
            }
        }
    }

    public static void replaceLastUid(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, Integer.class);
        if (index != -1) {
            int uid = (int) args[index];
            if (uid == BActivityThread.getBUid()) {
                args[index] = BlackBoxCore.getHostUid();
            }
        }
    }

    public static String replaceLastAppPkg(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, String.class);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static String replaceSequenceAppPkg(Object[] args, int sequence) {
        int index = ArrayUtils.indexOf(args, String.class, sequence);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static int getParamsIndex(Class[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            Class obj = args[i];
            if (obj.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    public static int getIndex(Object[] args, Class<?> type) {
        return getIndex(args, type, 0);
    }

    public static int getIndex(Object[] args, Class<?> type, int start) {
        for (int i = start; i < args.length; i++) {
            Object obj = args[i];
            if (obj != null && obj.getClass() == type) {
                return i;
            }
            if (type.isInstance(obj)) {
                return i;
            }
        }
        return -1;
    }

    public static Class<?>[] getAllInterface(Class clazz) {
        HashSet<Class<?>> classes = new HashSet<>();
        getAllInterfaces(clazz, classes);
        Class<?>[] result = new Class[classes.size()];
        classes.toArray(result);
        return result;
    }


    public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
        Class<?>[] classes = clazz.getInterfaces();
        if (classes.length != 0) {
            interfaceCollection.addAll(Arrays.asList(classes));
        }
        if (clazz.getSuperclass() != Object.class) {
            getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
        }
    }


}
