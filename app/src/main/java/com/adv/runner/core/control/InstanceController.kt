package com.adv.runner.core.control

import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.diagnostics.RunLogger
import com.adv.runner.engine.api.VirtualizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 实例状态机与持久化对账。
 *
 * 职责：
 * 1. 把磁盘上的实例重新挂回引擎（[VirtualizationEngine.attachExisting]）；
 * 2. 用引擎的 [VirtualizationEngine.queryState] 取证，修正「撒谎」的状态；
 * 3. 把结果落盘，并**如实上报**任何一步的失败。
 *
 * 失败语义（这是本类最容易被改坏的地方）：
 * - 读盘失败：返回 [ReconcileOutcome.loadError]，**绝不返回空列表**冒充「没有实例」；
 * - 单条取证失败：该条保持磁盘状态并记入 [ReconcileOutcome.failures]，其它实例照常；
 * - 落盘失败：内存结果照样交给上层（用户能操作），但 [ReconcileOutcome.saveError]
 *   必须让上层提示「本次改动没写进磁盘」。
 *
 * 状态修正规则见 [InstanceStateMachine]。
 */
class InstanceController(
    private val store: InstanceDataSource,
    private val engine: VirtualizationEngine,
    private val logger: RunLogger? = null
) {

    suspend fun reconcile(): ReconcileOutcome = withContext(Dispatchers.IO) {
        val saved = try {
            store.load()
        } catch (t: Throwable) {
            logger?.error(TAG, "读取实例清单失败", t)
            return@withContext ReconcileOutcome(
                instances = emptyList(),
                loadError = describeFailure(t)
            )
        }

        if (saved.isEmpty()) {
            return@withContext ReconcileOutcome(
                instances = emptyList(),
                nothingToReconcile = true
            )
        }

        val failures = mutableListOf<ReconcileFailure>()
        val reconciled = saved.map { instance ->
            try {
                reconcileOne(instance)
            } catch (t: Throwable) {
                logger?.warn(TAG, "实例对账失败（保留磁盘状态）：${instance.packageName}", t)
                failures += ReconcileFailure(
                    instanceId = instance.id,
                    packageName = instance.packageName,
                    stage = ReconcileFailure.Stage.PROBE,
                    message = describeFailure(t)
                )
                instance
            }
        }

        val saveError = try {
            store.save(reconciled)
            null
        } catch (t: Throwable) {
            logger?.error(TAG, "对账结果落盘失败（磁盘保持上一份）", t)
            describeFailure(t)
        }

        ReconcileOutcome(
            instances = reconciled,
            saveError = saveError,
            failures = failures
        )
    }

    private suspend fun reconcileOne(instance: RuntimeInstance): RuntimeInstance {
        // 先重建引擎内存映射，否则 queryState 只会得到 NOT_INSTALLED。
        // 重挂失败不致命（下一步会取证），但必须留痕，不能静默。
        val attachError = runCatching { engine.attachExisting(instance) }.exceptionOrNull()
        if (attachError != null) {
            logger?.warn(TAG, "重挂实例到引擎失败：${instance.packageName}", attachError)
        }

        val probe = when (val result = engine.queryState(instance.id)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> {
                if (attachError != null) {
                    // 重挂失败 + 取证失败：这条实例无法确认，交给上层记为失败明细
                    throw IllegalStateException(
                        "取证失败：${result.code} ${result.message}" +
                            "（重挂也失败：${describeFailure(attachError)}）"
                    )
                }
                logger?.warn(
                    TAG,
                    "取证失败，沿用磁盘状态：${instance.packageName} ${result.code} ${result.message}"
                )
                return InstanceStateMachine.onProbeUnavailable(instance)
            }
        }

        if (!InstanceStateMachine.canTransition(instance.state, probe.state)) {
            // 引擎是事实来源，状态照样以它为准；只把异常跳变留痕，便于发现引擎 bug
            logger?.warn(
                TAG,
                "非法状态转换：${instance.packageName} ${instance.state} -> ${probe.state}（以引擎为准）"
            )
        }
        return InstanceStateMachine.onProbeAvailable(instance, probe)
    }

    private companion object {
        const val TAG = "InstanceController"
    }
}
