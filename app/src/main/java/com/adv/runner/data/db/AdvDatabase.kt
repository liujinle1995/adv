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
 * `exportSchema` 暂时关闭；等进入需要验证迁移的阶段，再打开并配置 schemaLocation。
 */
@Database(
    entities = [
        ApkInfoEntity::class,
        RuntimeInstanceEntity::class,
        RunEventEntity::class,
        EngineProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AdvDatabase : RoomDatabase() {
    abstract fun apkInfoDao(): ApkInfoDao
    abstract fun runtimeInstanceDao(): RuntimeInstanceDao
    abstract fun runEventDao(): RunEventDao
    abstract fun engineProfileDao(): EngineProfileDao
}
