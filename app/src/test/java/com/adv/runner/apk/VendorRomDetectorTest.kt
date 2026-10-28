package com.adv.runner.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商 ROM 识别。
 *
 * 只依赖 `Build.MANUFACTURER` / `Build.BRAND`，不读私有 `ro.build.*` 属性
 * （hidden API 会被拦）。识别结果只用于给出后台/悬浮窗提示，不参与放行判定，
 * 所以这里测的是「别把小米认成华为」这种硬错误。
 */
class VendorRomDetectorTest {

    @Test
    fun `小米系三个品牌都归到小米`() {
        assertEquals(VendorRom.XIAOMI, VendorRomDetector.classify("Xiaomi", "Redmi"))
        assertEquals(VendorRom.XIAOMI, VendorRomDetector.classify("Xiaomi", "POCO"))
        assertEquals(VendorRom.XIAOMI, VendorRomDetector.classify("Xiaomi", "Xiaomi"))
    }

    @Test
    fun `荣耀与华为分开识别`() {
        assertEquals(VendorRom.HONOR, VendorRomDetector.classify("HONOR", "HONOR"))
        assertEquals(VendorRom.HUAWEI, VendorRomDetector.classify("HUAWEI", "HUAWEI"))
    }

    @Test
    fun `OPPO 与 realme 同源`() {
        assertEquals(VendorRom.OPPO, VendorRomDetector.classify("OPPO", "OPPO"))
        assertEquals(VendorRom.OPPO, VendorRomDetector.classify("realme", "realme"))
    }

    @Test
    fun `一加要在一加品牌上命中而不是被 OPPO 抢走`() {
        assertEquals(VendorRom.ONEPLUS, VendorRomDetector.classify("OnePlus", "OnePlus"))
    }

    @Test
    fun `vivo 与 iQOO 同源`() {
        assertEquals(VendorRom.VIVO, VendorRomDetector.classify("vivo", "iQOO"))
    }

    @Test
    fun `三星与魅族各自识别`() {
        assertEquals(VendorRom.SAMSUNG, VendorRomDetector.classify("samsung", "samsung"))
        assertEquals(VendorRom.MEIZU, VendorRomDetector.classify("Meizu", "meizu"))
    }

    @Test
    fun `模拟器归为未知而不是其他`() {
        assertEquals(VendorRom.UNKNOWN, VendorRomDetector.classify("unknown", "generic"))
        assertEquals(VendorRom.UNKNOWN, VendorRomDetector.classify("", ""))
        assertEquals(VendorRom.UNKNOWN, VendorRomDetector.classify(null, null))
    }

    @Test
    fun `没收录的厂商归为其他`() {
        assertEquals(VendorRom.OTHER, VendorRomDetector.classify("TECNO", "TECNO"))
    }

    @Test
    fun `限制激进的厂商要给出引导提示`() {
        val restricted = listOf(
            VendorRom.XIAOMI, VendorRom.HONOR, VendorRom.HUAWEI,
            VendorRom.OPPO, VendorRom.VIVO, VendorRom.MEIZU
        )
        restricted.forEach { rom ->
            val notes = VendorRomDetector.knownRestrictions(rom)
            assertTrue("$rom 应该有提示", notes.isNotEmpty())
            assertTrue("$rom 提示要提到悬浮窗", notes.any { it.contains("悬浮窗") })
        }
    }

    @Test
    fun `未知厂商不编造提示`() {
        assertTrue(VendorRomDetector.knownRestrictions(VendorRom.UNKNOWN).isEmpty())
    }
}
