// 阶段 2 骨架：单模块 + BlackBox 引擎（第三方，见 third_party/blackbox）
// 阶段 3 再按 docs/02-architecture.md 拆分自有代码的多模块
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Room 注解处理用 KSP（Kotlin 2.0 下 KAPT 已被官方建议替换）
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// ---------------------------------------------------------------------------
// 引擎模块（third_party/blackbox）自带的示例单测是上游骨架（`2 + 2 == 4`），
// 没有声明 junit 依赖，跑 `gradle test` 时会在编译期失败、把整个测试任务带崩。
// 这些模块属于第三方代码，我们不做单测覆盖，直接关掉它们的测试任务；
// 本项目自己的测试全部在 :app 下（纯 JVM，不需要设备）。
// ---------------------------------------------------------------------------
subprojects {
    if (name.startsWith("bb-")) {
        // 补上 junit，让上游示例单测至少能编译通过（它们不参与我们的测试目标）
        plugins.withId("com.android.library") {
            dependencies.add("testImplementation", "junit:junit:4.13.2")
        }
        tasks.matching { it.name.startsWith("test") }.configureEach { enabled = false }
    }
}
