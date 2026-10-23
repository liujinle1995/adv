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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 引擎详情二级页：引擎能力 + 设备信息（设备信息跟在引擎内容后面）。 */
@Composable
fun EngineDetailScreen(state: AppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.openSettings() }, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("引擎", style = MaterialTheme.typography.titleLarge)
        }

        // 引擎能力
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("引擎能力", fontWeight = FontWeight.SemiBold)
                if (state.caps == null) {
                    Text("引擎尚未初始化。", style = MaterialTheme.typography.bodySmall)
                } else {
                    CapabilitiesCard(state)
                }
            }
        }

        // 设备信息（跟在引擎后面）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设备信息", fontWeight = FontWeight.SemiBold)
                Text("系统 API：${state.deviceSdk}", style = MaterialTheme.typography.bodySmall)
                Text("设备 ABI：${state.deviceAbis.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("调试 APK：${state.debugApkPath ?: "无"}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
