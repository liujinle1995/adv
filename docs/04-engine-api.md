# 引擎适配接口（VirtualizationEngine）

## 1. 设计原则

1. **接口是"管理器契约"，不是"引擎能力清单"。** 接口里出现的每个方法，引擎都可能答 `UNSUPPORTED_BY_ENGINE`。UI 必须能展示"不支持"，而不是崩溃或假装成功。
2. **接口不暴露引擎 SDK 类型。** 方法参数只用本项目模型（`InstallRequest`/`RuntimeState`），否则上层被迫依赖具体引擎。
3. **能力先于调用。** 所有业务动作前先读 `capabilities()`；业务层据此决定按钮是否可用、话术怎么写。
4. **失败必须可解释。** 每个 `Failure` 带错误码 + 中文消息 + 诊断 ID（指向 `run_event` 与日志文件）。
5. **单引擎单实例。** 一个进程只允许一个引擎被 `initialize`（引擎 SDK 通常是全局单例，重复初始化必崩）。

---

## 2. 接口定义

```kotlin
package com.adv.runner.engine.api

interface VirtualizationEngine {

    val id: String                 // "blackbox" / "virtualapp" / "mock"
    val displayName: String

    /** 幂等。重复调用返回同一份 capabilities。失败后允许再次调用（例如 so 加载失败后重试）。 */
    suspend fun initialize(context: EngineContext): EngineResult<EngineCapabilities>

    /** 已初始化则返回缓存值；未初始化返回 Failure(ENGINE_NOT_AVAILABLE)。 */
    fun capabilities(): EngineCapabilities?

    /**
     * 安装 APK 到指定虚拟 userId 槽位。
     * 语义：不做"覆盖系统已装包"的动作；只在引擎命名空间内落包。
     * 若该槽位已存在同包名且签名不同 → 必须返回 SIGNATURE_MISMATCH（由上层决定是否新建槽位）。
     */
    suspend fun installApk(request: InstallRequest): EngineResult<InstalledApp>

    /** 发起启动。返回 Success 只代表"已受理"，真实运行态靠 queryState 判定。 */
    suspend fun launch(instanceId: String, options: LaunchOptions = LaunchOptions()): EngineResult<Unit>

    /** 停止应用（尽力优雅停止，超时后强杀该实例的 client 进程）。 */
    suspend fun stop(instanceId: String): EngineResult<Unit>

    /** 查询真实状态：必须基于进程/引擎状态，而不是上次调用的缓存。 */
    suspend fun queryState(instanceId: String): EngineResult<RuntimeProbe>

    /** 清除应用数据（保留包，回到首次启动状态）。 */
    suspend fun clearData(instanceId: String): EngineResult<Unit>

    /** 卸载包并从引擎包表移除；数据根由上层删除（或在实现里一并删除并声明）。 */
    suspend fun uninstall(instanceId: String): EngineResult<Unit>

    /** 引擎自检：so 加载、启动耗时、存储可写、关键隐藏 API 可用性。写入诊断。 */
    suspend fun selfCheck(): EngineResult<EngineSelfCheck>

    /** 采集引擎进程 logcat 片段，返回文件路径。用于崩溃/ANR 归因。 */
    suspend fun captureLogs(instanceId: String, maxBytes: Long = 512 * 1024): EngineResult<String>

    /** 释放引擎资源（进程退出前调用；不保证一定被系统调用）。 */
    fun release()
}

data class EngineContext(
    val appContext: Context,
    val storageRoot: File,            // files/engine/<engineId>
    val logger: RunLogger,
    val deviceAbis: List<String>,
    val deviceSdk: Int
)

data class InstallRequest(
    val instanceId: String,
    val apkPath: String,             // 私有目录里的只读副本
    val sha256: String,
    val packageName: String,
    val virtualUserId: Int,
    val requireSignatureMatch: List<String>? = null   // 期望签名摘要；不匹配即失败
)

data class InstalledApp(
    val packageName: String,
    val installedVersionCode: Long,
    val virtualUserId: Int,
    val dataRoot: String,
    val signatureSha256: List<String>
)

data class LaunchOptions(
    val launchActivity: String? = null,   // null = 用 launcher activity
    val extraArgs: Map<String, String> = emptyMap()
)

data class RuntimeProbe(
    val state: RuntimeState,
    val pid: Int?,
    val processName: String?,
    val evidence: String,             // 状态判定依据，写日志用，例如 "engine.isRunning=true; pid=12345 alive"
    val exitReason: ExitReason? = null
)
```

### 2.1 为什么 `launch` 不返回状态

