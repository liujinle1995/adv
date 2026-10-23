pluginManagement {
    repositories {
        // 国内网络下 dl.google.com 的 TLS 握手经常被打断，优先走阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        // BlackBox 引擎依赖：BlackReflection 只在 jitpack 发布；
        // free_reflection 3.0.1 原在已停服的 jcenter，用阿里云公共镜像兜底。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "adv-runner"
include(":app")

// ---- BlackBox 2.1.0 引擎（Apache-2.0，源码见 third_party/blackbox/LICENSE）----
// 只搬引擎，不搬它的 demo app（demo 依赖 jcenter 上已停服的三方 UI 库）
include(":bb-core")
include(":bb-fake")
include(":bb-hook")
include(":bb-mirror")
include(":bb-pine-core")
include(":bb-pine-xposed")
include(":bb-pine-xposed-res")

project(":bb-core").projectDir = file("third_party/blackbox/Bcore")
project(":bb-fake").projectDir = file("third_party/blackbox/Bcore/black-fake")
project(":bb-hook").projectDir = file("third_party/blackbox/Bcore/black-hook")
project(":bb-mirror").projectDir = file("third_party/blackbox/android-mirror")
project(":bb-pine-core").projectDir = file("third_party/blackbox/Bcore/pine-core")
project(":bb-pine-xposed").projectDir = file("third_party/blackbox/Bcore/pine-xposed")
project(":bb-pine-xposed-res").projectDir = file("third_party/blackbox/Bcore/pine-xposed-res")
