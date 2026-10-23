# 阶段一：技术可行性分析与开源引擎比较

> 核实时间：2026-10-23（本文所有仓库事实均标注了核实来源与核实方式）
> 结论先行：**技术上可行，但"可用的引擎"必须付钱或自己维护，不存在"拿来即用、免费、支持 Android 14+ 的开源引擎"。**

---

## 1. 问题拆解：我们要解决的到底是什么

需求表面是"同一 App 高版本和低版本共存"，拆开后是 4 个独立问题，难度完全不同：

| 编号 | 子问题 | 难度 | 说明 |
|---|---|---|---|
| P1 | 同包名、不同签名的 APK 在**同一台设备**上共存 | 中 | 系统包管理器以 `packageName` 为主键，签名是更新校验的一部分，宿主机不可能同时装两个同包名不同签名的包。只能用**包名空间隔离**（虚拟化 / 改包名重打包 / 多用户或工作资料）绕开 |
| P2 | 低版本 App 在**更高 Android 系统**上正常跑 | 高 | 老 `targetSdk` 的 App 会命中新系统行为变更（前台服务类型、动态广播导出标志、PendingIntent 可变性、存储权限、非 SDK 接口限制等）。这是本项目最大的不确定性来源 |
| P3 | 数据与宿主机原 App 完全分离 | 中 | 虚拟化天然满足（引擎给每个实例独立数据根目录）；改包名重打包也天然满足（不同包名 = 不同 `/data/data`） |
| P4 | 不 Root、不解锁 BL | 已满足 | 三条候选路线都不需要 Root。任何要求 Root / Xposed / 刷机的方案直接排除 |

关键判断：**P1 只是"空间"问题，P2 才是"能不能用"的问题。** 很多方案能做出共存，但目标 App 一登录就崩、一联网就被风控拦。

---

## 2. 三类可行技术路线的定位

| 路线 | 原理 | 是否需要 Root | 签名冲突 | 隔离强度 | 目标 App 改动 | 典型代表 |
|---|---|---|---|---|---|---|
| **A. 应用级虚拟化（沙箱/容器引擎）** | 在宿主 App 进程内重建一套"Android Framework 代理"，把第三方 APK 的四大组件、ClassLoader、资源、IO 重定向到自己的命名空间 | 否 | 引擎内部自己维护包表，**同包名不同签名可共存**（机制上支持，需实测） | 弱—中（同 UID 用户态隔离，不是内核隔离） | 无需改动 | VirtualApp、BlackBox、DroidPlugin(商业版) |
| **B. 改包名重打包** | 反编译 APK，改 `packageName`/`authorities`/`applicationId`，重新签名后像普通 App 一样安装 | 否 | 天然不冲突（不同包名） | 强（系统级 UID 隔离） | **必须改 APK** | apktool + apksigner、LSPatch、各种"分身"工具 |
| **C. 系统级隔离（多用户 / 工作资料 / 虚拟机）** | 用 `DevicePolicyManager` 建工作资料；或用 pKVM/AVF、第三方云手机 | 否（工作资料需用户授权） | 工作资料是**不同 user**，但同包名不同签名**大概率仍被拒**（待实测） | 强—最强 | 无需改动 | Island / Shelter、AVF(Microdroid)、VMOS 云手机 |

**本项目的需求（不改目标 APK、要能登录联网、要数据隔离）指向路线 A。** 路线 B 是唯一"零引擎依赖"的兜底方案，但它违反"低版本 APK 经过修改也不影响使用"的隐含前提（因为修改本身可能破坏 App），且法律风险更高。路线 C 的工作资料对"同包名不同签名"很可能直接失败，AVF 目前跑不了完整 GUI 第三方应用。

---

## 3. 候选引擎逐个核实

### 3.1 VirtualApp（asLody/VirtualApp）

