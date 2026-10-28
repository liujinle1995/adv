package com.adv.runner.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 尺寸与间距令牌（4dp 栅格）。
 *
 * 之前按钮和卡片里散着各种字面量，宽屏下就出现了「大长条按钮」：
 * 一个 Button 铺满 2000dp 宽的窗口，既不像 Material 3 的按钮，也浪费横向空间。
 * 这里把间距、最小触控目标、CTA 最大宽度集中定义，界面只引用语义名。
 */
object Dimens {
    /** 图标与文字之间的紧间距。 */
    val spaceXs: Dp = 4.dp
    /** 相邻按钮之间的最小间距（BTN-003：≥8dp）。 */
    val spaceSm: Dp = 8.dp
    /** 同一分组内的间距。 */
    val spaceMd: Dp = 12.dp
    /** 卡片内边距。 */
    val spaceLg: Dp = 16.dp
    /** 页面外边距。 */
    val spaceXl: Dp = 20.dp

    /** 最小触控目标（BTN-002：Android ≥48dp）。 */
    val minTouchTarget: Dp = 48.dp
    /** 按钮最小高度，保证 48dp 触控区不被内容压扁。 */
    val minButtonHeight: Dp = 48.dp

    /**
     * 主动作按钮的最大宽度。
     * 宽屏（平板 / PC 模式窗口）下不再无限拉伸，保持 Material 3 的按钮比例。
     */
    val ctaMaxWidth: Dp = 320.dp
}