引擎的 `launchApp()` 通常是"发一个 Intent 给引擎 server"，server 异步 fork client 进程。若同步返回 `RUNNING`，会出现"UI 显示运行中但进程已被杀"的经典假状态。因此**契约上把"受理"和"运行"分开**，由 `queryState` 提供证据链。

### 2.2 `queryState` 的证据要求

实现方必须按以下优先级取证，并在 `evidence` 里写清楚：

1. 引擎 SDK 自身的"是否运行"查询（若存在，如 `isAppRunning(pkg, userId)`）；
2. `ActivityManager.getRunningAppProcesses()` 中本 App 的 client 进程（Android 5.1+ 仅返回自己进程，够用）；
3. 已知 pid 的 `/proc/<pid>` 存在性；
4. 以上全不可用 → 返回 `UNKNOWN`，**不要猜**。

---

## 3. 引擎能力矩阵与"诚实标注"要求

`EngineCapabilities` 中每个字段必须是三态（`SUPPORTED / PARTIAL / UNSUPPORTED / UNKNOWN`）。**上线前必须用真机实测填表，未测的一律 `UNKNOWN`。**

| 能力 | BlackBox 2.1.0（预期，待实测） | VirtualApp 商业版（预期，待实测） |
|---|---|---|
| `samePackageDifferentSignature` | UNKNOWN（机制上允许自建包表；需实测） | UNKNOWN（同上） |
| `multiInstance` | SUPPORTED（userId 槽位机制） | SUPPORTED |
| `nativeLibs` | PARTIAL（分 32/64 位产物） | SUPPORTED（宣称纯 64 位可跑） |
| `supports32BitApk` | PARTIAL（仅 32 位产物） | UNKNOWN |
| `webView` | UNKNOWN（需实测多实例 WebView 数据目录） | UNKNOWN |
| `gms` | UNKNOWN（README 未承诺） | PARTIAL（宣称"完美运行 Google 服务"，属自述） |
| `notificationProxy` | UNKNOWN | UNKNOWN |
| `backgroundService` | UNKNOWN | UNKNOWN |
| `clearData` | UNKNOWN（需查 SDK 是否有 `clearAppData`） | SUPPORTED（README 提到 `clearAppData`） |
| `permissionInterception` | PARTIAL（部分虚拟化） | PARTIAL |
| `requiresRoot` | false | false |

> 这张表的"预期"列**不是结论**，是阶段 2 要验证的假设。任何一格在没有实测证据前不得写成 `SUPPORTED` 对外展示。

---

## 4. 适配层实现要点

### 4.1 通用骨架（所有引擎共用）

```kotlin
abstract class BaseEngineAdapter : VirtualizationEngine {
    private val mutex = Mutex()
    @Volatile private var caps: EngineCapabilities? = null

    override suspend fun capabilities() = caps

    override suspend fun launch(instanceId: String, options: LaunchOptions) = mutex.withLock {
        val c = caps ?: return@withLock EngineResult.failure(EngineErrorCode.ENGINE_NOT_AVAILABLE, "引擎未初始化")
        if (c.multiInstance == Capability.UNSUPPORTED) {
            return@withLock EngineResult.failure(EngineErrorCode.UNSUPPORTED_BY_ENGINE, "当前引擎不支持多实例")
        }
        doLaunch(instanceId, options)
    }
    protected abstract suspend fun doLaunch(instanceId: String, options: LaunchOptions): EngineResult<Unit>
    // ... 其余同理
}
```

要点：**串行化**。引擎包表写入几乎都不可重入，并发 `install` 会写坏状态。

### 4.2 BlackBox 适配（`engine/blackbox`）

BlackBox 的公开入口是 `BlackBoxCore.get()`（`top.niunaijun.blackbox.BlackBoxCore`），源码在 `Bcore` 模块。适配时对照 `2.1.0` tag 的真实方法签名，**不要凭记忆写**。预期映射：

| 接口方法 | BlackBox 侧（以 2.1.0 tag 源码为准，**需逐个核对**） |
|---|---|
| `initialize` | `BlackBoxCore.get().doCreate()` / `startup()` 类入口 + 权限授予流程 |
| `installApk` | `installPackageAsUser(apkPath, userId)` 系列 |
| `launch` | 构造 launcher Intent 后 `launchApk(pkg, userId, intent)` |
| `stop` | `stopPackage(pkg, userId)`（若无公开 API，用杀 client 进程兜底并在 capabilities 标 `PARTIAL`） |
| `queryState` | `isAppRunning` 或进程枚举 |
| `clearData` | 若有 `clearAppData` 用之；否则删除 `vuser/<uid>/<pkg>` 后 `PARTIAL` 标注 |
| `uninstall` | `uninstallPackageAsUser(pkg, userId)` |
| `captureLogs` | `Runtime.exec("logcat -d --pid=<pid>")` 或 `Debug` API |

