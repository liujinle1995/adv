package com.adv.runner.core.control

import com.adv.runner.core.model.RuntimeInstance

/**
 * 实例清单的读写契约。
 *
 * 之所以要这一层接口：状态机与对账逻辑必须能在**纯 JVM 单测**里跑，
 * 不能为了测「读失败要不要返回空列表」而去起一个 Room 实例。
 * [com.adv.runner.data.InstanceRepository] 是生产实现（Room）。
 *
 * 约定：**失败一律抛异常**，不允许返回空列表冒充「没有实例」。
 * 空列表只能表示「确实没有实例」，这是本接口最重要的一条语义。
 */
interface InstanceDataSource {

    suspend fun load(): List<RuntimeInstance>

    suspend fun save(instances: List<RuntimeInstance>)

    suspend fun delete(id: String)
}
