package com.adv.runner.ui

/**
 * 页面枚举。
 *
 * - 底部导航主 Tab：[HOME]（首页＝导入入口 + 实例管理）/ [DATA]（数据管理）/ [SETTINGS]（设置）
 * - 二级页：[IMPORT]（导入流程，从首页进入）/ [DETAIL]（实例详情）/ [RUN]（运行与日志）
 *
 * 设计说明：导入不再单列 Tab，避免与首页功能重复；首页即唯一入口，
 * 点「导入 APK」进入 [IMPORT] 流程，完成后回首页看到新实例。
 *
 * [glyph] 是底部导航图标用的单字符符号，避免为图标单独引入 material-icons 依赖。
 */
enum class Screen(
    val label: String,
    val glyph: String,
    val isTopLevel: Boolean
) {
    HOME("首页", "⌂", true),
    IMPORT("导入", "+", false),
    DETAIL("详情", "ℹ", false),
    RUN("运行", "▶", false),
    DATA("数据", "▦", true),
    SETTINGS("设置", "⚙", true),
    CHANGELOG("更新记录", "", false),
    LICENSES("开源声明", "", false),
    PERMISSIONS("权限风险", "", false),
    ENGINE_DETAIL("引擎详情", "", false),
    DIAGNOSTICS("诊断", "", false);

    companion object {
        val topLevel: List<Screen> get() = entries.filter { it.isTopLevel }
    }
}
