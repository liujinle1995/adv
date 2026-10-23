package com.adv.runner.data

import androidx.room.withTransaction
import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.data.db.AdvDatabase
import com.adv.runner.data.db.toEntity
import com.adv.runner.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 数据仓库层：替换阶段 2 的 JSON（InstanceStore）/ JSONL（EventStore）文件存储。
 *
 * 接口刻意保持与旧 Store 的语义一致（整体读写 / 追加 + 查询），
 * 这样 [com.adv.runner.ui.AppState] 与 [com.adv.runner.core.control.InstanceController]
 * 的切换成本最小。
 */

class ApkRepository(private val db: AdvDatabase) {

    suspend fun upsert(apk: ApkInfo) {
        db.apkInfoDao().upsert(apk.toEntity())
    }

    suspend fun getBySha256(sha256: String): ApkInfo? =
        db.apkInfoDao().getBySha256(sha256)?.toModel()

    suspend fun getAll(): List<ApkInfo> =
        db.apkInfoDao().getAll().map { it.toModel() }

    fun observeAll(): Flow<List<ApkInfo>> =
        db.apkInfoDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun delete(sha256: String) {
        db.apkInfoDao().deleteById(sha256)
    }
}

class InstanceRepository(private val db: AdvDatabase) {

    suspend fun load(): List<RuntimeInstance> =
        db.runtimeInstanceDao().getAll().map { it.toModel() }

    /** 整体替换：事务内清空重写，语义等价于旧 JSON 文件的「整文件覆盖」。 */
    suspend fun save(list: List<RuntimeInstance>) {
        db.withTransaction {
            db.runtimeInstanceDao().deleteAll()
            db.runtimeInstanceDao().upsertAll(list.map { it.toEntity() })
        }
    }

    suspend fun delete(id: String) {
        db.runtimeInstanceDao().deleteById(id)
    }
}

class EventRepository(private val db: AdvDatabase) {

    /** 追加一条事件并回填自增 id（Room 保证线程安全，无需再手动 Mutex 串行）。 */
    suspend fun append(event: RunEvent): RunEvent {
        val id = db.runEventDao().insert(event.toEntity())
        return event.copy(id = id)
    }

    /** 整体替换（旧 JSONL 一次性迁入用）：事务内清空重写，id 交给 Room 自增以保持时间单调。 */
    suspend fun replaceAll(events: List<RunEvent>) {
        db.withTransaction {
            db.runEventDao().deleteAll()
            db.runEventDao().insertAll(events.map { it.copy(id = 0).toEntity() })
        }
    }

    /** 按实例查询，时间倒序（最新在前），受 [limit] 约束。 */
    suspend fun query(instanceId: String?, limit: Int = 200): List<RunEvent> =
        db.runEventDao().getByInstance(instanceId, limit).map { it.toModel() }

    /** 保留策略：只保留最近 [keep] 条或 [keepDays] 天。 */
    suspend fun trim(keep: Int = 2000, keepDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        db.runEventDao().trim(keep, cutoff)
    }
}
