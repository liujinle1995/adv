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
            versionCode = 80,
            versionName = "0.9.16",
            title = "诊断升级：可导出虚拟应用自身日志",
            highlights = listOf(
                "定位「图片封面全空、视频一直转圈，但接口数据正常」：沙箱里接口 JSON、TTNet 取网页都正常（实测 205ms 拿到完整 HTML），图片和播放器却不动",
                "新增内核态线程快照（/proc/self/task：状态 + 线程名），纯 native 线程（Chromium/Cronet/TTNet/MDL）Java 栈是空的，只能从这里看",
                "新增 Fresco 管线状态探针（是否初始化、是否被 pause）和 native 库映射清单",
                "新增「用应用自己的图片管线拉一张图」实验：实测沙箱里能成功下载并解码（1.9 秒拿到 CloseableReference），证明图片管线本身没坏",
                "触发诊断时会顺带导出虚拟应用自己的日志、目录树、shared_prefs",
                "修复诊断触发只在宿主进程生效的问题：同一个进程会先拿到宿主 Application，必须用目标应用自己的 ClassLoader",
                "把字节自带的串行执行器也换成标准单线程执行器（图片/播放链路的最后一步常排在这里）",
                "修掉每 2 秒一次、持续 180 秒的内容观测日志：一天能写 5 MB，现在内容区一正常就收工",
            ),
        ),
        ChangelogEntry(
            versionCode = 74,
            versionName = "0.9.10",
            title = "清空数据保留设备身份，避免清完就永久断网",
            highlights = listOf(
                "清空数据会连设备注册凭据一起删掉，应用重新注册时被服务端风控拒绝，主界面会永久显示「网络出错」且点重试无效",
                "现在清空数据只清业务数据（数据库/文件/缓存），保留设备标识（shared_prefs），清完仍能正常联网",
            ),
        ),
        ChangelogEntry(
            versionCode = 73,
            versionName = "0.9.9",
            title = "修重启卡死、启动页兜底提速、清数据后不再自己退出",
            highlights = listOf(
                "重启实例卡死：已在运行的实例再点启动会重复创建进程，两个进程抢同一个虚拟 uid，后者永久卡在 Application.onCreate。现在启动前会先停掉旧进程并等它真正退出",
                "启动页不跳转时的兜底从 25 秒缩短到 10 秒，进主界面不用再干等",
                "清空数据后隐私弹窗会被误关：启动页补救逻辑在有弹窗时不再插手，弹窗交回给用户点",
            ),
        ),
        ChangelogEntry(
            versionCode = 72,
            versionName = "0.9.8",
            title = "实例强制停止 + 单实例运行约束",
            highlights = listOf(
                "新增「强制停止」按钮：杀目标实例所有进程（含 :xxx 子进程），解决普通「停止」点了没反应",
                "同一时间只能运行一个实例：启动新实例前自动强制停掉其它仍在运行的实例",
            ),
        ),
        ChangelogEntry(
            versionCode = 71,
            versionName = "0.9.7",
            title = "补齐应用依赖的初始化，启动页不再白屏、主界面能联网",
            highlights = listOf(
                "启动页白屏根因是应用自身的网络层（RPC）未初始化，引擎现在会在应用初始化后补跑它",
                "同时补跑账号、图片加载（Fresco）与全局 HTTP 客户端，主界面不再显示「网络出错」",
                "跳过沙箱内必然卡死的 Cronet 内核初始化，改走普通 HTTP 通道",
                "修复 Android 17 新增 AIDL 方法导致的崩溃（绑定服务时整个进程被杀）",
                "启动页补救改为「等依赖就绪再执行」，不再依赖固定延时",
            ),
        ),
        ChangelogEntry(
            versionCode = 70,
            versionName = "0.9.6",
            title = "启动页卡死时自动进入主界面",
            highlights = listOf(
                "启动页 25 秒仍未跳转时，自动启动应用的主界面（已用红果验证可正常进入）",
                "启动页初始化抛出的异常现在会写进诊断日志（ROM 屏蔽 logcat 时也能看到）",
                "清数据时一并清除应用的「连续崩溃计数」，避免其卡在安全模式初始化分支",
                "新增调试入口：可指定组件启动（跳过启动页），格式 包名/类名",
            ),
        ),
        ChangelogEntry(
            versionCode = 69,
            versionName = "0.9.5",
            title = "实例清单不再因启动异常而“消失”",
            highlights = listOf(
                "先读本地实例清单并立即上屏，引擎初始化失败也不影响实例可见",
                "引擎初始化/对账异常不再被静默吞掉：写入日志与事件，并在首页说明原因、提供重试",
                "空列表区分「加载中」「引擎失败」「确实没有实例」，不再一律显示“暂无实例”",
                "写清单增加保护：内存清单异常为空时禁止整表覆盖，按并集保留磁盘上的实例",
            ),
        ),
        ChangelogEntry(
            versionCode = 68,
            versionName = "0.9.4",
            title = "弹窗移除调用栈更完整",
            highlights = listOf(
                "去掉反射中间帧，完整记录是谁关闭了弹窗",
            ),
        ),
        ChangelogEntry(
            versionCode = 67,
            versionName = "0.9.3",
            title = "定位弹窗消失问题",
            highlights = listOf(
                "记录窗口被移除的调用栈，用于排查虚拟应用弹窗闪现即消失",
            ),
        ),
        ChangelogEntry(
            versionCode = 66,
            versionName = "0.9.2",
            title = "移除会崩溃的进程名替换方案",
            highlights = listOf(
                "撤掉在 Android 17 上会导致原生崩溃的进程名伪造路径",
                "恢复稳定的启动流程，保留完整诊断信息",
            ),
        ),
        ChangelogEntry(
            versionCode = 65,
            versionName = "0.9.1",
            title = "方法级替换进程名查询",
            highlights = listOf(
                "直接替换系统的进程名查询方法，让应用从第一行代码就拿到正确名字",
            ),
        ),
        ChangelogEntry(
            versionCode = 64,
            versionName = "0.9.0",
            title = "启动最早期解除隐藏 API 限制",
            highlights = listOf(
                "虚拟应用启动前先解除隐藏接口限制，进程名修正才能真正生效",
                "针对启动页停在原地不再前进的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 63,
            versionName = "0.8.9",
            title = "原生方式写入进程名缓存",
            highlights = listOf(
                "改由原生接口写入系统进程名字段，绕开反射限制",
            ),
        ),
        ChangelogEntry(
            versionCode = 62,
            versionName = "0.8.8",
            title = "进程名修正可见化",
            highlights = listOf(
                "原生层进程名修正会回报实际执行结果，便于确认是否生效",
            ),
        ),
        ChangelogEntry(
            versionCode = 61,
            versionName = "0.8.7",
            title = "在原生层改正进程名缓存",
            highlights = listOf(
                "启动最早期就用原生调用覆盖系统缓存的进程名，绕开反射限制",
                "虚拟应用从第一行代码起就认为自己是主进程",
            ),
        ),
        ChangelogEntry(
            versionCode = 60,
            versionName = "0.8.6",
            title = "启动即修正进程身份",
            highlights = listOf(
                "定位到系统缓存的进程名称字段，启动时立刻覆盖",
                "应用不再把自己误判成子进程，启动页可正常跳转",
            ),
        ),
        ChangelogEntry(
            versionCode = 59,
            versionName = "0.8.5",
            title = "修正虚拟应用看到的自身进程名",
            highlights = listOf(
                "先改正进程名再读取，避免应用把自己误判成子进程",
                "已缓存的旧进程名会被自动改写并记录",
                "修复部分应用停在启动页不跳转的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 58,
            versionName = "0.8.4",
            title = "补齐生命周期探针",
            highlights = listOf(
                "改用系统级生命周期回调记录虚拟页面状态，判据更可靠",
                "针对「停在启动页不跳转」继续定位",
            ),
        ),
        ChangelogEntry(
            versionCode = 57,
            versionName = "0.8.3",
            title = "记录活动生命周期回调",
            highlights = listOf(
                "记录虚拟应用每个页面收到的 onCreate/onStart/onResume 等回调，便于定位启动页不跳转",
                "同时记录内容视图的子控件数量，判断页面是否真的渲染完成",
            ),
        ),
        ChangelogEntry(
            versionCode = 56,
            versionName = "0.8.2",
            title = "启动卡顿诊断更细",
            highlights = listOf(
                "启动后分三个时间点抓取全部线程快照，能看到启动页之后卡在哪",
                "进程名修正改为扫描系统字段，兼容不同系统版本",
            ),
        ),
        ChangelogEntry(
            versionCode = 55,
            versionName = "0.8.1",
            title = "底层改进程标识",
            highlights = listOf(
                "改为直接改写系统记录的进程名，使虚拟应用彻底以自身身份运行",
                "修复上一种做法在新系统上无效的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 54,
            versionName = "0.8.0",
            title = "虚拟应用以自身身份运行",
            highlights = listOf(
                "虚拟应用读取到的进程名已是它自己，不再暴露宿主进程",
                "新系统已移除旧的改进程名接口，改用底层替换实现",
                "这是部分应用始终无法完成初始化、界面出不来的关键原因",
            ),
        ),
        ChangelogEntry(
            versionCode = 53,
            versionName = "0.7.9",
            title = "修正虚拟应用的进程标识",
            highlights = listOf(
                "虚拟应用现在对外呈现的进程名是它自己的",
                "修复依赖进程名判断身份的应用始终无法完成初始化的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 52,
            versionName = "0.7.8",
            title = "排查加固应用死锁",
            highlights = listOf(
                "记录进程身份在各个来源里的真实取值，用于定位主进程误判",
                "调试启动支持清空实例数据，排除历史失败累积造成的干扰",
            ),
        ),
        ChangelogEntry(
            versionCode = 51,
            versionName = "0.7.7",
            title = "加固应用卡点定位",
            highlights = listOf(
                "启动卡住时自动记录全部线程状态，可定位到被阻塞的具体子任务",
                "已确认加固应用自身初始化框架在沙箱内会死等，非本程序缺陷",
                "普通应用可正常启动并显示界面",
            ),
        ),
        ChangelogEntry(
            versionCode = 50,
            versionName = "0.7.6",
            title = "修正虚拟进程身份",
            highlights = listOf(
                "虚拟应用现在能拿到的进程名是它自己的，不再暴露宿主进程",
                "修复部分应用因误判「非主进程」而卡在初始化、始终不显示界面的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 49,
            versionName = "0.7.5",
            title = "诊断加固应用初始化",
            highlights = listOf(
                "启动卡住时自动记录全部线程状态，定位到具体被阻塞的子任务",
                "已确认加固应用的组件库解压完整，问题出在其自身初始化流程",
            ),
        ),
        ChangelogEntry(
            versionCode = 48,
            versionName = "0.7.4",
            title = "沙箱启动全面打通",
            highlights = listOf(
                "虚拟应用已能在沙箱内完整启动并显示界面，黑屏问题解决",
                "修复应用上下文创建、原生崩溃、启动卡死三处根因",
                "针对加固类应用增加卡点自诊断，便于定位其初始化阻塞",
            ),
        ),
        ChangelogEntry(
            versionCode = 47,
            versionName = "0.7.3",
            title = "绕过外部存储卡死",
            highlights = listOf(
                "启动阶段不再触发外部存储访问，避免主线程被系统授权流程挂住",
                "先保证启动链路能完整走通，外部存储重定向后续单独处理",
            ),
        ),
        ChangelogEntry(
            versionCode = 46,
            versionName = "0.7.2",
            title = "继续细分启动卡点",
            highlights = listOf(
                "启动过程中的目录准备与规则注册分别记录",
                "用于确认剩余阻塞点落在创建目录还是注册重定向规则",
            ),
        ),
        ChangelogEntry(
            versionCode = 45,
            versionName = "0.7.1",
            title = "修复启动主线程卡死",
            highlights = listOf(
                "修复虚拟应用启动时主线程永久挂住的问题",
                "不再在虚拟进程里查询系统外部存储路径",
                "这一处卡死正是启动流程走不到界面的直接原因",
            ),
        ),
        ChangelogEntry(
            versionCode = 44,
            versionName = "0.7.0",
            title = "继续追启动卡点",
            highlights = listOf(
                "原生崩溃已解决，进程可以稳定存活",
                "启动流程记录细到路径重定向各阶段，定位剩余卡点",
            ),
        ),
        ChangelogEntry(
            versionCode = 43,
            versionName = "0.6.9",
            title = "修复启动时的原生崩溃",
            highlights = listOf(
                "修复虚拟应用启动到一半进程直接消失的问题",
                "新系统上部分内部接口已不可用，取不到时改为安全降级而不是崩溃",
                "这是黑屏真正的原因：进程在创建应用上下文时就被打死了",
            ),
        ),
        ChangelogEntry(
            versionCode = 42,
            versionName = "0.6.8",
            title = "启动链路全程定位",
            highlights = listOf(
                "启动流程改为逐步记录，可精确定位卡在哪个环节",
                "补齐应用上下文加载器与实际 Application 类的可用性检测",
                "异常不再静默，直接写入诊断日志",
            ),
        ),
        ChangelogEntry(
            versionCode = 41,
            versionName = "0.6.7",
            title = "修复应用上下文创建",
            highlights = listOf(
                "修复虚拟应用的应用上下文从未被创建的问题",
                "改用系统自身入口获取虚拟应用的包信息，不再误用宿主的那一份",
                "这是界面无法创建的根因所在",
            ),
        ),
        ChangelogEntry(
            versionCode = 40,
            versionName = "0.6.6",
            title = "停止重启循环",
            highlights = listOf(
                "移除会引发进程反复重启的诊断调用，启动流程恢复稳定",
            ),
        ),
        ChangelogEntry(
            versionCode = 39,
            versionName = "0.6.5",
            title = "回退事务直执行",
            highlights = listOf(
                "移除直接执行启动事务的做法，避免触发进程重启循环",
                "保留启动前置检查的诊断记录",
            ),
        ),
        ChangelogEntry(
            versionCode = 38,
            versionName = "0.6.4",
            title = "直接执行启动事务",
            highlights = listOf(
                "前置检查已确认可用，改为由引擎直接把启动事务交给系统执行",
            ),
        ),
        ChangelogEntry(
            versionCode = 37,
            versionName = "0.6.3",
            title = "绕开系统分发链",
            highlights = listOf(
                "启动事务直接交给系统处理，不再依赖可能被 ROM 改写的消息分发链",
                "修正启动前的前置检查方式，避免误报",
            ),
        ),
        ChangelogEntry(
            versionCode = 36,
            versionName = "0.6.2",
            title = "定位初始化缺失",
            highlights = listOf(
                "记录系统运行时的关键成员是否已初始化，定位界面创建失败的具体原因",
            ),
        ),
        ChangelogEntry(
            versionCode = 35,
            versionName = "0.6.1",
            title = "复现创建前置步骤",
            highlights = listOf(
                "用系统自身入口复现界面创建的前置步骤，记录成功或失败原因",
            ),
        ),
        ChangelogEntry(
            versionCode = 34,
            versionName = "0.6.0",
            title = "回退可疑改动",
            highlights = listOf(
                "去掉启动时指定的窗口尺寸，交回系统默认的窗口决策",
                "去掉大屏强制跟随系统方向的覆盖，按应用自身声明处理",
                "这两处是此前为修黑屏引入的，实测无效且有副作用",
            ),
        ),
        ChangelogEntry(
            versionCode = 33,
            versionName = "0.5.7",
            title = "探测窗口会话",
            highlights = listOf(
                "启动前主动探测窗口会话能否建立，并记录失败原因",
            ),
        ),
        ChangelogEntry(
            versionCode = 32,
            versionName = "0.5.6",
            title = "调试入口可用",
            highlights = listOf(
                "调试启动会等待实例列表就绪，并记录匹配结果",
            ),
        ),
        ChangelogEntry(
            versionCode = 31,
            versionName = "0.5.5",
            title = "启动消息不再被吞",
            highlights = listOf(
                "修复系统启动消息被其他回调拦截、导致虚拟界面永远不创建的问题",
                "记录启动消息的实际处理者，便于后续排查",
            ),
        ),
        ChangelogEntry(
            versionCode = 30,
            versionName = "0.5.4",
            title = "追踪启动消息",
            highlights = listOf(
                "记录主线程消息与框架实现的实时状态，确认启动消息是否到达",
                "记录启动壳是否被加载，区分类未加载与创建中断",
            ),
        ),
        ChangelogEntry(
            versionCode = 29,
            versionName = "0.5.3",
            title = "验证系统创建能力",
            highlights = listOf(
                "改为由启动壳自行拉起虚拟界面，绕开系统侧被替换的启动参数",
                "记录虚拟应用类的真实加载结果，确认类加载是否正常",
            ),
        ),
        ChangelogEntry(
            versionCode = 28,
            versionName = "0.5.2",
            title = "检查启动依赖",
            highlights = listOf(
                "记录虚拟界面创建依赖的安装包路径与类加载结果",
                "用于确认系统侧解析界面时缺失的是哪一项",
            ),
        ),
        ChangelogEntry(
            versionCode = 27,
            versionName = "0.5.1",
            title = "校验框架替换",
            highlights = listOf(
                "注入后立刻回读实际生效的实现，确认替换是否真的发生",
                "替换失败会被记录下来，而不是静默退回系统默认实现",
            ),
        ),
        ChangelogEntry(
            versionCode = 26,
            versionName = "0.5.0",
            title = "记录注入结果",
            highlights = listOf(
                "记录每项框架注入的成败，注入失败不再被静默忽略",
                "重新启用界面创建前的环境检查，避免落到默认实现导致界面起不来",
            ),
        ),
        ChangelogEntry(
            versionCode = 25,
            versionName = "0.4.9",
            title = "定位虚拟界面创建",
            highlights = listOf(
                "在界面创建的每一步打点：实例化、上下文修正、生命周期回调",
                "用于确认虚拟界面到底卡在创建链路的哪一步",
            ),
        ),
        ChangelogEntry(
            versionCode = 24,
            versionName = "0.4.8",
            title = "启动链路打点",
            highlights = listOf(
                "记录虚拟界面被创建的唯一入口：启动参数是否被正确解析",
                "记录进程初始化状态与重启分支，用于定位启动环节中断的位置",
                "新增按包名直接启动实例的调试入口，便于真机自动化验证",
            ),
        ),
        ChangelogEntry(
            versionCode = 23,
            versionName = "0.4.7",
            title = "诊断日志可读",
            highlights = listOf(
                "引擎日志改为多候选目录写入，虚拟进程里也能落到可读位置",
                "诊断页与诊断包会扫描虚拟应用数据目录，不再出现日志为空的情况",
                "启动壳的关键步骤全程兜底，异常不再导致虚拟进程直接崩溃",
            ),
        ),
        ChangelogEntry(
            versionCode = 22,
            versionName = "0.4.6",
            title = "修复 API 37 启动崩溃",
            highlights = listOf(
                "修复 Android 17 上启动虚拟应用必崩的问题（Application 未就绪导致空指针）",
                "这是此前平板黑屏的真正原因：虚拟应用进程在启动阶段就被杀掉",
                "顺带修复 Android 13+ 读取启动参数用了废弃 API 的问题",
            ),
        ),
        ChangelogEntry(
            versionCode = 21,
            versionName = "0.4.5",
            title = "引擎日志跨进程",
            highlights = listOf(
                "修复引擎日志写入失败：引擎跑在虚拟应用进程，静态回调跨进程不共享",
                "改为由引擎直接写日志文件，诊断页按文件读取，能拿到真实现场",
            ),
        ),
        ChangelogEntry(
            versionCode = 20,
            versionName = "0.4.4",
            title = "引擎诊断日志",
            highlights = listOf(
                "诊断页新增「引擎日志」：显示 Activity 启动与窗口添加的现场记录",
                "引擎侧关键路径接入本地日志，绕开部分机型被限制的系统 logcat",
                "记录窗口添加的返回码，可直接看出窗口是否被系统拒绝",
            ),
        ),
        ChangelogEntry(
            versionCode = 19,
            versionName = "0.4.3",
            title = "平板黑屏修复",
            highlights = listOf(
                "修复锁定屏幕方向的应用（如红果）在平板上黑屏的问题",
                "原因：应用声明的方向让系统启用 letterbox 兼容窗口，沙箱窗口在其中拿不到尺寸",
                "大屏设备上改为跟随系统方向，虚拟界面按真实窗口全屏布局",
            ),
        ),
        ChangelogEntry(
            versionCode = 18,
            versionName = "0.4.2",
            title = "修复 Android 17 黑屏",
            highlights = listOf(
                "修复在 Android 17 平板上启动虚拟应用后全黑屏、只剩悬浮按钮的问题",
                "原因：引擎启动虚拟界面时使用了多任务标志，被系统当成“文档/多开窗口”塞进自由窗口",
                "现在只申请单任务启动，并显式要求全屏窗口模式，兼容平板的多窗口策略",
            ),
        ),
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
