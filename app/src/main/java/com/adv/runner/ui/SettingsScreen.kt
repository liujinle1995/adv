package com.adv.runner.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adv.runner.BuildConfig
import com.adv.runner.R
import com.adv.runner.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope

/**
 * 设置页：入口列表风格。
 *
 * - 外观：夜间模式横向选择（SegmentedButton）
 * - 引擎：入口，点进二级页看能力 + 设备信息
 * - 诊断：入口，点进二级页做自检 / 日志级别 / 导出
 * - 关于：版本号 + 开源声明入口
 */
@Composable
fun SettingsScreen(state: AppState, scope: CoroutineScope) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // 外观（夜间模式横向选择）
        AppearanceCard(state)

        // 引擎入口
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingLinkRow("引擎") { state.openEngineDetail() }
            }
        }

        // 诊断入口
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingLinkRow("诊断") { state.openDiagnostics() }
            }
        }

        // 悬浮窗权限入口
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingLinkRow(
                    label = "悬浮窗权限",
                    value = if (state.overlayPermissionGranted()) "已开启" else "未开启"
                ) { state.requestOverlayPermission() }
            }
        }

        // 更新记录入口
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingLinkRow(
                    label = "更新记录",
                    value = "v${BuildConfig.VERSION_NAME}"
                ) { state.openChangelog() }
            }
        }

        // 关于：版本号 + 开源声明
        AboutCard(state)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppearanceCard(state: AppState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外观", fontWeight = FontWeight.SemiBold)

            // 横向选择：跟随系统 / 浅色 / 深色
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeRepository.mode == mode,
                        onClick = { state.themeRepository.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
                    ) {
                        Text(mode.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(state: AppState) {
    val context = LocalContext.current
    // 安装时间：用来判断"手上这个包是不是最新构建"。若改了功能却忘了升版本号，
    // 这里的时间会变而版本号不变，一眼就能看出来（更新记录跟着漏记的情况）。
    val builtAt = remember {
        runCatching {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(info.lastUpdateTime))
        }.getOrNull()
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            // 版本号
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("关于", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (builtAt != null) {
                        Text(
                            "构建 $builtAt（${BuildConfig.VERSION_CODE}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingLinkRow("开源声明") { state.openLicenses() }
        }
    }
}

@Composable
private fun SettingLinkRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
