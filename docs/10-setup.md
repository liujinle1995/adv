# Android Studio 项目初始化与构建

## 1. 本机环境现状（已核实）

| 项 | 值 |
|---|---|
| JDK | Eclipse Temurin **17.0.20**（`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`） |
| Android SDK | `C:\Users\94591\AppData\Local\Android\Sdk` |
| 已装 platforms | android-34 / 35 / 36 / 36.1 / 37.0 |
| 已装 build-tools | 34.0.0 / 35.0.0 / 36.0.0 / 36.1.0 / 37.0.0 |
| adb | `C:\Users\94591\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 已连接真机 | **无**（`adb devices` 为空）→ 阶段 2 必须接一台 |

本项目使用：AGP **8.13.2** / Gradle **8.13** / Kotlin **2.0.21** / `compileSdk 37` / `minSdk 31` / `targetSdk 37`（支持 API 31–37，即 Android 12–17）。

> **Android 17（API 37）编译说明**：本项目已升级 AGP 到 **8.13.2**，可以直接解析本机
> `platforms/android-37.0`（新版 SDK 管理器的 XML v4 schema），因此 `compileSdk` 真正提升到
> **37**，targetSdk 37 的编译期 API 校验与行为变更检查全部生效。
>
> 升级 AGP 时踩到一个路径坑：AGP 8.13 的 aidl 生成器会把完整命令行写进生成文件头部注释，
> 本项目路径含 `\blackbox\core\system\user\`，其中 **`\user`** 会被 javac 当作 Unicode 转义
> （`\u` + `ser`）从而报「非法的 Unicode 转义」。已在 `third_party/blackbox/Bcore/build.gradle`
> 里加了编译前清洗 task（把生成文件注释中的 `\u` 改写成 `/u`），不要删除。

### 1.1 本机已实测的环境坑（重要）

| 现象 | 实测结论 |
|---|---|
| `ERROR: JAVA_HOME is set to an invalid directory: ...jdk-17.0.17.10-hotspot` | 本机系统环境变量 `JAVA_HOME` 指向一个**已不存在**的 JDK（17.0.17.10），实际安装的是 **17.0.20.101**。构建前需修正 `JAVA_HOME`，或在 Studio 里把 Gradle JDK 指到 17.0.20.101 |
| 本机无 `~/.gradle` 缓存、也无 Gradle 安装 | 首次构建需联网下载 Gradle 发行版（约 130MB）+ AGP/Compose 依赖，首次约 4–5 分钟 |

**本机已验证通过的构建命令**（Gradle 未装时用临时解压的发行版）：

```powershell
Set-Item -Path env:JAVA_HOME -Value 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
cd d:\app\adv
& "C:\Users\94591\AppData\Local\Temp\g89\gradle-8.9\bin\gradle.bat" :app:assembleDebug --console=plain
# BUILD SUCCESSFUL in 4m 17s
# app/build/outputs/apk/debug/app-debug.apk (9.5 MB)
```

---

## 2. 首次打开

1. Android Studio → `Open` → 选择 `d:\app\adv`（含 `settings.gradle.kts` 的目录）。
2. 首次同步时 Studio 会提示生成 Gradle Wrapper → 同意（或见 §3 手动生成）。
3. `File → Project Structure → SDK Location` 确认 SDK 路径正确（会自动写 `local.properties`）。
4. `Sync Project with Gradle Files`。

> `local.properties` 含本机路径，**不要提交到 git**（`.gitignore` 已排除）。

---

## 3. 手动生成 Gradle Wrapper

仓库里没有 `gradle-wrapper.jar`（二进制文件不适合手工生成），两种方式二选一：

```bash
# 方式 A：本机已装 Gradle
gradle wrapper --gradle-version 8.9

# 方式 B：找一个任意已有 Android 工程，复制 gradle/wrapper/ 整个目录过来，
#        并把 gradle-wrapper.properties 的 distributionUrl 改为 gradle-8.9-bin.zip
```

生成后：

```bash
./gradlew :app:assembleDebug
# Windows PowerShell
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 4. 真机准备

