package com.adv.runner.engine.mock

import com.adv.runner.core.model.Capability
import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineErrorCode
import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.engineFailure
import com.adv.runner.diagnostics.RunLogger
import com.adv.runner.engine.api.EngineContext
import com.adv.runner.engine.api.EngineSelfCheck
import com.adv.runner.engine.api.InstallRequest
import com.adv.runner.engine.api.InstalledApp
import com.adv.runner.engine.api.LaunchOptions
import com.adv.runner.engine.api.RuntimeProbe
import com.adv.runner.engine.api.SelfCheckItem
import com.adv.runner.engine.api.VirtualizationEngine
import java.io.File

/**
 * 模拟引擎：**不加载任何第三方 APK**，只用于把 UI / 状态机 / 日志链路跑通。
 *
 * 它存在的唯一目的：在真实引擎尚未验证前，避免"UI 与引擎同时调不通"。
 * 所有能力一律 UNKNOWN，UI 必须显示"模拟引擎"横幅。
 */
class MockEngine(private val logger: RunLogger) : VirtualizationEngine {

    override val id: String = "mock"
    override val displayName: String = "MockEngine（流程演示，不提供真实虚拟化）"

    private var context: EngineContext? = null
    private var caps: EngineCapabilities? = null
    private val states = mutableMapOf<String, RuntimeState>()
    private val roots = mutableMapOf<String, String>()

    override suspend fun initialize(context: EngineContext): EngineResult<EngineCapabilities> {
        this.context = context
        val snapshot = EngineCapabilities(
            engineId = id,
            engineVersion = "0.1.0-mock",
            supportedSdk = 26..36,
            samePackageDifferentSignature = Capability.UNKNOWN,
            multiInstance = Capability.SUPPORTED,
            nativeLibs = Capability.UNSUPPORTED,
            supports32BitApk = Capability.UNKNOWN,
            clearData = Capability.SUPPORTED,
            requiresRoot = false,
            notes = listOf(
                "当前为模拟引擎：不加载任何第三方 APK。",
                "真实虚拟化能力需在阶段 1 用 BlackBox 2.1.0 探针实测后填入。"
            )
        )
        caps = snapshot
        logger.info(TAG, "MockEngine initialized, storageRoot=${context.storageRoot.absolutePath}")
        return EngineResult.Success(snapshot)
    }

    override fun capabilities(): EngineCapabilities? = caps

    override suspend fun attachExisting(instance: RuntimeInstance) {
        states.putIfAbsent(instance.id, instance.state)
        roots.putIfAbsent(instance.id, instance.dataRoot)
    }

    override suspend fun installApk(request: InstallRequest): EngineResult<InstalledApp> {
        val ctx = context
            ?: return engineFailure(EngineErrorCode.ENGINE_NOT_AVAILABLE, "引擎未初始化")
        val apk = File(request.apkPath)
        if (!apk.isFile) {
            return engineFailure(EngineErrorCode.INSTALL_FAILED, "APK 文件不存在：${request.apkPath}")
        }
        val dataRoot = File(ctx.storageRoot, "vuser/${request.virtualUserId}/${request.packageName}")
        if (!dataRoot.exists() && !dataRoot.mkdirs()) {
            return engineFailure(EngineErrorCode.INSTALL_FAILED, "无法创建实例数据目录：${dataRoot.absolutePath}")
        }
        states[request.instanceId] = RuntimeState.INSTALLED
        roots[request.instanceId] = dataRoot.absolutePath
        logger.info(TAG, "MOCK install instance=${request.instanceId} pkg=${request.packageName} uid=${request.virtualUserId}")
        return EngineResult.Success(
            InstalledApp(
                packageName = request.packageName,
                installedVersionCode = 0L,
                virtualUserId = request.virtualUserId,
                dataRoot = dataRoot.absolutePath,
                signatureSha256 = request.expectedSignatureSha256 ?: emptyList()
            )
        )
    }

