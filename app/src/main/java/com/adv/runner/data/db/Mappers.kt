package com.adv.runner.data.db

import com.adv.runner.core.model.ApkInfo
import com.adv.runner.core.model.ApkPermission
import com.adv.runner.core.model.ComponentSummary
import com.adv.runner.core.model.CompatIssue
import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RunEventType
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.Severity
import com.adv.runner.core.model.SignatureMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 领域模型（`core.model`）与 Room 实体之间的映射。
 *
 * 枚举统一按 `name` 存 TEXT；解析时用安全转换，未知值回退到「中性」默认值，
 * 避免历史数据里出现新枚举值导致崩溃。
 */

/* ---------------- RuntimeInstance ---------------- */

fun RuntimeInstance.toEntity(): RuntimeInstanceEntity = RuntimeInstanceEntity(
    id = id,
    apkId = apkId,
    packageName = packageName,
    displayName = displayName,
    engineId = engineId,
    engineVersion = engineVersion,
    virtualUserId = virtualUserId,
    state = state.name,
    signatureMode = signatureMode.name,
    signatureAckAt = signatureAckAt,
    installedVersionCode = installedVersionCode,
    installedAt = installedAt,
    lastLaunchedAt = lastLaunchedAt,
    lastExitReason = lastExitReason?.name,
    dataRoot = dataRoot,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun RuntimeInstanceEntity.toModel(): RuntimeInstance = RuntimeInstance(
    id = id,
    apkId = apkId,
    packageName = packageName,
    displayName = displayName,
    engineId = engineId,
    engineVersion = engineVersion,
    virtualUserId = virtualUserId,
    state = enumOrDefault(state, RuntimeState.UNKNOWN_INTERRUPTED),
    signatureMode = enumOrDefault(signatureMode, SignatureMode.FIRST_INSTALL),
    signatureAckAt = signatureAckAt,
    installedVersionCode = installedVersionCode,
    installedAt = installedAt,
    lastLaunchedAt = lastLaunchedAt,
    lastExitReason = lastExitReason?.let { enumOrDefault(it, ExitReason.UNKNOWN) },
    dataRoot = dataRoot,
    createdAt = createdAt,
    updatedAt = updatedAt
)

/* ---------------- RunEvent ---------------- */

fun RunEvent.toEntity(): RunEventEntity = RunEventEntity(
    id = id,
    instanceId = instanceId,
    packageName = packageName,
    ts = ts,
    level = level,
    kind = type.name,
    code = code,
    message = message,
    // 诊断正文（log 文件引用 / 详情引用）暂未接入领域模型，先落空，后续阶段补。
    logFile = null,
    detailRef = null
)

fun RunEventEntity.toModel(): RunEvent = RunEvent(
    id = id,
    instanceId = instanceId,
    packageName = packageName,
    ts = ts,
    level = level,
    type = enumOrDefault(kind, RunEventType.RECONCILE),
    code = code,
    message = message
)

/* ---------------- ApkInfo ---------------- */

fun ApkInfo.toEntity(): ApkInfoEntity = ApkInfoEntity(
    id = sha256,
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    appLabel = appLabel,
    sha256 = sha256,
    fileSize = fileSize,
    storedPath = storedPath,
    originalName = originalFileName,
    signerSha256 = stringListToJson(signerSha256),
    signerSubject = signerSubject,
    minSdk = minSdk,
    targetSdk = targetSdk,
    compileSdk = compileSdk,
    abis = stringListToJson(abis),
    permissions = permissionsToJson(permissions),
    launcherActivity = launcherActivity,
    components = componentsToJson(components),
    isSplit = isSplit,
    isDebuggable = isDebuggable,
    importedAt = importedAt
)

fun ApkInfoEntity.toModel(): ApkInfo = ApkInfo(
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    appLabel = appLabel,
    sha256 = sha256,
    fileSize = fileSize,
    storedPath = storedPath,
    originalFileName = originalName,
    signerSha256 = jsonToStringList(signerSha256),
    signerSubject = signerSubject,
    minSdk = minSdk,
    targetSdk = targetSdk,
    compileSdk = compileSdk,
    abis = jsonToStringList(abis),
    permissions = jsonToPermissions(permissions),
    launcherActivity = launcherActivity,
    components = jsonToComponents(components),
    isSplit = isSplit,
    isDebuggable = isDebuggable,
    importedAt = importedAt,
    // 兼容性结论是派生数据，不落库；需要时由 CompatibilityChecker 重算
    issues = emptyList()
)

/* ---------------- JSON 辅助 ---------------- */

private fun stringListToJson(list: List<String>): String = JSONArray(list).toString()

private fun jsonToStringList(json: String): List<String> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { arr.getString(it) }
}.getOrDefault(emptyList())

private fun permissionsToJson(list: List<ApkPermission>): String = JSONArray().apply {
    list.forEach { p ->
        put(JSONObject().apply {
            put("name", p.name)
            put("dangerous", p.dangerous)
            put("protectionLevel", p.protectionLevel)
            put("group", p.group ?: JSONObject.NULL)
        })
    }
}.toString()

private fun jsonToPermissions(json: String): List<ApkPermission> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ApkPermission(
            name = o.optString("name"),
            dangerous = o.optBoolean("dangerous"),
            protectionLevel = o.optString("protectionLevel"),
            group = if (o.isNull("group")) null else o.optString("group")
        )
    }
}.getOrDefault(emptyList())

private fun componentsToJson(c: ComponentSummary): String = JSONObject().apply {
    put("activities", c.activities)
    put("services", c.services)
    put("receivers", c.receivers)
    put("providers", c.providers)
}.toString()

private fun jsonToComponents(json: String): ComponentSummary = runCatching {
    val o = JSONObject(json)
    ComponentSummary(
        activities = o.optInt("activities"),
        services = o.optInt("services"),
        receivers = o.optInt("receivers"),
        providers = o.optInt("providers")
    )
}.getOrDefault(ComponentSummary())

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
