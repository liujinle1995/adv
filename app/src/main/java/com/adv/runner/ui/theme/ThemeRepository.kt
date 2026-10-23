package com.adv.runner.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 应用主题偏好：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/**
 * 主题偏好管理：用 SharedPreferences 持久化（单一字符串，避免引入 DataStore 依赖）。
 *
 * [mode] 是可观察状态，主题切换立即生效并同步落盘。
 */
class ThemeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("adv_theme", Context.MODE_PRIVATE)

    var mode by mutableStateOf(readMode())
        private set

    fun setThemeMode(value: ThemeMode) {
        mode = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    private fun readMode(): ThemeMode {
        val saved = prefs.getString(KEY_MODE, null)
        return saved?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    private companion object {
        const val KEY_MODE = "theme_mode"
    }
}
