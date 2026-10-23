package com.adv.runner.apk

import java.io.File
import java.util.zip.ZipFile

/**
 * 只读解析 APK 内的二进制 AndroidManifest.xml（AXML），
 * 提取公开 API 拿不到的 compileSdkVersion / isSplitRequired。
 *
 * 不依赖系统隐藏 API，纯按 AXML 二进制格式（小端）读取：
 * chunk 头 → 字符串池(StringPool) → StartElement 的 attribute 表。
 *
 * 容错：任何一步解析失败都返回 null，由调用方回退到默认值，不阻断解析主流程。
 */
object BinaryManifestReader {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    data class Metadata(val compileSdk: Int?, val isSplit: Boolean)

    fun read(apkFile: File): Metadata? = runCatching {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@runCatching null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()?.let(::parse)

    private fun parse(b: ByteArray): Metadata {
        // 跳过 XML 文件头（type + headerSize + chunkSize = 8 字节）
        var pos = 8
        var pool: List<String> = emptyList()
        var compileSdk: Int? = null
        var isSplit = false

        while (pos + 8 <= b.size) {
            val type = leShort(b, pos)
            val headerSize = leShort(b, pos + 2)
            val size = leInt(b, pos + 4)
            if (size < headerSize || size < 8 || pos + size > b.size) break

            when (type) {
                RES_STRING_POOL_TYPE -> pool = parseStringPool(b, pos, headerSize)
                RES_XML_START_ELEMENT_TYPE -> {
                    val dataStart = pos + headerSize
                    val nameIndex = leInt(b, dataStart + 12)
                    val name = if (nameIndex in pool.indices) pool[nameIndex] else ""
                    if (name == "manifest") {
                        val attrCount = leShort(b, dataStart + 20)
                        if (attrCount in 0..1024) {
                            val attrs = parseAttributes(b, dataStart + 28, attrCount, pool)
                            attrs["compileSdkVersion"]?.toIntOrNull()?.let { compileSdk = it }
                            if (attrs["isSplitRequired"] == "true" || attrs.containsKey("splitName")) {
                                isSplit = true
                            }
                        }
                    }
                }
            }
            pos += size
        }
        return Metadata(compileSdk, isSplit)
    }

    private fun parseStringPool(b: ByteArray, chunkStart: Int, headerSize: Int): List<String> {
        val dataStart = chunkStart + headerSize
        val stringCount = leInt(b, dataStart)
        if (stringCount < 0 || stringCount > 65535) return emptyList()

        val flags = leInt(b, dataStart + 8)
        val stringsStart = leInt(b, dataStart + 12)
        val isUtf8 = (flags and 0x100) != 0

        val offsetsBase = dataStart + 20
        val stringsBase = dataStart + stringsStart
        if (offsetsBase + stringCount * 4L > b.size) return emptyList()

        val result = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = stringsBase + leInt(b, offsetsBase + i * 4)
            if (off < 0 || off >= b.size) {
                result.add("")
            } else {
                result.add(if (isUtf8) readUtf8(b, off) else readUtf16(b, off))
            }
        }
        return result
    }

    private fun parseAttributes(b: ByteArray, base: Int, count: Int, pool: List<String>): Map<String, String> {
        val map = HashMap<String, String>(count)
        var off = base
        for (i in 0 until count) {
            if (off + 20 > b.size) break
            // attribute: ns(4) + name(4) + rawValue(4) + typedValue(16)
            val nameIndex = leInt(b, off + 4)
            val rawValueIndex = leInt(b, off + 8)
            val dataType = b[off + 15].toInt() and 0xFF
            val data = leInt(b, off + 16)

            val name = if (nameIndex in pool.indices) pool[nameIndex] else ""
            val value = when (dataType) {
                TYPE_STRING -> poolAt(pool, if (rawValueIndex >= 0) rawValueIndex else data)
                TYPE_INT_DEC, TYPE_INT_HEX -> data.toString()
                TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
                else -> poolAt(pool, rawValueIndex)
            }
            if (name.isNotEmpty()) map[name] = value
            off += 20
        }
        return map
    }

    private fun poolAt(pool: List<String>, index: Int): String =
        if (index in pool.indices) pool[index] else ""

    private fun readUtf8(b: ByteArray, off: Int): String {
        var p = off
        var len = b[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (b[p].toInt() and 0xFF)
            p++
        }
        if (len < 0 || p + len > b.size) return ""
        var end = p + len
        if (end > p && b[end - 1] == 0.toByte()) end--
        return String(b, p, end - p, Charsets.UTF_8)
    }

    private fun readUtf16(b: ByteArray, off: Int): String {
        var p = off
        val charCount = leShort(b, p)
        p += 2
        if (p + charCount * 2L > b.size) return ""
        val chars = CharArray(charCount)
        for (i in 0 until charCount) chars[i] = leShort(b, p + i * 2).toChar()
        var end = chars.size
        if (end > 0 && chars[end - 1] == '\u0000') end--
        return String(chars, 0, end)
    }

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
