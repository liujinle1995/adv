package com.adv.runner.engine.blackbox;

import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/**
 * BlackBox 的宿主配置。
 *
 * 用 Java 写是为了避免 Kotlin 对 Java 抽象 getter 的 synthetic property 覆盖歧义。
 *
 * 说明：
 * - isEnableLauncherActivity：关闭引擎自带的"虚拟应用列表"界面，入口由我们自己的 Compose UI 负责；
 * - isEnableDaemonService：MVP 先关闭守护进程，减少一个常驻进程与厂商省电策略的冲突面。
 */
public class AdvClientConfiguration extends ClientConfiguration {

    private final String hostPackageName;

    public AdvClientConfiguration(String hostPackageName) {
        this.hostPackageName = hostPackageName;
    }

    @Override
    public String getHostPackageName() {
        return hostPackageName;
    }

    @Override
    public boolean isEnableLauncherActivity() {
        return false;
    }

    @Override
    public boolean isEnableDaemonService() {
        return false;
    }
}
