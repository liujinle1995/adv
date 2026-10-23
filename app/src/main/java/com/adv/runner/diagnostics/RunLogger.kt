package com.adv.runner.diagnostics

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地运行日志。
 *
 * 约定：
 * 1. 只写本机私有目录，默认不上传；
 * 2. 写入前强制脱敏（token / password / cookie / Authorization / Bearer）；
 * 3. 单线程串行写，避免并发写坏文件；
 * 4. 单文件 2MB 滚动，最多保留 10 个文件。
 */
class RunLogger(
    private val logDir: File,
    @Volatile var minLevel: Level = Level.INFO
) {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adv-run-logger").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val redactionRules = listOf(
        Regex(
            "(?i)(token|password|passwd|pwd|cookie|authorization|secret|sessionid|access_key)\\s*[=:]\\s*[^\\s,;&\"']+"
        ),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+")
    )

    fun redact(raw: String): String {
        var out = raw
        redactionRules.forEach { rule ->
            out = rule.replace(out) { m ->
                val key = m.value.substringBefore('=').substringBefore(':').trim()
                "$key=***"
            }
        }
        return out
    }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (level.ordinal < minLevel.ordinal) return
        val line = buildString {
            append(stamp.format(Date()))
            append(' ').append(level.name.padEnd(5))
            append(" [").append(tag).append("] ")
            append(redact(message))
            if (throwable != null) {
                append(" | ").append(throwable.javaClass.simpleName).append(": ")
                append(redact(throwable.message ?: ""))
            }
            append('\n')
        }
        // 同时镜像到 logcat，方便 adb 侧实时抓取（内容已脱敏）
        runCatching {
            Log.println(
                when (level) {
                    Level.DEBUG -> Log.DEBUG
                    Level.INFO -> Log.INFO
                    Level.WARN -> Log.WARN
                    Level.ERROR -> Log.ERROR
                },
                "adv/$tag",
                redact(message) + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
            )
        }
        executor.execute {
            runCatching {
                if (!logDir.exists()) logDir.mkdirs()
                val file = currentLogFile()
                rotateIfNeeded(file)
                file.appendText(line)
            }
        }
    }

    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)

    /** 最近一次日志文件；诊断页展示与导出用。 */
    fun latestLogFile(): File? =
        logDir.listFiles { f -> f.isFile && f.extension == "log" }?.maxByOrNull { it.lastModified() }

    fun flushAndShutdown() {
        runCatching {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun currentLogFile(): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(logDir, "run-$day.log")
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val rolled = File(file.parentFile, "${file.nameWithoutExtension}-${System.currentTimeMillis()}.log")
        file.renameTo(rolled)
        val all = file.parentFile?.listFiles { f -> f.isFile && f.extension == "log" }
            ?.sortedBy { it.lastModified() } ?: return
        all.dropLast(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_FILES = 10
    }
}
