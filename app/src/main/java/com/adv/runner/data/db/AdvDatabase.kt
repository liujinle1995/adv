package com.adv.runner.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 主数据库 `adv.db`，版本 1。
 *
 * 迁移策略（对齐 `docs/03-data-model.md` §4）：
 * - 升级时显式写 `Migration`，禁止 `fallbackToDestructiveMigration`；
 * - version 1 是全新库，暂无历史迁移；阶段 3.2 会把 JSON/JSONL 文件里的既有数据
 *   一次性迁入，而不是把文件存储当作 Room 的「旧版本」。
 *
 * `exportSchema` 已打开（schemaLocation 见 app/build.gradle.kts）：导出的 JSON 是
 * `DbSchemaTest` 的比对基线，实体改动而忘了升版本会被它拦下。
 */
@Database(
    entities = [
        ApkInfoEntity::class,
        RuntimeInstanceEntity::class,
        RunEventEntity::class,
        EngineProfileEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AdvDatabase : RoomDatabase() {
    abstract fun apkInfoDao(): ApkInfoDao
    abstract fun runtimeInstanceDao(): RuntimeInstanceDao
    abstract fun runEventDao(): RunEventDao
    abstract fun engineProfileDao(): EngineProfileDao
}