| 项目 | 核实结果 |
|---|---|
| 仓库 | `github.com/asLody/VirtualApp`（Public，11.1k Stars / 3.0k Forks） |
| 许可证 | **仓库内没有 `LICENSE` 文件，也不是 OSI 开源许可**。README 明确写明代码归属"山东盒一网络科技有限公司（原济宁市罗盒网络科技）"，"需要使用 Github 上的代码时，请购买商业授权"，并声明未授权商用/上架将"取证后报警（侵犯著作权罪）或起诉" |
| 维护状态 | README 原文："**Github 上代码已在 2017年12月份停止更新**，商业版代码在持续更新中"。商业版更新日志记录到 **2026-10-21**（746 条），含"兼容最新 Android 17.0""16KB 内存页""ART/JNI 校验""ARM64 指令重定位"等 |
| 宣称支持版本 | README 表格：5.0 / 5.1 / 6.0 / 7.0 / 8.0 / 9.1 / 10.0 / 11.0 / 12.0 / 13.0 / 14.0 / 15.0 / 16.0 / 17.0 均"支持"；但仓库 About 简介仍写 "Support 14.0 in business version"（**自相矛盾**）。均为**厂商自述，未经我方验证** |
| 同包名不同签名 | 机制上支持：引擎自建 PackageSetting 与虚拟 userId，多开就是同包名多 userId；安装时的签名校验由引擎自己实现（不是系统 PMS） |
| 关键 API（公开 README） | `VirtualCore.get().startup()` → `installPackageAsUser(userId, pkg)` → `VActivityManager.get().launchApp(userId, pkg)`；卸载 `uninstallPackageAsUser`、清数据 `clearAppData` |
| 商业版额外能力（自述） | Binder 拦截（不再用动态代理）、Seccomp-BPF 拦截、纯 64 位 App、内置 Xposed、GMS、"不易被杀毒误报"、700+ 修复 |
| 风险 | ①**必须付费**，且是商业授权合同（需法务审阅授权范围/分发权/源码交付/二次开发归属）；②开源版停留在 Android 8/9 时代，**对本项目没有实用价值**；③价格、合同条款、是否允许公开分发到应用商店均需向厂商确认（**待核实**） |

**判断：唯一有"Android 13–17 可用"证据链的路线，但代价是钱 + 合同，不是技术问题。**

### 3.2 BlackBox（FBlackBox/BlackBox）

| 项目 | 核实结果 |
|---|---|
| 仓库 | `github.com/FBlackBox/BlackBox`（2.6k Stars / 722 Forks / 285 Commits，包名 `top.niunaijun.blackboxa`） |
| 许可证 | **Apache-2.0**（`2.1.0` tag 根目录下 `LICENSE` 存在，版权声明 "Copyright 2022 BlackBox"），代码源自 VirtualApp + VirtualAPK + BlackReflection + FreeReflection |
| 维护状态 | **上游已实质死亡**：默认分支的 README 已被替换为作者的一句停维声明（"this project affects so many innocent developers. So I decide to dissolve the telegram group and delete this project."），仓库文件列表只剩 `.github/.gitattributes/.gitignore/README.md`。**源码只存在于历史 tag**：最新 tag `2.1.0` 提交日期 **2022-04-18** |
| 宣称支持版本 | `2.1.0` README：Android **5.0–12.0**，不支持 4.x；分 32 位/64 位两个 app 产物；建议把 targetSdkVersion 降到 28 以下以获得更好兼容性。**无 Android 13+ 官方支持声明** |
| 结构 | `app`（UI）+ `Bcore`（核心引擎） |
| 同包名不同签名 | 机制同 VirtualApp 系（自建包表 + 虚拟 userId）。**未实测** |
| 社区分支 | `pulert/NewBlackbox`（fork 自 `ALEX5402/NewBlackbox`）：保留 Apache-2.0 与 "Copyright 2022 BlackBox"，README 宣称 Android 5.0–15.0，但 `minSdk 24 / compileSdk 35 / targetSdk 34`、`versionName 2.0-r1beta`、该 fork 仅 13 commits、根目录堆了 20+ 个 `*_FIX.md` 补丁说明、版本号前后矛盾（2.0-r1beta vs RELEASE_NOTES 2.4.0）。**只能当参考实现读，不能当生产依赖** |
| 风险 | ①停在 Android 12，目标机若是 13/14/15/16 需要自行适配（Binder 拦截、hidden API、64 位 only、16KB 页）；②作者已退场，无上游修复；③"源码可得 + Apache-2.0"是它最大价值——**适合做零成本的可行性探针和自我维护的起点** |

