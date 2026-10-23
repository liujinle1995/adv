package com.adv.runner

import android.app.Application
import android.content.Context
import com.adv.runner.diagnostics.HiddenApiProbe
import com.adv.runner.engine.blackbox.BlackBoxBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AdvApp : Application() {

    /**
     * 应用级协程作用域：长任务（备份/诊断导出等重 IO）绑定进程生命周期而非 Activity，
     * 切后台（Activity 走 onStop）时不会被取消。进程被杀仍依赖阶段 4 的对账恢复。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 必须在引擎自举之前跑：要观察的是"未做任何豁免时"的隐藏 API 状态
        HiddenApiProbe.beforeBootstrap(base)
        // 必须在 attachBaseContext 阶段完成，晚于此引擎无法接管进程
        BlackBoxBootstrap.attach(base)
        HiddenApiProbe.afterBootstrap(base)
    }

    override fun onCreate() {
        super.onCreate()
        BlackBoxBootstrap.create()
    }
}
