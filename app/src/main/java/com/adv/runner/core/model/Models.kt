package com.adv.runner.core.model

/* ---------------- APK 元数据 ---------------- */

enum class Severity { INFO, WARN, BLOCKER }

data class CompatIssue(
    val severity: Severity,
    val code: String,
    val message: String
)

data class ApkPermission(
    val name: String,
    val dangerous: Boolean,
    val protectionLevel: String,
    val group: String?
)

data class ComponentSummary(
    val activities: Int = 0,
    val services: Int = 0,
    val receivers: Int = 0,
    val providers: Int = 0
)

data class ApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val appLabel: String,
    val sha256: String,
    val fileSize: Long,
    val storedPath: String,
    val originalFileName: String? = null,
    val signerSha256: List<String>,
    val signerSubject: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int? = null,
    val abis: List<String>,
    val permissions: List<ApkPermission>,
    val launcherActivity: String?,
    val components: ComponentSummary,
    val isSplit: Boolean = false,
    val isDebuggable: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
    val issues: List<CompatIssue>
) {
    val hasBlocker: Boolean get() = issues.any { it.severity == Severity.BLOCKER }
}

/* ---------------- 运行实例 ---------------- */

enum class RuntimeState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPED,
    CRASHED,
    ANR,
    INSTALL_FAILED,
    LAUNCH_FAILED,
    CLEARING,
    DELETING,
    ENGINE_INCOMPATIBLE,
    UNKNOWN_INTERRUPTED
}

enum class ExitReason { USER_STOPPED, CRASHED, KILLED_BY_SYSTEM, ANR, UNKNOWN }

enum class SignatureMode { FIRST_INSTALL, SAME_SIGNATURE_UPDATE, SIGNATURE_MISMATCH }

data class RuntimeInstance(
    val id: String,
    val apkId: String? = null,
    val packageName: String,
    val displayName: String,
    val engineId: String,
    val engineVersion: String = "unknown",
    val virtualUserId: Int,
    val state: RuntimeState,
    val signatureMode: SignatureMode,
    val signatureAckAt: Long? = null,
    val installedVersionCode: Long?,
    val installedAt: Long? = null,
    val lastLaunchedAt: Long? = null,
    val dataRoot: String,
    val lastExitReason: ExitReason? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/* ---------------- 引擎能力与结果 ---------------- */

enum class Capability { SUPPORTED, PARTIAL, UNSUPPORTED, UNKNOWN }

data class EngineCapabilities(
    val engineId: String,
    val engineVersion: String,
    val supportedSdk: IntRange,
    val samePackageDifferentSignature: Capability = Capability.UNKNOWN,
    val multiInstance: Capability = Capability.UNKNOWN,
    val nativeLibs: Capability = Capability.UNKNOWN,
    val supports32BitApk: Capability = Capability.UNKNOWN,
    val webView: Capability = Capability.UNKNOWN,
    val gms: Capability = Capability.UNKNOWN,
    val notificationProxy: Capability = Capability.UNKNOWN,
    val backgroundService: Capability = Capability.UNKNOWN,
    val clearData: Capability = Capability.UNKNOWN,
    val exportData: Capability = Capability.UNKNOWN,
    val permissionInterception: Capability = Capability.UNKNOWN,
    val requiresRoot: Boolean = false,
    val notes: List<String> = emptyList()
)

enum class EngineErrorCode {
    ENGINE_NOT_AVAILABLE,
    UNSUPPORTED_BY_ENGINE,
    NOT_IMPLEMENTED,
    ABI_MISMATCH,
    SDK_OUT_OF_RANGE,
    SIGNATURE_MISMATCH,
    INSTALL_FAILED,
    LAUNCH_FAILED,
    CRASHED,
    ANR,
    INSUFFICIENT_STORAGE,
    INTERNAL
}

sealed interface EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>
    data class Failure(
        val code: EngineErrorCode,
        val message: String,
        val cause: Throwable? = null,
        val diagnosticsId: String? = null
    ) : EngineResult<Nothing>
}

fun <T> engineFailure(
    code: EngineErrorCode,
    message: String,
    cause: Throwable? = null
): EngineResult<T> = EngineResult.Failure(code, message, cause)

fun <T> EngineResult<T>.valueOrNull(): T? = (this as? EngineResult.Success)?.value
