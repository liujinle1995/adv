package com.adv.runner.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adv.runner.R
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 打开 APK 的 MIME 过滤：部分文件管理器对 apk 的 MIME 识别不准，最后一档放开通配兜底。
private val APK_MIME_TYPES = arrayOf(
    "application/vnd.android.package-archive",
    "application/octet-stream",
    "*/*"
)

@Composable
fun AppRoot(state: AppState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        state.initialize()
        state.debugApkPath?.let { state.debugAutoRun(it) }
    }
    // 拦截系统返回键 / 侧滑手势：二级页回上一级，顶级页放行（退出应用）
    BackHandler(enabled = !state.screen.isTopLevel) {
        state.back()
    }
    val wide = isWideScreen()
    Scaffold(
        bottomBar = {
            // 窄屏：底部导航；宽屏：无底部导航
            if (!wide && state.screen.isTopLevel) {
                NavigationBar {
                    Screen.topLevel.forEach { screen ->
                        NavigationBarItem(
                            selected = state.screen == screen,
                            onClick = {
                                when (screen) {
                                    Screen.HOME -> state.openHome()
                                    Screen.DATA -> state.openData()
                                    Screen.SETTINGS -> state.openSettings()
                                    else -> {}
                                }
                            },
                            icon = { Text(screen.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize()) {
            // 宽屏：左侧 NavigationRail
            if (wide && state.screen.isTopLevel) {
                NavigationRail(
                ) {
                    Spacer(Modifier.weight(1f))
                    Screen.topLevel.forEach { screen ->
                        NavigationRailItem(
                            selected = state.screen == screen,
                            onClick = {
                                when (screen) {
                                    Screen.HOME -> state.openHome()
                                    Screen.DATA -> state.openData()
                                    Screen.SETTINGS -> state.openSettings()
                                    else -> {}
                                }
                            },
                            icon = { Text(screen.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(screen.label) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            // 内容区：平板居中限宽，手机全宽
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        // 进入：淡入 + 轻微上移（decelerate，300ms）；退出：淡出（accelerate，200ms，更快）
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = LinearOutSlowInEasing
                            )
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = LinearOutSlowInEasing
                            ),
                            initialOffsetY = { if (forward) it / 16 else -it / 16 }
                        )).togetherWith(
                            fadeOut(
                                animationSpec = tween(
                                    durationMillis = 200,
                                    easing = FastOutLinearInEasing
                                )
                            )
                        )
                    },
                    label = "screen-transition",
                    modifier = Modifier
                        .fillMaxWidth()
                        // 设置页在宽屏用内部双栏，需要全宽；其余页面在超宽屏限宽防拉伸
                        .then(
                            if (state.screen == Screen.SETTINGS) Modifier
                            else Modifier.widthIn(max = 680.dp)
                        )
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(state, scope)
                        Screen.IMPORT -> ImportScreen(state, scope)
                        Screen.DETAIL -> DetailScreen(state, scope)
                        Screen.RUN -> RunScreen(state, scope)
                        Screen.DATA -> DataScreen(state, scope)
                        Screen.SETTINGS -> SettingsScreen(state, scope)
                        Screen.CHANGELOG -> ChangelogScreen(state)
                        Screen.LICENSES -> LicensesScreen(state)
                        Screen.PERMISSIONS -> PermissionScreen(state)
                        Screen.ENGINE_DETAIL -> EngineDetailScreen(state)
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(state, scope)
                    }
                }
            }
        }
    }

    // 签名冲突二次确认弹窗（全局覆盖，任何页面触发安装时都能显示）
    state.signatureConflict?.let { conflict ->
        SignatureConflictDialog(
            conflict = conflict,
            engineSupportsCoexist = state.caps?.samePackageDifferentSignature,
            onReplace = { scope.launch { state.confirmReplaceOnConflict() } },
            onCoexist = { scope.launch { state.confirmCoexistOnConflict() } },
            onCancel = { state.cancelConflict() }
        )
    }

    // 删除实例二次确认弹窗（全局覆盖）
    state.pendingDeleteId?.let { id ->
        val target = state.instances.firstOrNull { it.id == id }
        if (target != null) {
            DeleteConfirmDialog(
                instance = target,
                onConfirm = { scope.launch { state.confirmDelete() } },
                onCancel = { state.cancelDelete() }
            )
        }
    }
}

/* ---------------- 首页：实例列表 ---------------- */

/**
 * 运行前准备：缺权限时提示并直达系统设置。
 *
 * 悬浮返回按钮依赖「显示在其他应用上层」，引擎常驻通知依赖通知权限；
 * 缺了不会崩溃，但用户会莫名其妙"按钮没出来"，所以提前在这里拦一下。
 */
@Composable
private fun RunPrerequisiteCard(state: AppState) {
    if (state.hasOverlayPermission && state.hasNotificationPermission) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("运行前准备", fontWeight = FontWeight.SemiBold)
            if (!state.hasOverlayPermission) {
                Text(
                    "缺少「显示在其他应用上层」权限：启动虚拟应用后不会出现悬浮返回按钮。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { state.openOverlaySettings() }) { Text("去授权") }
            }
            if (!state.hasNotificationPermission) {
                Text(
                    "缺少通知权限：引擎常驻通知与虚拟应用的通知将无法显示。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { state.openNotificationSettings() }) { Text("去开启") }
            }
        }
    }
}

@Composable
private fun HomeScreen(state: AppState, scope: CoroutineScope) {
    // 直接弹系统文件选择器：选完由 state.import() 自动进入结果页，省掉中间的空白页
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { state.import(uri) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 从系统设置授权返回时刷新权限状态
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) state.refreshPermissions()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = { picker.launch(APK_MIME_TYPES) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("导入 APK") }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        RunPrerequisiteCard(state)

        HorizontalDivider()

        // 读一下图标版本号：回填完成会 +1，触发列表拿到新图标
        val iconEpoch = state.iconEpoch

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "实例列表（${state.visibleInstances.size}/${state.instances.size}）",
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (state.instances.isNotEmpty()) {
                TextButton(onClick = { state.cycleInstanceSort() }, enabled = !state.busy) {
                    Text("排序：${state.instanceSort.label}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 实例多了才显示搜索框，避免空列表时占位
        if (state.instances.size >= 4) {
            OutlinedTextField(
                value = state.instanceQuery,
                onValueChange = { state.updateInstanceQuery(it) },
                placeholder = { Text("搜索名称或包名") },
                singleLine = true,
                trailingIcon = {
                    if (state.instanceQuery.isNotEmpty()) {
                        TextButton(onClick = { state.updateInstanceQuery("") }) { Text("清除") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.instances.isEmpty()) {
            EmptyState(
                glyph = "⌂",
                title = "暂无实例",
                hint = "点上方「导入 APK」创建第一个实例。",
                actionLabel = "导入 APK",
                onAction = { picker.launch(APK_MIME_TYPES) }
            )
        } else if (state.visibleInstances.isEmpty()) {
            EmptyState(
                glyph = "⌕",
                title = "没有匹配的实例",
                hint = "换个关键词，或清除搜索条件。",
                actionLabel = "清除搜索",
                onAction = { state.updateInstanceQuery("") }
            )
        }
        state.visibleInstances.forEachIndexed { index, instance ->
            InstanceRow(
                instance = instance,
                busy = state.busy,
                iconFile = state.iconFileForInstance(instance),
                staggerIndex = index,
                onOpen = { state.openDetail(instance.id) },
                onLaunch = { scope.launch { state.launch(instance.id) } },
                onStop = { scope.launch { state.stop(instance.id) } },
                onDelete = { state.requestDelete(instance.id) }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 导入屏 ---------------- */

@Composable
private fun ImportScreen(state: AppState, scope: CoroutineScope) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { state.import(uri) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.backHome() }, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("导入确认", style = MaterialTheme.typography.titleLarge)
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = { picker.launch(APK_MIME_TYPES) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.apk == null) "选择 APK 文件" else "重新选择文件") }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.apk?.let { info ->
            ApkCard(info)
            Button(
                onClick = { scope.launch { state.installCurrent() } },
                enabled = !state.busy && !info.hasBlocker,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (info.hasBlocker) "存在阻断性问题，无法创建实例" else "创建实例") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 详情屏 ---------------- */

@Composable
private fun DetailScreen(state: AppState, scope: CoroutineScope) {
    val instance = state.selectedInstance
    LaunchedEffect(instance?.id) {
        instance?.let { state.loadEvents(it.id) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.backHome() }, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
        }
        if (instance == null) {
            Text("实例不存在或已被删除", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(file = state.iconFileForInstance(instance), size = 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(instance.displayName, style = MaterialTheme.typography.titleLarge)
                Text(instance.packageName, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)

        // 非用户主动停止的退出：明确告知，并暗示可以重开
        val abnormalExit = instance.lastExitReason != null &&
            instance.lastExitReason != ExitReason.USER_STOPPED &&
            instance.state != RuntimeState.RUNNING &&
            instance.state != RuntimeState.STARTING
        if (abnormalExit) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "上次是异常退出（${instance.lastExitReason}），不是手动停止。" +
                        "如果是应用自身崩溃，重新启动通常可以恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态：${instance.state}", fontWeight = FontWeight.SemiBold)
                Text("包名：${instance.packageName}", style = MaterialTheme.typography.bodySmall)
                Text("槽位 userId：${instance.virtualUserId}", style = MaterialTheme.typography.bodySmall)
                Text("引擎：${instance.engineId}", style = MaterialTheme.typography.bodySmall)
                Text("签名模式：${instance.signatureMode}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "已装版本 code：${instance.installedVersionCode ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("数据根：${instance.dataRoot}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "创建时间：${formatTime(instance.createdAt)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = { state.openRun(instance.id) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("运行与日志") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { state.launch(instance.id) } },
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text("启动") }
            OutlinedButton(
                onClick = { scope.launch { state.stop(instance.id) } },
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text("停止") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { state.clearData(instance.id) } },
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text("清空数据") }
            OutlinedButton(
                onClick = { scope.launch { state.openPermissions(instance.id) } },
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text("权限风险") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { state.requestDelete(instance.id) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("删除实例") }
        }
        Text("删除仅清除沙箱内数据，不影响宿主原始应用。", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("运行事件（${state.runEvents.size}）", fontWeight = FontWeight.SemiBold)
        if (state.runEvents.isEmpty()) {
            Text("暂无事件。执行启动/停止/清数据等操作后会在此记录。", style = MaterialTheme.typography.bodySmall)
        } else {
            state.runEvents.forEach { EventRow(it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

