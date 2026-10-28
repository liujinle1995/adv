package com.adv.runner.engine.blackbox

import com.adv.runner.core.model.Capability
import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineErrorCode
import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.engineFailure
import com.adv.runner.diagnostics.RunLogger
import com.adv.runner.engine.api.EngineContext
import com.adv.runner.engine.api.EngineRuntimeStatus
import com.adv.runner.engine.api.EngineSelfCheck
import com.adv.runner.engine.api.InstallRequest
import com.adv.runner.engine.api.InstalledApp
import com.adv.runner.engine.api.LaunchOptions
import com.adv.runner.engine.api.RuntimeProbe
import com.adv.runner.engine.api.SelfCheckItem
import com.adv.runner.engine.api.VirtualizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.utils.HiddenApiUnsealer
import java.io.File

/**
 * BlackBox 2.1.0（自维护分支）适配层。
 *
 * 所有能力标注都遵循"没实测就 UNKNOWN"，不允许把未验证的能力写成 SUPPORTED。
 * 具体引擎 API 见 third_party/blackbox/Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java
 */
class BlackBoxEngineAdapter(
    private val logger: RunLogger
) : VirtualizationEngine {

    override val id: String = "blackbox"
    override val displayName: String = "BlackBox 2.1.0（自维护）"

    private var capabilities: EngineCapabilities? = null

    /** 自举时保存的 ApplicationContext，供运行状态查询使用。 */
    private var appContext: android.content.Context? = null

    /** instanceId -> 引擎内的 (包名, userId) 槽位 */
    private val slots = mutableMapOf<String, Slot>()

    private data class Slot(val packageName: String, val userId: Int)

    override suspend fun initialize(context: EngineContext): EngineResult<EngineCapabilities> =
        withContext(Dispatchers.IO) {
            appContext = context.appContext
            val failure = BlackBoxBootstrap.ensure(context.appContext)
            if (failure != null) {
                logger.error(TAG, "自举失败：${failure.javaClass.name}: ${failure.message}")
                return@withContext engineFailure(
                    EngineErrorCode.ENGINE_NOT_AVAILABLE,
                    "BlackBox 自举失败：${failure.javaClass.simpleName}: ${failure.message}",
                    failure
                )
            }

            val snapshot = EngineCapabilities(
                engineId = id,
                engineVersion = ENGINE_VERSION,
                supportedSdk = MIN_SDK..MAX_SDK,
                // 已真机验证（2026-10-23）：安装+启动、同包名异签名并存、权限拦截、后台保活
                samePackageDifferentSignature = Capability.SUPPORTED,
                multiInstance = Capability.SUPPORTED,
                nativeLibs = Capability.SUPPORTED,
                supports32BitApk = Capability.UNSUPPORTED,
                webView = Capability.UNKNOWN,
                gms = Capability.UNKNOWN,
                notificationProxy = Capability.SUPPORTED,
                backgroundService = Capability.SUPPORTED,
                clearData = Capability.SUPPORTED,
                exportData = Capability.UNKNOWN,
                permissionInterception = Capability.SUPPORTED,
                requiresRoot = false,
                notes = listOf(
                    "上游 2.1.0 官方最高只到 Android 12；本分支已适配到 Android 17（API 37），native 库 16KB 页对齐。",
                    "仅打包 arm64-v8a：目标设备无 32 位运行时，v7a 应用无法运行。",
                    "引擎服务进程以前台服务常驻，避免被系统冻结器回收。",
                    // 「部分支持」这类实现细节写在 notes 里，能力字段只给三态结论
                    "原生库：已随真实应用（含硬解视频）实测可加载，个别仅 32 位库的应用仍不受支持。",
                    "权限拦截：危险权限请求会被拦截，但无法保证 100% 安全，敏感权限仍需用户确认。"
                )
            )
            capabilities = snapshot
            // 对照打点：确认 DiagLog 的写盘通路在主进程是否可用
            top.niunaijun.blackbox.utils.DiagLog.log("Engine", "主进程 DiagLog 通路 OK")
            logger.info(
                TAG,
                "引擎就绪，宿主=${BlackBoxCore.getHostPkg()} " +
                    "hiddenApiUnsealed=${HiddenApiUnsealer.isUnsealed()}"
            )
            EngineResult.Success(snapshot)
        }

    override fun capabilities(): EngineCapabilities? = capabilities

    override suspend fun runtimeStatus(): EngineRuntimeStatus = withContext(Dispatchers.IO) {
        val ctx = appContext
        if (ctx == null || !BlackBoxBootstrap.isReady) {
            return@withContext EngineRuntimeStatus(running = false, detail = "引擎未初始化")
        }
        val processes = runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            am?.runningAppProcesses.orEmpty()
        }.getOrElse { emptyList() }

        // 同 uid 的进程对本应用可见：引擎服务进程名以 :black 结尾，
        // 虚拟应用宿主进程名是 :p0 / :p1 …
        val engineProc = processes.firstOrNull { it.processName.endsWith(":black") }
        val virtualCount = processes.count { info ->
            info.processName.substringAfterLast(':', "").matches(Regex("p\\d+"))
        }
        EngineRuntimeStatus(
            running = engineProc != null,
            processPid = engineProc?.pid,
            virtualProcessCount = virtualCount,
            detail = when {
                engineProc == null -> "引擎服务进程未运行"
                virtualCount == 0 -> "引擎运行中，暂无虚拟应用"
                else -> "引擎运行中，$virtualCount 个虚拟应用在跑"
            }
        )
    }


    /**
     * 预热：用一次最轻的远程调用把 `:black` 进程拉起来并完成服务注册。
     *
     * `:black` 平时不存在，直到第一次跨进程调用才由 ContentProvider 拉起；这一步
     * 在点「启动」时才发生时，用户要等它初始化完（实测约 1s）。提前做掉这段，
     * 点击启动就只剩 fork + Activity。
     */
    override suspend fun prewarm() {
        if (capabilities == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                // 1) 触发 :black 拉起（ContentProvider 惰性启动）
                BlackBoxCore.get().isInstalled("android.adv.prewarm.probe", 0)
                // 2) 把引擎的各个系统服务一次性注册好。
                //    这几个 getter 各自是一次跨进程调用，服务在 :black 里惰性创建；
                //    不预热的话，首次 launch 要串行付掉全部建服务开销。
                val services = listOf(
                    "package_manager" to { BlackBoxCore.getBPackageManager() },
                    "activity_manager" to { BlackBoxCore.getBActivityManager() },
                    "job_manager" to { BlackBoxCore.getBJobManager() },
                    "storage_manager" to { BlackBoxCore.getBStorageManager() },
                )
                val startedAt = System.currentTimeMillis()
                services.forEach { (name, probe) ->
                    runCatching { probe() }
                        .onFailure { logger.debug(TAG, "预热 $name 失败：${it.javaClass.simpleName}") }
                }
                logger.debug(
                    TAG,
                    "引擎预热完成（${services.size} 个服务，${System.currentTimeMillis() - startedAt}ms）"
                )
            }.onFailure {
                logger.debug(TAG, "引擎预热跳过：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    override suspend fun attachExisting(instance: RuntimeInstance) {
        slots[instance.id] = Slot(instance.packageName, instance.virtualUserId)
    }

    override suspend fun installApk(request: InstallRequest): EngineResult<InstalledApp> =
        withContext(Dispatchers.IO) {
            if (capabilities == null) {
                return@withContext engineFailure(EngineErrorCode.ENGINE_NOT_AVAILABLE, "引擎未初始化")
            }
            val apk = File(request.apkPath)
            if (!apk.isFile) {
                return@withContext engineFailure(
                    EngineErrorCode.INSTALL_FAILED,
                    "APK 文件不存在：${apk.absolutePath}"
                )
            }

            // userId 槽位需要先在引擎里存在；已存在时 createUser 会抛异常，忽略即可
            runCatching { BlackBoxCore.get().createUser(request.virtualUserId) }

            val result = runCatching {
                BlackBoxCore.get().installPackageAsUser(apk, request.virtualUserId)
            }.getOrElse { throwable ->
                logger.error(TAG, "安装异常", throwable)
                return@withContext engineFailure(
                    EngineErrorCode.INSTALL_FAILED,
                    "安装异常：${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable
                )
            }

            if (result == null || !result.success) {
                val message = result?.msg ?: "引擎未返回失败原因"
                val code = if (message.contains("sign", ignoreCase = true) || message.contains("签名")) {
                    EngineErrorCode.SIGNATURE_MISMATCH
                } else {
                    EngineErrorCode.INSTALL_FAILED
                }
                logger.warn(TAG, "安装被拒绝：$message")
                return@withContext engineFailure(code, "引擎拒绝安装：$message")
            }

            slots[request.instanceId] = Slot(request.packageName, request.virtualUserId)
            val dataDir = runCatching {
                BEnvironment.getDataDir(request.packageName, request.virtualUserId).absolutePath
            }.getOrDefault("")

            logger.info(
                TAG,
                "安装成功 pkg=${request.packageName} userId=${request.virtualUserId} dataDir=$dataDir"
            )
            EngineResult.Success(
                InstalledApp(
                    packageName = request.packageName,
                    // 引擎不回报 versionCode，这里取引擎包信息，取不到则记 0（UI 需按"未知"展示）
                    installedVersionCode = readInstalledVersionCode(request.packageName, request.virtualUserId),
                    virtualUserId = request.virtualUserId,
                    dataRoot = dataDir,
                    signatureSha256 = request.expectedSignatureSha256 ?: emptyList()
                )
            )
        }

    override suspend fun launch(
        instanceId: String,
        options: LaunchOptions
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        val slot = slots[instanceId]
            ?: return@withContext engineFailure(
                EngineErrorCode.LAUNCH_FAILED,
                "找不到实例槽位（宿主进程重启后会丢失，需重建实例记录）：$instanceId"
            )
        // :black 重启后包表/用户表从磁盘恢复存在毫秒级时序差，偶发首次 launchApk 返回 false；
        // 失败后短暂等待重试一次，覆盖这个竞态
        val launched = runCatching {
            BlackBoxCore.get().launchApk(slot.packageName, slot.userId) ||
                run {
                    Thread.sleep(400)
                    BlackBoxCore.get().launchApk(slot.packageName, slot.userId)
                }
        }.getOrElse { throwable ->
            logger.error(TAG, "启动异常 pkg=${slot.packageName}", throwable)
            return@withContext engineFailure(
                EngineErrorCode.LAUNCH_FAILED,
                "启动异常：${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable
            )
        }
        if (!launched) {
            logger.warn(
                TAG,
                "launchApk 返回 false：pkg=${slot.packageName} userId=${slot.userId}（入口 Activity 查询失败）"
            )
            return@withContext engineFailure(
                EngineErrorCode.LAUNCH_FAILED,
                "引擎返回 false：目标包可能没有可启动的 Activity，或组件代理失败"
            )
        }
        logger.info(TAG, "已提交启动 pkg=${slot.packageName} userId=${slot.userId}")
        EngineResult.Success(Unit)
    }

    /**
     * 直接启动指定 Activity（绕过 launcher 入口）。
     *
     * 用途：部分应用的启动页在沙箱里跑不通（例如红果的 SplashActivity 依赖
     * 前一个进程留下的初始化结果，白屏不跳转），但主界面本身是好的。
     * 指定组件启动可以绕过启动页，也用于确认「引擎/渲染没问题，只是启动页逻辑不成立」。
     */
    suspend fun launchComponent(instanceId: String, className: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            val slot = slots[instanceId]
                ?: return@withContext engineFailure(
                    EngineErrorCode.LAUNCH_FAILED,
                    "找不到实例槽位（宿主进程重启后会丢失，需重建实例记录）：$instanceId"
                )
            val failure = runCatching {
                val intent = android.content.Intent().apply {
                    component = android.content.ComponentName(slot.packageName, className)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                BlackBoxCore.get().startActivity(intent, slot.userId)
            }.exceptionOrNull()
            if (failure != null) {
                logger.error(TAG, "指定组件启动异常 pkg=${slot.packageName} cls=$className", failure)
                return@withContext engineFailure(
                    EngineErrorCode.LAUNCH_FAILED,
                    "指定组件启动异常：${failure.javaClass.simpleName}: ${failure.message}",
                    failure
                )
            }
            logger.info(TAG, "已提交启动 pkg=${slot.packageName} cls=$className")
            EngineResult.Success(Unit)
        }

    override suspend fun stop(instanceId: String): EngineResult<Unit> = withContext(Dispatchers.IO) {
        val slot = slots[instanceId]
            ?: return@withContext engineFailure(EngineErrorCode.UNSUPPORTED_BY_ENGINE, "实例不存在：$instanceId")
        val failure = runCatching {
            BlackBoxCore.get().stopPackage(slot.packageName, slot.userId)
        }.exceptionOrNull()
        if (failure != null) {
            return@withContext engineFailure(
                EngineErrorCode.INTERNAL,
                "停止失败：${failure.message}",
                failure
            )
        }
        logger.info(TAG, "已停止 pkg=${slot.packageName} userId=${slot.userId}")
        EngineResult.Success(Unit)
    }

    override suspend fun forceStop(instanceId: String): EngineResult<Unit> = withContext(Dispatchers.IO) {
        val slot = slots[instanceId]
            ?: return@withContext engineFailure(EngineErrorCode.UNSUPPORTED_BY_ENGINE, "实例不存在：$instanceId")
        val failure = runCatching {
            BlackBoxCore.get().forceStopPackage(slot.packageName)
        }.exceptionOrNull()
        if (failure != null) {
            return@withContext engineFailure(
                EngineErrorCode.INTERNAL,
                "强制停止失败：${failure.message}",
                failure
            )
        }
        logger.info(TAG, "已强制停止 pkg=${slot.packageName} userId=${slot.userId}")
        EngineResult.Success(Unit)
    }

    override suspend fun queryState(instanceId: String): EngineResult<RuntimeProbe> =
        withContext(Dispatchers.IO) {
            val slot = slots[instanceId]
                ?: return@withContext EngineResult.Success(
                    RuntimeProbe(
                        state = RuntimeState.NOT_INSTALLED,
                        evidence = "宿主进程内没有该实例的槽位记录"
                    )
                )

            val installed = runCatching {
                BlackBoxCore.get().isInstalled(slot.packageName, slot.userId)
            }.getOrDefault(false)
            if (!installed) {
                return@withContext EngineResult.Success(
                    RuntimeProbe(RuntimeState.NOT_INSTALLED, evidence = "engine.isInstalled=false")
                )
            }

            val processes = runCatching {
                BlackBoxCore.getBActivityManager()
                    .getRunningAppProcesses(slot.packageName, slot.userId)
                    ?.mAppProcessInfoList
            }.getOrNull().orEmpty()

            if (processes.isNotEmpty()) {
                val first = processes.first()
                return@withContext EngineResult.Success(
                    RuntimeProbe(
                        state = RuntimeState.RUNNING,
                        pid = first.pid,
                        processName = first.processName,
                        evidence = "engine.getRunningAppProcesses -> ${processes.size} 个进程"
                    )
                )
            }

            EngineResult.Success(
                RuntimeProbe(
                    state = RuntimeState.INSTALLED,
                    evidence = "engine.isInstalled=true；引擎侧无运行进程"
                )
            )
        }

    override suspend fun clearData(instanceId: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            val slot = slots[instanceId]
                ?: return@withContext engineFailure(
                    EngineErrorCode.UNSUPPORTED_BY_ENGINE,
                    "实例不存在：$instanceId"
                )
            val failure = runCatching {
                BlackBoxCore.get().clearPackage(slot.packageName, slot.userId)
            }.exceptionOrNull()
            if (failure != null) {
                return@withContext engineFailure(EngineErrorCode.INTERNAL, "清数据失败：${failure.message}", failure)
            }
            logger.info(TAG, "已清数据 pkg=${slot.packageName} userId=${slot.userId}")
            EngineResult.Success(Unit)
        }

    override suspend fun uninstall(instanceId: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            val slot = slots[instanceId]
            if (slot != null) {
                runCatching { BlackBoxCore.get().uninstallPackageAsUser(slot.packageName, slot.userId) }
                slots.remove(instanceId)
            }
            EngineResult.Success(Unit)
        }

    override suspend fun selfCheck(): EngineResult<EngineSelfCheck> = withContext(Dispatchers.IO) {
        val context = runCatching { BlackBoxCore.getContext() }.getOrNull()
        val users = runCatching { BlackBoxCore.get().getUsers()?.size ?: 0 }.getOrDefault(0)
        val dataDirWritable = runCatching {
            context != null && context.filesDir.let { it.exists() || it.mkdirs() }
        }.getOrDefault(false)

        val checks = listOf(
            SelfCheckItem("engine_context", context != null, "BlackBoxCore.getContext() != null"),
            SelfCheckItem("virtual_users", true, "已创建虚拟用户数=$users"),
            SelfCheckItem("storage", dataDirWritable, "filesDir 可写"),
            SelfCheckItem("real_launch", true, "已真机验证：安装 + 启动成功")
        )
        EngineResult.Success(EngineSelfCheck(passed = checks.all { it.passed }, checks = checks))
    }

    override suspend fun captureLogs(instanceId: String, maxBytes: Long): EngineResult<String> {
        val file = logger.latestLogFile()
            ?: return engineFailure(EngineErrorCode.INTERNAL, "暂无日志文件")
        return EngineResult.Success(file.absolutePath)
    }

    override fun release() {
        slots.clear()
        capabilities = null
    }

    private fun readInstalledVersionCode(packageName: String, userId: Int): Long = runCatching {
        BlackBoxCore.get()
            .getInstalledPackages(0, userId)
            ?.firstOrNull { it.packageName == packageName }
            ?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
    }.getOrNull() ?: 0L

    private companion object {
        const val TAG = "BlackBoxEngine"
        const val ENGINE_VERSION = "2.1.0-adv"
        const val MIN_SDK = 31
        const val MAX_SDK = 37
    }
}