### 3.3 DroidPlugin（Qihoo360/DroidPlugin）

| 项目 | 核实结果 |
|---|---|
| 仓库 | `github.com/Qihoo360/DroidPlugin`（2.1k Stars / 149 Commits，未归档，master 单分支） |
| 许可证 | **LGPL-3.0** |
| 维护状态 | 开源仓库代码为 2016 年前后的插件化框架；README 主体是**商业产品 360DroidPlugin** 的宣传（宣称"适配 Android 14""鸿蒙 HarmonyOS 4.0""支持多款加固方案"等），更新说明到 2024 年 3 月上旬——**这些是商业产品的能力，不等于开源仓库代码的能力** |
| 宣称支持版本 | README **没有**给开源版任何版本矩阵 |
| 主要问题 | ①LGPL-3.0 对闭源分发有传染性约束（需动态链接边界论证 + 提供可替换的库源码），商业落地要先过法务；②开源代码年代久远，ART 之后四大组件 Hook 方案基本不可用于 Android 10+ |

**判断：开源部分是历史遗产；商业产品要单独付费且不开放源码。对"可自行维护"的目标不友好。**

### 3.4 VirtualAPK（didi/VirtualAPK）

Apache-2.0 的**插件化框架**，不是应用虚拟化：它要求插件在**编译期**依赖宿主提供的接口/壳工程，目标是"宿主 App 动态加载自己的业务模块"。**无法直接装载任意第三方 APK**，且已多年停更（本次未取到权威 pushed_at，标注**待验证**）。**不适用，仅作为"宿主-插件"思路的参考。**

### 3.5 twoyi（twoyi/twoyi）

| 项目 | 核实结果 |
|---|---|
| 许可证 | **MPL-2.0** |
| 原理 | App 级 Android 容器：以普通 App 身份跑一套近乎完整的内部 Android 系统（`rootfs.7z` 打进 APK 的 assets，Rust 写 `libtwoyi.so`，虚拟化 HAL） |
| 支持版本 | 宿主 **Android 8.1–12**；容器内部只有 **Android 8.1**（Android 10 是"将要支持"）；**仅 arm64-v8a** |
| 状态 | **2023-04-20 被作者归档（Archived）**，README 首行写明项目已终止（"complexity high, no revenue"）；内部 ROM **未完全开源**，需从官方 Release APK 里提取 `rootfs.7z` 才能构建 |
| 对本案价值 | 思路（整系统容器 = 彻底绕开 targetSdk/签名问题）值得借鉴，但**内部系统只有 8.1、宿主上限 12、已停维、ROM 未开源**，不可作为交付基础 |

### 3.6 AVF / pKVM / Microdroid（Android Virtualization Framework）

AOSP 官方文档（`source.android.com/docs/core/virtualization`，页面更新于 2025-03-04）核实到的事实：

- 只有 **ARM64** 设备支持；Hypervisor 是 **pKVM**，配套 `pvmfw`、`crosvm`、**Microdroid**（Google 提供的**裁剪版 Android**，不是完整 Android 框架）。
- `VirtualizationService` 的 Java API **是可选的、不在 `bootclasspath` 中**，即不属于常规公开框架 API；原生侧只暴露 **NDK 的一个子集**。
- 概览页**完全没有提到显示输出、GPU、SurfaceFlinger**；文档强调其定位是"以安全为导向的用例"。

**结论：AVF 不是"能跑第三方 Android GUI 应用的虚拟机"，它是给 OEM / 安全计算场景用的隔离执行环境。** 想在普通 App 里用它跑一个微信低版本，目前没有证据支持；把它当"强隔离参考架构"可以，当实现路线不行。

---

## 4. 对比矩阵（按本项目需求加权）

