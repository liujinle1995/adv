//
// Created by Milk on 4/9/21.
//

#include <IO.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"
#include "BaseHook.h"

/*
 * Class:     java_io_UnixFileSystem
 * Method:    canonicalize0
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_canonicalize0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    canonicalize0
 * Signature: (Ljava/lang/String;Z)Ljava/lang/String;
 *
 * Android 14+（实测 Android 16 / API 36 是这样）给 canonicalize0 的 native 签名
 * 加了一个 followLinks 参数，旧的 (String) 签名只剩一个 Java 包装方法。
 * 旧签名仍能被 GetMethodID 找到（包装方法在），但 JniHook::CheckFlags 现在会用
 * 反射判它非 native 并主动跳过，不会再走 RegisterNatives 抛 NoSuchMethodError。
 */
HOOK_JNI(jstring, canonicalize0_v2, JNIEnv *env, jobject obj, jstring path, jboolean followLinks) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_canonicalize0_v2(env, obj, redirect, followLinks);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getBooleanAttributes0
 * Signature: (Ljava/lang/String;)I
 */
HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    jstring redirect = IO::redirectPath(env, abspath);
    return orig_getBooleanAttributes0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getLastModifiedTime0
 * Signature: (Ljava/io/File;)J
 */
HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_getLastModifiedTime0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setPermission0
 * Signature: (Ljava/io/File;IZZ)Z
 */
HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setPermission0(env, obj, redirect, access, enable, owneronly);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createFileExclusively0
 * Signature: (Ljava/lang/String;)Z
 */
HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_createFileExclusively0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    list0
 * Signature: (Ljava/io/File;)[Ljava/lang/String;
 */
HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_list0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createDirectory0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_createDirectory0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setLastModifiedTime0
 * Signature: (Ljava/io/File;J)Z
 */
HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jobject time) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setLastModifiedTime0(env, obj, redirect, time);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setReadOnly0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setReadOnly0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getSpace0
 * Signature: (Ljava/io/File;I)J
 */
HOOK_JNI(jboolean, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getSpace0(env, obj, redirect, t);
}

void UnixFileSystemHook::init(JNIEnv *env) {
    const char *className = "java/io/UnixFileSystem";
    // 先试新签名（Android 14+），再试旧签名（Android 13 及以下）。
    // 探测是安全的：新签名在 13 及以下不存在（GetMethodID 返回 null 并清异常）；
    // 旧签名在 14+ 只是 Java 包装方法，CheckFlags 判非 native 后跳过，不会误注册。
    JniHook::HookJniFun(env, className, "canonicalize0", "(Ljava/lang/String;Z)Ljava/lang/String;",
                        (void *) new_canonicalize0_v2, (void **) (&orig_canonicalize0_v2), false);
    JniHook::HookJniFun(env, className, "canonicalize0", "(Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_canonicalize0, (void **) (&orig_canonicalize0), false);
//    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/lang/String;)I",
//                        (void *) new_getBooleanAttributes0,
//                        (void **) (&orig_getBooleanAttributes0), false);
    JniHook::HookJniFun(env, className, "getLastModifiedTime0", "(Ljava/io/File;)J",
                        (void *) new_getLastModifiedTime0, (void **) (&orig_getLastModifiedTime0),
                        false);
    JniHook::HookJniFun(env, className, "setPermission0", "(Ljava/io/File;IZZ)Z",
                        (void *) new_setPermission0, (void **) (&orig_setPermission0), false);
    JniHook::HookJniFun(env, className, "createFileExclusively0", "(Ljava/lang/String;)Z",
                        (void *) new_createFileExclusively0,
                        (void **) (&orig_createFileExclusively0), false);
    JniHook::HookJniFun(env, className, "list0", "(Ljava/io/File;)[Ljava/lang/String;",
                        (void *) new_list0, (void **) (&orig_list0), false);
    JniHook::HookJniFun(env, className, "createDirectory0", "(Ljava/io/File;)Z",
                        (void *) new_createDirectory0, (void **) (&orig_createDirectory0), false);
    JniHook::HookJniFun(env, className, "setLastModifiedTime0", "(Ljava/io/File;J)Z",
                        (void *) new_setLastModifiedTime0, (void **) (&orig_setLastModifiedTime0),
                        false);
    JniHook::HookJniFun(env, className, "setReadOnly0", "(Ljava/io/File;)Z",
                        (void *) new_setReadOnly0, (void **) (&orig_setReadOnly0), false);
    JniHook::HookJniFun(env, className, "getSpace0", "(Ljava/io/File;I)J",
                        (void *) new_getSpace0, (void **) (&orig_getSpace0), false);
}