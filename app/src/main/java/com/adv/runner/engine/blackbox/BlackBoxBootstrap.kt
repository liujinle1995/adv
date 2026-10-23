package com.adv.runner.engine.blackbox

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 引擎自举。
 *
 * BlackBox 要求 **在 Application.attachBaseContext 里** 完成 `doAttachBaseContext`，
 * 在 `onCreate` 里完成 `doCreate`。这两个时机不能挪到"用户点按钮"时才做，
 * 否则引擎的进程识别、ClassLoader 替换、ContentProvider 注册都已错过时机。
 *
 * 因此实际自举在 [com.adv.runner.AdvApp] 中触发，适配层只做"查询自举结果"。
 */
object BlackBoxBootstrap {

    @Volatile
    private var attached = false

    @Volatile
    private var created = false

    @Volatile
    var error: Throwable? = null
        private set

    fun attach(context: Context) {
        if (attached) return
        try {
            BlackBoxCore.get().doAttachBaseContext(
                context,
                AdvClientConfiguration(context.packageName)
            )
            attached = true
        } catch (t: Throwable) {
            error = t
        }
        Log.i("adv/Bootstrap", "attach process=${Application.getProcessName()} ok=$attached error=${error?.javaClass?.simpleName}")
    }

    fun create() {
        if (!attached || created) return
        try {
            BlackBoxCore.get().doCreate()
            created = true
        } catch (t: Throwable) {
            error = t
        }
    }

    /**
     * 幂等：若 Application 已经自举过则直接返回结果；否则补做一次。
     * @return null 表示成功，非 null 表示失败原因
     */
    fun ensure(context: Context): Throwable? {
        if (!attached) attach(context)
        if (!created) create()
        return error
    }

    val isReady: Boolean
        get() = attached && created && error == null
}
