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

/** 字节数格式化：贴近系统「存储」页的读法。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} ${units[0]}" else String.format("%.1f %s", value, units[index])
}

/** 数据管理：列出实例，提供清数据 / 备份 / 导入备份 / 删除（删除需二次确认）。 */
@Composable
fun DataScreen(state: AppState, scope: CoroutineScope) {
    val context = LocalContext.current
    var backupTargetId by remember { mutableStateOf<String?>(null) }
    var restoreTargetId by remember { mutableStateOf<String?>(null) }
    // 待确认清缓存的实例（删除缓存是破坏性操作，必须二次确认）
    var clearCacheTarget by remember { mutableStateOf<com.adv.runner.core.model.RuntimeInstance?>(null) }
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
    // 进页面就测一次占用（递归遍历放到 IO 线程，测完自动刷新界面）
    androidx.compose.runtime.LaunchedEffect(state.instances.size) {
        if (state.instances.isNotEmpty()) state.refreshDataUsage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("数据管理", style = MaterialTheme.typography.titleLarge)
        Text(
            "沙箱数据存储在引擎私有目录。删除实例仅清除沙箱内数据，不影响宿主原始应用。",
            style = MaterialTheme.typography.bodySmall
        )

        if (state.instances.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("沙箱占用合计", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { scope.launch { state.refreshDataUsage() } },
                            enabled = !state.usageBusy
                        ) { Text(if (state.usageBusy) "统计中…" else "刷新") }
                    }
                    val total = state.totalUsage
                    Text(
                        if (state.dataUsage.isEmpty()) "尚未统计"
                        else "共 ${formatBytes(total.totalBytes)} · 其中缓存 ${formatBytes(total.cacheBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.dataUsage.isNotEmpty()) {
                        Text(
                            "文件数 ${total.fileCount} · ${state.dataUsage.size} 个实例",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

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
                        val usage = state.dataUsage[instance.id]
                        Text(
                            when {
                                state.usageBusy && usage == null -> "占用：统计中…"
                                usage == null -> "占用：未统计"
                                usage.totalBytes == 0L -> "占用：0 B（尚未产生数据）"
                                else -> "占用：${formatBytes(usage.totalBytes)}" +
                                    "（缓存 ${formatBytes(usage.cacheBytes)} · ${usage.fileCount} 个文件）"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 运行中禁止清缓存：数据库/图片缓存正在被写，删了会让应用崩
                            val cacheClearingAllowed = !state.busy &&
                                instance.state != com.adv.runner.core.model.RuntimeState.RUNNING &&
                                instance.state != com.adv.runner.core.model.RuntimeState.STARTING
                            OutlinedButton(
                                onClick = { clearCacheTarget = instance },
                                enabled = cacheClearingAllowed,
                                modifier = Modifier.weight(1f)
                            ) { Text("清理缓存") }
                            OutlinedButton(
                                onClick = { scope.launch { state.clearData(instance.id) } },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("清空数据") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    backupTargetId = instance.id
                                    backupPicker.launch("${instance.packageName}-backup.zip")
                                },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("备份") }
                            OutlinedButton(
                                onClick = {
                                    restoreTargetId = instance.id
                                    restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                                },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("导入备份") }
                        }
                        OutlinedButton(
                            onClick = { state.requestDelete(instance.id) },
                            enabled = !state.busy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("删除实例") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // 清缓存二次确认
    clearCacheTarget?.let { target ->
        val cacheBytes = state.dataUsage[target.id]?.cacheBytes ?: 0L
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { clearCacheTarget = null },
            title = { Text("清理缓存") },
            text = {
                Text(
                    "将删除「${target.displayName}」的缓存目录" +
                        (if (cacheBytes > 0) "（约 ${formatBytes(cacheBytes)}）" else "") +
                        "。\n\n只清理 cache，聊天记录、登录状态等文件保留。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val id = target.id
                        clearCacheTarget = null
                        scope.launch { state.clearCache(id) }
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { clearCacheTarget = null }) { Text("取消") }
            }
        )
    }
}
