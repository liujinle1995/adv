package com.adv.runner.diagnostics

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.adv.runner.engine.blackbox.BlackBoxBootstrap
import top.niunaijun.blackbox.utils.HiddenApiUnsealer
import java.lang.reflect.Modifier

/**
 * 隐藏 API 访问探针（仅 debuggable 构建）。
 *
 * BlackBox 的 BlackReflection 在反射失败时会**静默返回 null**（`get()` 分支吞掉 Throwable），
 * 于是 hiddenapi 字段被拦时，现场只剩一个 "Integer.intValue() on null" 的 NPE，看不出真正原因。
 * 所以在这里把「引擎自举前 / 自举后」两个状态各打一行，作为适配期的固定观测点。
 *
 * 只写 logcat（tag `adv/HiddenApiProbe`），不参与任何业务逻辑。
 */
object HiddenApiProbe {

    private const val TAG = "adv/HiddenApiProbe"

    /**
     * 引擎自举前：记录系统的原始 hidden API 状态。
     *
     * 入口行**无条件**打印（含 debuggable 标志）：适配期需要确认「这个进程到底有没有跑到
     * Application.attachBaseContext」，否则日志缺失时无法区分"没跑"和"被过滤"。
     */
    fun beforeBootstrap(context: Context) {
        val debuggable = isDebuggable(context)
        Log.i(
            TAG,
            "[before] enter process=${processName()} sdk=${Build.VERSION.SDK_INT} " +
                "release=${Build.VERSION.RELEASE} targetSdk=${context.applicationInfo.targetSdkVersion} " +
                "debuggable=$debuggable"
        )
        if (!debuggable) return

        runCatching {
            val cls = Class.forName("me.weishu.reflection.Reflection")
            cls.getMethod("unseal", Context::class.java).invoke(null, context)
        }.onSuccess { Log.i(TAG, "[before] free_reflection.unseal -> $it (0=成功, -1=失败)") }
            .onFailure { Log.w(TAG, "[before] free_reflection.unseal 抛异常: ${it.javaClass.simpleName}") }

        readMember("android.app.ActivityThread\$H", "EXECUTE_TRANSACTION")
        readMember("android.app.ActivityThread", "mH")
    }

    /** 引擎自举后：自助豁免是否真的生效，这是所有 hook 能否工作的前提。 */
    fun afterBootstrap(context: Context) {
        val unsealed = HiddenApiUnsealer.isUnsealed()
        val engineError = BlackBoxBootstrap.error
        Log.i(
            TAG,
            "[after] process=${processName()} hiddenApiUnsealed=$unsealed " +
                "bootstrapError=${engineError?.let { it.javaClass.simpleName + ": " + it.message } ?: "none"}"
        )
    }

    private fun readMember(className: String, memberName: String) {
        try {
            val field = Class.forName(className).getDeclaredField(memberName)
            val static = Modifier.isStatic(field.modifiers)
            field.isAccessible = true
            val value = if (static) field.get(null) else "<non-static>"
            Log.i(TAG, "[before] $className.$memberName -> $value")
        } catch (t: Throwable) {
            // 被 hiddenapi 拦住时只有一行原因，不打堆栈
            Log.i(TAG, "[before] $className.$memberName -> denied (${t.javaClass.simpleName})")
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrDefault("?")
}
