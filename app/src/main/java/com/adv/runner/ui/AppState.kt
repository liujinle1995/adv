package com.adv.runner.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adv.runner.apk.ApkAnalyzer
import com.adv.runner.apk.ApkStore
import com.adv.runner.core.control.InstanceController
import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineResult
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

    val selectedInstance: RuntimeInstance? get() = instances.firstOrNull { it.id == selectedId }

    suspend fun initialize() {
        migrator?.migrate()
        val root = File(context.filesDir, "engine/blackbox")
        val before = store.load()
        when (val result = engine.initialize(
            EngineContext(context.applicationContext, root, logger, deviceAbis, deviceSdk)
        )) {
            is EngineResult.Success -> {
                caps = result.value
                instances = controller.reconcile()
                recordReconcileEvents(before)
                if (instances.isNotEmpty()) {
                    status = "已恢复 ${instances.size} 个实例并对账完成"
                }
                record(
                    RunEventType.INIT,
                    level = "INFO",
                    message = "引擎初始化成功：${result.value.engineId} ${result.value.engineVersion}"
                )
            }

            is EngineResult.Failure -> {
                status = "引擎初始化失败：${result.message}"
                record(
                    RunEventType.INIT,
                    level = "ERROR",
                    code = result.code.name,
                    message = "引擎初始化失败：${result.message}"
                )
            }
        }
    }

    suspend fun import(uri: Uri) {
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
        val request = InstallRequest(
            instanceId = instanceId,
            apkPath = info.storedPath,
            sha256 = info.sha256,
            packageName = info.packageName,
            virtualUserId = (instances.maxOfOrNull { it.virtualUserId } ?: -1) + 1,
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
        when (val r = engine.launch(id)) {
            is EngineResult.Success -> {
                val probe = engine.queryState(id)
                val newState = (probe as? EngineResult.Success)?.value?.state
                    ?: RuntimeState.UNKNOWN_INTERRUPTED
                persist(instances.map { if (it.id == id) it.copy(state = newState) else it })
                status = "已提交启动：${instance.packageName}"
                record(
                    RunEventType.LAUNCH,
                    instance = instance,
                    message = "启动请求已提交，状态：$newState"
                )
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
                persist(instances.map { if (it.id == id) it.copy(state = RuntimeState.STOPPED) else it })
                status = "已停止：${instance.packageName}"
                record(RunEventType.STOP, instance = instance, message = "实例已停止")
                floatingButton.hide()
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

    /** 启动成功后显示悬浮返回按钮；点击停止目标 App 返回宿主。 */
    private fun showFloatingButton(id: String) {
        if (!floatingButton.canDrawOverlays()) {
            status = "需要「显示在其他应用上层」权限才能显示悬浮返回按钮"
            return
        }
        floatingButton.show {
            floatingButton.hide()
            scope.launch { stop(id) }
        }
        // 轮询：目标 App 自行退出/崩溃后，自动移除悬浮按钮
        scope.launch {
            while (floatingButton.isShowing()) {
                kotlinx.coroutines.delay(2000)
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
        persist(updated)
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
                    // 1. 运行日志（已脱敏）
                    logger.latestLogFile()?.let { log ->
                        zip.putNextEntry(java.util.zip.ZipEntry("logs/${log.name}"))
                        log.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // 2. 设备信息
                    val deviceInfo = buildString {
                        appendLine("deviceSdk=$deviceSdk")
                        appendLine("deviceAbis=${deviceAbis.joinToString()}")
                        appendLine("appVersion=0.2.0-engine")
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

    fun openImport() {
        apk = null
        screen = Screen.IMPORT
    }

    fun openDetail(id: String) {
        selectedId = id
        runEvents = emptyList()
        screen = Screen.DETAIL
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

    private suspend fun persist(list: List<RuntimeInstance>) {
        instances = list
        store.save(list)
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