```bash
adb devices                       # 应看到设备序列号 + device
adb install -r app-debug.apk
adb shell am start -n com.adv.runner/.MainActivity

# 日志
adb logcat -c && adb logcat -v threadtime | findstr /i "adv runner AndroidRuntime"
```

厂商 ROM（MIUI / ColorOS / EMUI / HyperOS）额外设置：

| 项 | 位置 |
|---|---|
| USB 调试（安全设置） | 开发者选项 → "USB 调试（安全设置）"，否则无法模拟输入/安装 |
| 关闭"MIUI 优化" | 开发者选项（部分机型需要，否则后台进程被杀） |
| 允许后台运行 / 忽略电池优化 | 应用信息 → 省电策略 → 无限制 |
| 允许自启动 | 手机管家 → 自启动管理 |

---

## 5. 签名与重签名（阶段 1 测试 APK 用）

```bash
# 生成两把不同的 keystore
keytool -genkeypair -v -keystore probeA.jks -alias probeA -keyalg RSA -keysize 2048 ^
        -validity 10000 -storepass 123456 -keypass 123456 ^
        -dname "CN=Probe A, OU=Test, O=Adv, L=SH, ST=SH, C=CN"

keytool -genkeypair -v -keystore probeB.jks -alias probeB -keyalg RSA -keysize 2048 ^
        -validity 10000 -storepass 123456 -keypass 123456 ^
        -dname "CN=Probe B, OU=Test, O=Adv, L=SH, ST=SH, C=CN"

# 用 B 重签一个"同包名同版本号但签名不同"的 APK
"%ANDROID_HOME%\build-tools\35.0.0\apksigner.bat" sign ^
        --ks probeB.jks --ks-key-alias probeB ^
        --ks-pass pass:123456 --key-pass pass:123456 ^
        --out probe-B-v1.apk app-release-unsigned.apk

# 校验与查看证书指纹（用来和 App 内解析结果比对，验证 AC1）
"%ANDROID_HOME%\build-tools\35.0.0\apksigner.bat" verify --print-certs probe-B-v1.apk
aapt2 dump badging probe-B-v1.apk
```

> `apksigner` 会**替换**原签名。要保留 v1+v2+v3 时用默认参数即可（默认全开）。

---

## 6. 常见构建错误

| 报错 | 原因 | 解决 |
|---|---|---|
| `JAVA_HOME is set to an invalid directory` | Studio 的 Gradle JDK 指错 | `Settings → Build Tools → Gradle → Gradle JDK` 选 17 |
| `gradle: command not found` | 未装 Gradle 且未生成 wrapper | 见 §3 |
| `Failed to find target with hash string 'android-35'` | 未装该 platform | SDK Manager 安装 Android 15 (API 35) |
| `Unsupported class file major version 65` | 用 JDK 21 跑 AGP 8.7 | 统一用 JDK 17 |
| `Kotlin Compose Compiler plugin not found` | 用了 Kotlin 2.x 却没加 `org.jetbrains.kotlin.plugin.compose` | 检查 `build.gradle.kts` 两个文件里的 plugin 声明 |
| `SDK location not found` | 缺 `local.properties` | 在项目根建 `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（装测试 APK 时） | 设备上已有同包名不同签名的包 | 这是**我们要验证的现象本身**；在宿主机上先卸载或换包名 |

---

## 7. 骨架的已知未完成项（不要误判为 bug）

| 现象 | 说明 |
|---|---|
| 界面里没有真实虚拟化 | 当前接的是 `MockEngine`，横幅已明示 |
| 没有持久化 | 阶段 2 骨架不接 Room；实例列表在内存里，重启即清空 |
| `launcherActivity` 为空 | 公开 API 无法解析未安装 APK 的 IntentFilter，交给引擎安装后解析 |
| 权限只展示不拦截 | 真实拦截能力取决于引擎，未实测前一律 `UNKNOWN` |
