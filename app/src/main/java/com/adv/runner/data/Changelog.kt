package com.adv.runner.data

/**
 * 更新记录数据源（唯一事实来源）。
 *
 * 约定：每次发版必须在此追加一条 [ChangelogEntry]，versionCode 与
 * app/build.gradle.kts 的 versionCode 对齐。若最新条目的 versionCode 小于
 * BuildConfig.VERSION_CODE，更新记录页会显示断更警告（防止版本 bump 后忘记录）。
 */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    /** 版本主题，一行概括。 */
    val title: String,
    /** 本次变更要点。 */
    val highlights: List<String>,
)

object Changelog {

    /** 按 versionCode 降序（新版本在上）。 */
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            versionCode = 17,
            versionName = "0.4.1",
            title = "清理缓存",
            highlights = listOf(
                "数据管理新增「清理缓存」：只删 cache 目录，保留 files 与数据库",
                "清理前二次确认并显示可释放的空间，清理后占用数字自动刷新",
                "虚拟应用运行中禁止清理，避免删到正在写入的缓存导致崩溃",
            ),
        ),
        ChangelogEntry(
            versionCode = 16,
            versionName = "0.4.0",
            title = "数据占用可见",
            highlights = listOf(
                "数据管理页显示沙箱占用合计、缓存占用与文件数",
                "每个实例单独显示占用（总量 / 缓存 / 文件数），不再只给一串目录路径",
                "占用统计在后台线程递归统计，可手动刷新",
            ),
        ),
        ChangelogEntry(
            versionCode = 15,
            versionName = "0.3.9",
            title = "导入少点一步",
            highlights = listOf(
                "首页点「导入 APK」直接弹出系统文件选择器，不再先进入一个空白页面",
                "选完文件自动进入结果确认页，可查看解析信息并创建实例",
                "结果页支持「重新选择文件」，选错包不用退回首页",
            ),
        ),
        ChangelogEntry(
            versionCode = 14,
            versionName = "0.3.8",
            title = "修复顶部留白与状态栏",
            highlights = listOf(
                "修复首页顶部大片留白：状态栏高度被重复计算了两次",
                "修复状态栏时间、电量等图标在浅色背景下看不清的问题",
                "状态栏与导航栏图标颜色现在跟随深色/浅色主题自动切换",
            ),
        ),
        ChangelogEntry(
            versionCode = 13,
            versionName = "0.3.7",
            title = "图标、搜索与异常重启",
            highlights = listOf(
                "实例列表与详情页显示应用图标（导入时自动提取并缓存，不占数据库）",
                "首页支持按名称/包名搜索，列表可按「最近使用 / 名称 / 导入时间」排序",
                "虚拟应用异常退出（崩溃、被系统杀）时明确提示，并提供「重新启动」",
                "首页新增「运行前准备」：缺悬浮窗或通知权限时直接给出授权入口",
            ),
        ),
        ChangelogEntry(
            versionCode = 12,
            versionName = "0.3.6",
            title = "半圆贴边",
            highlights = listOf(
                "悬浮按钮松手后吸附到屏幕左右边缘，并缩成只露出一半的半圆（类似 iOS 辅助触控）",
                "点一下半圆会先完整滑出再响应，拖动时不会从屏幕外开始跟手",
                "闲置 3 秒自动变淡，触摸立即恢复清晰",
            ),
        ),
        ChangelogEntry(
            versionCode = 11,
            versionName = "0.3.5",
            title = "悬浮按钮换装",
            highlights = listOf(
                "悬浮停止按钮直径从 56dp 缩到 44dp，显示位置不变，但遮挡更少",
                "配色由实心蓝改为半透明白底 + 深色 ✕，不再抢眼，观感更轻",
                "阴影与描边同步减弱，整体更通透",
                "闲置 3 秒自动变淡成一层浅影，触摸立即恢复清晰",
                "拖动松手后自动吸附到最近的左/右屏幕边缘，带过渡动画",
            ),
        ),
        ChangelogEntry(
            versionCode = 10,
            versionName = "0.3.4",
            title = "帧率诊断",
            highlights = listOf(
                "引擎页「设备」新增屏幕刷新率显示，能直接看出当前是 60Hz 还是高刷新率",
                "当系统把屏幕限制在低刷新率时给出提示，并说明在系统设置哪个位置调高",
                "虚拟应用的帧率上限由系统刷新率决定：应用侧无法绕过系统的 60Hz 限制",
            ),
        ),
        ChangelogEntry(
            versionCode = 9,
            versionName = "0.3.3",
            title = "引擎页改版",
            highlights = listOf(
                "设置 → 引擎新增实时运行状态：直接显示引擎是否启动、引擎进程 PID、正在运行的虚拟应用数",
                "未启动时提供「启动引擎」按钮，可手动拉起引擎进程；另有「刷新」即时更新状态",
                "精简重复内容：去掉重复标题与和能力表重复的说明，能力改为紧凑两列，只列已确认项",
                "更新过时的引擎说明（已适配 Android 17、原生库 16KB 页对齐、引擎以前台服务常驻）",
            ),
        ),
        ChangelogEntry(
            versionCode = 8,
            versionName = "0.3.2",
            title = "引擎预热与启动观测",
            highlights = listOf(
                "引擎预热扩展到包管理、活动管理、任务、存储全部系统服务，首次启动少付跨进程初始化开销",
                "新增启动耗时打点：日志记录「点按 → 虚拟进程创建」耗时，便于后续持续调优",
            ),
        ),
        ChangelogEntry(
            versionCode = 7,
            versionName = "0.3.1",
            title = "启动速度与稳定性优化",
            highlights = listOf(
                "引擎服务进程改为前台服务常驻，不再被系统频繁回收（实测连续存活从 45 秒提升到 3 分钟以上）",
                "新增 dex 预热：安装后提前完成字节码编译，虚拟应用首次启动明显更快",
                "引擎进程预热：进入详情页即提前拉起引擎，点启动时等待更短",
                "虚拟应用首帧实测从约 2.4 秒降到约 1.3 秒",
                "悬浮按钮的状态轮询降频，减少跨进程调用开销",
                "运行日志改为批量写入，减少磁盘读写次数",
            ),
        ),
        ChangelogEntry(
            versionCode = 6,
            versionName = "0.3.0",
            title = "Android 17 正式支持",
            highlights = listOf(
                "编译工具链升级到 AGP 8.13，compileSdk 提升到 37，真正按 Android 17 SDK 编译",
                "native 库改为 16KB 页对齐，兼容 Android 15+ 的 16KB 页新设备（向后兼容 4KB 页）",
                "补齐 Android 17 新增的局域网访问权限，以及虚拟应用常用的相机/录音/通知/存储/定位权限",
                "代理界面补全色彩模式等配置变更声明，适配大屏与折叠屏的可调整窗口",
                "宿主界面在旋转、分屏、主题切换时不再重建",
            ),
        ),
        ChangelogEntry(
            versionCode = 5,
            versionName = "0.2.3",
            title = "停止可靠性修复",
            highlights = listOf(
                "修复引擎服务进程被系统冻结器杀死后，点悬浮按钮停止虚拟应用无反应的问题",
                "引擎进程注册表丢失时，停止操作改按磁盘进程记录兜底击杀，不再静默失败",
                "虚拟应用启动时优先复用磁盘上存活的进程记录重新绑定，避免重复绑定导致 WebView 数据目录冲突",
                "悬浮按钮点击后立即弹 Toast 反馈，停止完成自动返回宿主界面",
                "修复停止结果提示在后台线程弹 Toast 导致宿主进程崩溃的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 4,
            versionName = "0.2.2",
            title = "引擎稳定性修复",
            highlights = listOf(
                "修复 Android 16 上 JNI Hook 方法标志位扫描偏移失效导致引擎初始化失败的问题",
                "修复获取引擎服务时的无限递归 StackOverflowError",
                "修复引擎服务进程重启后虚拟主进程重复绑定、WebView 数据目录锁冲突崩溃的问题",
                "清理 native 方法签名误注册与过期临时 dex 文件",
            ),
        ),
        ChangelogEntry(
            versionCode = 3,
            versionName = "0.2.1",
            title = "Android 17 支持",
            highlights = listOf(
                "适配 Android 17（API 37）：targetSdk 升级，隐藏 API 探测兼容新版本",
                "支持以 android-37.0 平台编译，引擎 Hook 兼容 API 36 运行时布局",
            ),
        ),
        ChangelogEntry(
            versionCode = 2,
            versionName = "0.2.0",
            title = "引擎接入",
            highlights = listOf(
                "接入 BlackBox 引擎，支持导入 / 安装 / 启动 / 停止 / 清数据",
                "实例与运行事件改存 Room 数据库",
                "首页与设置页信息架构调整",
            ),
        ),
        ChangelogEntry(
            versionCode = 1,
            versionName = "0.1.0",
            title = "首个版本",
            highlights = listOf(
                "最小可用闭环：导入 → 建实例 → 启动",
            ),
        ),
    )

    /** 当前安装版本的记录；找不到返回 null。 */
    fun entryFor(versionCode: Int): ChangelogEntry? =
        entries.firstOrNull { it.versionCode == versionCode }

    /** 是否有比 [currentVersionCode] 更新的记录（用于入口红点/未读提示）。 */
    fun hasNewerThan(currentVersionCode: Int): Boolean =
        entries.any { it.versionCode > currentVersionCode }

    /** 最新记录的 versionCode 是否落后于当前安装版本（断更自检）。 */
    fun isStale(currentVersionCode: Int): Boolean =
        entries.maxOf { it.versionCode } < currentVersionCode
}
