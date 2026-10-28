package com.adv.runner.apk

import com.adv.runner.core.model.CompatIssue
import com.adv.runner.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统版本 × ABI × 厂商 ROM 的判定矩阵。
 *
 * 这些用例对应真机上的三条硬约束（见 `docs/device-matrix.md`）：
 * 目标机只有 arm64-v8a、无 32 位运行时、页大小 4KB。判定错一个就会让用户
 * 导入一堆"装得上但跑不起来"的包，或者在导入阶段就被误判死。
 */
class CompatibilityMatrixTest {

    private val arm64Only = DeviceProfile(
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
        vendorRom = VendorRom.OTHER
    )

    private fun apk(
        minSdk: Int = 24,
        targetSdk: Int = 34,
        abis: List<String> = emptyList(),
        dangerous: Int = 0
    ) = ApkCompatInput(minSdk, targetSdk, abis, dangerous)

    private fun List<CompatIssue>.codes() = map { it.code }.toSet()

    private fun List<CompatIssue>.blockers() =
        filter { it.severity == Severity.BLOCKER }.map { it.code }.toSet()

    /* ---------------- 系统版本矩阵 ---------------- */

    @Test
    fun `Android 12 到 17 都能接受 minSdk 31 的应用`() {
        (31..37).forEach { sdk ->
            val issues = CompatibilityRules.check(
                apk = apk(minSdk = 31),
                device = DeviceProfile(sdkInt = sdk, abis = listOf("arm64-v8a")),
                engineSdkRange = 31..37
            )
            assertTrue("API $sdk 不该有 BLOCKER：${issues.codes()}", issues.blockers().isEmpty())
        }
    }

    @Test
    fun `应用要求的最低版本高于设备时必须拦`() {
        val issues = CompatibilityRules.check(
            apk = apk(minSdk = 34),
            device = DeviceProfile(sdkInt = 31, abis = listOf("arm64-v8a"))
        )
        assertTrue(issues.blockers().contains("MIN_SDK_TOO_HIGH"))
    }

    @Test
    fun `恰好等于设备版本不算过高`() {
        val issues = CompatibilityRules.check(
            apk = apk(minSdk = 33),
            device = DeviceProfile(sdkInt = 33, abis = listOf("arm64-v8a"))
        )
        assertFalse(issues.codes().contains("MIN_SDK_TOO_HIGH"))
    }

    @Test
    fun `设备版本超出引擎声明范围要拦`() {
        val issues = CompatibilityRules.check(
            apk = apk(),
            device = DeviceProfile(sdkInt = 38, abis = listOf("arm64-v8a")),
            engineSdkRange = 31..37
        )
        assertTrue(issues.blockers().contains("ENGINE_SDK_UNSUPPORTED"))
    }

    @Test
    fun `targetSdk 低于 23 要警告但不是拦截`() {
        val issues = CompatibilityRules.check(apk(targetSdk = 21), arm64Only)
        val codes = issues.codes()
        assertTrue(codes.contains("TARGET_SDK_TOO_LOW"))
        assertFalse(issues.blockers().contains("TARGET_SDK_TOO_LOW"))
    }

    @Test
    fun `targetSdk 明显落后于设备要警告`() {
        val issues = CompatibilityRules.check(apk(targetSdk = 28), arm64Only)
        assertTrue(issues.codes().contains("TARGET_SDK_OLD"))
    }

    /* ---------------- ABI 矩阵 ---------------- */

    @Test
    fun `纯 Java 应用任何 ABI 设备都能装`() {
        val issues = CompatibilityRules.check(apk(abis = emptyList()), arm64Only)
        assertTrue(issues.blockers().isEmpty())
    }

    @Test
    fun `arm64 应用装在只有 arm64 的设备上没问题`() {
        val issues = CompatibilityRules.check(apk(abis = listOf("arm64-v8a")), arm64Only)
        assertTrue(issues.blockers().isEmpty())
    }

    @Test
    fun `只有 v7a 的应用装在无 32 位运行时的设备上要拦`() {
        val issues = CompatibilityRules.check(apk(abis = listOf("armeabi-v7a")), arm64Only)
        assertTrue("必须拦住：${issues.codes()}", issues.blockers().contains("ABI_MISMATCH"))
    }

    @Test
    fun `同时带 arm64 和 v7a 的包在 arm64 设备上可装`() {
        val issues = CompatibilityRules.check(
            apk(abis = listOf("arm64-v8a", "armeabi-v7a")),
            arm64Only
        )
        assertTrue(issues.blockers().isEmpty())
    }

    @Test
    fun `带 32 位运行时的老设备上 v7a 包可装`() {
        val legacy = DeviceProfile(
            sdkInt = 31,
            abis = listOf("armeabi-v7a", "arm64-v8a")
        )
        val issues = CompatibilityRules.check(apk(abis = listOf("armeabi-v7a")), legacy)
        // ABI 匹配，但引擎不支持 32 位应用时仍要拦
        assertFalse(issues.codes().contains("ABI_MISMATCH"))

        val blocked = CompatibilityRules.check(
            apk(abis = listOf("armeabi-v7a")),
            legacy,
            engineAllows32Bit = false
        )
        assertTrue(blocked.blockers().contains("ONLY_32BIT"))
    }

    @Test
    fun `x86 包装在 arm 设备上要拦`() {
        val issues = CompatibilityRules.check(
            apk(abis = listOf("x86", "x86_64")),
            arm64Only
        )
        assertTrue(issues.blockers().contains("ABI_MISMATCH"))
    }

    /* ---------------- 组合矩阵 ---------------- */

    @Test
    fun `七个系统版本乘四种 ABI 组合都没有例外路径`() {
        val abiSets = listOf(
            emptyList(),
            listOf("arm64-v8a"),
            listOf("armeabi-v7a"),
            listOf("armeabi-v7a", "arm64-v8a")
        )
        var checked = 0
        (31..37).forEach { sdk ->
            abiSets.forEach { abis ->
                val issues = CompatibilityRules.check(
                    apk = apk(minSdk = 24, targetSdk = 34, abis = abis),
                    device = DeviceProfile(sdkInt = sdk, abis = listOf("arm64-v8a")),
                    engineSdkRange = 31..37
                )
                val hasBlockers = issues.blockers().isNotEmpty()
                val expectedBlock = abis == listOf("armeabi-v7a")
                assertEquals(
                    "API $sdk abis=$abis 判定不符合预期：${issues.codes()}",
                    expectedBlock,
                    hasBlockers
                )
                checked++
            }
        }
        assertEquals(28, checked)
    }

    @Test
    fun `危险权限只提示不拦`() {
        val issues = CompatibilityRules.check(apk(dangerous = 3), arm64Only)
        assertTrue(issues.codes().contains("DANGEROUS_PERMISSIONS"))
        assertTrue(issues.blockers().isEmpty())
    }
}
