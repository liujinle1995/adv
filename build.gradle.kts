// 阶段 2 骨架：单模块 + BlackBox 引擎（第三方，见 third_party/blackbox）
// 阶段 3 再按 docs/02-architecture.md 拆分自有代码的多模块
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Room 注解处理用 KSP（Kotlin 2.0 下 KAPT 已被官方建议替换）
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
