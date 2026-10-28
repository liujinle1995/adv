# 应用虚幻器（ADV）

在**非 Root** 的 Android 设备上，于应用内部运行**同包名、不同签名**的第三方 APK —— 让「商店安装版」和「自己签名的版本」在同一台机器上共存。

| | |
|---|---|
| 版本 | **0.9.27**（versionCode 91） |
| 系统 | `minSdk 31` / `targetSdk 37` / `compileSdk 37`（Android 12 – 17） |
| ABI | 仅 `arm64-v8a` |
| 技术栈 | Kotlin 2.0.21 · Jetpack Compose (Material 3) · Room 2.6.1 · BlackBox 2.1.0（自维护分支） |
| 构建 | AGP 8.13.2 · Gradle 8.13 · JDK 17 · NDK 27.0.12077973 |

---

## 能做什么

- **导入 APK**：解析包名、版本、签名摘要、SDK 区间、ABI、权限、组件、SHA-256，不依赖任何隐藏 API
- **实例管理**：为每个 APK 建独立实例（独立数据目录、独立虚拟 userId），同包名异签名与系统已装版本并存
- **运行控制**：启动 / 停止 / 强制停止 / 删除；同一时刻只允许一个实例运行，避免多实例互抢资源
- **数据管理**：缓存清理、清空数据（保留设备身份，避免清完断网）、备份与导入备份、迁移旧版数据
- **诊断**：运行日志（强制脱敏 + 滚动清理）、一键导出虚拟应用自身日志、引擎能力详情、隐藏 API 探针
- **悬浮返回按钮**：虚拟应用内部没有返回键时，用宿主悬浮按钮退栈
- **权限指引 / 设置 / 更新记录 / 开源声明**：悬浮窗与通知权限引导、主题切换、版本变更记录、第三方许可清单

## 明确的边界

1. 这是**用户态应用级虚拟化**，不是虚拟机、不是独立内核；虚拟 App 与宿主**同 UID**，无法防御恶意 App 的越权攻击。
2. 实例**数据**与系统已装版本隔离（不同目录树），但**不承诺内核级隔离**。
3. 签名变更会让绑定签名的 SDK 功能（微信/支付宝登录、地图、部分推送）失效——这是**引擎之外**的限制。
4. **不做**设备指纹伪造、不做反检测绕过、不承诺「防封号」。用途限于运行自己有权使用的应用。
5. 没有实测数据前，不写性能与兼容性数字。

---

## 架构

```text
UI (Compose：3 个主 Tab + 8 个二级页)  ──  AppState / Screen 状态流
        │
core/control/InstanceController  ──  启停收敛、单实例约束、事件落库
        │
data/ (Room: 实例表 / 事件表 / DAO / 迁移)
        │
engine/api/VirtualizationEngine   ──  统一契约（真实引擎 / Mock 引擎可互换）
        │
engine/blackbox/BlackBoxBootstrap + BlackBoxEngineAdapter
        │
third_party/blackbox（Bcore 引擎，7 个 Gradle 子模块）
```

自有代码只依赖 `VirtualizationEngine` 接口，引擎实现可替换；`MockEngine` 用于不加载 APK 的流程自测。

```text
app/src/main/java/com/adv/runner/
├── MainActivity.kt / AdvApp.kt      # 入口与全局状态宿主
├── apk/                             # APK 解析（含二进制 manifest 兜底）、副本管理
├── core/model/                      # 领域模型、状态机、运行事件
├── core/control/                    # 实例控制器（启停/单实例/异常退出判定）
├── core/store/                      # 实例与事件存储门面
├── data/db/                         # Room 实体、DAO、迁移
├── engine/                          # 引擎契约 + BlackBox 适配 + Mock
├── diagnostics/                     # 日志脱敏、隐藏 API 探针
└── ui/                              # 3 主 Tab（首页/数据/设置）+ 8 二级页 + 主题 + 尺寸令牌 + 悬浮按钮
```

---

## 引擎层：为什么必须自维护一份 BlackBox

