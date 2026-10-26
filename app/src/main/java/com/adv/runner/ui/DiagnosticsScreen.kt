package com.adv.runner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adv.runner.diagnostics.RunLogger
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 诊断二级页：引擎自检 + 日志级别切换 + 导出诊断包。 */
@Composable
fun DiagnosticsScreen(state: AppState, scope: CoroutineScope) {
    // 进页面读一次日志；部分 ROM 限制了系统 logcat，引擎侧打点只能在这里看
    LaunchedEffect(Unit) { state.loadEngineLog() }
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
            Text("诊断", style = MaterialTheme.typography.titleLarge)
        }

        // 引擎自检
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("引擎自检", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = { scope.launch { state.runSelfCheck() } },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("运行引擎自检") }
                state.selfCheck?.let { check ->
                    Text(
                        "自检结果：${if (check.passed) "通过" else "存在问题"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (check.passed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    check.checks.forEach { item ->
                        Text(
                            "· ${item.name}：${if (item.passed) "OK" else "FAIL"}（${item.detail}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.passed) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 引擎日志
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("引擎日志", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { scope.launch { state.loadEngineLog() } }) { Text("刷新") }
                }
                if (state.engineLog.isEmpty()) {
                    Text(
                        "暂无日志。启动一次虚拟应用后再点刷新，这里会显示引擎侧的 Activity 启动与窗口添加记录。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "最近 ${state.engineLog.size} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.engineLog.forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 日志级别
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日志级别", fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RunLogger.Level.entries.forEachIndexed { index, level ->
                        SegmentedButton(
                            selected = state.logLevel == level,
                            onClick = { state.changeLogLevel(level) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RunLogger.Level.entries.size
                            )
                        ) {
                            Text(level.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // 导出诊断包
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("诊断包", fontWeight = FontWeight.SemiBold)
                val context = LocalContext.current
                val diagPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            val tmp = java.io.File(context.cacheDir, "diagnostics-${System.currentTimeMillis()}.zip")
                            if (state.exportDiagnostics(tmp)) {
                                runCatching {
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        tmp.inputStream().use { it.copyTo(out) }
                                    }
                                }
                            }
                            tmp.delete()
                        }
                    }
                }
                OutlinedButton(
                    onClick = { diagPicker.launch("adv-diagnostics.zip") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出诊断包") }
                Text(
                    "诊断包包含：运行日志 + 设备信息 + 引擎能力表（均已脱敏）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
