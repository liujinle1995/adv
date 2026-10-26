package com.adv.runner

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.adv.runner.apk.ApkAnalyzer
import com.adv.runner.apk.ApkStore
import com.adv.runner.core.control.InstanceController
import com.adv.runner.data.ApkRepository
import com.adv.runner.data.EventRepository
import com.adv.runner.data.InstanceRepository
import com.adv.runner.data.LegacyMigrator
import com.adv.runner.data.db.DatabaseProvider
import com.adv.runner.diagnostics.RunLogger
import com.adv.runner.engine.blackbox.BlackBoxEngineAdapter
import com.adv.runner.ui.AppRoot
import com.adv.runner.ui.AppState
import com.adv.runner.ui.theme.AdvTheme
import com.adv.runner.ui.theme.ThemeRepository
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机（非平板）锁定竖屏；平板（sw600dp+）允许横竖屏自适应
        if (!resources.getBoolean(R.bool.is_tablet)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent {
            val appContext = LocalContext.current.applicationContext
            val themeRepository = remember { ThemeRepository(appContext) }
            AdvTheme(mode = themeRepository.mode) {
                // Android 15+ 对 targetSdk 35+ 强制 edge-to-edge：状态栏透明、内容延伸到状态栏下。
                // 必须显式把状态栏/导航栏图标切换成与背景相反的颜色，否则浅色背景 + 浅色图标 = 看不见。
                val view = LocalView.current
                val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = lightBackground
                        isAppearanceLightNavigationBars = lightBackground
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = remember {
                        val logger = RunLogger(File(appContext.filesDir, "logs"))
                        // 系统 logcat 在部分 ROM（荣耀平板等）上几乎为空，
                        // 把引擎关键路径日志转到本地文件，供应用内诊断页查看。
                        top.niunaijun.blackbox.utils.DiagLog.install { tag, message ->
                            logger.info("engine/$tag", message)
                        }
                        val engine = BlackBoxEngineAdapter(logger)
                        val db = DatabaseProvider.get(appContext)
                        val store = InstanceRepository(db)
                        val eventStore = EventRepository(db)
                        val apkRepository = ApkRepository(db)
                        val migrator = LegacyMigrator(appContext, store, eventStore)
                        AppState(
                            context = appContext,
                            logger = logger,
                            analyzer = ApkAnalyzer(appContext),
                            engine = engine,
                            store = store,
                            eventStore = eventStore,
                            controller = InstanceController(store, engine),
                            migrator = migrator,
                            apkStore = ApkStore(File(appContext.filesDir, "apks")),
                            apkRepository = apkRepository,
                            themeRepository = themeRepository,
                            deviceAbis = Build.SUPPORTED_ABIS.toList(),
                            deviceSdk = Build.VERSION.SDK_INT,
                            scope = (appContext as AdvApp).appScope,
                            debugApkPath = intent?.getStringExtra(EXTRA_DEBUG_APK),
                            debugLaunchPkg = intent?.getStringExtra(EXTRA_DEBUG_LAUNCH)
                        )
                    }
                    AppRoot(state)
                }
            }
        }
    }

    companion object {
        /**
         * 调试入口：跳过 SAF 选择，直接「解析 → 安装 → 启动」指定 APK，
         * 便于用 logcat 反复迭代引擎适配（真机上手动点太慢）：
         *
         * adb shell am start -n com.adv.runner/.MainActivity \
         *     --es debug_apk /sdcard/Android/data/com.adv.runner/files/base.apk
         */
        const val EXTRA_DEBUG_APK = "debug_apk"

        /**
         * 调试入口：直接启动已存在的实例（省掉 UI 点击），用于真机自动化验证：
         *
         * adb shell am start -n com.adv.runner/.MainActivity \
         *     --es debug_launch com.phoenix.read
         */
        const val EXTRA_DEBUG_LAUNCH = "debug_launch"
    }
}
