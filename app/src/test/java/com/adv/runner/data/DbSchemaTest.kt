package com.adv.runner.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据库结构守卫。
 *
 * 为什么不是 `MigrationTestHelper`：那套 API 需要一个真实 SQLite 设备（androidTest），
 * 本机 CI 没有连接设备时跑不了。这里退一步守住更常见的事故：
 *
 * 1. **实体改了、迁移没写**：列集合是"金标准"，改了任何一列这条用例都会红，
 *    强制你先想清楚「老用户的库怎么办」，再去更新这里的期望值。
 * 2. **悄悄退回 destructive migration**：`fallbackToDestructiveMigration()` 一加，
 *    升级即清库，实例清单会整批消失。这里直接对源码断言禁止出现。
 * 3. **schema 没导出**：没有 schema JSON 就没法做真正的迁移测试，必须导出来入库。
 */
class DbSchemaTest {

    private val schemaDir = File("schemas/com.adv.runner.data.db.AdvDatabase")
    private val databaseSource = File(
        "src/main/java/com/adv/runner/data/db/AdvDatabase.kt"
    )
    private val providerSource = File(
        "src/main/java/com/adv/runner/data/db/DatabaseProvider.kt"
    )

    /** 金标准：表 → 列。任何实体的增删列都必须同步修改这里，等同于"迁移审查"。 */
    private val expectedColumns = mapOf(
        "apk_info" to listOf(
            "id", "package_name", "version_name", "version_code", "app_label", "sha256",
            "file_size", "stored_path", "original_name", "signer_sha256", "signer_subject",
            "min_sdk", "target_sdk", "compile_sdk", "abis", "permissions",
            "launcher_activity", "components", "is_split", "is_debuggable", "imported_at"
        ),
        "runtime_instance" to listOf(
            "id", "apk_id", "package_name", "display_name", "engine_id", "engine_version",
            "virtual_user_id", "state", "signature_mode", "signature_ack_at",
            "installed_version_code", "installed_at", "last_launched_at",
            "last_exit_reason", "data_root", "created_at", "updated_at"
        ),
        "run_event" to listOf(
            "id", "instance_id", "package_name", "ts", "level", "kind", "code",
            "message", "log_file", "detail_ref"
        ),
        "engine_profile" to listOf(
            "engine_id", "engine_version", "device_sdk", "device_abis",
            "capabilities", "last_check_at", "last_result"
        )
    )

    private fun schemaJson(): JSONObject {
        assertTrue(
            "缺少导出的 schema：${schemaDir.absolutePath}（检查 ksp 的 room.schemaLocation）",
            schemaDir.isDirectory
        )
        val files = schemaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue("schema 目录里没有 JSON", files.isNotEmpty())
        // 取版本号最大的那份
        val latest = files.maxByOrNull { it.name.removeSuffix(".json").toIntOrNull() ?: 0 }!!
        return JSONObject(latest.readText())
    }

    private fun entities(json: JSONObject): Map<String, List<String>> {
        val db = json.getJSONObject("database")
        val arr = db.getJSONArray("entities")
        val result = LinkedHashMap<String, List<String>>()
        for (i in 0 until arr.length()) {
            val entity = arr.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).map {
                fields.getJSONObject(it).getString("columnName")
            }
            result[entity.getString("tableName")] = columns
        }
        return result
    }

    @Test
    fun `schema 里四张表与实体的列集合必须与金标准一致`() {
        val actual = entities(schemaJson())
        assertEquals(
            "表数量不对（增删实体请同步更新本测试并确认迁移）",
            expectedColumns.keys,
            actual.keys
        )
        expectedColumns.forEach { (table, expected) ->
            assertEquals(
                "表 $table 的列发生了变化：改了实体就必须评估迁移，再更新这里的金标准",
                expected.sorted(),
                actual[table]!!.sorted()
            )
        }
    }

    @Test
    fun `schema 版本与源码里声明的版本一致`() {
        val declared = Regex("""version\s*=\s*(\d+)""")
            .find(databaseSource.readText())
            ?.groupValues?.get(1)?.toInt()
        assertTrue("没能从 AdvDatabase.kt 读到 version", declared != null)
        val schemaVersion = schemaJson().getJSONObject("database").getInt("version")
        assertEquals(
            "schema 导出目录与源码版本不一致，先重新编译再提交",
            declared,
            schemaVersion
        )
    }

    @Test
    fun `每张表都必须有主键`() {
        val json = schemaJson()
        val arr = json.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until arr.length()) {
            val entity = arr.getJSONObject(i)
            // Room 导出的是对象：{"autoGenerate":..., "columnNames":[...]}
            val pk = entity.optJSONObject("primaryKey")
            val columns = pk?.optJSONArray("columnNames")
            assertTrue(
                "表 ${entity.getString("tableName")} 没有主键",
                columns != null && columns.length() > 0
            )
        }
    }

    @Test
    fun `数据库构建禁止 destructive migration`() {
        val source = providerSource.readText()
        assertFalse(
            "出现了 fallbackToDestructiveMigration：升级会清空用户数据",
            source.contains("fallbackToDestructiveMigration")
        )
        // 目前没有历史版本，迁移表可以为空；一旦升版本必须同时出现 addMigrations
        val version = Regex("""version\s*=\s*(\d+)""")
            .find(databaseSource.readText())?.groupValues?.get(1)?.toInt() ?: 1
        if (version > 1) {
            assertTrue(
                "库版本已升到 $version，但 DatabaseProvider 没有配置任何 Migration",
                source.contains("addMigrations")
            )
        }
    }
}
