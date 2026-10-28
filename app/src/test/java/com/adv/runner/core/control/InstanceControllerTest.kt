package com.adv.runner.core.control

import com.adv.runner.core.model.EngineCapabilities
import com.adv.runner.core.model.EngineErrorCode
import com.adv.runner.core.model.EngineResult
import com.adv.runner.core.model.RuntimeInstance
import com.adv.runner.core.model.RuntimeState
import com.adv.runner.core.model.SignatureMode
import com.adv.runner.core.model.engineFailure
import com.adv.runner.engine.api.EngineContext
import com.adv.runner.engine.api.EngineRuntimeStatus
import com.adv.runner.engine.api.EngineSelfCheck
import com.adv.runner.engine.api.InstallRequest
import com.adv.runner.engine.api.InstalledApp
import com.adv.runner.engine.api.LaunchOptions
import com.adv.runner.engine.api.RuntimeProbe
import com.adv.runner.engine.api.VirtualizationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对账的单测。
 *
 * 覆盖的是「失败不能被伪装成正常」这一组语义：
 * - 读盘失败 → loadError，而不是空列表；
 * - 落盘失败 → saveError，但内存结果仍然可用；
 * - 单条取证挂掉 → 记 failure，不拖垮其它实例。
 */
class InstanceControllerTest {

    private val runningOnDisk = instance("a", "com.a", RuntimeState.RUNNING)
    private val installedOnDisk = instance("b", "com.b", RuntimeState.INSTALLED)

    @Test
    fun `读盘失败必须报错而不是返回空列表`() = runTest {
        val store = FakeStore(loadThrows = IllegalStateException("db locked"))
        val controller = InstanceController(store, FakeEngine())

        val outcome = controller.reconcile()

        assertTrue("读盘失败必须标记为致命", outcome.isFatal)
        assertNotNull(outcome.loadError)
        assertTrue(outcome.loadError!!.contains("db locked"))
        assertEquals(emptyList<RuntimeInstance>(), outcome.instances)
        // 关键：不能声称"没有实例"
        assertFalse(outcome.nothingToReconcile)
    }

    @Test
    fun `磁盘真的没有实例时不算失败`() = runTest {
        val controller = InstanceController(FakeStore(initial = emptyList()), FakeEngine())

        val outcome = controller.reconcile()

        assertFalse(outcome.isFatal)
        assertNull(outcome.loadError)
        assertTrue(outcome.nothingToReconcile)
        assertTrue(outcome.instances.isEmpty())
    }

    @Test
    fun `落盘失败仍返回内存结果并标记错误`() = runTest {
        val store = FakeStore(
            initial = listOf(installedOnDisk),
            saveThrows = IllegalStateException("disk full")
        )
        val engine = FakeEngine(probes = mapOf("b" to probe(RuntimeState.INSTALLED)))
        val controller = InstanceController(store, engine)

        val outcome = controller.reconcile()

        assertFalse(outcome.isFatal)
        assertNotNull(outcome.saveError)
        assertTrue(outcome.saveError!!.contains("disk full"))
        assertEquals(1, outcome.instances.size)
        assertTrue(outcome.hasWarnings)
    }

    @Test
    fun `单条实例取证异常不影响其它实例`() = runTest {
        val store = FakeStore(initial = listOf(runningOnDisk, installedOnDisk))
        val engine = FakeEngine(
            probes = mapOf("b" to probe(RuntimeState.INSTALLED)),
            probeThrowsFor = setOf("a")
        )
        val controller = InstanceController(store, engine)

        val outcome = controller.reconcile()

        assertEquals(2, outcome.instances.size)
        assertEquals(1, outcome.failures.size)
        assertEquals("a", outcome.failures.single().instanceId)
        // 失败的那条保持磁盘状态，不被清空
        assertEquals(RuntimeState.RUNNING, outcome.instances.first { it.id == "a" }.state)
        assertEquals(RuntimeState.INSTALLED, outcome.instances.first { it.id == "b" }.state)
    }

