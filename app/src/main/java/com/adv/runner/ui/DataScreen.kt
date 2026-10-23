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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 数据管理：列出实例，提供清数据 / 备份 / 导入备份 / 删除（删除需二次确认）。 */
@Composable
fun DataScreen(state: AppState, scope: CoroutineScope) {
    val context = LocalContext.current
    var backupTargetId by remember { mutableStateOf<String?>(null) }
    var restoreTargetId by remember { mutableStateOf<String?>(null) }
    // 备份：选导出位置（SAF CreateDocument）
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val id = backupTargetId
            if (id != null) {
                scope.launch {
                    val tmp = java.io.File(context.cacheDir, "backup-${System.currentTimeMillis()}.zip")
                    if (state.backup(id, tmp)) {
                        // 复制到用户选择的位置
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
    }
    // 导入备份：选备份文件（SAF OpenDocument）
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val id = restoreTargetId
            if (id != null) {
                scope.launch {
                    val tmp = java.io.File(context.cacheDir, "restore-${System.currentTimeMillis()}.zip")
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                    }
                    state.restore(id, tmp)
                    tmp.delete()
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("数据管理", style = MaterialTheme.typography.titleLarge)
        Text(
            "沙箱数据存储在引擎私有目录。删除实例仅清除沙箱内数据，不影响宿主原始应用。",
            style = MaterialTheme.typography.bodySmall
        )

        if (state.instances.isEmpty()) {
            EmptyState("▦", "暂无实例", "导入并创建实例后，可在此管理各实例的沙箱数据。")
        } else {
            state.instances.forEach { instance ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(instance.displayName, fontWeight = FontWeight.SemiBold)
                        Text("包名：${instance.packageName}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "状态：${instance.state} · userId=${instance.virtualUserId}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("数据根：${instance.dataRoot}", style = MaterialTheme.typography.bodySmall)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { scope.launch { state.clearData(instance.id) } },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("清空数据") }
                            OutlinedButton(
                                onClick = {
                                    backupTargetId = instance.id
                                    backupPicker.launch("${instance.packageName}-backup.zip")
                                },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("备份") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    restoreTargetId = instance.id
                                    restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                                },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("导入备份") }
                            OutlinedButton(
                                onClick = { state.requestDelete(instance.id) },
                                enabled = !state.busy,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("删除实例") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
