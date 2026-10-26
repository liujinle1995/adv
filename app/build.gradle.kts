plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.adv.runner"
    // Android 17（API 37）正式支持：AGP 8.13 可解析新版 SDK 管理器的
    // android-37.0（package.xml 为 repository XML v4），compileSdk 提升到 37，
    // 使 targetSdk 37 的编译期 API 校验、行为变更 lint 真正生效。
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.adv.runner"
        // 需求：最低支持 Android 12（API 31），最高支持 Android 17（API 37）
        minSdk = 31
        targetSdk = 37
        versionCode = 55
        versionName = "0.8.1"

        ndk {
            // 目标设备（realme RMX3888 / Android 16）无 32 位运行时
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 复用 debug 签名，让 release 也能直接覆盖安装到真机。
            // 关键作用：release 非 debuggable → 进程级 CheckJNI 关闭，避免虚拟化场景下
            // 每帧 JNI(Log.d) 被 CheckJNI 检查拖垮主线程导致的 ANR。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 版本号与更新记录页从 BuildConfig 读取，避免多处硬编码不一致
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // BlackBox 引擎（自维护分支，源码在 third_party/blackbox）
    implementation(project(":bb-core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room（阶段 3.2：adv.db，替换阶段 2 的 JSON/JSONL 文件存储）
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// 发版一致性自检
//
// 「更新记录」的数据源是 src/main/java/com/adv/runner/data/Changelog.kt，
// 必须与 defaultConfig.versionCode 同步。历史上出现过两次「改了功能却没写记录」，
// 这里在每次构建的 preBuild 阶段自检，不一致就打印醒目告警（不阻断构建，
// 避免开发中途被打断）。
// ---------------------------------------------------------------------------
tasks.register("checkChangelogVersion") {
    doLast {
        val changelog = file("src/main/java/com/adv/runner/data/Changelog.kt")
        if (!changelog.exists()) return@doLast
        val newest = Regex("""versionCode\s*=\s*(\d+)""")
            .findAll(changelog.readText())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val current = android.defaultConfig.versionCode ?: 0
        if (newest != current) {
            logger.warn(
                "\n⚠⚠ 版本号与更新记录不一致：当前 versionCode=$current，" +
                    "Changelog.kt 最新记录=$newest。\n" +
                    "   请先在 data/Changelog.kt 补一条对应版本的记录。\n"
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("checkChangelogVersion")
}

// ---------------------------------------------------------------------------
// 构建产物分发：每次 assembleRelease 之后，把正式版 APK 复制到 Z 盘归档目录。
//
// 需求来自用户：正式版构建完成后自动留一份到 Z:/应用虚幻器。
// 用 finalizedBy 挂在 assembleRelease 后面，任何人执行构建都会触发，不依赖 IDE/脚本。
// Z 盘是外接/网络盘，可能临时不可用 —— 所有失败只告警，绝不让构建失败。
// ---------------------------------------------------------------------------
tasks.register("copyReleaseApkToZ") {
    group = "distribution"
    description = "把正式版 APK 复制到 Z:/应用虚幻器"

    val srcProvider = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val versionName = android.defaultConfig.versionName ?: "unknown"
    val destDir = File("Z:/应用虚幻器")

    doLast {
        val src = srcProvider.get().asFile
        if (!src.isFile) {
            logger.warn("⚠ 未找到正式版 APK，跳过归档：${src.absolutePath}")
            return@doLast
        }
        if (!destDir.isDirectory) {
            logger.warn("⚠ 归档目录不存在，跳过：${destDir.absolutePath}")
            return@doLast
        }
        val dest = File(destDir, "应用虚幻器-v$versionName-release.apk")
        runCatching { src.copyTo(dest, overwrite = true) }
            .onSuccess {
                logger.lifecycle(
                    "✅ 正式版已归档：${dest.absolutePath}（${dest.length() / 1024 / 1024} MB）"
                )
            }
            .onFailure {
                logger.warn("⚠ 复制到 Z 盘失败（不影响构建结果）：${it.message}")
            }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApkToZ")
}
