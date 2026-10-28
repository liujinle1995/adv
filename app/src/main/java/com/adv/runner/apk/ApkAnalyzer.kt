package com.adv.runner.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.ApkPermission
import com.adv.runner.core.model.ComponentSummary
import com.adv.runner.core.model.CompatIssue
import com.adv.runner.core.model.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * APK 解析：只读，不安装、不修改目标 APK。
 *
 * 解析手段全部来自公开 API（PackageManager.getPackageArchiveInfo + ZipFile + CertificateFactory），
 * 不使用任何隐藏 API，因此无需反射豁免。
 */
class ApkAnalyzer(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun analyze(
        file: File,
        onProgress: (Float) -> Unit = {}
    ): Result<ApkInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "文件不存在：${file.absolutePath}" }

            val digest = file.sha256(onProgress)
            val signatureFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val flags = signatureFlag or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS

            val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("无法解析该文件：不是合法的 APK，或文件已损坏")
            val appInfo = packageInfo.applicationInfo
                ?: error("APK 缺少 applicationInfo 节点")
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(packageInfo.packageName ?: "未知")

            val signers = extractSigners(packageInfo)
            // 顺带把应用图标落盘到 files/icons/<sha256>.png，供列表/详情展示。
            // 不写数据库：路径由 sha256 推导，取不到文件就当没有图标。
            cacheIcon(appInfo, file, digest)
            val certificates = signers.mapNotNull { signature -> parseCertificate(signature) }
            val signerSha256 = certificates.map { cert -> cert.encoded.sha256Hex() }
            val signerSubject = certificates.firstOrNull()?.subjectX500Principal?.name

            val permissions = (packageInfo.requestedPermissions ?: emptyArray()).map { name ->
                val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
                val protection = info?.protectionLevel ?: 0
                ApkPermission(
                    name = name,
                    dangerous = (protection and PROTECTION_BASE_MASK) == PROTECTION_DANGEROUS,
                    protectionLevel = protectionLevelText(protection),
                    group = info?.group
                )
            }

            val abis = readAbis(file)
            val manifest = BinaryManifestReader.read(file)

            ApkInfo(
                packageName = packageInfo.packageName ?: error("APK 缺少包名"),
                versionName = packageInfo.versionName ?: "未知",
                versionCode = packageInfo.versionCodeCompat(),
                appLabel = label,
                sha256 = digest,
                fileSize = file.length(),
                storedPath = file.absolutePath,
                signerSha256 = signerSha256,
                signerSubject = signerSubject,
                minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
                targetSdk = appInfo.targetSdkVersion,
                compileSdk = manifest?.compileSdk,
                abis = abis,
                permissions = permissions,
                // 入口 Activity 需要解析 IntentFilter，公开 API 对"未安装的 APK"不提供，
                // 因此这里返回 null，由引擎在安装后自行解析入口，避免给出错误信息。
                launcherActivity = null,
                components = ComponentSummary(
                    activities = packageInfo.activities?.size ?: 0,
                    services = packageInfo.services?.size ?: 0,
                    receivers = packageInfo.receivers?.size ?: 0,
                    providers = packageInfo.providers?.size ?: 0
                ),
                // compileSdk / isSplit 来自纯 Kotlin 的 AXML 解析（BinaryManifestReader），不碰系统隐藏 API。
                isSplit = manifest?.isSplit ?: false,
                isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                issues = emptyList()
            )
        }
    }

    /**
     * 兼容性判定。
     *
     * 规则本体在 [CompatibilityRules]（纯函数，可被测试矩阵穷举），
     * 这里只负责把 [ApkInfo] + 设备信息装成输入。
     */
    fun checkCompat(
        info: ApkInfo,
        deviceSdk: Int,
        deviceAbis: List<String>,
        engineSdkRange: IntRange? = null,
        engineAllows32Bit: Boolean = true,
        device: DeviceProfile = DeviceProfile(sdkInt = deviceSdk, abis = deviceAbis)
    ): List<CompatIssue> = CompatibilityRules.check(
        apk = ApkCompatInput(
            minSdk = info.minSdk,
            targetSdk = info.targetSdk,
            abis = info.abis,
            dangerousPermissionCount = info.permissions.count { it.dangerous }
        ),
        device = device,
        engineSdkRange = engineSdkRange,
        engineAllows32Bit = engineAllows32Bit
    )

    /* ---------------- 内部实现 ---------------- */

    @Suppress("DEPRECATION")
    private fun extractSigners(packageInfo: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptyList()
            return if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners.toList()
            } else {
                signingInfo.signingCertificateHistory.toList()
            }
        }
        return packageInfo.signatures?.toList() ?: emptyList()
    }

    private fun parseCertificate(signature: Signature): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun readAbis(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                .mapNotNull { it.split('/').getOrNull(1) }
                .distinct()
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun File.sha256(onProgress: (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = length().coerceAtLeast(1L)
        var done = 0L
        FileInputStream(this).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                done += read
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 补生成图标：给"导入时还没有图标缓存"的历史实例用。
     * 已有缓存直接返回，不重复解码。
     */
    fun ensureIcon(apkFile: File, sha256: String) {
        val out = File(File(context.filesDir, "icons"), "$sha256.png")
        if (out.isFile && out.length() > 0) return
        if (!apkFile.isFile) {
            android.util.Log.w(TAG, "ensureIcon: APK 不存在 ${apkFile.absolutePath}")
            return
        }
        runCatching {
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
            val appInfo = info?.applicationInfo
            if (appInfo == null) {
                android.util.Log.w(TAG, "ensureIcon: archive 解析失败 ${apkFile.name}")
                return
            }
            cacheIcon(appInfo, apkFile, sha256)
            android.util.Log.i(TAG, "ensureIcon: 结果 exists=${out.isFile} size=${out.length()}")
        }.onFailure {
            android.util.Log.w(TAG, "ensureIcon 异常: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 提取 APK 图标并缓存到 `files/icons/<sha256>.png`（144×144）。
     *
     * 未安装的 APK 想取图标，必须先把 [ApplicationInfo.sourceDir] /
     * `publicSourceDir` 指向 APK 路径，否则 `getResourcesForApplication` 会失败。
     * 全程静默失败：图标只是展示用，不能影响解析主流程。
     */
    private fun cacheIcon(appInfo: ApplicationInfo, apkFile: File, sha256: String) {
        runCatching {
            val dir = File(context.filesDir, "icons").apply { mkdirs() }
            val out = File(dir, "$sha256.png")
            if (out.isFile && out.length() > 0) return

            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath

            // 未安装 APK 取图标：loadIcon(pm) 是官方推荐做法（内部读 archive 资源）；
            // 拿不到再退回按资源 id 解。
            val drawable: Drawable = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                ?: runCatching {
                    val res = pm.getResourcesForApplication(appInfo)
                    if (appInfo.icon != 0) res.getDrawableForDensity(appInfo.icon, 480, null) else null
                }.getOrNull()
                ?: run {
                    android.util.Log.w(TAG, "cacheIcon: 无可用 drawable pkg=${appInfo.packageName}")
                    return
                }

            val w = drawable.intrinsicWidth.coerceIn(1, 1024)
            val h = drawable.intrinsicHeight.coerceIn(1, 1024)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bitmap))
            val scaled = Bitmap.createScaledBitmap(bitmap, 144, 144, true)
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }.onFailure {
            android.util.Log.w(TAG, "cacheIcon 异常: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun protectionLevelText(level: Int): String = when (level and PROTECTION_BASE_MASK) {
        PROTECTION_NORMAL -> "normal"
        PROTECTION_DANGEROUS -> "dangerous"
        PROTECTION_SIGNATURE -> "signature"
        PROTECTION_SIGNATURE_OR_SYSTEM -> "signature|system"
        else -> "0x${level.toString(16)}"
    }

    private companion object {
        const val TAG = "adv/ApkAnalyzer"
        const val PROTECTION_BASE_MASK = 0xF
        const val PROTECTION_NORMAL = 0
        const val PROTECTION_DANGEROUS = 1
        const val PROTECTION_SIGNATURE = 2
        const val PROTECTION_SIGNATURE_OR_SYSTEM = 3
    }
}
