package com.panapods.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * LC3 副地址「断开即清」的回归测试。
 *
 * 对应 v206 的修复：换一副耳机后旧副地址若残留，仍会参与 `isCurrentDevice()`
 * 匹配，把新设备误判成同一副（或命中旧耳机）。
 *
 * 覆盖的是**顺序** bug：v206 把 `if (!connected) lc3MacAddress = null` 写在了
 * `lc3MacAddress = lc3Addr` **之前**，而断开时调用方（PanaBleService.publishBridgeState）
 * 传进来的 `lc3Addr` 正是当前缓存值 —— 非空且 ≠ 主地址，于是刚清掉立刻被原样写回，
 * 「断开即清」一次都没生效过。v217 改为先赋值后清空。
 */
class PanaBridgeLc3Test {

    private val primary = "AC:DE:48:00:11:22"
    private val secondary = "AC:DE:48:00:11:33"

    /** PanaBridge 是进程级单例，测试之间共享状态 —— 每条用例先归零。 */
    @Before
    fun reset() {
        // 断开 + 不带副地址 = 走「断开即清」那条路径，正好把缓存抹干净。
        // （不能用 setLc3MacAddress(null)：它对空值是 early-return，清不掉。）
        PanaBridge.publishStateToCache(-1, -1, -1, 0, null, null, false, null)
    }

    @Test
    fun `secondary address is recorded while connected`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, secondary)
        assertEquals(secondary, PanaBridge.getLc3MacAddress())
    }

    @Test
    fun `disconnect clears secondary address even when caller passes the cached value back`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, secondary)
        // 断开广播携带的 lc3Addr 就是上面刚写进去的值 —— 这正是 publishBridgeState 的实际参数。
        PanaBridge.publishStateToCache(-1, -1, -1, -1, "TWS", primary, false, secondary)
        assertNull(PanaBridge.getLc3MacAddress())
    }

    @Test
    fun `disconnect without secondary address also clears it`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, secondary)
        PanaBridge.publishStateToCache(-1, -1, -1, -1, "TWS", primary, false, null)
        assertNull(PanaBridge.getLc3MacAddress())
    }

    @Test
    fun `secondary equal to primary is not treated as a secondary address`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, primary)
        assertNull(PanaBridge.getLc3MacAddress())
    }

    @Test
    fun `blank secondary is ignored`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, "  ")
        assertNull(PanaBridge.getLc3MacAddress())
    }

    @Test
    fun `connected refresh keeps the secondary address`() {
        PanaBridge.publishStateToCache(80, 85, 50, 0, "TWS", primary, true, secondary)
        // 电量刷新不带副地址时不能把已知的副地址抹掉。
        PanaBridge.publishStateToCache(79, 84, 49, 0, "TWS", primary, true, null)
        assertEquals(secondary, PanaBridge.getLc3MacAddress())
    }
}
