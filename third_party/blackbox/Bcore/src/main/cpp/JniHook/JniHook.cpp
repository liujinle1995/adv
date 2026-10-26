//
// Created by Milk on 3/8/21.
//

#include <jni.h>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;

    int class_flags_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

// 这三个 getter 在 MethodUtils 不可用（Android 新版可能限制反射）时必须返回空串，
// 而不是把 null jstring 交给 GetStringUTFChars —— 后者同样会走 JniAbort 打死进程。
static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_desc_id) {
        return "";
    }
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    if (!desc) {
        env->ExceptionClear();
        return "";
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_declaring_class_id) {
        return "";
    }
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    if (!desc) {
        env->ExceptionClear();
        return "";
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_name_id) {
        return "";
    }
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    if (!desc) {
        env->ExceptionClear();
        return "";
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return (flags & flag) == flag;
}

inline static bool ClearFastNativeFlag(char *art_method) {
    // FastNative
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

// 说明：以下两个转换函数在 Android 14+ 上必须能「失败但不崩」。
// 高版本 ART 会移除/隐藏 getArtField()、Executable.artMethod 这类内部反射入口，
// 一旦 GetMethodID/GetFieldID 返回 null 还去调 Call*Method，ART 会走
// JavaVMExt::JniAbort 直接 SIGSEGV 打死进程（现场无 Java 崩溃栈）。
// 拿不到就退回 jfieldID/jmethodID 原值——在 ART 里它们本身就是 ArtField*/ArtMethod*。
static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (executable) {
            jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
            if (artId) {
                jobject method = env->ToReflectedMethod(clazz, methodId, true);
                if (method) {
                    jlong value = env->GetLongField(method, artId);
                    if (!env->ExceptionCheck()) {
                        return reinterpret_cast<void *>(value);
                    }
                }
            }
        }
        env->ExceptionClear();
    }
    return methodId;
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (!field) {
        return nullptr;
    }
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (fieldClass) {
            jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
            if (getArtField) {
                jlong value = env->CallLongMethod(field, getArtField);
                if (!env->ExceptionCheck()) {
                    return reinterpret_cast<void *>(value);
                }
            }
        }
        env->ExceptionClear();
    }
    return env->FromReflectedField(field);
}

// 通过反射判断方法是否 native：
// 不依赖 ArtMethod 内存布局，任何 API 级别都准。
// Method.getModifiers() 是公开 API，dex 里的 native 标志位会如实反映。
static bool IsNativeByReflection(JNIEnv *env, jclass clazz, jmethodID methodId) {
    jclass executable = env->FindClass("java/lang/reflect/Executable");
    if (!executable) {
        env->ExceptionClear();
        return false;
    }
    jmethodID getModifiers = env->GetMethodID(executable, "getModifiers", "()I");
    if (!getModifiers) {
        env->ExceptionClear();
        return false;
    }
    jobject reflected = env->ToReflectedMethod(clazz, methodId, JNI_TRUE);
    if (!reflected) {
        env->ExceptionClear();
        return false;
    }
    jint modifiers = env->CallIntMethod(reflected, getModifiers);
    env->DeleteLocalRef(reflected);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return (static_cast<uint32_t>(modifiers) & kAccNative) != 0;
}

bool CheckFlags(JNIEnv *env, jclass clazz, jmethodID methodId, void *artMethod) {
    bool isNative = IsNativeByReflection(env, clazz, methodId);
    if (!isNative && HookEnv.art_method_flags_offset != 0) {
        // 反射抓不到结果时的兜底：读 flags 偏移。
        // 偏移未定位成功时 offset 0 是入口指针/Class 指针，不能拿来判断。
        isNative = HasAccessFlag(static_cast<char *>(artMethod), kAccNative);
    }
    if (!isNative) {
        // 不是 native 就不要 RegisterNatives——Android 14+ 上
        // UnixFileSystem.canonicalize0(String) 只是 Java 包装方法，强行注册会抛 NoSuchMethodError。
        ALOGD("skip non-native method");
        return false;
    }
    if (HookEnv.art_method_flags_offset != 0) {
        ClearFastNativeFlag(static_cast<char *>(artMethod));
    }
    return true;
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_desc_id) {
        ALOGD("HookJniFun skipped: MethodUtils unavailable");
        return;
    }
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
}

