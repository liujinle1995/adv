# Android 17（API 37）适配清单

> 目标：从「targetSdk 写了 37」变成「真正按 API 37 编译、链接、并按 API 37 的行为变更完成适配」。
> 采集时间：2026-10-24。

## 1. 工具链与编译期

| 项 | 之前 | 现在 | 说明 |
|---|---|---|---|
| AGP | 8.7.3 | **8.13.2** | 8.7.x 只能解析 SDK 的 XML v3，读不了本机 `android-37.0`（XML v4） |
| compileSdk | 36 | **37** | 真正用 Android 17 平台编译，targetSdk 37 的编译期校验生效 |
| targetSdk | 37 | 37 | 不变 |
| Gradle | 8.13 | 8.13 | AGP 8.13 要求 Gradle ≥ 8.13 |

验证方式：

```
aapt2 dump badging app-debug.apk | grep -E "compileSdk|targetSdk|platformBuildVersion"
# compileSdkVersion='37' platformBuildVersionName='17' targetSdkVersion:'37'
```

### 1.1 升级 AGP 带来的路径坑（已修）

AGP 8.13 的 aidl 生成器会把完整命令行写进生成文件头部注释。本项目路径包含
`...\core\system\user\IBUserManagerService.aidl`，其中 **`\user`** 命中 javac 的 Unicode
转义前缀（`\u` 后应跟 4 位十六进制），于是报「非法的 Unicode 转义」。
处理：`third_party/blackbox/Bcore/build.gradle` 里在 JavaCompile 前清洗生成文件注释，
把 `\u` 改写成 `/u`（只动注释，不影响代码）。

## 2. 链接期：16KB 内存页

Android 15 起支持 **16KB 内存页**设备，Android 17 时代新机型普遍为 16KB 页。
这类设备要求 ELF 的 `LOAD` 段按 16KB（`0x4000`）对齐，否则 `dlopen` 直接失败。

| 库 | 之前 | 现在 |
|---|---|---|
| `libblackbox.so` | `0x1000`（4KB） | **`0x4000`（16KB）** |
| `libpine.so` | `0x1000`（4KB） | **`0x4000`（16KB）** |

改动：

- `Bcore/src/main/cpp/CMakeLists.txt`：`target_link_options(blackbox PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")`
- `Bcore/pine-core/src/main/cpp/CMakeLists.txt`：`LINKER_FLAGS` 追加同样两个参数

注意：16KB 对齐的 .so **向后兼容** 4KB 页设备（已在 realme RMX3888 / 4KB 页真机回归通过），
所以这项改动对现有用户零风险。

验证方式：

```
llvm-readelf -l lib/arm64-v8a/libblackbox.so | grep LOAD
# 每一行末尾应为 0x4000
zipalign -c -P 16 -v 4 app-debug.apk | tail -1   # Verification successful
```

## 3. targetSdk 37 的行为变更逐项处理

| # | Android 17 变更 | 对本项目的影响 | 处理 |
|---|---|---|---|
| 1 | **static final 字段不可修改**（反射抛 `IllegalAccessException`，JNI `SetStaticLongField` 直接崩） | 引擎 Hook 层大量使用反射 / native 写字段 | 已核查：引擎不使用 `SetStatic*Field` 写 static final；`HiddenApiUnsealer` 只操作实例字段 → **无需改动，需 API 37 设备实测确认** |
| 2 | **MessageQueue 改为无锁实现**（反射其私有字段会失效） | 若引擎 Hook 了 `MessageQueue` 内部字段会失效 | 已核查：引擎未 Hook `MessageQueue` → 无需改动 |
| 3 | **大屏（sw≥600dp）忽略 screenOrientation / resizeableActivity / maxAspectRatio** | 虚拟 App 在平板、折叠屏、桌面模式下被强制可调整窗口 | 代理 Activity 本就未固定方向；`configChanges` 已覆盖 orientation/screenSize/smallestScreenSize/colorMode，配置变化不会销毁重建 |
| 4 | **Activity 不再因 CONFIG_KEYBOARD/NAVIGATION/TOUCHSCREEN/COLOR_MODE 重建** | 依赖重建刷新资源的页面会失效 | 代理 Activity 与宿主 MainActivity 都显式声明了 `configChanges`，本来就自行处理，不受该变更影响；本轮为代理 Activity 补上缺失的 `colorMode` |
| 5 | **新增 `ACCESS_LOCAL_NETWORK` 运行时权限**（局域网访问） | 虚拟 App 在 API 37 上访问投屏/智能家居等局域网设备会失败 | 宿主清单已声明该权限（容器同 UID，虚拟 App 的权限校验落在宿主上） |
| 6 | RemoteViews 位图内存上限 | 本项目无桌面小组件 | 不涉及 |
| 7 | ECH（Encrypted Client Hello） | 跟随系统网络栈 | 不涉及 |

## 4. 验证边界（重要）

| 项 | 状态 |
|---|---|
| arm64 真机 Android 16（API 36）/ 4KB 页 | ✅ 已回归：编译、安装、启动虚拟 App、JniHook 初始化、悬浮按钮停止全链路正常 |
| 16KB 页设备 | ⚠️ 链接层已满足（ELF 0x4000 对齐），**尚无 16KB 页真机可实测** |
| Android 17（API 37）运行时 | ⚠️ 未实测。本机模拟器为 **x86_64** 镜像，而项目仅提供 arm64-v8a native 库，且 pine 的 trampoline 缺 x86_64 汇编写法，无法在模拟器上跑通引擎；需要一台 **arm64 的 Android 17 设备**做最终确认 |

模拟器信息（供后续接入时使用）：`~/.android/avd/Pixel_10_Pro_XL.avd`，镜像
`system-images/android-37.0/google_apis_playstore_ps16k/x86_64`，16KB 页、1344×2992。

## 5. 后续若要支持 x86_64（模拟器 / x86 平板）

需要补 `pine-core/src/main/cpp/trampoline/arch/x86_64.{S,cpp,h}`（当前只有 arm32/arm64/thumb2/x86），
并在三个模块的 `abiFilters` 里加 `x86_64`。属于移植级工作，未在本轮范围内。
