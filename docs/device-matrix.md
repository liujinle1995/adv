# 设备矩阵与真机验证记录

> 所有结论均为本机 `adb` 实测，采集时间 2026-10-23。

## 1. 已接入设备 D1

| 项 | 值 | 命令 |
|---|---|---|
| 型号 | realme **RMX3888** | `adb shell getprop ro.product.model` |
| 系统 | **Android 16（API 36）** | `getprop ro.build.version.release / sdk` |
| 安全补丁 | 2026-08-01 | `getprop ro.build.version.security_patch` |
| 版本号 | RMX3888_16.0.10.500(CN01) | `getprop ro.build.display.id` |
| ABI | **仅 arm64-v8a**（`abilist32` 为空） | `getprop ro.product.cpu.abilist / abilist32` |
| 内存页 | **4096**（4KB，非 16KB） | `adb shell getconf PAGESIZE` |

### 1.1 这台设备带来的三条硬约束

1. **无 32 位运行时**。任何只带 `armeabi-v7a` 原生库的低版本 APK 在这台机器上**根本跑不了**（不只是沙箱里，系统里也跑不了）。导入时必须直接判 `ABI_MISMATCH`。
2. **Android 16 是当前引擎支持的末端**。BlackBox 2.1.0 源码里最高只到 `isS()`（Android 12），无 13/14/15/16 任何适配分支。
3. 页大小 4KB → 16KB 对齐问题**在这台设备上暂时不触发**（换新机型才需要处理）。

---

## 2. 开发机工具链

| 项 | 值 |
|---|---|
| JDK | Temurin **17.0.20.101**（注意：系统 `JAVA_HOME` 指向已不存在的 `17.0.17.10`，**必须先修正**） |
| SDK platforms | android-34 / 35 / 36 / 36.1 / 37.0 |
| build-tools | 34.0.0 / 35.0.0 / 36.0.0 / 36.1.0 / 37.0.0 |
| NDK | 27.0.12077973 / 28.2.13676358 / 30.0.14904198 |
| CMake | 3.22.1 |
| git | 2.53.0 |

---

## 3. 已完成的验证（真机）

### V-AC1：APK 解析准确性（对应验收标准 1）—— **PASS**

样本：本项目自身 `app-debug.apk`，由 `adb` 推入私有目录后由 App 解析。

| 字段 | App 解析结果 | 独立工具结果 | 一致性 |
|---|---|---|---|
| 包名 | `com.adv.runner` | `aapt2` | ✅ |
| versionName / Code | `0.1.0-spike` / `1` | `build.gradle.kts` | ✅ |
| minSdk / targetSdk | 26 / 35 | 同上 | ✅ |
| ABI | `[arm64-v8a, armeabi-v7a, x86, x86_64]` | APK 内 `lib/` 目录（含 androidx 原生库） | ✅ |
| 签名证书 SHA-256 | `9bd30c7c8ce54ed7817479137de64f4fba7876a375c490a3f8f2d824b8b6e80c` | `apksigner verify --print-certs` 同值 | ✅ |
| 签名主体 | `C=US,O=Android,CN=Android Debug` | `apksigner` 同值 | ✅ |
| 文件 SHA-256 | `d46f813a5e0fe140f6733f5ce67212ab92172f43c2d64aa48e2c134826a78ba7` | `Get-FileHash` 同值 | ✅ |

结论：`PackageManager.getPackageArchiveInfo` + `CertificateFactory` + 流式 SHA-256 这条**纯公开 API** 的解析链路在 Android 16 上工作正常，无需任何隐藏 API。

### V-UI：骨架运行 —— **PASS（修了一个 bug）**

- 安装、启动、`MockEngine` 初始化日志正常（`storageRoot=/data/user/0/com.adv.runner/files/engine/mock`）。
- **发现并修复**：`targetSdk 35` 起系统强制 edge-to-edge，标题被状态栏遮挡 → 根布局补 `windowInsetsPadding(WindowInsets.safeDrawing)`。
- 发现：ColorOS 的文件选择器会把 SAF 请求路由到 `com.coloros.filemanager`，导入流程需按真机实测的 picker 行为设计（不能用 AOSP 的假设）。

### V-P1：同包名不同签名的系统安装行为 —— **PASS（按预期失败）**

用第二把 keystore 重签同一份 APK 后覆盖安装：

```
adb install -r spike\same-pkg-diff-sign.apk
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.adv.runner
         signatures do not match newer version; ignoring!]
```

结论：**在 Android 16 上，"同包名 + 不同签名"通过系统安装器共存是不可能的**。这正是本项目必须做包名空间隔离（虚拟化）或改包名重打包的直接证据，同时说明 UI 上"签名冲突"提示不是多虑。

---

## 4. 待补的设备（当前只有 1 台，不够）

| 需求 | 原因 |
|---|---|
| 一台 Android 13（API 33）或 12 的设备 | BlackBox 2.1.0 只在 ≤12/13 有可用可能；用来区分"引擎不行"与"系统太新" |
| 一台 Android 15（API 35） | 覆盖中间版本行为差异（edge-to-edge 强制、16KB 页过渡） |
| 至少一台非 realme/ColorOS 的 ROM | 厂商杀进程、文件选择器、权限弹窗差异是常见失败源 |

> 单设备矩阵无法支撑"兼容性"结论。当前只能声称：**在 realme RMX3888 / Android 16 上验证过 X/Y**，不能推广到其它机型。
