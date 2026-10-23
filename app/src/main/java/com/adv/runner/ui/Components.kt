package com.adv.runner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.Capability
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- 通用状态组件（空态 / 错误态 / 加载态） ---------------- */

@Composable
fun EmptyState(
    glyph: String,
    title: String,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, style = MaterialTheme.typography.displayMedium)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⚠", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.error)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) { Text("重试") }
        }
    }
}

@Composable
fun LoadingState(
    text: String = "加载中…",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

/* ---------------- 实例 / APK / 事件 展示组件 ---------------- */

@Composable
fun CapabilitiesCard(state: AppState) {
    val c = state.caps ?: return
    Text("引擎能力（未实测一律 UNKNOWN）", fontWeight = FontWeight.SemiBold)
    Text("引擎：${c.engineId} ${c.engineVersion}", style = MaterialTheme.typography.bodySmall)
    Text("声明支持：API ${c.supportedSdk.first}–${c.supportedSdk.last}", style = MaterialTheme.typography.bodySmall)
    Text("同包名不同签名：${c.samePackageDifferentSignature}", style = MaterialTheme.typography.bodySmall)
    Text("多实例：${c.multiInstance}", style = MaterialTheme.typography.bodySmall)
    Text("原生库：${c.nativeLibs}", style = MaterialTheme.typography.bodySmall)
    Text("清数据：${c.clearData}", style = MaterialTheme.typography.bodySmall)
    Text("权限拦截：${c.permissionInterception}", style = MaterialTheme.typography.bodySmall)
    c.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun InstanceRow(
    instance: RuntimeInstance,
    busy: Boolean,
    onOpen: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    // 列表入场 stagger 序号，0 起；>=0 时启用淡入+上移入场
    staggerIndex: Int = -1
) {
    var entered by remember(instance.id) { mutableStateOf(staggerIndex < 0) }
    LaunchedEffect(instance.id) {
        if (staggerIndex >= 0 && !entered) {
            // 微级联：每项延迟 30ms，配合 AnimatedContent 的页面切换节奏
            kotlinx.coroutines.delay(staggerIndex * 30L)
            entered = true
        }
    }
    val rowAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 250,
            easing = LinearOutSlowInEasing
        ),
        label = "instance-row-alpha"
    )
    val offsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 12f,
        animationSpec = tween(
            durationMillis = 250,
            easing = LinearOutSlowInEasing
        ),
        label = "instance-row-offset"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rowAlpha
                translationY = offsetY
            }
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(instance.displayName, fontWeight = FontWeight.SemiBold)
            Text(
                "状态：${instance.state}  ·  userId=${instance.virtualUserId}  ·  引擎=${instance.engineId}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLaunch, enabled = !busy) { Text("启动") }
                OutlinedButton(onClick = onStop, enabled = !busy) { Text("停止") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
        }
    }
}

@Composable
fun ApkCard(info: ApkInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${info.appLabel}（${info.packageName}）", fontWeight = FontWeight.SemiBold)
            Text("版本：${info.versionName}（${info.versionCode}）", style = MaterialTheme.typography.bodySmall)
            Text("minSdk ${info.minSdk} / targetSdk ${info.targetSdk}", style = MaterialTheme.typography.bodySmall)
            Text("ABI：${info.abis.ifEmpty { listOf("无原生库") }.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text(
                "签名 SHA-256：${info.signerSha256.joinToString().take(64).ifEmpty { "读取失败" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("签名主体：${info.signerSubject ?: "未知"}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256：${info.sha256.take(32)}…", style = MaterialTheme.typography.bodySmall)
            Text(
                "组件：Activity ${info.components.activities} / Service ${info.components.services} / " +
                    "Receiver ${info.components.receivers} / Provider ${info.components.providers}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "权限 ${info.permissions.size} 个（危险 ${info.permissions.count { it.dangerous }} 个）",
                style = MaterialTheme.typography.bodySmall
            )
            info.issues.forEach { issue ->
                val color = when (issue.severity) {
                    Severity.BLOCKER -> MaterialTheme.colorScheme.error
                    Severity.WARN -> MaterialTheme.colorScheme.tertiary
                    Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "【${issue.severity}】${issue.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}

@Composable
fun EventRow(event: RunEvent) {
    val accent = when (event.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            buildString {
                append(formatTime(event.ts))
                append("  [").append(event.type).append(']')
                if (event.level != "INFO") append("  · ").append(event.level)
            },
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Text(event.message, style = MaterialTheme.typography.bodySmall)
        event.code?.let {
            Text("code：$it", style = MaterialTheme.typography.labelSmall, color = accent)
        }
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

/**
 * 删除实例二次确认弹窗：明确说明「只删沙箱内数据」，不可撤销。
 */
@Composable
fun DeleteConfirmDialog(
    instance: RuntimeInstance,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("确认删除实例") },
        text = {
            Text(
                "将删除「${instance.displayName}」（${instance.packageName}）。\n" +
                    "仅清除沙箱内数据，不影响宿主原始应用。此操作不可撤销。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

/**
 * 签名冲突二次确认弹窗：同包名 APK 的签名与已有实例不同，
 * 用户必须二选一，禁止静默覆盖。
 */
@Composable
fun SignatureConflictDialog(
    conflict: SignatureConflict,
    engineSupportsCoexist: Capability?,
    onReplace: () -> Unit,
    onCoexist: () -> Unit,
    onCancel: () -> Unit
) {
    // 「并存」是否可用取决于引擎能力：UNKNOWN/SUPPORTED/PARTIAL 允许尝试（但 UNKNOWN 需警示），UNSUPPORTED 禁用
    val coexistEnabled = engineSupportsCoexist != Capability.UNSUPPORTED
    val coexistHint = when (engineSupportsCoexist) {
        Capability.UNKNOWN -> "引擎能力未验证，并存可能失败"
        Capability.SUPPORTED -> "引擎支持同包名不同签名并存"
        Capability.PARTIAL -> "引擎部分支持并存，可能受限"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("检测到签名冲突") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "已存在实例「${conflict.existingDisplayName}」（${conflict.packageName}），" +
                        "但当前 APK 的签名与它不同。"
                )
                Text(
                    "覆盖安装会清空旧实例数据；并存将保留旧实例但可能失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 并存选项放在正文，附能力说明
                OutlinedButton(
                    onClick = onCoexist,
                    enabled = coexistEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (coexistEnabled) "新建独立实例" else "并存不可用") }
                coexistHint?.takeIf { coexistEnabled }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text("清数据重装", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
