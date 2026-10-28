package com.adv.runner.apk

import com.adv.runner.core.model.CompatIssue
import com.adv.runner.core.model.Severity

/**
 * APK 兼容性判定（纯函数，无 Android 依赖）。
 *
 * 从 [ApkAnalyzer] 里抽出来的原因：这段规则是「导入时判死」的唯一依据，
 * 需要被测试矩阵穷举（系统版本 × ABI × 厂商 ROM），不能藏在依赖 `PackageManager` 的类里。
 *
 * 每条结论都必须可解释：给定输入能reproduce，给定输出能说清依据。
 */
data class ApkCompatInput(
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val dangerousPermissionCount: Int
) {
    val hasNativeLibs: Boolean get() = abis.isNotEmpty()

    /** 只带 32 位原生库（现实中 v7a-only 的老包很常见）。 */
    val only32Bit: Boolean
        get() = abis.isNotEmpty() && abis.all {
            it == "armeabi-v7a" || it == "armeabi" || it == "x86"
        }
}

object CompatibilityRules {

    fun check(
        apk: ApkCompatInput,
        device: DeviceProfile,
        engineSdkRange: IntRange? = null,
        engineAllows32Bit: Boolean = true
    ): List<CompatIssue> {
        val issues = mutableListOf<CompatIssue>()

        if (apk.minSdk > device.sdkInt) {
            issues += CompatIssue(
                Severity.BLOCKER,
                "MIN_SDK_TOO_HIGH",
                "该应用要求 Android API ${apk.minSdk} 及以上，当前设备为 API ${device.sdkInt}，无法运行。"
            )
        }
        if (engineSdkRange != null && device.sdkInt !in engineSdkRange) {
            issues += CompatIssue(
                Severity.BLOCKER,
                "ENGINE_SDK_UNSUPPORTED",
                "当前运行引擎仅声明支持 API ${engineSdkRange.first}–${engineSdkRange.last}，" +
                    "本机为 API ${device.sdkInt}，需实测确认。"
            )
        }
        if (apk.hasNativeLibs) {
            if (!device.supportsAnyAbi(apk.abis)) {
                issues += CompatIssue(
                    Severity.BLOCKER,
                    "ABI_MISMATCH",
                    "该应用包含原生库（${apk.abis.joinToString()}），" +
                        "与设备 ABI（${device.abis.joinToString()}）不匹配。"
                )
            }
            // 引擎放行但设备没有 32 位运行时的情况不用单独判：
            // 此时 supportsAnyAbi 必然为 false，上面已经判过 ABI_MISMATCH。
            if (apk.only32Bit && !engineAllows32Bit) {
                issues += CompatIssue(
                    Severity.BLOCKER,
                    "ONLY_32BIT",
                    "该应用仅有 32 位原生库，当前引擎/设备不支持 32 位应用。"
                )
            }
        }
        if (apk.targetSdk < 23) {
            issues += CompatIssue(
                Severity.WARN,
                "TARGET_SDK_TOO_LOW",
                "targetSdk=${apk.targetSdk} 过低。系统安装器通常会拒绝此类 APK；沙箱内是否受限需实测。"
            )
        } else if (apk.targetSdk <= device.sdkInt - 4) {
            issues += CompatIssue(
                Severity.WARN,
                "TARGET_SDK_OLD",
                "targetSdk=${apk.targetSdk} 明显低于设备 API ${device.sdkInt}，可能命中系统行为变更。"
            )
        }
        if (apk.dangerousPermissionCount > 0) {
            issues += CompatIssue(
                Severity.INFO,
                "DANGEROUS_PERMISSIONS",
                "该应用声明了 ${apk.dangerousPermissionCount} 个危险权限，请在详情页逐项确认。"
            )
        }
        return issues
    }
}
