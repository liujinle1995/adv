package com.adv.runner.data.db

import android.content.Context
import androidx.room.Room

/**
 * 进程内单例的 [AdvDatabase]。
 *
 * 不放在 DI 框架里（阶段 3.1 才拆模块/引入 DI），先用单例保证：
 * 1. 全 App 只有一个 Room 实例（Room 官方也建议单例）；
 * 2. 数据库构建不阻塞主线程（首次访问时才构建，随后复用）。
 */
object DatabaseProvider {

    private const val DB_NAME = "adv.db"

    @Volatile
    private var instance: AdvDatabase? = null

    fun get(context: Context): AdvDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            AdvDatabase::class.java,
            DB_NAME
        ).build().also { instance = it }
    }
}
