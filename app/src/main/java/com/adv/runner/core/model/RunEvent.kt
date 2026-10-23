package com.adv.runner.core.model

/**
 * 运行事件类型。
 *
 * 与 `docs/03-data-model.md` §2.3 `run_event.kind` 对齐，额外加入 INIT
 * （引擎/宿主自身生命周期，不属于某个实例）。
 */
enum class RunEventType { INIT, INSTALL, LAUNCH, STOP, CLEAR, DELETE, CRASH, ANR, RECONCILE }

/**
 * 一条运行事件（诊断日志索引，正文为已脱敏的 message）。
 *
 * 阶段 2 用 JSONL 文件落库（见 [com.adv.runner.core.store.EventStore]），
 * 阶段 3.2 再切 Room 的 `run_event` 表，届时字段语义保持一致。
 *
 * @param id       由 EventStore 分配的单调递增序号
 * @param instanceId 所属实例（INIT 等宿主级事件为 null）
 * @param packageName 冗余包名，便于无实例上下文时也能检索
 * @param ts       epoch millis
 * @param level    DEBUG / INFO / WARN / ERROR
 * @param type     事件类型
 * @param code     可选 EngineErrorCode 名称，失败事件携带
 * @param message  已脱敏的可读描述
 */
data class RunEvent(
    val id: Long,
    val instanceId: String?,
    val packageName: String?,
    val ts: Long,
    val level: String,
    val type: RunEventType,
    val code: String?,
    val message: String
)
