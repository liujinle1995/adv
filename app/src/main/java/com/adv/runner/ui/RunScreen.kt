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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 运行与日志：针对单个实例的启动/停止/清数据控制 + 实时事件流。 */
@Composable
fun RunScreen(state: AppState, scope: CoroutineScope) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { if (instance != null) state.openDetail(instance.id) else state.backHome() },
                enabled = !state.busy
            ) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("运行与日志", style = MaterialTheme.typography.titleLarge)
        }

        if (instance == null) {
            EmptyState("▶", "实例不存在或已被删除", "返回首页查看实例列表。")
            return@Column
        }

        Text(instance.displayName, fontWeight = FontWeight.SemiBold)
        Text("状态：${instance.state}", style = MaterialTheme.typography.bodySmall)
        Text(state.status, style = MaterialTheme.typography.bodySmall)

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
        OutlinedButton(
            onClick = { scope.launch { state.clearData(instance.id) } },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("清空数据") }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HorizontalDivider()
        Text("运行事件（${state.runEvents.size}）", fontWeight = FontWeight.SemiBold)
        if (state.runEvents.isEmpty()) {
            EmptyState(
                glyph = "◷",
                title = "暂无事件",
                hint = "执行启动 / 停止 / 清数据等操作后会在此实时记录。"
            )
        } else {
            state.runEvents.forEach { EventRow(it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