**适配时必须面对的现实问题**（都会在阶段 2 暴露）：

1. BlackBox 2.1.0 的 `targetSdk`/`compileSdk` 很老，需要先把宿主工程升到 `compileSdk 36`、`targetSdk 36` 才能上架，而升级过程本身可能破坏引擎的 hidden API 访问（`FreeReflection`/`BlackReflection` 依赖具体 API 形态）。
2. Android 14 起对 `PendingIntent` 可变性、动态注册 receiver 的 `RECEIVER_EXPORTED` 强制要求，引擎里大量 `registerReceiver` 需要补标志。
3. Android 15 的 16KB 页要求 → 自带 `.so` 需 16KB 对齐。
4. 引擎声明的进程（如 `:p0`、`:x`）必须保留在 manifest，且不能被 `android:process` 与宿主业务冲突。

### 4.3 VirtualApp 适配（`engine/virtualapp`）

仅在**取得商业授权后**启用该模块。公开 README 给出的三步：

```java
VirtualCore.get().startup();                                  // 初始化
VirtualCore.get().installPackageAsUser(userId, packageName);  // 安装（也可从 APK 路径安装）
VActivityManager.get().launchApp(userId, packageName);        // 启动
```

适配层要做的事：把 `userId` 槽位与我们的 `runtime_instance.virtual_user_id` 一一对应；把 VA 的异常（`install 返回非 0`）翻译成我们的错误码；把 `clearAppData`/`uninstallPackageAsUser` 包装成幂等操作。

> ⚠️ 授权未落地前，此模块只放接口实现骨架与 TODO，**不写入任何 VA 的私有代码或反编译产物**。

### 4.4 Mock 适配（`engine/mock`）

用途：在引擎尚未验证前，把 UI/数据库/诊断链路跑通；**它不加载任何第三方 APK**。

行为约定：
- `installApk`：只做文件校验 + 记录，返回 `InstalledApp`，100ms 延迟；
- `launch`：启动一个空 Activity 模拟"运行中"，或直接返回 `UNSUPPORTED_BY_ENGINE`；
- 所有 UI 上必须显示醒目的"当前为模拟引擎（不提供真实虚拟化）"横幅，防止误以为功能已完成。

---

## 5. EngineGateway：选引擎、探测、降级、对账

```kotlin
class EngineGateway(
    private val factories: Map<String, () -> VirtualizationEngine>,
    private val instanceRepo: InstanceRepository,
    private val logger: RunLogger
) {
    suspend fun ensureInitialized(preferred: String): EngineResult<EngineCapabilities>
    suspend fun install(req: InstallRequest): EngineResult<InstalledApp>
    suspend fun launch(instanceId: String): EngineResult<Unit>
    suspend fun reconcileAll()            // App 启动/回到前台/重启后调用
}
```

`reconcileAll()` 规则：

| 数据库状态 | 探针结果 | 处理 |
|---|---|---|
| `RUNNING` | 进程不存在 | → `CRASHED`/`KILLED_BY_SYSTEM`，写 `run_event` |
| `STARTING` 超过 90s | 任意 | → `LAUNCH_FAILED(超时)` |
| `INSTALLED` | 引擎包表里没有 | → `NOT_INSTALLED`（引擎数据被清） |
| `INSTALLING`/`CLEARING`/`DELETING` 残留 | 任意 | → 标记 `UNKNOWN_INTERRUPTED`，提供"重试" |
| `engine_profile.device_sdk` ≠ 当前 | 任意 | 强制 `selfCheck()`，失败则全实例 `ENGINE_INCOMPATIBLE` |

---

## 6. 接口验收清单（写完适配层就能自测）

- [ ] 未初始化时调用任何方法都返回 `ENGINE_NOT_AVAILABLE`，不抛异常
- [ ] 连续两次 `initialize` 不崩溃、返回同一份 capabilities
- [ ] `installApk` 传不存在路径 → `INSTALL_FAILED`（不是 `INTERNAL`）
- [ ] `installApk` 传签名不符的包 → `SIGNATURE_MISMATCH`
- [ ] `launch` 一个未安装的实例 → `LAUNCH_FAILED`，且数据库状态不变成 `RUNNING`
- [ ] 强杀 client 进程后 5s 内 `queryState` 返回 `CRASHED`（或在下次 `reconcileAll` 修正）
- [ ] `clearData` 后再次 `launch` = 首次启动体验（无残留登录态）
- [ ] `uninstall` 两次不报错（幂等）
- [ ] 所有 `Failure` 都能在 `run_event` 里找到对应行，且日志不含明文 token/密码
