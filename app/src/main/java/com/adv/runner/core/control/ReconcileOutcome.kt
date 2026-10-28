package com.adv.runner.core.control

import com.adv.runner.core.model.RuntimeInstance

/**
 * 单条实例在对账中失败（引擎取证抛异常、槽位重挂失败等）。
 *
 * 单条失败不影响其它实例，但**必须被上报**：以前这里是 `runCatching{}` 全吞，
 * 结果就是「实例看着在，但状态永远是错的」这种无从排查的问题。
 */
data class ReconcileFailure(
    val instanceId: String,
    val packageName: String,
    val stage: Stage,
    val message: String
) {
    enum class Stage { ATTACH, PROBE }
}

/**
 * 对账结果。刻意不是 `List<RuntimeInstance>`：
 * 只有列表就没法区分「真的没有实例」和「读失败了」，调用方只能一律当成空。
 */
data class ReconcileOutcome(
    /** 对账后的实例清单（读取失败时为空，且必须结合 [loadError] 判断）。 */
    val instances: List<RuntimeInstance>,
    /** 读取磁盘清单失败。非空时 [instances] 无意义，上层必须走失败分支。 */
    val loadError: String? = null,
    /** 对账结果落盘失败。内存结果是可信的，但下次启动会读到旧状态。 */
    val saveError: String? = null,
    /** 逐条实例的失败明细。 */
    val failures: List<ReconcileFailure> = emptyList(),
    /** 磁盘上本来就没有实例，属于正常路径，不算失败。 */
    val nothingToReconcile: Boolean = false
) {
    val isFatal: Boolean get() = loadError != null

    val hasWarnings: Boolean get() = saveError != null || failures.isNotEmpty()
}

/** 把异常压成单行可展示文本（不吞堆栈，堆栈另有 logger 记录）。 */
internal fun describeFailure(t: Throwable): String =
    "${t.javaClass.simpleName}: ${t.message ?: "无错误信息"}"
