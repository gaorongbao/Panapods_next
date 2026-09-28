package com.panapods.bridge

/**
 * 官方 App 连接让权租约的**纯状态机**（无 Android 依赖，可直接 JVM 单测）。
 *
 * 语义对齐 SonyPods 的 SoundConnectHandover：
 * - 官方 App 存在任一持有源（Activity / 前台服务 / 真实 BLE 会话）→ acquire；
 * - 所有持有源消失后（宽限期在 Hook 侧）→ release；
 * - 同一 leaseId 的重复 acquire 幂等（引擎侧不再二次让出）；
 * - 迟到的旧租约 release 直接丢弃，避免和新租约错位。
 */
class OfficialLeaseState {

    private var id: String? = null

    /** 当前租约身份；null 表示引擎独占。 */
    val leaseId: String? get() = id

    fun isHeld(): Boolean = id != null

    /**
     * 申明/续租。
     *
     * @return true 表示状态由「空闲 → 持有」，调用方需要让出连接；
     *         同 id 续租返回 false（幂等）。
     */
    @Synchronized
    fun acquire(newId: String): Boolean {
        if (newId.isEmpty()) return false
        if (id == newId) return false
        val becameHeld = id == null
        id = newId
        return becameHeld
    }

    /**
     * 归还。
     *
     * @param leaseId 释放方声明的租约身份；null 表示无条件释放（如 Binder 死亡回调）
     * @return true 表示状态由「持有 → 空闲」，调用方需要恢复连接
     */
    @Synchronized
    fun release(leaseId: String?): Boolean {
        val current = id ?: return false
        if (leaseId != null && leaseId != current) return false
        id = null
        return true
    }

    @Synchronized
    fun current(): String? = id
}
