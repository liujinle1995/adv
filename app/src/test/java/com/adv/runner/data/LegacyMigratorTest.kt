package com.adv.runner.data

import com.adv.runner.core.control.InstanceDataSource
import com.adv.runner.core.model.RunEvent
import com.adv.runner.core.model.RuntimeInstance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧数据迁移（JSON / JSONL → Room）的测试。
 *
 * 重点是**失败时的行为**：原文件必须留在原处。
 * 旧实现 `runCatching{...}.getOrDefault(emptyList())` 会把「文件损坏」当成
 * 「文件里没有数据」，然后照样改名成 `.bak`——用户的实例清单就这么被藏起来，且无任何提示。
 */
class LegacyMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val instancesJson = """
        [
          {"id":"i1","packageName":"com.a","displayName":"A","engineId":"blackbox",
           "virtualUserId":1,"state":"INSTALLED","signatureMode":"FIRST_INSTALL",
           "installedVersionCode":10,"dataRoot":"/tmp/i1","lastExitReason":null,"createdAt":1000},
          {"id":"i2","packageName":"com.b","displayName":"B","engineId":"blackbox",
           "virtualUserId":2,"state":"RUNNING","signatureMode":"SIGNATURE_MISMATCH",
           "installedVersionCode":11,"dataRoot":"/tmp/i2","lastExitReason":"CRASHED","createdAt":2000}
        ]
    """.trimIndent()

    private val eventsJsonl = """
        {"id":1,"instanceId":"i1","packageName":"com.a","ts":1000,"level":"INFO","type":"INSTALL","code":null,"message":"装好了"}
        {"id":2,"instanceId":"i1","packageName":"com.a","ts":2000,"level":"ERROR","type":"CRASH","code":"CRASHED","message":"崩了"}
    """.trimIndent()

    @Test
    fun `正常迁移会写入两条实例和两条事件并把原文件改名`() = runTest {
        val dir = temp.newFolder("files")
        val instanceFile = File(dir, "instances.json").apply { writeText(instancesJson) }
        val eventFile = File(dir, "events.jsonl").apply { writeText(eventsJsonl) }

        val instanceSink = RecordingInstanceSink()
        val eventSink = RecordingEventSink()

        val report = LegacyMigrator(dir, instanceSink, eventSink).migrate()

        assertTrue(report.isSuccess)
        assertEquals(2, report.instanceCount)
        assertEquals(2, report.eventCount)
        assertEquals(2, instanceSink.saved!!.size)
        assertEquals(2, eventSink.saved!!.size)
        // 事件按旧 id 升序搬运，保证 Room 自增后时间仍单调
        assertEquals(listOf(1L, 2L), eventSink.saved!!.map { it.id })
        assertFalse("原文件应已改名", instanceFile.exists())
        assertTrue(File(dir, "instances.json.bak").exists())
        assertTrue(File(dir, "events.jsonl.bak").exists())
    }

    @Test
    fun `实例清单损坏时必须报错且保留原文件`() = runTest {
        val dir = temp.newFolder("files")
        val broken = File(dir, "instances.json").apply { writeText("[{\"id\":\"x\"") }
        val instanceSink = RecordingInstanceSink()

        val report = LegacyMigrator(dir, instanceSink, RecordingEventSink()).migrate()

        assertFalse(report.isSuccess)
        assertNotNull(report.instanceError)
        assertNull(instanceSink.saved)
        assertTrue("原文件不能被藏起来", broken.exists())
        assertFalse(File(dir, "instances.json.bak").exists())
    }

    @Test
    fun `单条记录缺字段也算损坏不会静默丢弃`() = runTest {
        val dir = temp.newFolder("files")
        File(dir, "instances.json").apply {
            writeText(
                """[{"id":"ok","packageName":"com.a","displayName":"A","engineId":"blackbox",
                   "virtualUserId":1,"state":"INSTALLED","signatureMode":"FIRST_INSTALL",
                   "installedVersionCode":1,"dataRoot":"/tmp/a","lastExitReason":null,"createdAt":1},
                   {"packageName":"com.b"}]"""
            )
        }
        val instanceSink = RecordingInstanceSink()

        val report = LegacyMigrator(dir, instanceSink, RecordingEventSink()).migrate()

        assertFalse(report.isSuccess)
        assertNull("不能只迁一半", instanceSink.saved)
        assertTrue(File(dir, "instances.json").exists())
    }

    @Test
    fun `事件文件坏行不影响实例迁移`() = runTest {
        val dir = temp.newFolder("files")
        File(dir, "instances.json").apply { writeText(instancesJson) }
        val eventFile = File(dir, "events.jsonl").apply {
            writeText("{\"id\":1,\"type\":\"INSTALL\"}\n这不是 JSON\n")
        }
        val instanceSink = RecordingInstanceSink()
        val eventSink = RecordingEventSink()

        val report = LegacyMigrator(dir, instanceSink, eventSink).migrate()

        assertFalse(report.isSuccess)
        assertEquals("实例仍应迁移成功", 2, report.instanceCount)
        assertNotNull(report.eventError)
        assertNull(eventSink.saved)
        assertTrue("坏的事件文件要保留", eventFile.exists())
        assertTrue(File(dir, "instances.json.bak").exists())
    }

    @Test
    fun `写数据库失败时保留原文件`() = runTest {
        val dir = temp.newFolder("files")
        val instanceFile = File(dir, "instances.json").apply { writeText(instancesJson) }
        val failing = object : InstanceDataSource {
            override suspend fun load(): List<RuntimeInstance> = emptyList()
            override suspend fun save(instances: List<RuntimeInstance>) =
                throw IllegalStateException("db locked")
            override suspend fun delete(id: String) = Unit
        }

        val report = LegacyMigrator(dir, failing, RecordingEventSink()).migrate()

        assertFalse(report.isSuccess)
        assertTrue(report.instanceError!!.contains("db locked"))
        assertTrue(instanceFile.exists())
    }

    @Test
    fun `没有旧文件时直接跳过`() = runTest {
        val dir = temp.newFolder("files")
        val report = LegacyMigrator(dir, RecordingInstanceSink(), RecordingEventSink()).migrate()

        assertTrue(report.skipped)
        assertTrue(report.isSuccess)
        assertFalse(report.hasData)
    }

    @Test
    fun `空文件改名后不算有数据`() = runTest {
        val dir = temp.newFolder("files")
        File(dir, "instances.json").writeText("[]")
        val report = LegacyMigrator(dir, RecordingInstanceSink(), RecordingEventSink()).migrate()

        assertTrue(report.isSuccess)
        assertEquals(0, report.instanceCount)
        assertTrue(File(dir, "instances.json.bak").exists())
        assertFalse(report.hasData)
    }

    private class RecordingInstanceSink : InstanceDataSource {
        var saved: List<RuntimeInstance>? = null
        override suspend fun load(): List<RuntimeInstance> = saved ?: emptyList()
        override suspend fun save(instances: List<RuntimeInstance>) {
            saved = instances
        }
        override suspend fun delete(id: String) = Unit
    }

    private class RecordingEventSink : EventSink {
        var saved: List<RunEvent>? = null
        override suspend fun replaceAll(events: List<RunEvent>) {
            saved = events
        }
    }
}
