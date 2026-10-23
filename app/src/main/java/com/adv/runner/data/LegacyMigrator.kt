package com.adv.runner.data

import android.content.Context
import com.adv.runner.core.store.EventStore
import com.adv.runner.core.store.InstanceStore
import java.io.File

/**
 * 一次性把阶段 2 的文件存储迁入 Room：
 * - `instances.json`（JSON）→ [InstanceRepository]
 * - `events.jsonl`（JSONL）→ [EventRepository]
 *
 * 约束：
 * 1. 幂等——实例与事件均采用「清空重写」，迁移被中断后重跑也不会产生重复数据；
 * 2. 只在旧文件存在时才动作，成功后把旧文件改名 `.bak` 保留回滚，不直接删除；
 * 3. 迁移本身不抛异常中断启动（由上层对账用引擎真实状态兜底）。
 */
class LegacyMigrator(
    private val context: Context,
    private val instances: InstanceRepository,
    private val events: EventRepository
) {

    suspend fun migrate() {
        val dir = context.filesDir
        val instanceFile = File(dir, "instances.json")
        val eventFile = File(dir, "events.jsonl")
        if (!instanceFile.isFile && !eventFile.isFile) return

        // 实例：整体导入（InstanceStore.load 是同步读）
        runCatching { InstanceStore(instanceFile).load() }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { instances.save(it) }

        // 事件：按旧 id 正序搬运，Room 自增后保持时间单调
        val legacyEvents = try {
            EventStore(eventFile).query(null, Int.MAX_VALUE)
        } catch (t: Throwable) {
            emptyList()
        }
        if (legacyEvents.isNotEmpty()) {
            events.replaceAll(legacyEvents.sortedBy { it.id })
        }

        markMigrated(instanceFile, eventFile)
    }

    private fun markMigrated(instanceFile: File, eventFile: File) {
        runCatching { instanceFile.renameTo(File(instanceFile.parentFile, "instances.json.bak")) }
        runCatching { eventFile.renameTo(File(eventFile.parentFile, "events.jsonl.bak")) }
    }
}