    @Test
    fun `引擎取证失败时过渡态降级为未知中断`() = runTest {
        val store = FakeStore(initial = listOf(runningOnDisk))
        val engine = FakeEngine(
            failureFor = mapOf("a" to EngineErrorCode.INTERNAL)
        )
        val controller = InstanceController(store, engine)

        val outcome = controller.reconcile()

        assertEquals(RuntimeState.UNKNOWN_INTERRUPTED, outcome.instances.single().state)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun `对账成功后状态写回磁盘`() = runTest {
        val store = FakeStore(initial = listOf(runningOnDisk))
        val engine = FakeEngine(probes = mapOf("a" to probe(RuntimeState.STOPPED)))
        val controller = InstanceController(store, engine)

        controller.reconcile()

        assertEquals(RuntimeState.STOPPED, store.saved!!.single().state)
    }

    /* ---------------- 测试替身 ---------------- */

    private class FakeStore(
        var initial: List<RuntimeInstance> = emptyList(),
        private val loadThrows: Throwable? = null,
        private val saveThrows: Throwable? = null
    ) : InstanceDataSource {

        var saved: List<RuntimeInstance>? = null

        override suspend fun load(): List<RuntimeInstance> {
            loadThrows?.let { throw it }
            return initial
        }

        override suspend fun save(instances: List<RuntimeInstance>) {
            saveThrows?.let { throw it }
            saved = instances
            initial = instances
        }

        override suspend fun delete(id: String) {
            initial = initial.filterNot { it.id == id }
        }
    }

    private class FakeEngine(
        private val probes: Map<String, RuntimeProbe> = emptyMap(),
        private val probeThrowsFor: Set<String> = emptySet(),
        private val failureFor: Map<String, EngineErrorCode> = emptyMap()
    ) : VirtualizationEngine {

        override val id: String = "fake"
        override val displayName: String = "Fake Engine"

        override suspend fun initialize(context: EngineContext): EngineResult<EngineCapabilities> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun runtimeStatus(): EngineRuntimeStatus = EngineRuntimeStatus.unknown()

        override fun capabilities(): EngineCapabilities? = null

        override suspend fun attachExisting(instance: RuntimeInstance) = Unit

        override suspend fun installApk(request: InstallRequest): EngineResult<InstalledApp> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun launch(
            instanceId: String,
            options: LaunchOptions
        ): EngineResult<Unit> = engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun stop(instanceId: String): EngineResult<Unit> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun forceStop(instanceId: String): EngineResult<Unit> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun queryState(instanceId: String): EngineResult<RuntimeProbe> {
            if (instanceId in probeThrowsFor) throw IllegalStateException("probe boom")
            failureFor[instanceId]?.let { return engineFailure(it, "fake failure") }
            val probe = probes[instanceId] ?: RuntimeProbe(
                state = RuntimeState.INSTALLED,
                evidence = "fake"
            )
            return EngineResult.Success(probe)
        }

        override suspend fun clearData(instanceId: String): EngineResult<Unit> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun uninstall(instanceId: String): EngineResult<Unit> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun selfCheck(): EngineResult<EngineSelfCheck> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override suspend fun captureLogs(instanceId: String, maxBytes: Long): EngineResult<String> =
            engineFailure(EngineErrorCode.NOT_IMPLEMENTED, "fake")

        override fun release() = Unit
    }

    private companion object {
        fun instance(id: String, pkg: String, state: RuntimeState) = RuntimeInstance(
            id = id,
            packageName = pkg,
            displayName = pkg,
            engineId = "fake",
            virtualUserId = 1,
            state = state,
            signatureMode = SignatureMode.FIRST_INSTALL,
            installedVersionCode = 1L,
            dataRoot = "/tmp/$id"
        )

        fun probe(state: RuntimeState) = RuntimeProbe(state = state, evidence = "test")
    }
}
