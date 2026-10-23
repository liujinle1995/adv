package com.adv.runner.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
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
     * 兼容性判定。所有结论都可解释，不允许"凭感觉判死"。
     */
    fun checkCompat(
        info: ApkInfo,
        deviceSdk: Int,
        deviceAbis: List<String>,
        engineSdkRange: IntRange? = null,
        engineAllows32Bit: Boolean = true
    ): List<CompatIssue> {
        val issues = mutableListOf<CompatIssue>()

        if (info.minSdk > deviceSdk) {
            issues += CompatIssue(
                Severity.BLOCKER,
                "MIN_SDK_TOO_HIGH",
                "该应用要求 Android API ${info.minSdk} 及以上，当前设备为 API $deviceSdk，无法运行。"
            )
        }
        if (engineSdkRange != null && deviceSdk !in engineSdkRange) {
            issues += CompatIssue(
                Severity.BLOCKER,
                "ENGINE_SDK_UNSUPPORTED",
                "当前运行引擎仅声明支持 API ${engineSdkRange.first}–${engineSdkRange.last}，本机为 API $deviceSdk，需实测确认。"
            )
        }
        if (info.abis.isNotEmpty()) {
            val supported = info.abis.any { it in deviceAbis }
            if (!supported) {
                issues += CompatIssue(
                    Severity.BLOCKER,
                    "ABI_MISMATCH",
                    "该应用包含原生库（${info.abis.joinToString()}），与设备 ABI（${deviceAbis.joinToString()}）不匹配。"
                )
            }
            val only32Bit = info.abis.all { it == "armeabi-v7a" || it == "armeabi" || it == "x86" }
            if (only32Bit && !engineAllows32Bit) {
                issues += CompatIssue(
                    Severity.BLOCKER,
                    "ONLY_32BIT",
                    "该应用仅有 32 位原生库，当前引擎/设备不支持 32 位应用。"
                )
            }
        }
        if (info.targetSdk < 23) {
            issues += CompatIssue(
                Severity.WARN,
                "TARGET_SDK_TOO_LOW",
                "targetSdk=${info.targetSdk} 过低。系统安装器通常会拒绝此类 APK；沙箱内是否受限需实测。"
            )
        } else if (info.targetSdk <= deviceSdk - 4) {
            issues += CompatIssue(
                Severity.WARN,
                "TARGET_SDK_OLD",
                "targetSdk=${info.targetSdk} 明显低于设备 API $deviceSdk，可能命中系统行为变更。"
            )
        }
        if (info.permissions.any { it.dangerous }) {
            issues += CompatIssue(
                Severity.INFO,
                "DANGEROUS_PERMISSIONS",
                "该应用声明了 ${info.permissions.count { it.dangerous }} 个危险权限，请在详情页逐项确认。"
            )
        }
        return issues
    }

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

    private fun protectionLevelText(level: Int): String = when (level and PROTECTION_BASE_MASK) {
        PROTECTION_NORMAL -> "normal"
        PROTECTION_DANGEROUS -> "dangerous"
        PROTECTION_SIGNATURE -> "signature"
        PROTECTION_SIGNATURE_OR_SYSTEM -> "signature|system"
        else -> "0x${level.toString(16)}"
    }

    private companion object {
        const val PROTECTION_BASE_MASK = 0xF
        const val PROTECTION_NORMAL = 0
        const val PROTECTION_DANGEROUS = 1
        const val PROTECTION_SIGNATURE = 2
        const val PROTECTION_SIGNATURE_OR_SYSTEM = 3
    }
}
