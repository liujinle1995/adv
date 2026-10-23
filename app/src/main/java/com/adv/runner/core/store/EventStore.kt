package com.adv.runner.core.store

import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RunEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 运行事件存储（JSONL：每行一个 JSON 对象）。
 *
 * 阶段 2 用追加式文件方案，不引入 Room：事件只增不改、按实例查询，
 * JSONL 天然适合追加写 + 顺序读，损坏时最多丢最后半行，不影响历史。
 *
 * 约定：
 * 1. 追加写单行序列化，串行写入（Mutex），避免并发写坏文件；
 * 2. id 单调递增（重启后从文件尾恢复），供详情页稳定排序；
 * 3. 查询按 instanceId 过滤，返回时间倒序（最新在前），受 [limit] 约束。
 */
class EventStore(private val file: File) {

    private val mutex = Mutex()

    /**
     * 追加一条事件并回填 id。返回最终落盘的事件（id 已分配）。
     */
    suspend fun append(event: RunEvent): RunEvent = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val stored = event.copy(id = nextId())
            runCatching { file.appendText(serialize(stored).toString() + "\n") }
            stored
        }
    }

    /**
     * 查询事件。`instanceId == null` 时返回全部（宿主级事件）。
     * 返回时间倒序（最新在前），最多 [limit] 条。
     */
    suspend fun query(instanceId: String?, limit: Int = 200): List<RunEvent> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            val events: List<RunEvent> = runCatching {
                file.readLines()
                    .mapNotNull { line -> runCatching { parse(JSONObject(line)) }.getOrNull() }
                    .filter { instanceId == null || it.instanceId == instanceId }
                    .takeLast(limit)
                    .reversed()
            }.getOrDefault(emptyList())
            events
        }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun nextId(): Long {
        if (!file.isFile) return 1L
        val max: Long? = runCatching {
            file.readLines()
                .mapNotNull { line -> runCatching { JSONObject(line).optLong("id") }.getOrNull() }
                .maxOrNull()
        }.getOrNull()
        return (max ?: 0L) + 1
    }

    private fun serialize(e: RunEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("instanceId", e.instanceId ?: JSONObject.NULL)
        put("packageName", e.packageName ?: JSONObject.NULL)
        put("ts", e.ts)
        put("level", e.level)
        put("type", e.type.name)
        put("code", e.code ?: JSONObject.NULL)
        put("message", e.message)
    }

    private fun parse(o: JSONObject): RunEvent = RunEvent(
        id = o.optLong("id"),
        instanceId = if (o.isNull("instanceId")) null else o.optString("instanceId"),
        packageName = if (o.isNull("packageName")) null else o.optString("packageName"),
        ts = o.optLong("ts"),
        level = o.optString("level", "INFO"),
        type = runCatching { RunEventType.valueOf(o.optString("type")) }
            .getOrDefault(RunEventType.RECONCILE),
        code = if (o.isNull("code")) null else o.optString("code"),
        message = o.optString("message")
    )
}
