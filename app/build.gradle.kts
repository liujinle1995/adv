plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.adv.runner"
    // 用 AGP 可识别的 android-36 编译；本机 android-37.0 是新版 SDK 管理器的 XML v4 格式，
    // AGP 8.7.x 解析不了。代码未使用 API 37 新 API，compileSdk 36 足够。
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.adv.runner"
        // 需求：最低支持 Android 12（API 31），最高支持 Android 17（API 37）
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-engine"

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
