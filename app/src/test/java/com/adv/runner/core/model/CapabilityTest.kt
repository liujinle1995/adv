package com.adv.runner.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎能力三态的单测。
 *
 * 第一条用例是「防回归」：以前这里是四态（SUPPORTED / PARTIAL / UNSUPPORTED / UNKNOWN），
 * 每个调用点都得自己解释 PARTIAL 算支持还是不支持，导致同一份能力在不同页面结论不一致。
 * 现在只允许三态，谁再加回模糊档位，这条用例就会红。
 */
class CapabilityTest {

    @Test
    fun `能力只有三种取值`() {
        assertEquals(
            listOf("SUPPORTED", "UNSUPPORTED", "UNKNOWN"),
            Capability.entries.map { it.name }
        )
    }

    @Test
    fun `已确认支持才允许直接执行`() {
        assertEquals(CapabilityDecision.ALLOW, Capability.SUPPORTED.decide())
    }

    @Test
    fun `已确认不支持必须拦住`() {
        assertEquals(CapabilityDecision.BLOCK, Capability.UNSUPPORTED.decide())
    }

    @Test
    fun `未验证要用户确认而不是默默放行或默默拒绝`() {
        assertEquals(CapabilityDecision.CONFIRM, Capability.UNKNOWN.decide())
    }

    @Test
    fun `引擎没报这个字段等价于未验证`() {
        val missing: Capability? = null
        assertEquals(CapabilityDecision.CONFIRM, missing.decideOrUnknown())
    }

    @Test
    fun `只有支持和不支持算已知结论`() {
        assertTrue(Capability.SUPPORTED.isKnown)
        assertTrue(Capability.UNSUPPORTED.isKnown)
        assertFalse(Capability.UNKNOWN.isKnown)
    }

    @Test
    fun `未验证不算支持`() {
        assertFalse(Capability.UNKNOWN.isConfirmedSupported)
        assertTrue(Capability.SUPPORTED.isConfirmedSupported)
    }
}
