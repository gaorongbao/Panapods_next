package com.panapods.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 *
 * 持久化存储用户设置和耳机连接信息。
 * 使用 SharedPreferences (可后续迁移到 DataStore)。
 */
class ConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "panapods_config"
        private const val KEY_LAST_ADDRESS = "last_bt_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
        private const val KEY_ROOT_KEEPALIVE = "root_keepalive"
        private const val KEY_SWAP_EAR_SIDES = "swap_ear_sides"

        /** 通知栏「切换降噪」按钮参与循环的模式集合（模式名，按固定顺序循环）。 */
        private const val KEY_ANC_CYCLE_MODES = "anc_cycle_modes"

        /** 连接成功时是否弹出系统官方快连弹窗（复刻 SonyPods「连接弹窗样式=官方弹窗」）。 */
        private const val KEY_CONNECT_POPUP = "connect_popup"

        /**
         * 循环切换的固定顺序（对齐 SonyPods `ANC_CYCLE_MODE_ORDER`）：
         * 降噪 → 环境声 → 关闭。勾选的模式按此顺序参与循环，与 UI 勾选顺序无关。
         */
        val ANC_CYCLE_MODE_ORDER = listOf("NOISE_CANCELING", "AMBIENT", "OFF")

        /** 模式名 → AncMode 数值（未知名返回 null，读取时过滤）。 */
        fun ancModeIntOf(name: String): Int? = when (name) {
            "NOISE_CANCELING" -> com.panapods.headphones.AncMode.NOISE_CANCELING
            "AMBIENT" -> com.panapods.headphones.AncMode.AMBIENT
            "OFF" -> com.panapods.headphones.AncMode.OFF
            else -> null
        }

        /** AncMode 数值 → 模式名（用于把历史配置归一成名字集合）。 */
        fun ancModeNameOf(mode: Int): String? = when (mode) {
            com.panapods.headphones.AncMode.NOISE_CANCELING -> "NOISE_CANCELING"
            com.panapods.headphones.AncMode.AMBIENT -> "AMBIENT"
            com.panapods.headphones.AncMode.OFF -> "OFF"
            else -> null
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ 蓝牙地址 ============

    var lastBtAddress: String?
        get() = prefs.getString(KEY_LAST_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_LAST_ADDRESS, value).apply()

    var lastDeviceName: String?
        get() = prefs.getString(KEY_LAST_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_DEVICE_NAME, value).apply()

    // ============ 自动连接 ============

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    // ============ 后台隐藏 / 保活 ============

    /** 开启后 App 进入后台时从最近任务列表中移除（默认关闭，需用户手动开启）。 */
    var hideFromRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_FROM_RECENTS, value).apply()

    /** 开启后使用 root 周期拉起 BLE 服务并加入电池白名单（默认关闭，需用户手动开启）。 */
    var rootKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_ROOT_KEEPALIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_KEEPALIVE, value).apply()

    // ============ 电量显示 ============

    /**
     * v169：显示前把左右耳电量对调。
     *
     * 主耳（agent）的物理侧是机型/固件约定，代码里的默认值是实测得出的
     * （EAH-AZ100 主耳=右耳）。万一某些批次约定相反，用户开这个开关即可纠正，
     * 不必重新编译。默认关闭。
     */
    var swapEarSides: Boolean
        get() = prefs.getBoolean(KEY_SWAP_EAR_SIDES, false)
        set(value) = prefs.edit().putBoolean(KEY_SWAP_EAR_SIDES, value).apply()

    // ============ 循环切换降噪 / 连接弹窗 ============

    /**
     * 通知栏「切换降噪」按钮参与循环的模式名集合，默认三种全开。
     *
     * 读取时按 [ANC_CYCLE_MODE_ORDER] 归一（过滤非法值）；**空集合不回退到默认**
     * ——与 SonyPods 一致，空集合由引擎侧兜底为全选，避免脏配置静默覆盖用户选择。
     * 勾选界面保证至少保留一个。
     */
    var ancCycleModes: Set<String>
        get() = prefs.getStringSet(KEY_ANC_CYCLE_MODES, null)
            ?.filterTo(LinkedHashSet()) { it in ANC_CYCLE_MODE_ORDER }
            ?: ANC_CYCLE_MODE_ORDER.toSet()
        set(value) {
            val normalized = ANC_CYCLE_MODE_ORDER
                .filterTo(LinkedHashSet()) { it in value }
            prefs.edit().putStringSet(KEY_ANC_CYCLE_MODES, normalized).apply()
        }

    /** 连接成功时由 com.xiaomi.bluetooth 弹出官方快连设备卡片（默认开启）。 */
    var connectPopupEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONNECT_POPUP, true)
        set(value) = prefs.edit().putBoolean(KEY_CONNECT_POPUP, value).apply()
}
