package com.adv.runner

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = remember {
                        val logger = RunLogger(File(appContext.filesDir, "logs"))
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
                            debugApkPath = intent?.getStringExtra(EXTRA_DEBUG_APK)
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
    }
}
