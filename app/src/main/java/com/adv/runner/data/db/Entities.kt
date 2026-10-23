package com.adv.runner.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体层，与 `docs/03-data-model.md` §2 的四张表一一对应。
 *
 * 约定：
 * 1. 枚举一律存 TEXT（`name`），时间戳一律 Long（epoch millis）；
 * 2. 复杂对象（签名集合 / ABI / 权限 / 组件 / 能力表）用 JSON 字符串列，
 *    只做展示与筛查，不做 JOIN 查询，避免过度拆分；
 * 3. 领域模型（`core.model`）与实体解耦，映射放在 Repository 层（任务 3.2）。
 */

/** `apk_info` */
@Entity(
    tableName = "apk_info",
    indices = [Index(value = ["sha256"], unique = true), Index(value = ["package_name", "version_code"])]
)
data class ApkInfoEntity(
    @PrimaryKey val id: String,                       // 用 sha256 作为主键（内容寻址，同内容必同记录）
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "version_name") val versionName: String,
    @ColumnInfo(name = "version_code") val versionCode: Long,
    @ColumnInfo(name = "app_label") val appLabel: String,
    val sha256: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "stored_path") val storedPath: String,
    @ColumnInfo(name = "original_name") val originalName: String?,
    @ColumnInfo(name = "signer_sha256") val signerSha256: String,   // JSON 数组
    @ColumnInfo(name = "signer_subject") val signerSubject: String?,
    @ColumnInfo(name = "min_sdk") val minSdk: Int,
    @ColumnInfo(name = "target_sdk") val targetSdk: Int,
    @ColumnInfo(name = "compile_sdk") val compileSdk: Int?,
    val abis: String,                                                // JSON 数组
    val permissions: String,                                         // JSON 数组
    @ColumnInfo(name = "launcher_activity") val launcherActivity: String?,
    val components: String,                                          // JSON
    @ColumnInfo(name = "is_split") val isSplit: Boolean,
    @ColumnInfo(name = "is_debuggable") val isDebuggable: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long
)

/** `runtime_instance` */
@Entity(
    tableName = "runtime_instance",
    indices = [
        Index(value = ["engine_id", "virtual_user_id"], unique = true),
        Index(value = ["state"]),
        Index(value = ["package_name"])
    ]
)
data class RuntimeInstanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "apk_id") val apkId: String?,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "engine_id") val engineId: String,
    @ColumnInfo(name = "engine_version") val engineVersion: String,
    @ColumnInfo(name = "virtual_user_id") val virtualUserId: Int,
    val state: String,
    @ColumnInfo(name = "signature_mode") val signatureMode: String,
    @ColumnInfo(name = "signature_ack_at") val signatureAckAt: Long?,
    @ColumnInfo(name = "installed_version_code") val installedVersionCode: Long?,
    @ColumnInfo(name = "installed_at") val installedAt: Long?,
    @ColumnInfo(name = "last_launched_at") val lastLaunchedAt: Long?,
    @ColumnInfo(name = "last_exit_reason") val lastExitReason: String?,
    @ColumnInfo(name = "data_root") val dataRoot: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** `run_event`（诊断日志索引，正文在文件里） */
@Entity(
    tableName = "run_event",
    indices = [Index(value = ["instance_id", "ts"]), Index(value = ["ts"])]
)
data class RunEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "instance_id") val instanceId: String?,
    @ColumnInfo(name = "package_name") val packageName: String?,
    val ts: Long,
    val level: String,             // DEBUG/INFO/WARN/ERROR
    val kind: String,              // INIT/INSTALL/LAUNCH/STOP/CRASH/ANR/CLEAR/DELETE/RECONCILE
    val code: String?,             // EngineErrorCode
    val message: String,           // 已脱敏
    @ColumnInfo(name = "log_file") val logFile: String?,
    @ColumnInfo(name = "detail_ref") val detailRef: String?
)

/** `engine_profile`（引擎探测结果缓存） */
@Entity(tableName = "engine_profile")
data class EngineProfileEntity(
    @PrimaryKey @ColumnInfo(name = "engine_id") val engineId: String,
    @ColumnInfo(name = "engine_version") val engineVersion: String,
    @ColumnInfo(name = "device_sdk") val deviceSdk: Int,
    @ColumnInfo(name = "device_abis") val deviceAbis: String,   // JSON
    val capabilities: String,                                   // JSON(EngineCapabilities)
    @ColumnInfo(name = "last_check_at") val lastCheckAt: Long,
    @ColumnInfo(name = "last_result") val lastResult: String    // PASS / FAIL / PARTIAL
)
