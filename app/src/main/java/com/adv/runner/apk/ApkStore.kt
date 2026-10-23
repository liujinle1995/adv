package com.adv.runner.apk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * APK 文件的私有目录存储与去重。
 *
 * 职责：
 * 1. 把 SAF 选中的 APK 复制到宿主私有目录，路径稳定（不随系统缓存清理丢失）；
 * 2. 以内容 SHA-256 命名，重复导入同一文件时复用已存在文件，不重复占空间。
 *
 * 命名：`dir/<sha256>.apk`。副本在宿主进程内，供 ApkAnalyzer 与引擎安装复用。
 */
class ApkStore(private val dir: File) {

    /**
     * 导入并去重。返回最终落盘文件（一定是 `dir/<sha256>.apk`）。
     * 抛出异常时不会留下半截临时文件。
     */
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "import-${System.currentTimeMillis()}.tmp")
        val sha256 = try {
            copyAndHash(context, uri, tmp)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        val target = fileFor(sha256)
        when {
            target.exists() -> runCatching { tmp.delete() }
            tmp.renameTo(target) -> Unit
            else -> {
                tmp.copyTo(target, overwrite = true)
                runCatching { tmp.delete() }
            }
        }
        target
    }

    fun fileFor(sha256: String): File = File(dir, "$sha256.apk")

    private fun copyAndHash(context: Context, uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法读取所选文件")
        input.use { source ->
            target.outputStream().use { dest ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    dest.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
