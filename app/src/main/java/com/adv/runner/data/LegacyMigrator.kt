package com.adv.runner.data

import com.adv.runner.core.control.InstanceDataSource
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.store.EventStore
import com.adv.runner.core.store.InstanceStore
import java.io.File

/**
 * 一次性把阶段 2 的文件存储迁入 Room：
 * - `instances.json`（JSON）→ [InstanceDataSource]
 * - `events.jsonl`（JSONL）→ [EventSink]
 *
 * 三条硬性约束（都是踩过坑之后定下来的）：
 *
 * 1. **读取失败不许当成"没有数据"**：旧实现用 `getOrDefault(emptyList())`，
 *    文件损坏时既没迁移成功、又把原文件改名成 `.bak`，用户的实例清单被静默藏起来。
 *    现在改为严格读取（[InstanceStore.loadStrict] / [EventStore.queryStrict]），
 *    失败就保留原文件、把错误报上去。
 * 2. **迁移不是原子的**：实例与事件分别判定，哪个成功就只改名哪个，避免互相拖累。
 * 3. **幂等**：写入采用「清空重写」，中断后重跑不会产生重复数据。
 */
class LegacyMigrator(
    /** 旧数据所在目录（生产环境是 `context.filesDir`）。 */
    private val dir: File,
    private val instances: InstanceDataSource,
    private val events: EventSink
) {

    suspend fun migrate(): MigrationReport {
        val instanceFile = File(dir, "instances.json")
        val eventFile = File(dir, "events.jsonl")
        if (!instanceFile.isFile && !eventFile.isFile) {
            return MigrationReport(skipped = true)
        }

        var instanceCount = 0
        var instanceError: String? = null
        if (instanceFile.isFile) {
            val loaded = try {
                InstanceStore(instanceFile).loadStrict()
            } catch (t: Throwable) {
                instanceError = "${t.javaClass.simpleName}: ${t.message}"
                null
            }
            if (loaded != null) {
                if (loaded.isEmpty()) {
                    // 空文件属于正常历史遗留，改名即可
                    renameToBak(instanceFile)
                } else {
                    try {
                        instances.save(loaded)
                        instanceCount = loaded.size
                        renameToBak(instanceFile)
                    } catch (t: Throwable) {
                        instanceError = "写入数据库失败：${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            }
        }

        var eventCount = 0
        var eventError: String? = null
        if (eventFile.isFile) {
            val loaded: List<RunEvent>? = try {
                EventStore(eventFile).queryStrict(null, Int.MAX_VALUE)
            } catch (t: Throwable) {
                eventError = "${t.javaClass.simpleName}: ${t.message}"
                null
            }
            if (loaded != null) {
                if (loaded.isEmpty()) {
                    renameToBak(eventFile)
                } else {
                    try {
                        // 旧 id 升序搬运，Room 自增后保持时间单调
                        events.replaceAll(loaded.sortedBy { it.id })
                        eventCount = loaded.size
                        renameToBak(eventFile)
                    } catch (t: Throwable) {
                        eventError = "写入数据库失败：${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            }
        }

        return MigrationReport(
            instanceCount = instanceCount,
            eventCount = eventCount,
            instanceError = instanceError,
            eventError = eventError
        )
    }

    private fun renameToBak(file: File) {
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.bak")) }
    }
}

/** 迁移结果。失败时**原文件仍在原处**，用户可以重试或手工排查。 */
data class MigrationReport(
    val skipped: Boolean = false,
    val instanceCount: Int = 0,
    val eventCount: Int = 0,
    val instanceError: String? = null,
    val eventError: String? = null
) {
    val isSuccess: Boolean get() = instanceError == null && eventError == null

    val hasData: Boolean get() = instanceCount > 0 || eventCount > 0
}
