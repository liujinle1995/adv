package com.adv.runner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adv.runner.core.model.Capability
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 引擎详情二级页。
 *
 * 结构（自上而下）：
 * 1. 运行状态——引擎进程在不在、有几个虚拟应用在跑，带「启动引擎 / 刷新」；
 * 2. 引擎信息——版本、支持范围、是否要 Root；
 * 3. 能力表——只列已定论的能力（UNKNOWN 的不占位，避免噪音）；
 * 4. 设备——系统版本与 ABI（调试信息归诊断页，不在这里）。
 */
@Composable
fun EngineDetailScreen(state: AppState) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.openSettings() }, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("引擎", style = MaterialTheme.typography.titleLarge)
        }

        RuntimeStatusCard(
            state = state,
            onRefresh = {
                state.refreshScreenInfo()
                scope.launch { state.refreshEngineStatus() }
            },
            onStart = { scope.launch { state.startEngine() } },
        )
        EngineInfoCard(state)
        CapabilitiesCardV2(state)
        DeviceCard(state)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RuntimeStatusCard(state: AppState, onRefresh: () -> Unit, onStart: () -> Unit) {
    val status = state.engineStatus
    val running = status?.running == true
    val dotColor = when {
        status == null -> MaterialTheme.colorScheme.onSurfaceVariant
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = dotColor, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        status == null -> "查询中…"
                        running -> "引擎运行中"
                        else -> "引擎未启动"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (status != null) {
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
                StatusLine("引擎进程", status.processPid?.let { "PID $it" } ?: "未运行")
                StatusLine("虚拟应用进程", "${status.virtualProcessCount} 个")
            } else {
                Text("正在查询引擎状态…", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.engineStatusBusy
                ) { Text("刷新") }
                if (!running) {
                    Button(
                        onClick = onStart,
                        enabled = !state.engineStatusBusy
                    ) { Text("启动引擎") }
                }
            }
        }
    }
}

@Composable
private fun EngineInfoCard(state: AppState) {
    val caps = state.caps
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("引擎信息", fontWeight = FontWeight.SemiBold)
            if (caps == null) {
                Text("引擎尚未初始化", style = MaterialTheme.typography.bodySmall)
            } else {
                StatusLine("引擎", "${caps.engineId} ${caps.engineVersion}")
                StatusLine("支持范围", "Android 12–17（API ${caps.supportedSdk.first}–${caps.supportedSdk.last}）")
                StatusLine("需要 Root", if (caps.requiresRoot) "是" else "否")
                caps.notes.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CapabilitiesCardV2(state: AppState) {
    val caps = state.caps
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("能力", fontWeight = FontWeight.SemiBold)
            if (caps == null) {
                Text("引擎尚未初始化", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            // 只展示已有定论的能力：UNKNOWN 不占位，避免"未实测一律 UNKNOWN"刷屏
            val rows = listOf(
                "同包名异签名" to caps.samePackageDifferentSignature,
                "多实例" to caps.multiInstance,
                "权限拦截" to caps.permissionInterception,
                "清除数据" to caps.clearData,
                "原生库" to caps.nativeLibs,
                "32 位应用" to caps.supports32BitApk,
                "WebView" to caps.webView,
                "Google 服务" to caps.gms,
            ).filter { it.second != Capability.UNKNOWN }

            if (rows.isEmpty()) {
                Text("暂无已确认能力", style = MaterialTheme.typography.bodySmall)
            } else {
                rows.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { (name, cap) ->
                            Row(modifier = Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    cap.label(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(state: AppState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("设备", fontWeight = FontWeight.SemiBold)
            StatusLine("系统", "Android API ${state.deviceSdk}")
            StatusLine("ABI", state.deviceAbis.joinToString())

            if (state.screenRefreshRate > 0f) {
                val current = state.screenRefreshRate.roundToInt()
                val max = state.screenMaxRefreshRate.roundToInt()
                StatusLine("屏幕刷新率", if (max > current) "$current Hz（最高 $max Hz）" else "$current Hz")
                // 刷新率被系统压到低档时，虚拟应用的帧率上限也会被压住，这里直接点明
                if (max > 0 && current < max - 1) {
                    Text(
                        "当前屏幕被限制在 ${current}Hz，虚拟应用最高只能渲染这么多帧。" +
                            "可在系统设置「显示与亮度 → 屏幕刷新率」中改为高刷新率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Capability.label(): String = when (this) {
    Capability.SUPPORTED -> "支持"
    Capability.UNSUPPORTED -> "不支持"
    Capability.UNKNOWN -> "未验证"
}