| 维度 | VirtualApp 商业版 | BlackBox 2.1.0 | NewBlackbox 等 fork | DroidPlugin | twoyi | AVF |
|---|---|---|---|---|---|---|
| 许可证可商用 | 需付费授权（非开源许可） | Apache-2.0（可商用，需保留声明） | Apache-2.0（但血缘与质量存疑） | LGPL-3.0（有传染约束） | MPL-2.0 | AOSP |
| 源码可获取 | 付费后交付 | 历史 tag 可取（默认分支已清空） | 可取 | 可取（旧） | 可取（ROM 除外） | 是 |
| 活跃维护 | 商业版活跃（2026-10 日志） | 已停（2022-04） | 半死（beta、补丁式） | 开源部分停 | 已归档 | 平台侧 |
| 官方宣称最高 Android | 17.0（自述） | 12.0 | 15.0（自述，配置矛盾） | 开源版无声明 | 宿主 12 | — |
| 同包名不同签名共存 | 支持（机制） | 支持（机制，未实测） | 同左 | 同左 | 整机隔离，天然支持 | 不适用 |
| 第三方 APK 免安装运行 | 是 | 是 | 是 | 是（旧系统） | 需装入容器系统 | 否（无 GUI） |
| 64 位 / 纯 64 位 App | 商业版支持 | 部分（分 32/64 产物） | 分架构构建 | 否 | arm64 only | — |
| GMS / Google 登录 | 商业版宣称"完美" | 有限 | 有限 | 无 | 取决于内部 ROM | — |
| 本项目的可用性 | **可用（付费）** | **探针 + 自维护起点** | 不建议 | **不建议** | 不建议 | **不可用** |

---

## 5. 推荐路线（三档，按预算决定，不是按技术偏好）

### 路线 A（推荐给"要交付生产可用产品"）：BlackBox 2.1.0 做探针 → 决策 → 购买 VirtualApp 商业授权

1. **第 1 周（零成本）**：拿 `FBlackBox/BlackBox` 的 `2.1.0` tag（Apache-2.0、源码完整）做成探针 APK，在目标机型（Android 13/14/15/16 各一台）上跑"同包名不同签名双实例"的 14 项验证（见 `05-spike-prototype.md`）。
   - 探针**不需要**先写 Compose UI，只要能 import + launch + 看 logcat。
   - 结果 3 种：全绿 / 部分绿 / 全红。
2. **第 2 周**：若探针在 Android 13 及以下可用 → 可以走路线 B 自维护；若只在 14+ 失败但业务必须支持 14+ → 向罗盒索取商业版授权报价与试用包，用同一套 14 项用例复测。
3. 商业版通过 → **引擎层只依赖 `VirtualizationEngine` 接口**，把 VA 适配器做成唯一实现，其余全部自研（UI/DB/诊断/导入/策略）。这样即使将来换引擎，上层零改动。

> 关键：**先花一周证明引擎能跑，再决定花钱或花人力。** 不要让 UI 先跑起来掩盖引擎不可用。

### 路线 B（推荐给"预算为 0、能接受自维护"）：BlackBox 2.1.0 自维护分支

- 合法（Apache-2.0），可自由修改与分发（需保留 LICENSE/NOTICE、声明修改、不借用其商标）。
- 代价：Android 13+ 适配是**真正的工程活**（非 SDK 接口隐藏、Binder 代理、组件导出标志、前台服务类型、16KB 页、纯 64 位），且没有上游支持。工作量评估：**人月级，且随系统升级持续投入**。
- 现实建议：只支持"你自己实测通过的机型矩阵"，在 UI 上明确写"未适配机型不保证运行"。

### 路线 C（兜底，不依赖任何引擎）：改包名重打包 + 系统原生安装

- 用 `apktool` 反编译改 `packageName`、`android:authorities`、`applicationId`、SDK 初始化里的包名，`apksigner` 用自签证书重签，然后当普通 App 安装。
- 优点：0 引擎风险、系统级 UID 隔离、数据绝对分离、性能原生。
- 缺点：**必须修改目标 APK**；加固/签名校验/`SafetyNet`/`Play Integrity`/微信登录 SDK（绑定包名+签名）会直接失败；需要"每个 App 每个版本"单独适配，不可规模化；修改第三方应用可能违反其服务条款。
- 定位：**单款 App 的临时方案，不是产品方案。**

