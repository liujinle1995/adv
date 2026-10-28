package com.adv.runner.apk

import android.os.Build

/**
 * 厂商 ROM 家族。
 *
 * 只按 `Build.MANUFACTURER` / `Build.BRAND` 判定，不读 `ro.build.*` 私有属性：
 * 那些属性没有公开 API，走反射在 Android 9+ 会被 hidden API 策略拦。
 *
 * 这里只需要区分「限制后台/悬浮窗比较激进的厂商」和「接近 AOSP 的厂商」，
 * 不需要精确到具体版本号。
 */
enum class VendorRom {
    XIAOMI,
    HONOR,
    HUAWEI,
    OPPO,
    ONEPLUS,
    VIVO,
    SAMSUNG,
    MEIZU,

    /** 已知厂商但不在上表（如传音、联想等）。 */
    OTHER,

    /** 完全认不出来（模拟器 / 白牌机）。 */
    UNKNOWN
}

object VendorRomDetector {

    fun classify(manufacturer: String?, brand: String?): VendorRom {
        val keys = listOfNotNull(manufacturer, brand)
            .joinToString(" ")
            .lowercase()
        if (keys.isBlank()) return VendorRom.UNKNOWN

        return when {
            keys.contains("xiaomi") || keys.contains("redmi") || keys.contains("poco") -> VendorRom.XIAOMI
            keys.contains("honor") -> VendorRom.HONOR
            keys.contains("huawei") -> VendorRom.HUAWEI
            keys.contains("oneplus") -> VendorRom.ONEPLUS
            keys.contains("oppo") || keys.contains("realme") -> VendorRom.OPPO
            keys.contains("vivo") || keys.contains("iqoo") -> VendorRom.VIVO
            keys.contains("samsung") -> VendorRom.SAMSUNG
            keys.contains("meizu") -> VendorRom.MEIZU
            keys.contains("generic") || keys.contains("unknown") || keys.contains("aosp") -> VendorRom.UNKNOWN
            else -> VendorRom.OTHER
        }
    }

    /** 当前设备。 */
    fun fromBuild(): VendorRom = classify(Build.MANUFACTURER, Build.BRAND)

    /**
     * 该 ROM 上需要额外引导用户去开的权限。
     *
     * 依据是这类系统的公开行为（后台弹窗/悬浮窗/自启动默认收紧），
     * 不是针对某个具体机型的实测结论，因此只作为**提示**展示，不做流程拦截。
     */
    fun knownRestrictions(rom: VendorRom): List<String> = when (rom) {
        VendorRom.XIAOMI, VendorRom.HONOR, VendorRom.HUAWEI,
        VendorRom.OPPO, VendorRom.VIVO, VendorRom.MEIZU -> listOf(
            "悬浮窗权限默认关闭，需在系统设置中手动允许",
            "后台启动/自启动可能被限制，实例在后台被清理时属系统行为"
        )
        VendorRom.ONEPLUS, VendorRom.SAMSUNG -> listOf(
            "后台进程可能被省电策略限制，长时间后台运行需关闭对应优化"
        )
        VendorRom.OTHER -> listOf("未收录的厂商 ROM，后台与悬浮窗行为需实测")
        VendorRom.UNKNOWN -> emptyList()
    }
}

/**
 * 设备能力快照（纯数据，便于把测试矩阵跑在任意组合上）。
 */
data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    val vendorRom: VendorRom = VendorRom.UNKNOWN,
    val pageSize: Int = 4096
) {
    /** 设备是否带 32 位运行时（`abilist` 里是否含 32 位 ABI）。 */
    val has32BitRuntime: Boolean
        get() = abis.any { it == "armeabi-v7a" || it == "armeabi" || it == "x86" }

    fun supportsAnyAbi(apkAbis: Collection<String>): Boolean =
        apkAbis.any { it in abis }
}
