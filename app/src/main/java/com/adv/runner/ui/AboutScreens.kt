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

/** 更新记录二级页。 */
@Composable
fun ChangelogScreen(state: AppState) {
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
            Text("更新记录", style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("0.2.0 · 引擎接入", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "· 接入 BlackBox 引擎，支持导入 / 安装 / 启动 / 停止 / 清数据",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("· 实例与运行事件改存 Room 数据库", style = MaterialTheme.typography.bodySmall)
                Text("· 首页与设置页信息架构调整", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("0.1.0 · 首个版本", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("· 最小可用闭环：导入 → 建实例 → 启动", style = MaterialTheme.typography.bodySmall)
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
