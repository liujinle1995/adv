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

/**
 * 引擎能力的**三态**结论。
 *
 * 刻意只有三种取值，不允许再引入 `PARTIAL` 之类的模糊档：
 * - [SUPPORTED]：已实测通过，可以依赖；
 * - [UNSUPPORTED]：已确认不可用，必须拦住依赖它的功能；
 * - [UNKNOWN]：**没验证过**，既不等于支持也不等于不支持。
 *
 * 「部分支持」这种描述属于实现细节，写进 [EngineCapabilities.notes] 里，
 * 不参与流程判断——否则每个调用点都要自己解释 PARTIAL 该算支持还是不支持。
 */
enum class Capability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN;

    /** 是否已有确定结论（SUPPORTED / UNSUPPORTED）。 */
    val isKnown: Boolean get() = this != UNKNOWN

    /** 是否确认支持。UNKNOWN 不算支持。 */
    val isConfirmedSupported: Boolean get() = this == SUPPORTED
}

/**
 * 依赖某项能力时的动作裁决：把「三态」翻译成流程里唯一允许的三种行为，
 * 避免各调用点各写一套 `if (cap != null && cap != UNSUPPORTED)`。
 */
enum class CapabilityDecision {
    /** 可以执行。 */
    ALLOW,

    /** 功能依赖的能力已确认不可用，必须拦住。 */
    BLOCK,

    /** 能力未验证，允许继续但必须让用户确认风险。 */
    CONFIRM
}

fun Capability.decide(): CapabilityDecision = when (this) {
    Capability.SUPPORTED -> CapabilityDecision.ALLOW
    Capability.UNSUPPORTED -> CapabilityDecision.BLOCK
    Capability.UNKNOWN -> CapabilityDecision.CONFIRM
}

/** 能力缺失（引擎尚未报告该字段）与 UNKNOWN 同义处理，不再单独开分支。 */
fun Capability?.decideOrUnknown(): CapabilityDecision =
    (this ?: Capability.UNKNOWN).decide()


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
