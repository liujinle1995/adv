package com.adv.runner.core.control

import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.SignatureMode
import com.adv.runner.engine.api.RuntimeProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机单测：状态收敛与「异常退出」推断。
 *
 * 这是用户最容易感知到的一类 bug（"我明明点过停止/它明明崩了，界面却还显示运行中"），
 * 所以把规则从控制器里抽出来单测，而不是靠真机点一遍。
 */
class InstanceStateMachineTest {

    private fun instance(state: RuntimeState, lastExit: ExitReason? = null) = RuntimeInstance(
        id = "inst-1",
        packageName = "com.example.app",
        displayName = "示例",
        engineId = "blackbox",
        virtualUserId = 1,
        state = state,
        signatureMode = SignatureMode.FIRST_INSTALL,
        installedVersionCode = 1L,
        dataRoot = "/data/local/tmp/engine/1",
        lastExitReason = lastExit
    )

    private fun probe(state: RuntimeState, exit: ExitReason? = null) =
        RuntimeProbe(state = state, evidence = "test", exitReason = exit)

    @Test
    fun `运行中和启动中才算活跃`() {
        assertTrue(InstanceStateMachine.isActive(RuntimeState.RUNNING))
        assertTrue(InstanceStateMachine.isActive(RuntimeState.STARTING))
        // 崩溃不是活跃：否则界面上会显示"正在运行"却点不动任何东西
        assertFalse(InstanceStateMachine.isActive(RuntimeState.CRASHED))
        assertFalse(InstanceStateMachine.isActive(RuntimeState.ANR))
        assertFalse(InstanceStateMachine.isActive(RuntimeState.INSTALLED))
    }

    @Test
    fun `过渡态集合覆盖所有做到一半会被打断的状态`() {
        val expected = setOf(
            RuntimeState.STARTING, RuntimeState.RUNNING, RuntimeState.INSTALLING,
            RuntimeState.CLEARING, RuntimeState.DELETING
        )
        assertEquals(expected, InstanceStateMachine.TRANSITIONAL)
    }

    @Test
    fun `启动中走到运行中是合法的`() {
        assertTrue(
            InstanceStateMachine.canTransition(RuntimeState.STARTING, RuntimeState.RUNNING)
        )
    }

    @Test
    fun `自己到自己永远合法`() {
        RuntimeState.entries.forEach { state ->
            assertTrue("$state -> $state", InstanceStateMachine.canTransition(state, state))
        }
    }

    @Test
    fun `运行中不能凭空跳到安装中`() {
        assertFalse(
            InstanceStateMachine.canTransition(RuntimeState.RUNNING, RuntimeState.INSTALLING)
        )
    }

    @Test
    fun `已安装不能直接变成运行中（必须先经过启动中）`() {
        assertFalse(
            InstanceStateMachine.canTransition(RuntimeState.INSTALLED, RuntimeState.RUNNING)
        )
    }

    @Test
    fun `崩溃后可以重新启动也可以删除`() {
        assertTrue(
            InstanceStateMachine.canTransition(RuntimeState.CRASHED, RuntimeState.STARTING)
        )
        assertTrue(
            InstanceStateMachine.canTransition(RuntimeState.CRASHED, RuntimeState.DELETING)
        )
    }

    @Test
    fun `启动中被杀推断为系统杀死`() {
        assertEquals(
            ExitReason.KILLED_BY_SYSTEM,
            InstanceStateMachine.inferExitReason(RuntimeState.STARTING)
        )
    }

    @Test
    fun `运行中被杀推断为系统杀死`() {
        assertEquals(
            ExitReason.KILLED_BY_SYSTEM,
            InstanceStateMachine.inferExitReason(RuntimeState.RUNNING)
        )
    }

    @Test
    fun `安装一半被打断无法判断原因`() {
        assertEquals(
            ExitReason.UNKNOWN,
            InstanceStateMachine.inferExitReason(RuntimeState.INSTALLING)
        )
        assertEquals(
            ExitReason.UNKNOWN,
            InstanceStateMachine.inferExitReason(RuntimeState.CLEARING)
        )
    }

    @Test
    fun `取证失败时运行中降级为未知中断`() {
        val result = InstanceStateMachine.onProbeUnavailable(instance(RuntimeState.RUNNING))
        assertEquals(RuntimeState.UNKNOWN_INTERRUPTED, result.state)
        assertEquals(ExitReason.UNKNOWN, result.lastExitReason)
    }

    @Test
    fun `取证失败时已安装状态保持不动`() {
        val original = instance(RuntimeState.INSTALLED)
        assertEquals(original, InstanceStateMachine.onProbeUnavailable(original))
    }

    @Test
    fun `引擎说停了就记成停止并写入退出原因`() {
        val result = InstanceStateMachine.onProbeAvailable(
            instance(RuntimeState.RUNNING),
            probe(RuntimeState.STOPPED, ExitReason.CRASHED)
        )
        assertEquals(RuntimeState.STOPPED, result.state)
        assertEquals(ExitReason.CRASHED, result.lastExitReason)
    }

    @Test
    fun `引擎只说停了不说原因时按过渡态推断`() {
        val result = InstanceStateMachine.onProbeAvailable(
            instance(RuntimeState.RUNNING),
            probe(RuntimeState.STOPPED, exit = null)
        )
        assertEquals(RuntimeState.STOPPED, result.state)
        assertEquals(ExitReason.KILLED_BY_SYSTEM, result.lastExitReason)
    }

    @Test
    fun `引擎说还在跑时清掉历史退出原因`() {
        val result = InstanceStateMachine.onProbeAvailable(
            instance(RuntimeState.RUNNING, lastExit = ExitReason.CRASHED),
            probe(RuntimeState.RUNNING)
        )
        assertEquals(RuntimeState.RUNNING, result.state)
        assertEquals(ExitReason.CRASHED, result.lastExitReason)
    }

    @Test
    fun `非过渡态实例取证不会写入退出原因`() {
        val result = InstanceStateMachine.onProbeAvailable(
            instance(RuntimeState.INSTALLED),
            probe(RuntimeState.NOT_INSTALLED)
        )
        assertEquals(RuntimeState.NOT_INSTALLED, result.state)
        // 没有"上次运行被打断"这回事，不该凭空补一个退出原因
        assertNull(result.lastExitReason)
    }
}
