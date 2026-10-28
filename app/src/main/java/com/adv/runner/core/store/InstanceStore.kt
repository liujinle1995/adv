package com.adv.runner.core.store

import com.adv.runner.core.model.ExitReason
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.SignatureMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 实例清单的本地持久化（JSON 文件）。
 *
 * 阶段 2 用文件方案，不引入 Room：实例数量少、字段稳定，文件足够。
 * 阶段 3.2 再切 Room 并做迁移，届时保留本类的读写语义。
 *
 * 写入策略：先写临时文件再原子 rename，避免写到一半被杀导致清单损坏。
 * 损坏时宁可退回空列表，也不能让上层崩溃（对账会用引擎真实状态兜底）。
 */
class InstanceStore(private val file: File) {

    /**
     * 严格读取：任何一条解析不出来就抛异常。
     *
     * 迁移必须用这个入口。[load] 会静默跳过坏记录，用它判断「能否搬迁」会把
     * 「文件已损坏」误判成「文件里少了几条」，然后把原文件改名 .bak 藏起来。
     */
    fun loadStrict(): List<RuntimeInstance> {
        if (!file.isFile) return emptyList()
        val root = JSONArray(file.readText())
        val items = ArrayList<RuntimeInstance>(root.length())
        val errors = mutableListOf<String>()
        for (i in 0 until root.length()) {
            try {
                items += parse(root.getJSONObject(i))
            } catch (t: Throwable) {
                errors += "#$i ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "实例清单有 ${errors.size}/${root.length()} 条无法解析（${errors.first()}）"
            )
        }
        return items
    }

    fun load(): List<RuntimeInstance> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONArray(file.readText())
            (0 until root.length()).mapNotNull { i ->
                runCatching { parse(root.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun save(instances: List<RuntimeInstance>) {
        runCatching {
            val root = JSONArray()
            instances.forEach { root.put(serialize(it)) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            file.parentFile?.mkdirs()
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun serialize(instance: RuntimeInstance): JSONObject = JSONObject().apply {
        put("id", instance.id)
        put("packageName", instance.packageName)
        put("displayName", instance.displayName)
        put("engineId", instance.engineId)
        put("virtualUserId", instance.virtualUserId)
        put("state", instance.state.name)
        put("signatureMode", instance.signatureMode.name)
        put("installedVersionCode", instance.installedVersionCode ?: JSONObject.NULL)
        put("dataRoot", instance.dataRoot)
        put("lastExitReason", instance.lastExitReason?.name ?: JSONObject.NULL)
        put("createdAt", instance.createdAt)
    }

    private fun parse(obj: JSONObject): RuntimeInstance = RuntimeInstance(
        id = obj.getString("id"),
        packageName = obj.getString("packageName"),
        displayName = obj.optString("displayName"),
        engineId = obj.optString("engineId", "blackbox"),
        virtualUserId = obj.optInt("virtualUserId"),
        state = enumOrDefault(obj.optString("state"), RuntimeState.UNKNOWN_INTERRUPTED),
        signatureMode = enumOrDefault(obj.optString("signatureMode"), SignatureMode.FIRST_INSTALL),
        installedVersionCode = if (obj.isNull("installedVersionCode")) null else obj.optLong("installedVersionCode"),
        dataRoot = obj.optString("dataRoot"),
        lastExitReason = if (obj.isNull("lastExitReason")) null
        else enumOrDefault(obj.optString("lastExitReason"), ExitReason.UNKNOWN),
        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
