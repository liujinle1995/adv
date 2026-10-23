package com.adv.runner.core.control

import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.data.InstanceRepository
import com.adv.runner.engine.api.VirtualizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 实例状态机与持久化对账。
 *
 * 职责：
 * 1. 把磁盘上的实例重新挂回引擎（[VirtualizationEngine.attachExisting]）；
 * 2. 用引擎的 [VirtualizationEngine.queryState] 取证，修正「撒谎」的状态；
 * 3. 把结果落盘。
 *
 * 状态修正规则（只降级到可信状态，不凭空制造 RUNNING）：
 * - 磁盘里是 RUNNING/STARTING，但引擎侧已无进程 → 改为引擎返回的真实状态，并记录退出原因；
 * - 磁盘里是 INSTALLING/CLEARING/DELETING（上次走到一半被杀）→ UNKNOWN_INTERRUPTED；
 * - 引擎返回 NOT_INSTALLED（数据被外部清掉）→ NOT_INSTALLED。
 */
class InstanceController(
    private val store: InstanceRepository,
    private val engine: VirtualizationEngine
) {

    suspend fun reconcile(): List<RuntimeInstance> = withContext(Dispatchers.IO) {
        val saved = store.load()
        if (saved.isEmpty()) return@withContext emptyList()

        val reconciled = saved.map { instance ->
            // 先重建引擎内存映射，否则 queryState 只会得到 NOT_INSTALLED
            try {
                engine.attachExisting(instance)
            } catch (t: Throwable) {
                // 重建失败也不阻断对账
            }
            val probe = engine.queryState(instance.id)
            val probed = (probe as? EngineResult.Success)?.value
            if (probed == null) {
                // 取证失败：保持磁盘状态，但要避免把 RUNNING 继续假装下去
                if (instance.state in TRANSITIONAL) {
                    instance.copy(
                        state = RuntimeState.UNKNOWN_INTERRUPTED,
                        lastExitReason = ExitReason.UNKNOWN
                    )
                } else {
                    instance
                }
            } else {
                val exitReason = when {
                    instance.state in TRANSITIONAL && probed.state != RuntimeState.RUNNING ->
                        probed.exitReason ?: inferExit(instance.state)
                    else -> instance.lastExitReason
                }
                instance.copy(state = probed.state, lastExitReason = exitReason)
            }
        }

        store.save(reconciled)
        reconciled
    }

    private fun inferExit(state: RuntimeState): ExitReason = when (state) {
        RuntimeState.STARTING, RuntimeState.RUNNING -> ExitReason.KILLED_BY_SYSTEM
        RuntimeState.INSTALLING, RuntimeState.CLEARING, RuntimeState.DELETING -> ExitReason.UNKNOWN
        else -> ExitReason.UNKNOWN
    }

    private companion object {
        /** 这些状态意味着上次操作进行到一半进程就被杀了。 */
        val TRANSITIONAL = setOf(
            RuntimeState.STARTING,
            RuntimeState.RUNNING,
            RuntimeState.INSTALLING,
            RuntimeState.CLEARING,
            RuntimeState.DELETING
        )
    }
}