    override suspend fun launch(instanceId: String, options: LaunchOptions): EngineResult<Unit> {
        val current = states[instanceId]
            ?: return engineFailure(EngineErrorCode.LAUNCH_FAILED, "实例不存在或未安装：$instanceId")
        if (current == RuntimeState.RUNNING) {
            return engineFailure(EngineErrorCode.LAUNCH_FAILED, "实例已在运行中")
        }
        states[instanceId] = RuntimeState.RUNNING
        logger.info(TAG, "MOCK launch instance=$instanceId (模拟启动，未真正运行第三方进程)")
        return EngineResult.Success(Unit)
    }

    override suspend fun stop(instanceId: String): EngineResult<Unit> {
        if (!states.containsKey(instanceId)) {
            return engineFailure(EngineErrorCode.UNSUPPORTED_BY_ENGINE, "实例不存在：$instanceId")
        }
        states[instanceId] = RuntimeState.STOPPED
        logger.info(TAG, "MOCK stop instance=$instanceId")
        return EngineResult.Success(Unit)
    }

    override suspend fun queryState(instanceId: String): EngineResult<RuntimeProbe> {
        val state = states[instanceId]
            ?: return EngineResult.Success(RuntimeProbe(RuntimeState.NOT_INSTALLED, evidence = "mock: no record"))
        return EngineResult.Success(
            RuntimeProbe(
                state = state,
                evidence = "mock: 无真实进程，状态由内存模拟",
                exitReason = if (state == RuntimeState.STOPPED) ExitReason.USER_STOPPED else null
            )
        )
    }

    override suspend fun clearData(instanceId: String): EngineResult<Unit> {
        val root = roots[instanceId]
            ?: return engineFailure(EngineErrorCode.UNSUPPORTED_BY_ENGINE, "实例未安装，无法清数据")
        if (File(root).exists() && !File(root).deleteRecursively()) {
            return engineFailure(EngineErrorCode.INTERNAL, "清数据失败：$root")
        }
        states[instanceId] = RuntimeState.INSTALLED
        logger.info(TAG, "MOCK clearData instance=$instanceId root=$root")
        return EngineResult.Success(Unit)
    }

    override suspend fun uninstall(instanceId: String): EngineResult<Unit> {
        roots[instanceId]?.let { path -> runCatching { File(path).deleteRecursively() } }
        states.remove(instanceId)
        roots.remove(instanceId)
        logger.info(TAG, "MOCK uninstall instance=$instanceId（幂等）")
        return EngineResult.Success(Unit)
    }

    override suspend fun selfCheck(): EngineResult<EngineSelfCheck> {
        val ctx = context
            ?: return engineFailure(EngineErrorCode.ENGINE_NOT_AVAILABLE, "引擎未初始化")
        val writable = runCatching {
            if (!ctx.storageRoot.exists()) ctx.storageRoot.mkdirs()
            File(ctx.storageRoot, ".probe").apply { writeText("ok") }.delete()
            true
        }.getOrDefault(false)
        val checks = listOf(
            SelfCheckItem("storage", writable, ctx.storageRoot.absolutePath),
            SelfCheckItem("engine", true, "mock: 无需加载 native so"),
            SelfCheckItem("real_virtualization", false, "模拟引擎不提供真实虚拟化")
        )
        return EngineResult.Success(EngineSelfCheck(passed = checks.all { it.passed }, checks = checks))
    }

    override suspend fun captureLogs(instanceId: String, maxBytes: Long): EngineResult<String> {
        val file = logger.latestLogFile()
            ?: return engineFailure(EngineErrorCode.INTERNAL, "暂无日志文件")
        return EngineResult.Success(file.absolutePath)
    }

    override fun release() {
        states.clear()
        roots.clear()
        caps = null
        context = null
    }

    private companion object {
        const val TAG = "MockEngine"
    }
}
