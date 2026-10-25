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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adv.runner.BuildConfig
import com.adv.runner.data.Changelog

/** 更新记录二级页。数据来自 [com.adv.runner.data.Changelog]，发版时维护该文件。 */
@Composable
fun ChangelogScreen(state: AppState) {
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
            Text("更新记录", style = MaterialTheme.typography.titleLarge)
        }

        val currentVersionCode = BuildConfig.VERSION_CODE
        val stale = Changelog.isStale(currentVersionCode)
        if (stale) {
            // 断更自检：versionCode 已 bump 但 Changelog 未追加记录时暴露出来
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME}（versionCode=$currentVersionCode）" +
                        "尚无更新记录，请维护 data/Changelog.kt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Changelog.entries.forEach { entry ->
            val isCurrent = entry.versionCode == currentVersionCode
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${entry.versionName} · ${entry.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前版本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    entry.highlights.forEach { item ->
                        Text("· $item", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 开源声明二级页。 */
@Composable
fun LicensesScreen(state: AppState) {
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
            Text("开源声明", style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本应用基于以下开源项目构建：", style = MaterialTheme.typography.bodyMedium)
                OSSItem("BlackBox", "Apache-2.0", "应用虚拟化引擎")
                OSSItem("Jetpack Compose", "Apache-2.0", "UI 框架")
                OSSItem("AndroidX / Room", "Apache-2.0", "组件库与本地数据库")
                OSSItem("Kotlin", "Apache-2.0", "开发语言")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OSSItem(name: String, license: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$name · $license",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}
