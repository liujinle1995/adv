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

    /**
     * 写盘缓冲。
     *
     * 原实现每条日志一次 `File.appendText()`（open/write/close 三次系统调用），
     * 虚拟 App 启动时会密集打日志，这部分 IO 会挤占主线程外的调度。
     * 改为：先进内存缓冲，由单线程在短窗口内合并成一次写；WARN/ERROR 立即落盘。
     */
    private val buffer = StringBuilder()
    private val bufferLock = Any()
    private val drainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

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
        synchronized(bufferLock) { buffer.append(line) }
        scheduleDrain(immediate = level == Level.WARN || level == Level.ERROR)
    }

    /** 调度一次落盘；短窗口内多次调用会被合并（drainScheduled 去重）。 */
    private fun scheduleDrain(immediate: Boolean) {
        if (!drainScheduled.compareAndSet(false, true)) return
        executor.execute {
            runCatching {
                if (!immediate) Thread.sleep(DRAIN_WINDOW_MS)
                drain()
            }
            drainScheduled.set(false)
            // 落盘期间又有新日志进来：补一次，避免缓冲区滞留
            if (synchronized(bufferLock) { buffer.isNotEmpty() }) {
                scheduleDrain(immediate = false)
            }
        }
    }

    private fun drain() {
        val chunk = synchronized(bufferLock) {
            if (buffer.isEmpty()) return
            val s = buffer.toString()
            buffer.setLength(0)
            s
        }
        if (!logDir.exists()) logDir.mkdirs()
        val file = currentLogFile()
        rotateIfNeeded(file)
        file.appendText(chunk)
    }
    fun debug(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(Level.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)

    /** 最近一次日志文件；诊断页展示与导出用。 */
    /**
     * 读最近 [maxLines] 行，给应用内日志查看用。
     * 只读当前最新的一个文件，避免日志量大时全量扫描。
     */
    fun recentLines(maxLines: Int = 300): List<String> {
        val file = latestLogFile() ?: return emptyList()
        return runCatching { file.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }

    fun latestLogFile(): File? =
        logDir.listFiles { f -> f.isFile && f.extension == "log" }?.maxByOrNull { it.lastModified() }

    /** 立即落盘（导出诊断前调用，保证攒批窗口内的日志也进文件）。 */
    fun flush() {
        runCatching {
            executor.submit { drain() }.get(2, TimeUnit.SECONDS)
        }
    }

    fun flushAndShutdown() {
        runCatching {
            // 先把缓冲区剩余内容落盘，再关线程池
            executor.execute { runCatching { drain() } }
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

        /** 攒批窗口：普通日志在此窗口内的多条合并成一次写盘。 */
        const val DRAIN_WINDOW_MS = 200L
    }
}
