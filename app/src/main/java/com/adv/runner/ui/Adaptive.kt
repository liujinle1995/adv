package com.adv.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 布局断点：判断当前设备是否处于「宽屏」模式（平板横屏 / 折叠屏展开）。
 *
 * - 窄屏（手机竖屏 / 手机横屏被锁）：单列 + 底部导航
 * - 宽屏（sw≥600dp 且实际宽度 ≥840dp）：NavigationRail + 双栏
 *
 * 阈值 840dp 是 Material 3 推荐的双栏（ListDetail）分界点。
 */
@Composable
fun isWideScreen(): Boolean {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return widthDp >= 840
}