void
JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                    void *new_fun, void **orig_fun, bool is_static) {
    if (HookEnv.art_method_native_offset == 0) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        return;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method));
    if (!CheckFlags(env, clazz, method, artMethod)) {
        ALOGD("check flags error. class：%s, method：%s", class_name, method_name);
        return;
    }
    *orig_fun = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        // Android 14+ ArtMethod 布局变化后，flags 偏移的扫描可能失效，
        // CheckFlags 把「非 native 的同签名 Java 方法」误判成 native，
        // RegisterNatives 就会抛 NoSuchMethodError。
        // 若不清掉这个挂起异常，后续任何一次 JNI 调用（FindClass 等）都会
        // 触发 "JNI DETECTED ERROR IN APPLICATION" 直接 SIGABRT 打死虚拟进程，
        // 现场看起来就是「无 Java 崩溃栈、进程静默消失」。
        env->ExceptionClear();
        ALOGE("jni hook error. class：%s, method：%s", class_name, method_name);
        return;
    }
    // FastNative
    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    ALOGD("register class：%s, method：%s success!", class_name, method_name);
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    if (HookEnv.art_method_flags_offset == 0) {
        // flags 偏移未定位成功时 offset 0 是入口指针，直接写会把指针写坏，本应由调用方强制关闭。
        ALOGD("skip setAccessible(method): method flags offset unresolved");
        return;
    }
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext")))  JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    if (HookEnv.art_field_flags_offset == 0) {
        // 同 set_method_accessible：偏移未定位成功时不要盲写。
        ALOGD("skip setAccessible(field): field flags offset unresolved");
        return;
    }
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        env->ExceptionClear();
        ALOGE("jni register: JniHook class not found");
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        env->ExceptionClear();
        ALOGE("jni register error.");
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;
    // 任何一步失败都保持「偏移为 0」——所有 hook 会静默跳过（功能降级），
    // 但绝不崩溃。这是 Android 14+ 上 ArtMethod 布局不再稳定的必要兜底。
    HookEnv.art_method_native_offset = 0;
    HookEnv.art_method_flags_offset = 0;
    HookEnv.art_field_flags_offset = 0;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        env->ExceptionClear();
        ALOGE("JniHook init: anchor class not found, jni hook disabled");
        return;
    }
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");
    if (!nativeOffsetId || !nativeOffset2Id) {
        env->ExceptionClear();
        ALOGE("JniHook init: anchor methods missing, jni hook disabled");
        return;
    }

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    long methodDelta = (long) nativeOffset2 - (long) nativeOffset;
    // 两个锚点方法的地址差就是 ArtMethod 步长。差值不合理 = 没拿到真正的
    // ArtMethod（或布局不认识），继续按这个步长扫描会越界，直接放弃。
    if (!nativeOffset || !nativeOffset2 || methodDelta <= 0 || methodDelta > 256) {
        ALOGE("JniHook init: implausible method delta, jni hook disabled");
        HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");
        env->ExceptionClear();
        return;
    }
    HookEnv.art_method_size = (unsigned int) methodDelta;

    // calc native offset
    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    for (unsigned int i = 0; i < HookEnv.art_method_size; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            HookEnv.art_method_native_offset = (int) i;
            break;
        }
    }

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccNative;
    flags = flags | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        flags = flags | kAccPublicApi;
    }

    // Android 14+ （实测 API 34/36）链接期会给所有 native 方法统一置上
    // kAccFastNative/kAccCriticalNative，精确等于 dex flags 的匹配会失落（结果 flags_offset=0），
    // 扫描时必须把这个运行时标志位掉再比。
    const uint32_t runtime_only_mask = kAccFastNative | kAccCriticalNative;
    char *start = reinterpret_cast<char *>(artMethod);
    for (unsigned int i = 1; i < HookEnv.art_method_size; ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
        if ((value & ~runtime_only_mask) == (flags & ~runtime_only_mask)) {
            HookEnv.art_method_flags_offset = (int) (i * sizeof(uint32_t));
            break;
        }
    }
    if (HookEnv.art_method_flags_offset == 0) {
        // 找不到就打印 ArtMethod 头几个 word，方便下次新机型上定位布局。
        ALOGD("method flags scan miss, artMethod head: %08x %08x %08x %08x %08x %08x %08x %08x",
              *(uint32_t *) (start + 0), *(uint32_t *) (start + 4),
              *(uint32_t *) (start + 8), *(uint32_t *) (start + 12),
              *(uint32_t *) (start + 16), *(uint32_t *) (start + 20),
              *(uint32_t *) (start + 24), *(uint32_t *) (start + 28));
    }

    // ---- ArtField 步长与 flags 偏移 ----
    // 这一段在 Android 17 上曾因 getArtField() 被移除而直接把进程打崩，
    // 所以每个 JNI 入口都要判空之后再往下走。
    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");
    jobject reflectedField = nullptr;
    jobject reflectedField2 = nullptr;
    if (nativeOffsetFieldId && nativeOffsetField2Id) {
        reflectedField = env->ToReflectedField(clazz, nativeOffsetFieldId, true);
        reflectedField2 = env->ToReflectedField(clazz, nativeOffsetField2Id, true);
    }
    void *nativeOffsetField = GetFieldMethod(env, reflectedField);
    void *nativeOffsetField2 = GetFieldMethod(env, reflectedField2);
    long fieldDelta = (long) nativeOffsetField2 - (long) nativeOffsetField;
    if (nativeOffsetField && nativeOffsetField2 && fieldDelta > 0 && fieldDelta <= 256) {
        HookEnv.art_field_size = (unsigned int) fieldDelta;

        flags = 0x0;
        flags = flags | kAccPublic;
        flags = flags | kAccStatic;
        flags = flags | kAccFinal;
        if (api_level >= __ANDROID_API_Q__) {
            flags = flags | kAccPublicApi;
        }
        const uint32_t field_runtime_only_mask = kAccFastNative | kAccCriticalNative;
        char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
        for (unsigned int i = 1; i < HookEnv.art_field_size; ++i) {
            auto value = *(int32_t *) (fieldStart + i * sizeof(int32_t));
            if ((static_cast<uint32_t>(value) & ~field_runtime_only_mask) ==
                (flags & ~field_runtime_only_mask)) {
                HookEnv.art_field_flags_offset = (int) (i * sizeof(int32_t));
                break;
            }
        }
    } else {
        ALOGE("JniHook init: art field unresolved, setAccessible(field) disabled");
    }

    HookEnv.method_utils_class = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    if (!HookEnv.method_utils_class) {
        env->ExceptionClear();
        ALOGE("JniHook init: MethodUtils missing, HookJniFun disabled");
        return;
    }
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    env->ExceptionClear();
    // 偏移扫描是否成功决定 jnihook 在本机型上能不能工作，必须留一条可观测日志：
    // native_offset=0 或 flags_offset=0 说明 ArtMethod 布局没被正确识别，
    // 此时所有 hook 都会静默跳过（功能降级，但不应该崩）。
    ALOGD("JniHook init: api=%d method_size=%u native_offset=%d flags_offset=%d",
          HookEnv.api_level, HookEnv.art_method_size,
          HookEnv.art_method_native_offset, HookEnv.art_method_flags_offset);
}

