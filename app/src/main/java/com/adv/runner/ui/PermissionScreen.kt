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
import com.adv.runner.core.model.ApkPermission
import com.adv.runner.core.model.Capability

/**
 * 权限与风险提示页：列出实例 APK 的危险权限，逐条用人话解释，
 * 并标注底层引擎「是否真的拦截」——不生效的一律诚实标注，不假装安全。
 */
@Composable
fun PermissionScreen(state: AppState) {
    val instance = state.selectedInstance
    val info = state.permissionApk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { instance?.let { state.openDetail(it.id) } ?: state.openHome() },
                enabled = !state.busy
            ) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("权限风险", style = MaterialTheme.typography.titleLarge)
        }

        // 引擎拦截能力总览
        val interception = state.caps?.permissionInterception ?: Capability.UNKNOWN
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("引擎权限拦截能力", fontWeight = FontWeight.SemiBold)
                Text(
                    when (interception) {
                        Capability.SUPPORTED -> "已确认拦截：危险权限请求会被引擎真正拦截。"
                        Capability.PARTIAL -> "部分拦截：部分权限能拦截，部分可能放行（详见下方标注）。"
                        Capability.UNSUPPORTED -> "未拦截：引擎不拦截权限，应用可直接使用。"
                        Capability.UNKNOWN -> "未验证：拦截能力尚未实测，无法承诺安全。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (interception) {
                        Capability.SUPPORTED -> MaterialTheme.colorScheme.primary
                        Capability.PARTIAL -> MaterialTheme.colorScheme.tertiary
                        Capability.UNSUPPORTED, Capability.UNKNOWN -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    "重要提示：即便拦截，也无法保证 100% 安全；敏感操作请谨慎授权。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val dangerous = info?.permissions?.filter { it.dangerous }.orEmpty()
        Text("危险权限（${dangerous.size}）", fontWeight = FontWeight.SemiBold)

        if (dangerous.isEmpty()) {
            EmptyState("✓", "无危险权限", "该应用未申请危险权限。")
        } else {
            dangerous.forEach { permission ->
                PermissionRow(permission, interception)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(permission: ApkPermission, interception: Capability) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(permission.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(
                humanExplanation(permission.name),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "底层拦截状态：" + interceptionLabel(interception),
                style = MaterialTheme.typography.labelSmall,
                color = when (interception) {
                    Capability.SUPPORTED -> MaterialTheme.colorScheme.primary
                    Capability.PARTIAL -> MaterialTheme.colorScheme.tertiary
                    Capability.UNSUPPORTED, Capability.UNKNOWN -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

private fun interceptionLabel(capability: Capability): String = when (capability) {
    Capability.SUPPORTED -> "已确认拦截"
    Capability.PARTIAL -> "部分拦截（可能放行）"
    Capability.UNSUPPORTED -> "未拦截"
    Capability.UNKNOWN -> "未验证"
}

/** 危险权限 → 人话解释。未知权限给通用说明。 */
private fun humanExplanation(name: String): String {
    val base = name.substringAfterLast('.')
    return when (base) {
        "READ_CONTACTS", "WRITE_CONTACTS" -> "读取/修改你的通讯录联系人。"
        "READ_CALENDAR", "WRITE_CALENDAR" -> "读取/修改你的日历事件。"
        "CAMERA" -> "使用摄像头拍照或录像。"
        "READ_PHONE_STATE" -> "读取设备状态，如手机号、IMEI、通话状态。"
        "CALL_PHONE" -> "直接拨打电话。"
        "READ_CALL_LOG", "WRITE_CALL_LOG" -> "读取/修改你的通话记录。"
        "RECORD_AUDIO" -> "通过麦克风录音。"
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH" ->
            "读取/发送短信或彩信。"
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION" ->
            "获取你的精确/粗略位置（含后台定位）。"
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "MANAGE_EXTERNAL_STORAGE" ->
            "读取/写入你的相册、文件等存储内容。"
        "BODY_SENSORS" -> "读取心率等身体传感器数据。"
        "ACTIVITY_RECOGNITION" -> "识别你的运动状态（步行、跑步等）。"
        "GET_ACCOUNTS" -> "读取设备上的账号列表。"
        "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE" ->
            "连接/扫描附近的蓝牙设备。"
        "POST_NOTIFICATIONS" -> "向状态栏发送通知。"
        else -> "该权限的用途未收录，请谨慎授权。"
    }
}
