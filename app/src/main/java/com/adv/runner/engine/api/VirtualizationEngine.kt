package com.adv.runner.engine.api

import android.content.Context
import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.diagnostics.RunLogger
import java.io.File

/**
 * 引擎适配接口（管理器契约，不是某个引擎的能力清单）。
 *
 * 设计约束：
 * 1. 不暴露任何引擎 SDK 类型，上层只依赖本项目模型；
 * 2. 任何方法都可能返回 UNSUPPORTED_BY_ENGINE，UI 必须能展示"不支持"；
 * 3. launch() 成功只代表"已受理"，真实运行态以 queryState() 的取证结果为准；
 * 4. 所有失败必须带错误码与中文消息，并写入诊断日志。
 */
interface VirtualizationEngine {

    val id: String
    val displayName: String

    /** 幂等；失败后允许重试。 */
    suspend fun initialize(context: EngineContext): EngineResult<EngineCapabilities>

    /** 未初始化返回 null。 */
    fun capabilities(): EngineCapabilities?

    /**
     * 恢复历史实例的槽位映射。
     *
     * 引擎内部的「instanceId → (包名, userId)」映射是内存态，宿主进程重启后会丢失。
     * 上层在启动对账时调用本方法把磁盘上已有的实例重新挂回引擎，之后 queryState/launch
     * 才能正常工作。幂等，且只做内存重建、不触发任何引擎动作。
     */
    suspend fun attachExisting(instance: RuntimeInstance)

    suspend fun installApk(request: InstallRequest): EngineResult<InstalledApp>

    suspend fun launch(instanceId: String, options: LaunchOptions = LaunchOptions()): EngineResult<Unit>

    suspend fun stop(instanceId: String): EngineResult<Unit>

    suspend fun queryState(instanceId: String): EngineResult<RuntimeProbe>

    suspend fun clearData(instanceId: String): EngineResult<Unit>

    suspend fun uninstall(instanceId: String): EngineResult<Unit>

    suspend fun selfCheck(): EngineResult<EngineSelfCheck>

    suspend fun captureLogs(instanceId: String, maxBytes: Long = 512 * 1024): EngineResult<String>

    fun release()
}

data class EngineContext(
    val appContext: Context,
    val storageRoot: File,
    val logger: RunLogger,
    val deviceAbis: List<String>,
    val deviceSdk: Int
)

data class InstallRequest(
    val instanceId: String,
    val apkPath: String,
    val sha256: String,
    val packageName: String,
    val virtualUserId: Int,
    /** 期望签名摘要；传入时引擎必须校验，不匹配返回 SIGNATURE_MISMATCH。 */
    val expectedSignatureSha256: List<String>? = null
)

data class InstalledApp(
    val packageName: String,
    val installedVersionCode: Long,
    val virtualUserId: Int,
    val dataRoot: String,
    val signatureSha256: List<String>
)

data class LaunchOptions(
    val launchActivity: String? = null,
    val extraArgs: Map<String, String> = emptyMap()
)

data class RuntimeProbe(
    val state: RuntimeState,
    val pid: Int? = null,
    val processName: String? = null,
    /** 状态判定依据，例如 "engine.isRunning=true; pid=12345 alive" */
    val evidence: String,
    val exitReason: ExitReason? = null
)

data class EngineSelfCheck(
    val passed: Boolean,
    val checks: List<SelfCheckItem>
)

data class SelfCheckItem(
    val name: String,
    val passed: Boolean,
    val detail: String
)
