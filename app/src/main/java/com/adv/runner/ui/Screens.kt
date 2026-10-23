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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
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

@Composable
private fun HomeScreen(state: AppState, scope: CoroutineScope) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ADV 本地运行器", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = { state.openImport() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("导入 APK") }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HorizontalDivider()
        Text("实例列表（${state.instances.size}）", fontWeight = FontWeight.SemiBold)
        if (state.instances.isEmpty()) {
            EmptyState(
                glyph = "⌂",
                title = "暂无实例",
                hint = "点上方「导入 APK」创建第一个实例。",
                actionLabel = "导入 APK",
                onAction = { state.openImport() }
            )
        }
        state.instances.forEachIndexed { index, instance ->
            InstanceRow(
                instance = instance,
                busy = state.busy,
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.backHome() }, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("导入 APK", style = MaterialTheme.typography.titleLarge)
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = {
                picker.launch(
                    arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")
                )
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("选择 APK 文件") }

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
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
        Text(instance.displayName, style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.bodySmall)

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
                instance.lastExitReason?.let {
                    Text("上次退出原因：$it", style = MaterialTheme.typography.bodySmall)
                }
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

