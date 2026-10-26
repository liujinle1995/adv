//
// Created by Milk on 4/9/21.
//

#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <string>
#include <cstring>
#include <string.h>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/RuntimeHook.h>
#include "Utils/HexDump.h"

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
//    RuntimeHook::init(env);
    BinderHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");

    JniHook::InitJniHook(env, api_level);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    IO::init(env);
    nativeHook(env);
}

// ---------------------------------------------------------------------------
// 进程身份伪造（当前在 Android 17 上未生效，保留为适配挂点）
//
// Application 判断「我是不是主进程」最常用的写法是拿
// Process.myProcessName()（native 直读 /proc/self/cmdline）和包名比。
// 沙箱里这个值是 "宿主:p0"。
//
// 实测结论（Android 17 / 荣耀 YLP-W00）：
//   - Process.setArgV0 已被系统移除（NoSuchMethodException）
//   - 直接改写 argv 内存区后 /proc/self/cmdline 仍然是宿主名
//     （zygote fork 后该区域已不可原地修改）
//   - RegisterNatives("nativeMyProcessName") 返回成功，但 myProcessName()
//     的返回值不变，说明它背后并不是这个方法
// 所以这里目前只是「尝试 + 不报错」，不要指望它把进程名改掉。
// 红果最终能跑起来并不是靠这里，而是清掉了它自己累积的安全模式标记。
// ---------------------------------------------------------------------------
static std::string g_virtualProcessName;

static jstring my_process_name(JNIEnv *env, jclass) {
    if (g_virtualProcessName.empty()) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(g_virtualProcessName.c_str());
}

// 直接改内核 argv 内存区，让 /proc/self/cmdline 真的变成新名字。
//
// 定位方式：/proc/self/stat 的第 48/49 个字段就是 arg_start / arg_end
// （这是内核记录的 argv 内存范围）。只覆盖到原来的第一个 '\0' 为止，
// 不能把整段清零 —— argv 后面紧挨着 environ，全清会毁掉环境变量。
static void overwriteCmdline(const char *name) {
    char buf[2048];
    int fd = open("/proc/self/stat", O_RDONLY);
    if (fd < 0) {
        ALOGE("overwriteCmdline: open stat failed");
        return;
    }
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return;
    }
    buf[n] = '\0';

    // comm 字段可能含空格，跳过最后一个 ')'
    char *p = strrchr(buf, ')');
    if (p == nullptr) {
        return;
    }
    p++;

    unsigned long argStart = 0;
    unsigned long argEnd = 0;
    int idx = 3;  // ')' 之后第一个字段是 state，它对应第 3 个字段
    char *save = nullptr;
    for (char *tok = strtok_r(p, " ", &save); tok != nullptr;
         tok = strtok_r(nullptr, " ", &save), ++idx) {
        if (idx == 48) {
            argStart = strtoul(tok, nullptr, 10);
        } else if (idx == 49) {
            argEnd = strtoul(tok, nullptr, 10);
            break;
        }
    }
    if (argStart == 0 || argEnd <= argStart) {
        ALOGE("overwriteCmdline: bad range %lu-%lu", argStart, argEnd);
        return;
    }

    size_t total = argEnd - argStart;
    size_t oldLen = strnlen(reinterpret_cast<char *>(argStart), total);
    size_t newLen = strlen(name);
    if (newLen == 0 || newLen > oldLen) {
        // 新名字比原 cmdline 还长时没法原地写（会把 environ 挤掉），放弃。
        ALOGE("overwriteCmdline: name too long (%zu > %zu)", newLen, oldLen);
        return;
    }

    long pageSize = sysconf(_SC_PAGESIZE);
    unsigned long pageMask = static_cast<unsigned long>(pageSize) - 1;
    void *pageStart = reinterpret_cast<void *>(argStart & ~pageMask);
    size_t span = static_cast<size_t>(((argEnd & ~pageMask) + pageSize)
                                      - reinterpret_cast<unsigned long>(pageStart));
    if (mprotect(pageStart, span, PROT_READ | PROT_WRITE) != 0) {
        ALOGE("overwriteCmdline: mprotect failed");
        return;
    }
    memcpy(reinterpret_cast<void *>(argStart), name, newLen);
    memset(reinterpret_cast<void *>(argStart + newLen), 0, oldLen - newLen);
    ALOGD("overwriteCmdline ok -> %s", name);
}

void hookProcessName(JNIEnv *env, jclass, jstring java_name) {
    if (java_name == nullptr) {
        return;
    }
    const char *name = env->GetStringUTFChars(java_name, nullptr);
    if (name == nullptr) {
        env->ExceptionClear();
        return;
    }
    g_virtualProcessName = name;

    // 一：改内核 argv，让 /proc/self/cmdline 与 Process.myProcessName() 一起变。
    overwriteCmdline(name);

    env->ReleaseStringUTFChars(java_name, name);

    // 二：顺手把 nativeMyProcessName 也换掉。
    // 有些版本 myProcessName() 会缓存/走别的实现，双管齐下更稳；
    // 失败也无所谓，不影响上面那条主路径。
    jclass processCls = env->FindClass("android/os/Process");
    if (processCls == nullptr) {
        env->ExceptionClear();
        return;
    }
    JNINativeMethod method = {
            const_cast<char *>("nativeMyProcessName"),
            const_cast<char *>("()Ljava/lang/String;"),
            reinterpret_cast<void *>(my_process_name)
    };
    if (env->RegisterNatives(processCls, &method, 1) < 0) {
        env->ExceptionClear();
        ALOGE("hookProcessName: RegisterNatives(nativeMyProcessName) failed");
    }
}

static JNINativeMethod gMethods[] = {
        {"hideXposed",     "()V",                                     (void *) hideXposed},
        {"addIORule",      "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",       "()V",                                     (void *) enableIO},
        {"init",           "(I)V",                                    (void *) init},
        {"hookProcessName", "(Ljava/lang/String;)V",                  (void *) hookProcessName},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    registerMethod(env);
    return JNI_VERSION_1_6;
}