package com.adv.runner.core.control

import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.engine.api.RuntimeProbe

/**
 * 实例状态机（纯函数，无 Android / 数据库依赖，便于单测覆盖）。
 *
 * 两条独立的规则在这里收口：
 *
 * 1. **合法转换表**：只描述「哪些状态可以走到哪些状态」。它不改变流程，
 *    用于对账时发现异常跳变（例如磁盘写着 RUNNING、引擎说 INSTALLED），
 *    发现后留痕，仍然以引擎取证为准——引擎才是事实来源。
 * 2. **状态收敛**：把「磁盘状态 + 引擎探针结果」折算成最终状态与退出原因。
 *    原则是只降级到可信状态，绝不凭空制造 RUNNING。
 */
object InstanceStateMachine {

    /** 上次操作走到一半进程就被杀了，需要在对账时重新取证。 */
    val TRANSITIONAL: Set<RuntimeState> = setOf(
        RuntimeState.STARTING,
        RuntimeState.RUNNING,
        RuntimeState.INSTALLING,
        RuntimeState.CLEARING,
        RuntimeState.DELETING
    )

    /** 处于「用户认为它在跑」的状态。 */
    fun isActive(state: RuntimeState): Boolean =
        state == RuntimeState.RUNNING || state == RuntimeState.STARTING

    fun isTransitional(state: RuntimeState): Boolean = state in TRANSITIONAL

    /** 合法转换表：`from -> 允许到达的 to`。 */
    private val LEGAL_TRANSITIONS: Map<RuntimeState, Set<RuntimeState>> = mapOf(
        RuntimeState.NOT_INSTALLED to setOf(
            RuntimeState.INSTALLING, RuntimeState.INSTALL_FAILED
        ),
        RuntimeState.INSTALLING to setOf(
            RuntimeState.INSTALLED, RuntimeState.INSTALL_FAILED, RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.INSTALLED to setOf(
            RuntimeState.STARTING, RuntimeState.CLEARING, RuntimeState.DELETING,
            RuntimeState.ENGINE_INCOMPATIBLE, RuntimeState.NOT_INSTALLED,
            RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.STARTING to setOf(
            RuntimeState.RUNNING, RuntimeState.LAUNCH_FAILED, RuntimeState.CRASHED,
            RuntimeState.ANR, RuntimeState.STOPPED, RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.RUNNING to setOf(
            RuntimeState.STOPPED, RuntimeState.CRASHED, RuntimeState.ANR,
            RuntimeState.CLEARING, RuntimeState.DELETING, RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.STOPPED to setOf(
            RuntimeState.STARTING, RuntimeState.CLEARING, RuntimeState.DELETING,
            RuntimeState.NOT_INSTALLED
        ),
        RuntimeState.CRASHED to setOf(
            RuntimeState.STARTING, RuntimeState.CLEARING, RuntimeState.DELETING,
            RuntimeState.NOT_INSTALLED
        ),
        RuntimeState.ANR to setOf(
            RuntimeState.STARTING, RuntimeState.STOPPED, RuntimeState.CLEARING,
            RuntimeState.DELETING, RuntimeState.NOT_INSTALLED
        ),
        RuntimeState.INSTALL_FAILED to setOf(
            RuntimeState.INSTALLING, RuntimeState.DELETING
        ),
        RuntimeState.LAUNCH_FAILED to setOf(
            RuntimeState.STARTING, RuntimeState.CLEARING, RuntimeState.DELETING
        ),
        RuntimeState.CLEARING to setOf(
            RuntimeState.INSTALLED, RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.DELETING to setOf(
            RuntimeState.NOT_INSTALLED, RuntimeState.UNKNOWN_INTERRUPTED
        ),
        RuntimeState.ENGINE_INCOMPATIBLE to setOf(
            RuntimeState.INSTALLING, RuntimeState.DELETING
        ),
        RuntimeState.UNKNOWN_INTERRUPTED to setOf(
            RuntimeState.STARTING, RuntimeState.INSTALLED, RuntimeState.CLEARING,
            RuntimeState.DELETING, RuntimeState.NOT_INSTALLED, RuntimeState.UNKNOWN_INTERRUPTED
        )
    )

    fun canTransition(from: RuntimeState, to: RuntimeState): Boolean {
        if (from == to) return true
        return LEGAL_TRANSITIONS[from]?.contains(to) == true
    }

    /**
     * 上次是过渡态、结果进程没了，退出原因该记什么。
     *
     * STARTING/RUNNING 消失基本是被系统杀掉（用户停止会走 STOPPED 而不是这里）；
     * 安装/清数据/删除做到一半被打断，无法区分原因，记 UNKNOWN。
     */
    fun inferExitReason(diskState: RuntimeState): ExitReason = when (diskState) {
        RuntimeState.STARTING, RuntimeState.RUNNING -> ExitReason.KILLED_BY_SYSTEM
        else -> ExitReason.UNKNOWN
    }

    /** 取证失败（引擎报错/超时）时的收敛结果：不允许继续假装在运行。 */
    fun onProbeUnavailable(instance: RuntimeInstance): RuntimeInstance =
        if (isTransitional(instance.state)) {
            instance.copy(
                state = RuntimeState.UNKNOWN_INTERRUPTED,
                lastExitReason = ExitReason.UNKNOWN
            )
        } else {
            instance
        }

    /** 取证成功时的收敛结果：状态以引擎为准，退出原因按需补写。 */
    fun onProbeAvailable(instance: RuntimeInstance, probe: RuntimeProbe): RuntimeInstance {
        val exitReason = if (
            isTransitional(instance.state) && probe.state != RuntimeState.RUNNING
        ) {
            probe.exitReason ?: inferExitReason(instance.state)
        } else {
            instance.lastExitReason
        }
        return instance.copy(state = probe.state, lastExitReason = exitReason)
    }
}
