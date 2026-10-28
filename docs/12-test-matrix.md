# 测试矩阵：系统版本 × 厂商 ROM × ABI

> 目标：把「能不能装 / 能不能跑」的判定从"真机试错"变成**可穷举的规则 + 断言**。
> 规则本体在 `app/src/main/java/com/adv/runner/apk/CompatibilityRules.kt`（纯函数），
> 用例在 `app/src/test/java/com/adv/runner/apk/CompatibilityMatrixTest.kt`。

## 1. 怎么跑

```bash
# 全部本地单测（纯 JVM，不需要设备/模拟器）
gradle :app:testReleaseUnitTest

# 只看报告
start app/build/reports/tests/testReleaseUnitTest/index.html
```

当前覆盖 **64 个用例 / 7 个测试类**，全部 JVM 侧执行，不依赖 adb。

## 2. 三个维度的取值

### 2.1 系统版本（`sdkInt`）

| 值 | 对应 | 本项目立场 |
|---|---|---|
| 31 | Android 12 | `minSdk`，最低支持 |
| 32–35 | Android 12L – 15 | 支持区间，逐版本跑判定 |
| 36 | Android 16 | 主力验证机（realme RMX3888） |
| 37 | Android 17 | `targetSdk` / `compileSdk` |

判定规则（`MIN_SDK_TOO_HIGH` / `ENGINE_SDK_UNSUPPORTED`）在这 7 个值上各跑一遍，
确保没有"某个版本被漏判"的例外路径。

### 2.2 ABI

| APK 侧 | 设备 `arm64-v8a` only | 设备含 32 位运行时 |
|---|---|---|
| 无原生库（纯 Java） | 可装 | 可装 |
| `arm64-v8a` | 可装 | 可装 |
| `armeabi-v7a` only | **拦（ABI_MISMATCH）** | 视引擎是否放行 32 位 |
| `arm64-v8a` + `armeabi-v7a` | 可装 | 可装 |
| `x86` / `x86_64` | **拦（ABI_MISMATCH）** | 拦 |

「引擎放行 32 位但设备没有 32 位运行时」不单独判：此时 `supportsAnyAbi` 必然为假，
已经命中 `ABI_MISMATCH`，再补一条只会让同一次导入出现两个 BLOCKER。

### 2.3 厂商 ROM（`VendorRom`）

只按 `Build.MANUFACTURER` / `Build.BRAND` 识别，**不读私有 `ro.build.*` 属性**
（隐藏 API 在 Android 9+ 会被拦，反射拿不到稳定结果）。

| 家族 | 命中品牌 | 已知限制（只做提示，不拦流程） |
|---|---|---|
| `XIAOMI` | Xiaomi / Redmi / POCO | 悬浮窗默认关、后台自启动受限 |
| `HONOR` / `HUAWEI` | HONOR / HUAWEI | 同上，另加后台进程清理激进 |
| `OPPO` | OPPO / realme | 同上 |
| `VIVO` | vivo / iQOO | 同上 |
| `MEIZU` | Meizu | 同上 |
| `ONEPLUS` / `SAMSUNG` | OnePlus / Samsung | 省电策略可能限制长时间后台 |
| `OTHER` | 未收录厂商 | 需实测 |
| `UNKNOWN` | 模拟器 / 白牌 | 不编造提示 |

**为什么 ROM 不参与放行判定**：这套系统没有"某 ROM 一定跑不起来"的证据，
把它写进 BLOCKER 只会误伤。ROM 差异真正影响的是悬浮窗/后台存活，属于运行期引导。

## 3. 状态机与非正常退出

| 场景 | 判定 | 用例所在 |
|---|---|---|
| 磁盘 `RUNNING`，引擎说 `STOPPED` 且给了原因 | 采用引擎原因 | `InstanceStateMachineTest` |
| 磁盘 `RUNNING`，引擎只是说停了 | 记 `KILLED_BY_SYSTEM` | 同上 |
| 磁盘 `STARTING` → 进程消失 | 记 `KILLED_BY_SYSTEM` | 同上 |
| 磁盘 `INSTALLING/CLEARING/DELETING` 被打断 | 记 `UNKNOWN` | 同上 |
| 引擎取证失败（过渡态） | 降级 `UNKNOWN_INTERRUPTED` | 同上 |
| 引擎取证失败（`INSTALLED` 等稳态） | 保持磁盘状态 | 同上 |
| 磁盘 `INSTALLED` → 引擎说 `RUNNING` | 非法跳变：留痕但仍以引擎为准 | `canTransition` |

合法转换表在 `InstanceStateMachine.LEGAL_TRANSITIONS`。它不作为流程拦截，
只用来发现「引擎报了个不该出现的状态」——事实来源仍然是引擎。

## 4. 失败路径（这些用例比正常路径更重要）

| 场景 | 期望 | 用例 |
|---|---|---|
| 读实例清单抛异常 | `ReconcileOutcome.loadError`，**不返回空列表** | `InstanceControllerTest` |
| 磁盘确实没有实例 | `nothingToReconcile = true`，不算失败 | 同上 |
| 对账结果落盘失败 | `saveError` + 内存结果照常返回 | 同上 |
| 单条实例取证抛异常 | 记 `failures`，其它实例照常 | 同上 |
| 旧版 `instances.json` 损坏 | 报错 + **原文件不改名** | `LegacyMigratorTest` |
| 单条记录缺字段 | 整批不迁，报错 | 同上 |
| 事件文件有坏行 | 实例照迁，事件报错并保留原文件 | 同上 |
| 写库失败 | 报错 + 原文件保留 | 同上 |
| 实体改了但没升库版本 | `DbSchemaTest` 红 | `DbSchemaTest` |
| 加了 `fallbackToDestructiveMigration` | `DbSchemaTest` 红 | 同上 |

## 5. 设备矩阵（真机）

| 设备 | 系统 | ABI | 用途 |
|---|---|---|---|
| realme RMX3888 | Android 16（API 36） | arm64-v8a only | 解析一致性、签名冲突、启停、保活 |
| 大屏平板（3200×2136） | Android 15 级 | arm64-v8a | 发热、后台线程、长时播放 |

详细实测记录见 [device-matrix.md](device-matrix.md)。

## 6. 还没覆盖的（明确缺口）

1. **真正的 Room 迁移测试**：目前库版本为 1，没有历史迁移可测。首次升版本时
   必须在 `androidTest` 里加 `MigrationTestHelper` 用例（需要连接设备），
   并把新版本 schema 一起入库。`DbSchemaTest` 只是「结构别乱改」的兜底。
2. **厂商 ROM 实机遍历**：ROM 识别只有单元测试，没有在真机上逐个验证悬浮窗行为。
3. **引擎能力自检的真机回归**：`selfCheck()` 需要设备，未纳入自动测试。
