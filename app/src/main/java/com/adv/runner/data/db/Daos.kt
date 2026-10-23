package com.adv.runner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ApkInfoDao {
    @Upsert
    suspend fun upsert(apk: ApkInfoEntity)

    @Query("SELECT * FROM apk_info WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): ApkInfoEntity?

    @Query("SELECT * FROM apk_info ORDER BY imported_at DESC")
    suspend fun getAll(): List<ApkInfoEntity>

    @Query("SELECT * FROM apk_info ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<ApkInfoEntity>>

    @Query("DELETE FROM apk_info WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RuntimeInstanceDao {
    @Upsert
    suspend fun upsert(instance: RuntimeInstanceEntity)

    @Upsert
    suspend fun upsertAll(instances: List<RuntimeInstanceEntity>)

    @Query("DELETE FROM runtime_instance")
    suspend fun deleteAll()

    @Query("SELECT * FROM runtime_instance WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RuntimeInstanceEntity?

    @Query("SELECT * FROM runtime_instance ORDER BY created_at DESC")
    suspend fun getAll(): List<RuntimeInstanceEntity>

    @Query("SELECT * FROM runtime_instance ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RuntimeInstanceEntity>>

    @Query("SELECT * FROM runtime_instance WHERE package_name = :packageName")
    suspend fun getByPackage(packageName: String): List<RuntimeInstanceEntity>

    @Query("DELETE FROM runtime_instance WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RunEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: RunEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<RunEventEntity>)

    @Query("DELETE FROM run_event")
    suspend fun deleteAll()

    @Query("SELECT * FROM run_event WHERE instance_id = :instanceId ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun getByInstance(instanceId: String?, limit: Int = 200): List<RunEventEntity>

    @Query("SELECT * FROM run_event WHERE instance_id = :instanceId ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeByInstance(instanceId: String?, limit: Int = 200): Flow<List<RunEventEntity>>

    /**
     * 保留策略：run_event 只保留最近 [keep] 条或 [keepDays] 天（先到为准）。
     * 返回删除的行数。
     */
    @Query(
        "DELETE FROM run_event WHERE id NOT IN (" +
            "SELECT id FROM run_event ORDER BY ts DESC, id DESC LIMIT :keep" +
            ") AND ts < :cutoff"
    )
    suspend fun trim(keep: Int, cutoff: Long): Int
}

@Dao
interface EngineProfileDao {
    @Upsert
    suspend fun upsert(profile: EngineProfileEntity)

    @Query("SELECT * FROM engine_profile WHERE engine_id = :engineId LIMIT 1")
    suspend fun getByEngineId(engineId: String): EngineProfileEntity?
}
