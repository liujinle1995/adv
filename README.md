# ADV 本地应用虚拟运行器

非 Root Android 设备上，在应用内部运行**同包名、不同签名**的第三方 APK，与宿主已安装版本共存。

> 当前状态：**阶段 3 正式框架已完成**（Room 数据层 + 六页面 UI + 引擎接入 + 签名冲突流 + 数据管理 + 诊断 + 悬浮返回按钮）。
> 引擎已在真机验证：安装 + 启动成功、同包名异签名可并存、危险权限拦截生效、后台保活/通知正常。
>
> 支持范围：**API 31–37（Android 12–17）**，`targetSdk 37` / `compileSdk 36` / `minSdk 31` / 仅 arm64-v8a。
>
> 真机基线：realme RMX3888 / **Android 16（API 36）** / **仅 arm64-v8a** / 4KB 页。
> 已在该机验证：APK 解析（包名/版本/签名/SHA-256 与 `apksigner` 完全一致）、系统级签名冲突（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> 记录见 [docs/device-matrix.md](docs/device-matrix.md)。

---

## 一句话结论

**技术上可行，但没有"免费 + 支持 Android 14/15/16 + 已验证"的开源引擎。**
唯一有 Android 13–17 证据链的是 VirtualApp **商业版（需付费授权）**；免费可用的 BlackBox 2.1.0（Apache-2.0）停在 Android 12 且上游已停更，只能作为**零成本可行性探针**或**自维护起点**。
详见 [docs/01-feasibility.md](docs/01-feasibility.md)。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-feasibility.md](docs/01-feasibility.md) | **技术可行性分析 + 6 个引擎/方案逐个核实 + 推荐路线 + 主要风险**（含核实时间与来源） |
| [02-architecture.md](docs/02-architecture.md) | 架构图（分层图/时序图/进程模型）、模块目录、存储布局、错误与降级模型 |
| [03-data-model.md](docs/03-data-model.md) | `ApkInfo`/`RuntimeInstance`/`RuntimeState`/`EngineCapabilities`/`EngineResult` + Room 建表 SQL + 状态机 |
| [04-engine-api.md](docs/04-engine-api.md) | `VirtualizationEngine` 接口契约、能力三态、BlackBox/VirtualApp 映射要点、Gateway 对账规则 |
| [05-spike-prototype.md](docs/05-spike-prototype.md) | **阶段 2 最小验证原型**：Probe App 构造、14 项判定、真实 App 验证、决策树、已知失败模式 |
| [06-roadmap.md](docs/06-roadmap.md) | 阶段 0–5 任务清单、门禁、甘特图、优先级 |
| [07-test-plan.md](docs/07-test-plan.md) | 验收标准 AC1–AC8 + 60+ 测试用例 + 性能基线方法 |
| [08-compliance.md](docs/08-compliance.md) | 许可证风险、Apache/LGPL/GPL 义务、商店与隐私合规、发布检查单 |
| [09-risks.md](docs/09-risks.md) | 硬限制清单、风险登记册、4 个备选方案、ADR、止损条件 |
| [10-setup.md](docs/10-setup.md) | Android Studio 初始化、构建命令、真机准备、签名与重签名命令 |
| [device-matrix.md](docs/device-matrix.md) | **真机基线与实测记录**：设备硬约束、AC1 解析一致性、签名冲突取证、待补设备 |

---

## 代码骨架（阶段 2 起点）

```text
app/src/main/java/com/adv/runner/
├── MainActivity.kt                     # Compose 单屏：导入 → 解析 → 建实例 → 启停
├── core/model/Models.kt                # 领域模型 + EngineResult/错误码
├── apk/ApkAnalyzer.kt                  # 真实解析：包名/版本/签名摘要/SDK/ABI/权限/组件/SHA-256
├── engine/api/VirtualizationEngine.kt  # 引擎契约（不含任何引擎实现细节）
├── engine/mock/MockEngine.kt           # 模拟引擎（不加载 APK，只跑流程）
└── diagnostics/RunLogger.kt            # 本地日志 + 强制脱敏 + 滚动清理
```

**已实现且可独立验证**：APK 元数据解析（公开 API，无隐藏 API）、SHA-256、兼容性判定、实例状态机雏形、日志脱敏。

**构建状态：已在本机实际编译通过**（Gradle 8.9 + AGP 8.7.3 + JDK 17.0.20，`BUILD SUCCESSFUL in 4m 17s`），产物 `app/build/outputs/apk/debug/app-debug.apk`（9.5 MB）。真机运行验证待接入设备后进行。

**尚未实现（有意为之）**：真实引擎适配、Room 落库、多页面 UI、备份、多实例管理。这些在 `docs/06-roadmap.md` 的阶段 2/3 里，前置条件是阶段 1 的门禁通过。

---

## 快速开始

```bash
# 1. 用 Android Studio 打开本目录（会自动生成 Gradle Wrapper）
# 2. 或命令行（需本机已装 Gradle 8.9+）
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Android SDK（`compileSdk 36`，platform `android-36`）、`minSdk 31`。
详见 [docs/10-setup.md](docs/10-setup.md)。

---

## 明确的边界（不要对外说错）

1. 这是**用户态应用级虚拟化**，不是虚拟机、不是独立内核；虚拟 App 与宿主**同 UID**，无法防御恶意 App 的越权攻击。
2. 引擎内应用的**数据**与宿主机原应用隔离（不同目录树），但我们**不承诺内核级隔离**。
3. 签名变更会让绑定签名的 SDK 功能（微信/支付宝登录、地图、部分推送）失效；这是**引擎之外**的限制。
4. 我们**不做**设备指纹伪造、不做反检测绕过、不承诺"防封号"。
5. 没有实测数据前，性能与兼容性一律不写具体数字。

---

## 下一步需要你提供/决定的三件事

1. **一台真机**（Android 13/15/16 中至少两种，最好是你的主力机型）——阶段 1 的 14 项验证必须真机跑。
2. **目标低版本 APK**（用于 R1–R7 真实场景验证）；若涉及账号，请用测试账号。
3. **预算决策**：若探针证明 BlackBox 在目标系统不可用，是否采购 VirtualApp 商业授权？（这条决定排期，采购周期不可压缩）