### 明确不推荐

- `DroidPlugin` 开源代码（旧 + LGPL 约束）。
- `NewBlackbox` 一类"不知名 fork"（质量不可控、无上游、许可证血缘混乱）。
- `AVF`（不跑 GUI 应用）。
- `twoyi`（停维 + Android 8.1）。
- 任何"Xposed / 太极 / 免 Root Hook 框架"作为主引擎（它们解决的是 Hook，不解决 APK 装载与包名空间）。

---

## 6. 主要风险（按"会让项目失败"的程度排序）

| 级别 | 风险 | 具体表现 | 缓解 |
|---|---|---|---|
| **R1 致命** | 目标 App 在新系统上跑不起来 | 老 targetSdk 命中新系统行为变更；加固壳检测虚拟环境后自杀；App 检测 `multi-user`/无 launcher/进程名异常 | 阶段 1 探针先验证**真实目标 App**，不是只验证自建 Demo；准备"降级 SDK/换低系统真机"的话术 |
| **R2 致命** | 签名变更导致功能不可用 | 微信/QQ/支付宝登录 SDK 绑定签名；地图 SDK key 绑定 SHA1；Firebase/Google 登录校验签名；App 自带签名自校验 | 明确告知用户"此功能不可用"，UI 上做兼容性标记；提供"导入的是原签名包"检测 |
| **R3 高** | 服务端版本/环境风控 | 服务器拒绝老版本（强制升级）；设备指纹突变触发风控、封号 | 不做任何设备指纹伪造（伪造反而更危险）；UI 提示风险；不承诺"账号安全" |
| **R4 高** | 许可证与合规 | VirtualApp 未授权即商用 = 刑事风险；LGPL 传染；Apache-2.0 需保留声明；应用商店对"应用克隆/沙箱"类目政策不明 | 见 `08-compliance.md`，授权签字前不写任何一行 VA 代码 |
| **R5 中** | 32 位 App 无法运行 | Pixel 7+ 与部分 Android 15 设备已取消 32 位支持；纯 64 位引擎跑不了 32 位 so | 导入时检测 `nativeCode` ABI，直接判"不兼容"并说明原因 |
| **R6 中** | 16KB 页大小 | Android 15 起部分设备 4KB→16KB 页，引擎 native so 未对齐会加载失败 | 适配时用 `zipalign -P 16` + so 重编，或限定机型 |
| **R7 中** | 通知/前台服务 | Android 13 需 `POST_NOTIFICATIONS`；Android 14 强制声明 FGS type；引擎内的通知需代理 | 引擎能力探测；不支持时 UI 明确降级（"后台保活可能失败"） |
| **R8 中** | 进程被杀 / 状态恢复 | 引擎多进程架构被厂商省电策略杀掉；重启后实例状态与实际不符 | 启动时做"状态对账"（`state` 表 + `ProcessLifecycleOwner`），见 `03-data-model.md` |
| **R9 低** | 性能 | 引擎内启动慢 20%–100%、内存翻倍是常态 | 只做实测基线，不写营销数字 |

---

## 7. 本次核实中"未验证"的部分（必须由阶段 2 实测补上）

1. BlackBox 2.1.0 在 **Android 13 / 14 / 15 / 16** 上能否 startup 且安装/启动成功。
2. 同包名、不同签名的两个 APK 在同一引擎内**同时安装 → 同时运行**（而非顺序覆盖）。
3. 目标 App 的**登录、联网、WebView、JNI、通知、后台 Service** 在引擎内的真实表现。
4. 工作资料（Island/Shelter）方案下"同包名不同签名"是否被系统拒绝（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
5. VirtualApp 商业版的**报价、合同条款、是否允许公开上架**。
6. 各大应用市场（Google Play / 国内商店）对"运行第三方 APK 的沙箱应用"的具体审核口径。

> 本文不提供任何性能数字、成功率或"支持 Android 16"的结论，因为这些必须由真机实测建立。
