package com.adv.runner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adv.runner.apk.ApkAnalyzer
import com.adv.runner.apk.ApkStore
import com.adv.runner.BuildConfig
import com.adv.runner.MainActivity
import com.adv.runner.core.control.InstanceController
import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineErrorCode
import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.engineFailure
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RunEventType
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.SignatureMode
import com.adv.runner.data.ApkRepository
import com.adv.runner.data.EventRepository
import com.adv.runner.data.InstanceRepository
import com.adv.runner.data.LegacyMigrator
import com.adv.runner.diagnostics.RunLogger
import com.adv.runner.engine.api.EngineContext
import com.adv.runner.engine.api.EngineRuntimeStatus
import com.adv.runner.engine.api.EngineSelfCheck
import com.adv.runner.engine.api.InstallRequest
import com.adv.runner.engine.api.VirtualizationEngine
import com.adv.runner.ui.theme.ThemeMode
import com.adv.runner.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 应用状态与业务编排的唯一入口。
 *
 * UI 只负责渲染与触发，所有副作用（解析、安装、启动、停止、删除、持久化、对账）都收敛到这里，
 * 便于后续接 ViewModel / 多进程与测试。
 */
class AppState(
    private val context: Context,
    private val logger: RunLogger,
    private val analyzer: ApkAnalyzer,
    private val engine: VirtualizationEngine,
    private val store: InstanceRepository,
    private val eventStore: EventRepository,
    private val controller: InstanceController,
    private val apkStore: ApkStore,
    private val apkRepository: ApkRepository,
    val themeRepository: ThemeRepository,
    val deviceAbis: List<String>,
    val deviceSdk: Int,
    private val scope: CoroutineScope,
    val debugApkPath: String? = null,
    /** 调试入口：启动时直接拉起该包名的已有实例。 */
    val debugLaunchPkg: String? = null,
    /** 调试入口：拉起前先清空该实例的沙箱数据（用于排除「反复失败被记进安全模式」）。 */
    val debugReset: Boolean = false,
    private val migrator: LegacyMigrator? = null
) {

    private val floatingButton = FloatingButtonManager(context)

    var caps by mutableStateOf<EngineCapabilities?>(null)
        private set
    var apk by mutableStateOf<ApkInfo?>(null)
        private set
    var instances by mutableStateOf<List<RuntimeInstance>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("就绪：请导入一个 APK")
        private set

    /**
     * 启动阶段。
     *
     * 存在的理由：`instances` 为空既可能是「真没装过」，也可能是「清单还没读出来 / 读失败了」。
     * 旧实现两种情况都渲染成同一个空态「暂无实例」，于是引擎侧一次异常就能让用户
     * 以为实例全丢了（真机反馈："重启后实例都没了"）。
     */
    var bootPhase by mutableStateOf(BootPhase.LOADING)
        private set

    /** 启动失败的原因（引擎初始化/对账阶段），供空态页展示与重试。 */
    var initError by mutableStateOf<String?>(null)
        private set

    /** 防止 initialize() 并发重入（LaunchedEffect 与手动重试可能同时触发）。 */
    private var initializing = false
    var screen by mutableStateOf(Screen.HOME)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var runEvents by mutableStateOf<List<RunEvent>>(emptyList())
        private set

    /** 待用户确认的签名冲突信息；非空时 UI 弹二选一确认框。 */
    var signatureConflict by mutableStateOf<SignatureConflict?>(null)
        private set

    /** 待用户确认的删除实例 id；非空时 UI 弹删除确认框。 */
    var pendingDeleteId by mutableStateOf<String?>(null)
        private set

    /** 引擎自检结果；非空表示自检已执行。 */
    var selfCheck by mutableStateOf<EngineSelfCheck?>(null)
        private set

    /** 当前日志级别（可切换）。 */
    var logLevel by mutableStateOf(RunLogger.Level.INFO)
        private set

    /** 已做过 dex 预热的包名（进程内去重，避免重复打开）。 */
    private val warmedDexPackages = mutableSetOf<String>()

    /* ---------------- 图标 ---------------- */

    /** 实例/APK 图标文件；解析时缓存在 `files/icons/<sha256>.png`，没有就返回 null。 */
    fun iconFileFor(sha256: String?): File? {
        if (sha256.isNullOrBlank()) return null
        val f = File(File(context.filesDir, "icons"), "$sha256.png")
        return if (f.isFile && f.length() > 0) f else null
    }

    /** 扫 apks 目录，按包名找到对应的 APK 文件名（= sha256）。 */
    private fun findShaByPackage(packageName: String): String? {
        val dir = File(context.filesDir, "apks")
        val files = dir.listFiles { f -> f.isFile && f.extension == "apk" } ?: return null
        val pm = context.packageManager
        return files.firstNotNullOfOrNull { f ->
            val pkg = runCatching {
                pm.getPackageArchiveInfo(f.absolutePath, 0)?.packageName
            }.getOrNull()
            if (pkg == packageName) f.nameWithoutExtension else null
        }
    }

    /** 包名 → APK sha256，给 apkId 为空的历史实例兜底用。 */
    private var packageToSha: Map<String, String> = emptyMap()

    /**
     * 实例图标：优先用 instance.apkId，为空时按包名反查已导入的 APK。
     *
     * 早期版本创建的实例没有写 apkId，直接取 apkId 会导致图标永远为空。
     */
    fun iconFileForInstance(instance: RuntimeInstance): File? =
        iconFileFor(instance.apkId ?: packageToSha[instance.packageName])

    /** 给历史实例补生成图标缓存（幂等，已有则跳过）。 */
    private suspend fun backfillIcons() = withContext(Dispatchers.IO) {
        packageToSha = runCatching {
            apkRepository.getAll()
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.maxByOrNull { it.importedAt }!!.sha256 }
        }.getOrDefault(emptyMap())

        instances.forEach { instance ->
            // apkId 缺失时按包名反查：先查 apk_info 表，再扫 apks 目录（早期版本导出的实例两者都没有）
            val sha = instance.apkId
                ?: packageToSha[instance.packageName]
                ?: findShaByPackage(instance.packageName)
            if (sha == null) {
                android.util.Log.w("adv/Icons", "跳过：找不到来源 APK pkg=${instance.packageName}")
                return@forEach
            }
            // 记下来，供 iconFileForInstance 给 apkId 为空的实例取图标
            packageToSha = packageToSha + (instance.packageName to sha)
            if (iconFileFor(sha) != null) return@forEach
            val apk = apkStore.fileFor(sha)
            if (apk.isFile) {
                runCatching { analyzer.ensureIcon(apk, sha) }
                    .onSuccess { android.util.Log.i("adv/Icons", "图标已生成 ${sha.take(8)}") }
                    .onFailure { android.util.Log.w("adv/Icons", "图标生成失败: ${it.message}") }
            }
        }
        // 通知 UI 重新读取图标（文件是新写出来的，不触发重组会一直显示占位图）
        iconEpoch++
    }

    /* ---------------- 引擎日志（供诊断页查看） ---------------- */

    /** 最近若干行运行日志（含引擎侧打点）。 */
    var engineLog by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 读一次引擎日志。
     *
     * 优先读 `logs/engine-*.log`——那是引擎在**虚拟应用进程**里自己写的文件
     * （静态回调跨进程不共享，只能靠文件）。没有该文件时退回宿主自己的运行日志。
     */
    suspend fun loadEngineLog() {
        engineLog = withContext(Dispatchers.IO) {
            val latest = collectEngineLogFiles().maxByOrNull { it.lastModified() }
            val lines = latest
                ?.let { runCatching { it.readLines().takeLast(120) }.getOrDefault(emptyList()) }
                .orEmpty()
            if (lines.isNotEmpty()) lines else logger.recentLines(120)
        }
    }

    /**
     * 收集引擎日志文件。
     *
     * 引擎跑在虚拟应用进程，它的 uid 与宿主不同，写盘位置可能是：
     * - 宿主外部私有目录（最理想，adb 可直接拉）
     * - 虚拟应用自己的私有数据目录 `<dataDir>/blackbox/data/user/<uid>/<pkg>/files/logs`
     *
     * 所以这里把三处都扫一遍，避免「日志写了但用户看不到」。
     */
    internal fun collectEngineLogFiles(): List<File> {
        val result = mutableListOf<File>()
        fun scan(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith("engine-") && f.name.endsWith(".log") }
            }.getOrNull()?.let { result.addAll(it) }
        }

        scan(context.getExternalFilesDir("logs"))
        scan(File(context.filesDir, "logs"))

        // 虚拟应用数据目录（BlackBox 把虚拟应用的数据放在宿主 dataDir 下的 blackbox 里）
        val blackboxRoot = File(context.filesDir.parentFile, "blackbox")
        if (blackboxRoot.isDirectory) {
            runCatching {
                blackboxRoot.walkTopDown()
                    .maxDepth(9)
                    .filter { it.isFile && it.name.startsWith("engine-") && it.name.endsWith(".log") }
                    .forEach { result.add(it) }
            }
        }
        return result
    }

    /** 图标缓存版本号：回填完成 +1，触发列表重新读取图标文件。 */
    var iconEpoch by mutableStateOf(0)
        private set

    /* ---------------- 沙箱数据占用 ---------------- */

    /** 单个实例的沙箱占用。 */
    data class DataUsage(
        val totalBytes: Long = 0,
        val cacheBytes: Long = 0,
        val fileCount: Int = 0,
    )

    /** instanceId -> 占用；未测量到时不在 map 里。 */
    var dataUsage by mutableStateOf<Map<String, DataUsage>>(emptyMap())
        private set

    /** 占用测量进行中。 */
    var usageBusy by mutableStateOf(false)
        private set

    /** 全部实例合计。 */
    val totalUsage: DataUsage
        get() = dataUsage.values.fold(DataUsage()) { acc, u ->
            DataUsage(
                totalBytes = acc.totalBytes + u.totalBytes,
                cacheBytes = acc.cacheBytes + u.cacheBytes,
                fileCount = acc.fileCount + u.fileCount,
            )
        }

    /** 重新测量所有实例的沙箱占用（递归遍历，IO 线程）。 */
    suspend fun refreshDataUsage() {
        if (usageBusy) return
        usageBusy = true
        try {
            val snapshot = instances
            val result = withContext(Dispatchers.IO) {
                snapshot.associate { it.id to measureUsage(File(it.dataRoot)) }
            }
            dataUsage = result
        } finally {
            usageBusy = false
        }
    }

    /**
     * 统计目录占用。缓存目录单独算：BlackBox 把虚拟应用的 cache 放在 `<dataRoot>/cache`。
     * 遍历时跳过符号链接，并且任何单目录异常都忽略（权限/竞态），保证一定能返回数字。
     */
    private fun isSymlink(file: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(file.path).st_mode
        (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)

    private fun measureUsage(root: File): DataUsage {
        if (!root.isDirectory) return DataUsage()
        var total = 0L
        var files = 0

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_SCAN_DEPTH) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            children.forEach { child ->
                when {
                    isSymlink(child) -> Unit          // 符号链接不跟，避免环
                    child.isFile -> {
                        total += child.length()
                        files++
                    }
                    child.isDirectory -> walk(child, depth + 1)
                }
            }
        }

        walk(root, 0)
        var cache = 0L
        val cacheDir = File(root, "cache")
        if (cacheDir.isDirectory) {
            fun walkCache(dir: File, depth: Int) {
                if (depth > MAX_SCAN_DEPTH) return
                runCatching { dir.listFiles() }.getOrNull()?.forEach { child ->
                    if (child.isFile) cache += child.length() else if (child.isDirectory) walkCache(child, depth + 1)
                }
            }
            walkCache(cacheDir, 0)
        }
        return DataUsage(totalBytes = total, cacheBytes = cache, fileCount = files)
    }

    /* ---------------- 实例搜索 / 排序 ---------------- */

    /** 首页搜索关键词。 */
    var instanceQuery by mutableStateOf("")
        private set

    /** 首页排序方式。 */
    var instanceSort by mutableStateOf(InstanceSort.RECENT)
        private set

    fun updateInstanceQuery(value: String) {
        instanceQuery = value
    }

    fun cycleInstanceSort() {
        instanceSort = InstanceSort.entries[(instanceSort.ordinal + 1) % InstanceSort.entries.size]
    }

    /** 首页实际展示的实例：先按关键词过滤，再按当前排序方式排序。 */
    val visibleInstances: List<RuntimeInstance>
        get() {
            val q = instanceQuery.trim()
            val base = if (q.isEmpty()) {
                instances
            } else {
                instances.filter {
                    it.displayName.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            }
            return when (instanceSort) {
                // 从没启动过的排在后面，用 0 兜底
                InstanceSort.RECENT -> base.sortedByDescending { it.lastLaunchedAt ?: 0L }
                InstanceSort.NAME -> base.sortedBy { it.displayName.lowercase() }
                InstanceSort.CREATED -> base.sortedByDescending { it.createdAt }
            }
        }

    /* ---------------- 运行前权限 ---------------- */

    /** 「显示在其他应用上层」：悬浮返回按钮依赖它。 */
    var hasOverlayPermission by mutableStateOf(false)
        private set

    /** 通知权限（Android 13+）：引擎常驻通知与虚拟应用通知依赖它。 */
    var hasNotificationPermission by mutableStateOf(true)
        private set

    /** 应用回到前台时刷新一次（用户可能刚从系统设置授权回来）。 */
    fun refreshPermissions() {
        hasOverlayPermission = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(true)
        } else {
            true
        }
    }

    fun openOverlaySettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * dex 预热：在宿主进程里先把虚拟 APK 的 dex 打开一次，让 ART 生成 vdex。
     *
     * 实测（realme RMX3888 / 红果）：虚拟 APK 首次以 `run-from-apk` 方式被解释执行
     * （`Odex status ... cost:1145`），第二次变成 `verify`（`cost:694`）。
     * 把这段开销挪到安装完成后（后台线程），首次点「启动」可省约 0.8s。
     * 纯优化路径：任何异常都静默跳过，不影响安装结果。
     */
    private fun prewarmInstanceDex(packageName: String) {
        synchronized(warmedDexPackages) {
            if (!warmedDexPackages.add(packageName)) return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                // 引擎把虚拟 APK 落在 <app data>/blackbox/data/app/<pkg>/base.apk
                val apk = File(context.filesDir.parentFile, "blackbox/data/app/$packageName/base.apk")
                if (!apk.isFile) {
                    logger.debug("AppState", "dex 预热跳过（未找到 APK）：$packageName")
                    return@runCatching
                }
                val startedAt = System.currentTimeMillis()
                // API 26+：构造 DexFile 即让 ART 打开该 APK 的 dex，触发 vdex 生成
                dalvik.system.DexFile(apk).close()
                logger.info(
                    "AppState",
                    "dex 预热完成 pkg=$packageName 耗时=${System.currentTimeMillis() - startedAt}ms"
                )
            }.onFailure {
                logger.debug("AppState", "dex 预热跳过 pkg=$packageName：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    val selectedInstance: RuntimeInstance? get() = instances.firstOrNull { it.id == selectedId }

    suspend fun initialize() {
        if (initializing) return
        initializing = true
        bootPhase = BootPhase.LOADING
        initError = null
        try {
            runCatching { refreshPermissions() }
                .onFailure { logger.warn("AppState", "刷新权限状态失败", it) }

            // ① 先只读「磁盘上的实例清单」并立刻上屏。
            //    这一步刻意放在引擎初始化之前：清单在 Room 里，和引擎能不能起来无关。
            //    旧实现把它塞进「引擎初始化成功」分支，引擎侧任何一次异常都会让界面停在
            //    空列表（状态文字还是默认的「就绪」），用户看到的就是「实例全丢了」。
            val persisted = runCatching { store.load() }
                .onFailure { logger.error("AppState", "读取实例清单失败", it) }
                .getOrElse {
                    // 读失败不能再假装"没有实例"：标记失败并让首页说明原因
                    bootPhase = BootPhase.FAILED
                    initError = "读取本地实例清单失败：${it.javaClass.simpleName}: ${it.message}"
                    status = initError!!
                    logger.error("AppState", "启动中止：实例清单读取失败", it)
                    return
                }
            instances = persisted
            logger.info("AppState", "启动：磁盘实例 ${persisted.size} 个")

            runCatching { migrator?.migrate() }
                .onFailure { logger.warn("AppState", "旧版 JSON 迁移失败（已跳过）", it) }

            val root = File(context.filesDir, "engine/blackbox")
            // ② 引擎初始化：失败/抛异常都不允许影响已加载的清单
            val initResult = runCatching {
                engine.initialize(
                    EngineContext(context.applicationContext, root, logger, deviceAbis, deviceSdk)
                )
            }.getOrElse { t ->
                logger.error("AppState", "引擎初始化抛异常", t)
                engineFailure(
                    EngineErrorCode.ENGINE_NOT_AVAILABLE,
                    "${t.javaClass.simpleName}: ${t.message}"
                )
            }

            when (initResult) {
                is EngineResult.Success -> {
                    caps = initResult.value
                    // ③ 对账失败也不清空清单：宁可状态显示为"未知"，也不能让实例消失
                    val reconciled = runCatching { controller.reconcile() }
                        .onFailure { logger.error("AppState", "实例对账失败（保留磁盘状态）", it) }
                        .getOrNull()
                    if (reconciled != null) {
                        instances = reconciled
                        runCatching { recordReconcileEvents(persisted) }
                            .onFailure { logger.warn("AppState", "写对账事件失败", it) }
                    }
                    bootPhase = BootPhase.READY
                    status = when {
                        instances.isNotEmpty() -> "已恢复 ${instances.size} 个实例并对账完成"
                        else -> "就绪：请导入一个 APK"
                    }
                    runCatching {
                        record(
                            RunEventType.INIT,
                            level = "INFO",
                            message = "引擎初始化成功：${initResult.value.engineId} ${initResult.value.engineVersion}"
                        )
                    }.onFailure { logger.warn("AppState", "写 INIT 事件失败", it) }
                    // 预热引擎服务进程（:black），把首次启动的等待提前消化掉；失败不影响流程
                    scope.launch { runCatching { engine.prewarm() } }
                    // 历史实例补生成图标缓存（老版本导入时还没这个功能）
                    scope.launch { runCatching { backfillIcons() } }
                }

                is EngineResult.Failure -> {
                    bootPhase = BootPhase.FAILED
                    initError = initResult.message
                    // 关键：清单已加载就照常展示，只在状态栏说明引擎起不来
                    status = if (instances.isEmpty()) {
                        "引擎初始化失败：${initResult.message}"
                    } else {
                        "已加载 ${instances.size} 个实例；引擎初始化失败：${initResult.message}"
                    }
                    logger.error("AppState", "引擎初始化失败：${initResult.code} ${initResult.message}")
                    runCatching {
                        record(
                            RunEventType.INIT,
                            level = "ERROR",
                            code = initResult.code.name,
                            message = "引擎初始化失败：${initResult.message}"
                        )
                    }.onFailure { logger.warn("AppState", "写 INIT 失败事件失败", it) }
                }
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            // 兜底：任何未预料的异常都要留痕并让 UI 有机会重试，绝不能静默停在空列表
            bootPhase = BootPhase.FAILED
            initError = "${t.javaClass.simpleName}: ${t.message}"
            status = "启动失败：${initError}"
            logger.error("AppState", "启动流程异常", t)
        } finally {
            initializing = false
        }
    }

    /** 启动失败后的手动重试（空态页的「重试」按钮）。 */
    fun retryInitialize() {
        scope.launch { initialize() }
    }

    suspend fun import(uri: Uri) {
        // 这就是导入的唯一入口：选完文件直接进结果页，不再要求用户先点进一个空白二级页
        apk = null
        screen = Screen.IMPORT
        busy = true
        status = "正在复制并解析 APK…"
        val originalFileName = queryOriginalName(uri)
        runCatching { apkStore.import(context, uri) }
            .onSuccess { file -> analyze(file, originalFileName) }
            .onFailure { status = "读取所选文件失败：${it.message}" }
        busy = false
    }

    suspend fun analyze(file: File, originalFileName: String? = null) {
        busy = true
        val outcome = runCatching {
            val info = analyzer.analyze(file).getOrThrow()
            val issues = analyzer.checkCompat(
                info = info,
                deviceSdk = deviceSdk,
                deviceAbis = deviceAbis,
                engineSdkRange = engine.capabilities()?.supportedSdk,
                engineAllows32Bit = true
            )
            info.copy(issues = issues, originalFileName = originalFileName ?: info.originalFileName)
        }
        outcome.onSuccess {
            logger.info(
                "Analyzer",
                "pkg=${it.packageName} vn=${it.versionName} vc=${it.versionCode} " +
                    "min=${it.minSdk} target=${it.targetSdk} abis=${it.abis} " +
                    "signers=${it.signerSha256.joinToString()} subject=${it.signerSubject} " +
                    "sha256=${it.sha256} perms=${it.permissions.size}"
            )
            apk = it
            apkRepository.upsert(it)
            status = "解析完成：${it.packageName} ${it.versionName}（${it.versionCode}）"
        }.onFailure {
            status = "解析失败：${it.message}"
        }
        busy = false
    }

    /**
     * 创建实例入口：先检测「同包名、不同签名」冲突。
     * 冲突时必须用户二选一（清数据重装 / 新建独立实例），禁止静默覆盖。
     */
    suspend fun installCurrent(): RuntimeInstance? {
        val info = apk ?: return null

        val existing = instances.filter { it.packageName == info.packageName }
        val conflict = existing.firstOrNull {
            // 已存在同包名实例，且其来源 APK 的签名与当前待装 APK 不同
            val existingApk = it.apkId?.let { id -> apkRepository.getBySha256(id) }
            existingApk != null && existingApk.signerSha256 != info.signerSha256
        }

        if (conflict != null) {
            // 触发二次确认，不静默安装
            signatureConflict = SignatureConflict(
                packageName = info.packageName,
                existingInstanceId = conflict.id,
                existingDisplayName = conflict.displayName
            )
            status = "检测到签名冲突，请选择处理方式"
            return null
        }

        return doInstall(info, SignatureMode.FIRST_INSTALL)
    }

    /** 用户选择「清数据重装」：删除旧实例后，用新签名重装。 */
    suspend fun confirmReplaceOnConflict() {
        val conflict = signatureConflict ?: return
        signatureConflict = null
        // 删除旧实例（含引擎侧卸载 + 持久化移除）
        deleteInternal(conflict.existingInstanceId)
        val info = apk ?: return
        doInstall(info, SignatureMode.SIGNATURE_MISMATCH)
    }

    /** 用户选择「新建独立实例」：保留旧实例，尝试并存（引擎能力未知，可能失败）。 */
    suspend fun confirmCoexistOnConflict() {
        val conflict = signatureConflict ?: return
        signatureConflict = null
        val info = apk ?: return
        record(
            RunEventType.INSTALL,
            packageName = info.packageName,
            level = "WARN",
            message = "用户选择同包名异签名并存：引擎能力未验证，可能失败"
        )
        doInstall(info, SignatureMode.SIGNATURE_MISMATCH)
    }

    /** 用户取消冲突处理。 */
    fun cancelConflict() {
        signatureConflict = null
        status = "已取消安装"
    }

    private suspend fun doInstall(info: ApkInfo, signatureMode: SignatureMode): RuntimeInstance? {
        val instanceId = UUID.randomUUID().toString()
        // userId 槽位必须避开磁盘上已有的实例：内存清单万一没加载好，
        // 直接按 instances 取 max+1 会撞上 (engine_id, virtual_user_id) 唯一索引，
        // 整次落盘回滚，实例就"装上了但下次启动不见了"。
        val known = if (instances.isEmpty()) {
            runCatching { store.load() }.getOrDefault(emptyList())
        } else {
            instances
        }
        val request = InstallRequest(
            instanceId = instanceId,
            apkPath = info.storedPath,
            sha256 = info.sha256,
            packageName = info.packageName,
            virtualUserId = (known.maxOfOrNull { it.virtualUserId } ?: -1) + 1,
            expectedSignatureSha256 = info.signerSha256
        )
        return when (val result = engine.installApk(request)) {
            is EngineResult.Success -> {
                val installed = result.value
                val instance = RuntimeInstance(
                    id = instanceId,
                    apkId = info.sha256,
                    packageName = info.packageName,
                    displayName = "${info.appLabel} · ${info.versionName}",
                    engineId = engine.id,
                    virtualUserId = installed.virtualUserId,
                    state = RuntimeState.INSTALLED,
                    signatureMode = signatureMode,
                    signatureAckAt = if (signatureMode == SignatureMode.SIGNATURE_MISMATCH) {
                        System.currentTimeMillis()
                    } else null,
                    installedVersionCode = info.versionCode,
                    dataRoot = installed.dataRoot
                )
                persist(instances + instance)
                status = "实例已创建：${info.packageName}"
                record(
                    RunEventType.INSTALL,
                    instance = instance,
                    message = "实例创建成功：${info.packageName} v${info.versionCode}（${signatureMode.name}）"
                )
                // 创建成功后回首页，让用户立即看到新实例（首页是唯一入口）
                openHome()
                // 后台预热虚拟 APK 的 dex（生成 vdex），把首次启动的解释执行阶段提前做掉
                prewarmInstanceDex(info.packageName)
                instance
            }

            is EngineResult.Failure -> {
                status = "创建实例失败：${result.message}"
                record(
                    RunEventType.INSTALL,
                    packageName = info.packageName,
                    level = "ERROR",
                    code = result.code.name,
                    message = "创建实例失败：${result.message}"
                )
                null
            }
        }
    }

    suspend fun launch(id: String) {
        val instance = instances.firstOrNull { it.id == id } ?: return
        val launchStartedAt = System.currentTimeMillis()
        when (val r = engine.launch(id)) {
            is EngineResult.Success -> {
                val probe = engine.queryState(id)
                val newState = (probe as? EngineResult.Success)?.value?.state
                    ?: RuntimeState.UNKNOWN_INTERRUPTED
                persist(
                    instances.map {
                        if (it.id == id) {
                            it.copy(state = newState, lastLaunchedAt = System.currentTimeMillis())
                        } else it
                    }
                )
                status = "已提交启动：${instance.packageName}"
                record(
                    RunEventType.LAUNCH,
                    instance = instance,
                    message = "启动请求已提交，状态：$newState"
                )
                // 启动耗时打点：从点按到引擎报告虚拟进程 RUNNING，写进日志便于持续优化
                measureLaunchLatency(id, instance.packageName, launchStartedAt)
                // 启动成功后显示悬浮返回按钮
                showFloatingButton(id)
            }

            is EngineResult.Failure -> {
                status = "启动失败：${r.message}"
                record(
                    RunEventType.LAUNCH,
                    instance = instance,
                    level = "ERROR",
                    code = r.code.name,
                    message = "启动失败：${r.message}"
                )
            }
        }
    }

    suspend fun stop(id: String) {
        val instance = instances.firstOrNull { it.id == id } ?: return
        when (val r = engine.stop(id)) {
            is EngineResult.Success -> {
                persist(
                    instances.map {
                        if (it.id == id) {
                            it.copy(state = RuntimeState.STOPPED, lastExitReason = ExitReason.USER_STOPPED)
                        } else it
                    }
                )
                status = "已停止：${instance.packageName}"
                record(RunEventType.STOP, instance = instance, message = "实例已停止")
                floatingButton.hide()
                // 确保宿主界面回到前台：悬浮按钮触发停止时，虚拟 App 代理 Activity 的销毁
                // 可能有延迟，显式 reorder 让用户停止后立即看到宿主列表
                // （真机反馈：点悬浮按钮后 2 分钟无反应）。
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }

            is EngineResult.Failure -> {
                status = "停止失败：${r.message}"
                record(
                    RunEventType.STOP,
                    instance = instance,
                    level = "ERROR",
                    code = r.code.name,
                    message = "停止失败：${r.message}"
                )
            }
        }
    }

    /**
     * 启动耗时打点：从点按到引擎报告虚拟进程创建完成（RUNNING）。
     *
     * 注意这是**进程创建**的耗时（实测约 240ms），不是用户感知的首帧时间；
     * 首帧还要叠加 loadApp（dex 校验约 0.7s）与虚拟 Activity 启动，实测约 1.3–1.4s，
     * 应以 logcat 的 `ActivityTaskManager: Displayed ...ProxyActivity` 为准。
     */
    private fun measureLaunchLatency(id: String, packageName: String, startedAt: Long) {
        scope.launch(Dispatchers.IO) {
            val deadline = startedAt + LAUNCH_MEASURE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val state = (engine.queryState(id) as? EngineResult.Success)?.value?.state
                if (state == RuntimeState.RUNNING) {
                    // 进程真正起来后把状态从 INSTALLED 回写成 RUNNING：
                    // 否则列表一直显示"未运行"，且引擎被杀时无法判断是异常退出
                    if (instances.any { it.id == id && it.state != RuntimeState.RUNNING }) {
                        persist(
                            instances.map {
                                if (it.id == id) it.copy(state = RuntimeState.RUNNING) else it
                            }
                        )
                    }
                    logger.info(
                        "AppState",
                        "启动耗时 pkg=$packageName 点按→虚拟进程创建=${System.currentTimeMillis() - startedAt}ms"
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(LAUNCH_MEASURE_INTERVAL_MS)
            }
            logger.debug("AppState", "启动耗时 pkg=$packageName 未在 ${LAUNCH_MEASURE_TIMEOUT_MS}ms 内创建进程")
        }
    }

    /** 启动成功后显示悬浮返回按钮；点击停止目标 App 返回宿主。 */
    private fun showFloatingButton(id: String) {
        if (!floatingButton.canDrawOverlays()) {
            status = "需要「显示在其他应用上层」权限才能显示悬浮返回按钮"
            return
        }
        floatingButton.show {
            floatingButton.hide()
            // 悬浮按钮场景下用户视线在虚拟 App 全屏界面上，看不到宿主 UI 的 status，
            // 必须用 Toast 兜底反馈，否则 stop 耗时稍长就像"点了没反应"（真机反馈）。
            Toast.makeText(context, "正在停止虚拟应用…", Toast.LENGTH_SHORT).show()
            scope.launch {
                stop(id)
                // Toast 必须有 Looper：coroutine 跑在 Dispatchers.Default 上，
                // 直接 makeText 会抛 "Can't toast on a thread that has not called Looper.prepare"
                // 崩掉宿主进程（真机复现：点击停止后进程 FATAL，前台任务被移除落回桌面）。
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 轮询：目标 App 自行退出/崩溃后，自动移除悬浮按钮。
        // 每次查询是 2 次跨进程 Binder 调用（isInstalled + getRunningAppProcesses），
        // 5s 足够及时且把常驻 IPC 压到原来的 40%。
        scope.launch {
            while (floatingButton.isShowing()) {
                kotlinx.coroutines.delay(5000)
                val state = (engine.queryState(id) as? EngineResult.Success)?.value?.state
                if (state != null && state != RuntimeState.RUNNING && state != RuntimeState.STARTING) {
                    floatingButton.hide()
                    break
                }
            }
        }
    }

    /** 引导用户开启「显示在其他应用上层」权限。 */
    fun requestOverlayPermission() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun floatingButtonShowing(): Boolean = floatingButton.isShowing()

    /** 悬浮窗权限是否已授权。 */
    fun overlayPermissionGranted(): Boolean = floatingButton.canDrawOverlays()

    /**
     * 清理实例缓存：只删 `<dataRoot>/cache` 下的内容，不动 files / databases / shared_prefs。
     *
     * 运行中的实例禁止清理——数据库、图片缓存在被写，删了会崩。
     */
    suspend fun clearCache(id: String) {
        val instance = instances.firstOrNull { it.id == id } ?: return
        if (instance.state == RuntimeState.RUNNING || instance.state == RuntimeState.STARTING) {
            status = "请先停止「${instance.displayName}」，再清理缓存"
            return
        }
        val cacheDir = File(instance.dataRoot, "cache")
        val freed = withContext(Dispatchers.IO) {
            if (!cacheDir.isDirectory) {
                0L
            } else {
                val size = measureUsage(cacheDir).totalBytes
                deleteChildren(cacheDir)
                size
            }
        }
        status = if (freed > 0) {
            "已清理缓存：${instance.displayName} 释放 ${formatBytes(freed)}"
        } else {
            "缓存已为空：${instance.displayName}"
        }
        record(
            RunEventType.CLEAR,
            instance = instance,
            message = "已清理缓存，释放 ${formatBytes(freed)}"
        )
        // 重新统计，让页面上的数字立刻更新
        refreshDataUsage()
    }

    /** 删除目录下的所有内容，但保留目录本身。符号链接只删链接、不跟进去。 */
    private fun deleteChildren(dir: File) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            runCatching {
                if (isSymlink(child)) child.delete() else child.deleteRecursively()
            }
        }
    }

    suspend fun clearData(id: String) {
        val instance = instances.firstOrNull { it.id == id } ?: return
        when (val r = engine.clearData(id)) {
            is EngineResult.Success -> {
                persist(instances.map { if (it.id == id) it.copy(state = RuntimeState.INSTALLED) else it })
                status = "已清空数据：${instance.packageName}"
                record(RunEventType.CLEAR, instance = instance, message = "实例数据已清空")
            }

            is EngineResult.Failure -> {
                status = "清数据失败：${r.message}"
                record(
                    RunEventType.CLEAR,
                    instance = instance,
                    level = "ERROR",
                    code = r.code.name,
                    message = "清数据失败：${r.message}"
                )
            }
        }
    }

    /** 请求删除实例（触发二次确认，不直接删）。 */
    fun requestDelete(id: String) {
        pendingDeleteId = id
    }

    /** 用户确认删除。 */
    suspend fun confirmDelete() {
        val id = pendingDeleteId ?: return
        pendingDeleteId = null
        deleteInternal(id)
    }

    /** 直接删除实例（内部用，签名冲突「清数据重装」也复用）。 */
    private suspend fun deleteInternal(id: String) {
        val instance = instances.firstOrNull { it.id == id }
        engine.uninstall(id)
        val updated = instances.filterNot { it.id == id }
        // 删掉最后一个实例是合法的整体清空，需要显式放行
        persist(updated, allowEmptyOverwrite = true)
        if (instance != null) {
            record(RunEventType.DELETE, instance = instance, message = "实例已删除（仅沙箱内数据）")
        }
        if (selectedId == id) {
            selectedId = null
            runEvents = emptyList()
            screen = Screen.HOME
        }
        status = "实例已删除（仅删除沙箱内数据）"
    }

    /** 用户取消删除。 */
    fun cancelDelete() {
        pendingDeleteId = null
    }

    /** 备份实例数据到 [targetFile]（zip 打包 dataRoot）。返回是否成功。 */
    suspend fun backup(id: String, targetFile: File): Boolean {
        val instance = instances.firstOrNull { it.id == id } ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                val src = File(instance.dataRoot)
                if (!src.exists()) return@withContext false
                targetFile.parentFile?.mkdirs()
                val zip = java.util.zip.ZipOutputStream(targetFile.outputStream())
                try {
                    src.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(src).path.replace('\\', '/')
                        zip.putNextEntry(java.util.zip.ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                } finally {
                    zip.close()
                }
                true
            }
        }.getOrDefault(false).also { ok ->
            status = if (ok) "备份完成：${targetFile.name}" else "备份失败"
            record(
                RunEventType.RECONCILE,
                instance = instance,
                level = if (ok) "INFO" else "ERROR",
                message = if (ok) "实例数据已备份到 ${targetFile.name}" else "实例数据备份失败"
            )
        }
    }

    /** 执行引擎自检并缓存结果。 */
    suspend fun runSelfCheck() {
        busy = true
        when (val result = engine.selfCheck()) {
            is EngineResult.Success -> {
                selfCheck = result.value
                status = if (result.value.passed) "自检通过" else "自检发现问题，详见诊断页"
            }
            is EngineResult.Failure -> {
                status = "自检失败：${result.message}"
            }
        }
        busy = false
    }

    /** 切换日志级别。 */
    fun changeLogLevel(level: RunLogger.Level) {
        logLevel = level
        logger.minLevel = level
    }

    /** 导出诊断包（zip）：运行日志 + 设备信息 + 引擎能力表，均已脱敏。 */
    suspend fun exportDiagnostics(targetFile: File): Boolean {
        return runCatching {
            withContext(Dispatchers.IO) {
                targetFile.parentFile?.mkdirs()
                val zip = java.util.zip.ZipOutputStream(targetFile.outputStream())
                try {
                    // 1. 运行日志（已脱敏）；先 flush，保证攒批缓冲里的最新日志也进包
                    logger.flush()
                    logger.latestLogFile()?.let { log ->
                        zip.putNextEntry(java.util.zip.ZipEntry("logs/${log.name}"))
                        log.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // 引擎在虚拟应用进程里写的日志：每个文件单独入包，用工程名区分来源
                    collectEngineLogFiles().forEachIndexed { index, log ->
                        val entryName = "engine/${index}-${log.parentFile?.name ?: "log"}-${log.name}"
                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                        log.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // 2. 设备信息
                    val deviceInfo = buildString {
                        appendLine("deviceSdk=$deviceSdk")
                        appendLine("deviceAbis=${deviceAbis.joinToString()}")
                        appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("device.txt"))
                    zip.write(deviceInfo.toByteArray())
                    zip.closeEntry()
                    // 3. 引擎能力表
                    caps?.let { c ->
                        val capInfo = buildString {
                            appendLine("engineId=${c.engineId}")
                            appendLine("engineVersion=${c.engineVersion}")
                            appendLine("supportedSdk=${c.supportedSdk}")
                            appendLine("samePackageDifferentSignature=${c.samePackageDifferentSignature}")
                            appendLine("multiInstance=${c.multiInstance}")
                            appendLine("nativeLibs=${c.nativeLibs}")
                            appendLine("clearData=${c.clearData}")
                            appendLine("permissionInterception=${c.permissionInterception}")
                            c.notes.forEach { appendLine("note=$it") }
                        }
                        zip.putNextEntry(java.util.zip.ZipEntry("engine-capabilities.txt"))
                        zip.write(capInfo.toByteArray())
                        zip.closeEntry()
                    }
                } finally {
                    zip.close()
                }
                true
            }
        }.getOrDefault(false).also { ok ->
            status = if (ok) "诊断包已导出：${targetFile.name}" else "诊断包导出失败"
        }
    }

    /** 从备份文件恢复实例数据。 */
    suspend fun restore(id: String, backupFile: File): Boolean {
        val instance = instances.firstOrNull { it.id == id } ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                val dest = File(instance.dataRoot)
                dest.mkdirs()
                val zip = java.util.zip.ZipInputStream(backupFile.inputStream())
                try {
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(dest, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                } finally {
                    zip.close()
                }
                true
            }
        }.getOrDefault(false).also { ok ->
            status = if (ok) "导入备份完成" else "导入备份失败"
            record(
                RunEventType.RECONCILE,
                instance = instance,
                level = if (ok) "INFO" else "ERROR",
                message = if (ok) "已从备份恢复实例数据" else "导入备份失败"
            )
        }
    }

    /** adb 调试入口：解析 → 安装 → 启动 一条龙，必须在 [initialize] 完成后调用。 */
    /** 调试入口：按包名直接启动已有实例，免去 UI 点击（真机自动化验证用）。 */
    suspend fun debugLaunchPackage(pkg: String, resetData: Boolean = false) {
        // 实例列表可能还在加载，轮询等一会
        var instance = instances.firstOrNull { it.packageName == pkg }
        var waited = 0
        while (instance == null && waited < 6000) {
            kotlinx.coroutines.delay(300)
            waited += 300
            instance = instances.firstOrNull { it.packageName == pkg }
        }
        top.niunaijun.blackbox.utils.DiagLog.log("debug",
                "launch pkg=" + pkg + " found=" + (instance != null)
                        + " reset=" + resetData
                        + " known=" + instances.joinToString(",") { it.packageName })
        if (instance == null) {
            status = "调试启动失败：未找到 $pkg 的实例"
            return
        }
        if (resetData) {
            val removed = withContext(Dispatchers.IO) { wipeInstanceData(pkg) }
            top.niunaijun.blackbox.utils.DiagLog.log("debug", "reset removed dirs=$removed")
            status = "已清空数据，正在启动：${instance.displayName}"
        } else {
            status = "调试启动：${instance.displayName}"
        }
        launch(instance.id)
    }

    /**
     * 清掉某个虚拟应用的沙箱数据目录。
     *
     * 目的：Tinker 这类热修复框架在连续多次启动失败后会记下"安全模式"标记，
     * 之后每次启动都走退化分支（红果实测会走 AbsApplication.initSafeModeOtherTasks
     * 并死等一个从未被调度的任务）。反复调试时这个标记会一直累积，
     * 让"环境不兼容"和"历史失败残留"混在一起，没法判断。
     *
     * 只删 data/user/<id>/<pkg> 和 data/user_de/<id>/<pkg>，
     * 不动 data/app/<pkg>（那里是 apk 本体），删完应用相当于首次启动。
     */
    private fun wipeInstanceData(pkg: String): Int {
        val boxRoot = File(context.filesDir.parentFile, "blackbox")
        var removed = 0
        for (sub in listOf("data/user", "data/user_de")) {
            val base = File(boxRoot, sub)
            base.listFiles()?.forEach { userDir ->
                val target = File(userDir, pkg)
                if (target.exists() && target.deleteRecursively()) {
                    removed++
                }
            }
        }
        return removed
    }

    suspend fun debugAutoRun(path: String) {
        val file = File(path)
        if (!file.isFile) {
            status = "调试启动失败：文件不存在 $path"
            return
        }
        busy = true
        status = "调试启动：解析 ${file.name}"
        analyze(file, file.name)
        val info = apk
        if (info == null) {
            status = "调试启动失败：APK 解析未通过"
        } else if (info.hasBlocker) {
            status = "调试启动失败：存在阻断性问题"
        } else {
            installCurrent()?.let { instance ->
                kotlinx.coroutines.delay(500)
                launch(instance.id)
            }
        }
        busy = false
    }

    fun openDetail(id: String) {
        selectedId = id
        runEvents = emptyList()
        screen = Screen.DETAIL
        // 用户在详情页大概率会点「启动」：提前预热引擎进程 + 虚拟 APK 的 dex
        val pkg = instances.firstOrNull { it.id == id }?.packageName
        scope.launch { engine.prewarm() }
        if (pkg != null) prewarmInstanceDex(pkg)
    }

    fun openRun(id: String) {
        selectedId = id
        runEvents = emptyList()
        screen = Screen.RUN
    }

    fun openHome() {
        selectedId = null
        runEvents = emptyList()
        screen = Screen.HOME
    }

    fun openData() {
        selectedId = null
        screen = Screen.DATA
    }

    fun openSettings() {
        selectedId = null
        screen = Screen.SETTINGS
    }

    fun openChangelog() {
        screen = Screen.CHANGELOG
    }

    fun openLicenses() {
        screen = Screen.LICENSES
    }

    /** 权限页展示的 APK 信息（从库里按实例 apkId 加载）。 */
    var permissionApk by mutableStateOf<ApkInfo?>(null)
        private set

    /** 打开权限风险页（需要已选中的实例），并加载其 APK 信息。 */
    suspend fun openPermissions(id: String) {
        selectedId = id
        val instance = instances.firstOrNull { it.id == id }
        permissionApk = instance?.apkId?.let { aid -> apkRepository.getBySha256(aid) }
        screen = Screen.PERMISSIONS
    }

    fun openEngineDetail() {
        screen = Screen.ENGINE_DETAIL
        // 进入页面即拉一次实时状态（引擎进程是否在、有几个虚拟应用在跑）+ 屏幕刷新率
        refreshScreenInfo()
        scope.launch { refreshEngineStatus() }
    }

    /** 引擎运行时状态；null 表示尚未查询。 */
    var engineStatus by mutableStateOf<EngineRuntimeStatus?>(null)
        private set

    /** 引擎状态查询/拉起中，用于按钮禁用与提示。 */
    var engineStatusBusy by mutableStateOf(false)
        private set

    /** 当前屏幕刷新率（Hz）；0 表示读不到。 */
    var screenRefreshRate by mutableStateOf(0f)
        private set

    /** 屏幕支持的最高刷新率（Hz）。 */
    var screenMaxRefreshRate by mutableStateOf(0f)
        private set

    /** 读一次屏幕刷新率。进引擎页/点刷新时调用，不轮询。 */
    fun refreshScreenInfo() {
        runCatching {
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
            screenRefreshRate = display.refreshRate
            screenMaxRefreshRate = display.supportedModes
                .maxOfOrNull { it.refreshRate } ?: display.refreshRate
        }
    }

    /** 刷新引擎运行状态（跨进程查询，IO 线程）。 */
    suspend fun refreshEngineStatus() {
        if (engineStatusBusy) return
        engineStatusBusy = true
        try {
            engineStatus = engine.runtimeStatus()
        } catch (t: Throwable) {
            engineStatus = EngineRuntimeStatus(running = false, detail = "状态查询失败：${t.javaClass.simpleName}")
        } finally {
            engineStatusBusy = false
        }
    }

    /** 手动拉起引擎服务进程（预热），成功后刷新状态。 */
    suspend fun startEngine() {
        engineStatusBusy = true
        try {
            engine.prewarm()
            engineStatus = engine.runtimeStatus()
            status = if (engineStatus?.running == true) "引擎已启动" else "引擎启动失败，请查看诊断日志"
        } finally {
            engineStatusBusy = false
        }
    }

    fun openDiagnostics() {
        screen = Screen.DIAGNOSTICS
    }

    /** 系统返回键 / 侧滑手势的返回逻辑：二级页回上一级；顶级页不处理（交由系统退出）。 */
    fun back() {
        when (screen) {
            Screen.IMPORT -> openHome()
            Screen.DETAIL -> openHome()
            Screen.RUN -> selectedId?.let { openDetail(it) } ?: openHome()
            Screen.CHANGELOG -> openSettings()
            Screen.LICENSES -> openSettings()
            Screen.PERMISSIONS -> selectedId?.let { openDetail(it) } ?: openHome()
            Screen.ENGINE_DETAIL -> openSettings()
            Screen.DIAGNOSTICS -> openSettings()
            else -> Unit
        }
    }

    /** 详情页进入时加载该实例的运行事件（最新在前）。 */
    suspend fun loadEvents(id: String) {
        runEvents = eventStore.query(id)
    }

    fun backHome() {
        selectedId = null
        runEvents = emptyList()
        screen = Screen.HOME
    }

    /**
     * 落盘实例清单。
     *
     * 关键防御：内存清单为空时**拒绝**把磁盘上已有的实例整表覆盖掉。
     * 旧实现是「deleteAll + insert」的整表替换语义，一旦某次调用发生在
     * 清单还没加载好的时刻（initialize 失败、协程被取消），磁盘上的实例就被抹掉了，
     * 表现为「重启后实例全丢」。删除最后一个实例这类合法清空走 [allowEmptyOverwrite]。
     */
    private suspend fun persist(list: List<RuntimeInstance>, allowEmptyOverwrite: Boolean = false) {
        val previous = instances
        if (list.isEmpty() && previous.isNotEmpty() && !allowEmptyOverwrite) {
            logger.error(
                "AppState",
                "拒绝把 ${previous.size} 个实例的清单覆盖为空（疑似初始化未完成）"
            )
            return
        }
        // 内存清单为空却要写非空清单：说明加载环节出过问题（初始化失败/被取消）。
        // 此时整表覆盖会顺手删掉没被加载进来的历史实例，改为与磁盘做并集——
        // 宁可多留一条，也不静默删除用户的数据。
        var target = list
        if (previous.isEmpty() && list.isNotEmpty()) {
            val onDisk = runCatching { store.load() }.getOrDefault(emptyList())
            val ids = list.mapTo(HashSet()) { it.id }
            val missing = onDisk.filterNot { it.id in ids }
            if (missing.isNotEmpty()) {
                logger.warn("AppState", "内存清单为空但磁盘有 ${onDisk.size} 个实例，按并集写入")
                target = missing + list
            }
        }
        instances = target
        // 落盘失败不向上抛：抛出去会把调用方的协程打断（安装/删除流程走一半），
        // 而磁盘上仍是上一份完整清单——记日志、让状态如实提示即可。
        runCatching { store.save(target) }
            .onFailure {
                logger.error("AppState", "写入实例清单失败（磁盘保持上一份）", it)
                status = "写入实例清单失败：${it.javaClass.simpleName}: ${it.message}"
            }
    }

    private suspend fun record(
        type: RunEventType,
        instance: RuntimeInstance? = null,
        packageName: String? = instance?.packageName,
        level: String = "INFO",
        code: String? = null,
        message: String
    ) {
        eventStore.append(
            RunEvent(
                id = 0,
                instanceId = instance?.id,
                packageName = packageName,
                ts = System.currentTimeMillis(),
                level = level,
                type = type,
                code = code,
                message = message
            )
        )
        // 详情页正展示该实例时，实时刷新事件列表
        if (selectedId != null && instance?.id == selectedId) {
            runEvents = eventStore.query(selectedId)
        }
    }

    /** 把对账产生的状态修正落成 CRASH/RECONCILE 事件。 */
    private suspend fun recordReconcileEvents(before: List<RuntimeInstance>) {
        before.forEach { prev ->
            val now = instances.firstOrNull { it.id == prev.id } ?: return@forEach
            if (now.state != prev.state) {
                val crashed = now.state == RuntimeState.CRASHED
                record(
                    type = if (crashed) RunEventType.CRASH else RunEventType.RECONCILE,
                    instance = now,
                    level = if (crashed) "ERROR" else "WARN",
                    message = "对账修正状态：${prev.state} → ${now.state}" +
                        (now.lastExitReason?.let { "（${it}）" } ?: "")
                )
            }
        }
    }

    /** 从 SAF 提供的 [uri] 读取原始文件名，取不到则返回 null。 */
    private fun queryOriginalName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    private companion object {
        /** 占用统计的最大递归深度，防止异常目录结构打爆栈。 */
        const val MAX_SCAN_DEPTH = 24

        /** 启动耗时观测窗口：超过这个时间仍未 RUNNING 就记为"未就绪"。 */
        const val LAUNCH_MEASURE_TIMEOUT_MS = 10_000L

        /** 启动耗时轮询间隔（只在启动过程中跑，避免常驻 IPC）。 */
        const val LAUNCH_MEASURE_INTERVAL_MS = 400L
    }
}

/**
 * 启动阶段。
 *
 * LOADING：清单还没读出来（首帧），不能显示「暂无实例」；
 * READY：清单与引擎都已就绪，此时列表为空才是真的没有实例；
 * FAILED：读清单或引擎初始化失败——必须说明原因并给重试入口，
 *         不能让失败伪装成「你没有实例」。
 */
enum class BootPhase { LOADING, READY, FAILED }

/** 首页实例排序方式。 */
enum class InstanceSort(val label: String) {    RECENT("最近使用"),
    NAME("名称"),
    CREATED("导入时间"),
}

/**
 * 签名冲突待确认信息：安装同包名 APK 时发现已有实例来源 APK 的签名不同，
 * 弹出二选一，用户必须显式选择，禁止静默覆盖。
 */
data class SignatureConflict(
    val packageName: String,
    val existingInstanceId: String,
    val existingDisplayName: String
)