BlackBox 2.1.0（Apache-2.0）上游停更在 Android 12，把它拉到 Android 12–17 能**真正跑起来**，靠的是一批针对宿主的兼容补丁。这些补丁是本仓库最有价值的部分，全部在 `third_party/blackbox/Bcore/.../AppInstrumentation.java`，也都有独立的验证记录：

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | 图片永远空白、翻页无数据、视频不播 | bytex 插桩把线程包成不执行的 `ThreadProxy`，线程池「只收不做」（`poolSize=0 / completed=0`） | 启动最早处 `setEnableType(0)` 关闭线程代理 |
| 2 | 视频详情页零请求、RxJava 全系列卡死 | `RxJavaPlugins` 的调度器被宿主接管，`Schedulers.from` 又不支持延时任务 | 换成全新构造的原生调度器实例注入 |
| 3 | 封面/图片请求全部排队不发 | 字节 `SsHttpExecutor` 背后的 dispatcher 池 `size=8 active=8 done=0` | 替换 dispatcher 池；必要时用 Proxy 接管 `jb0.a` 请求队列（0.9.24 起默认关闭，原队列已能自跑） |
| 4 | 大屏播放时整机 98% CPU、结温 100℃+ | 沙箱进程落在 `cpu:/vip` cgroup 永不被降级，埋点/监控/预加载线程全速空转 | 接管池加并发上限 + 旁路线程降级（UI/渲染/解码/音频/binder/GC 受保护） |

补丁背后有一套可复用的诊断方法（线程增量采样、`/proc/<pid>/task/*/comm`、`dumpsys thermalservice`、cgroup 对比、请求级埋点），写在 `docs/` 与提交历史里。

---

## 构建

前置：**JDK 17**、Android SDK（platform `android-37`、build-tools 37）、**Gradle 8.13**。

```bash
# 1) 指向本机 SDK（local.properties 不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 仓库未提交 Gradle Wrapper，首次可先生成，或直接用本机 Gradle 8.13
gradle wrapper --gradle-version 8.13

# 3) 出包
JAVA_HOME=/path/to/jdk-17 gradle assembleRelease

# 4) 安装
adb install -r app/build/outputs/apk/release/app-release.apk
```

**为什么 release 也复用 debug 签名**：release 的 `debuggable=false` 会关掉进程级 CheckJNI，避免虚拟化场景下每帧 JNI 调用被检查拖垮主线程导致 ANR；同时便于直接覆盖安装到测试机。要发布到商店请替换为自己的正式签名。

---

## 已验证环境

| 项 | 值 |
|---|---|
| 机型 | realme **RMX3888** |
| 系统 | **Android 16（API 36）**，安全补丁 2026-08-01 |
| ABI | 仅 `arm64-v8a`（无 32 位运行时） |
| 内存页 | 4096（4KB） |

已验证：APK 解析结果与 `apksigner` 完全一致、系统级签名冲突正常暴露（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）、实例安装/启动/后台保活/通知、危险权限拦截、大屏设备播放与发热实测。详见 [docs/device-matrix.md](docs/device-matrix.md)。

---

## 文档

`docs/` 下是设计草案、可行性核实与实测记录，**属于决策留痕**，与当前代码不一定同步；接口与行为以 `app/` 源码为准。

| 文档 | 内容 |
|---|---|
| [01-feasibility.md](docs/01-feasibility.md) | 技术可行性、6 个引擎方案逐个核实（含来源与时间） |
| [02-architecture.md](docs/02-architecture.md) | 分层图、时序图、进程模型、存储布局、错误降级模型 |
| [03-data-model.md](docs/03-data-model.md) | 领域模型、Room 建表、状态机 |
| [04-engine-api.md](docs/04-engine-api.md) | `VirtualizationEngine` 契约、能力三态、引擎映射要点 |
| [05-spike-prototype.md](docs/05-spike-prototype.md) | 最小验证原型与 14 项判定、已知失败模式 |
| [06-roadmap.md](docs/06-roadmap.md) | 阶段任务清单与门禁 |
| [07-test-plan.md](docs/07-test-plan.md) | 验收标准与测试用例、性能基线方法 |
| [08-compliance.md](docs/08-compliance.md) | 许可证义务、商店与隐私合规检查单 |
| [09-risks.md](docs/09-risks.md) | 硬限制清单、风险登记册、ADR、止损条件 |
| [10-setup.md](docs/10-setup.md) | 环境初始化、构建命令、签名与重签名 |
| [11-android17.md](docs/11-android17.md) | API 37 适配清单（工具链、行为变更） |
| [device-matrix.md](docs/device-matrix.md) | 真机基线、解析一致性、签名冲突取证 |

---

## 致谢与许可

- 引擎基于 **BlackBox 2.1.0**（Apache-2.0），源码与许可证见 [third_party/blackbox](third_party/blackbox)（含 `LICENSE`）。本仓库对引擎源码有修改，按 Apache-2.0 要求保留原版权与许可声明。
- 构建镜像使用阿里云 Maven 镜像与 jitpack（国内网络下的可用性兜底）。
- 本仓库自有代码暂未附加开源许可证。
