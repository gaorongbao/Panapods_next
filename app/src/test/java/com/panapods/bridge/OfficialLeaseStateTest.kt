package com.panapods.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方 App（Technics Audio Connect）连接让权租约的纯状态机测试。
 *
 * 对应 [com.panapods.hook.TechnicsHandoverHook] 的 acquire/release 时序，
 * 覆盖引擎侧必须正确让位/恢复的四类情况：
 * 首次独占、幂等重申、迟到 release、Binder 死亡兜底。
 */
class OfficialLeaseStateTest {

    @Test
    fun `idle state is not held`() {
        val state = OfficialLeaseState()
        assertFalse(state.isHeld())
        assertNull(state.current())
    }

    @Test
    fun `first acquire transitions to held exactly once`() {
        val state = OfficialLeaseState()

        assertTrue(state.acquire("pid:1"))
        assertTrue(state.isHeld())
        assertEquals("pid:1", state.current())

        // 同一租约重申（engine_ready 触发的 reassert）：仍持有，不得再次让位。
        assertFalse(state.acquire("pid:1"))
        assertEquals("pid:1", state.current())
    }

    @Test
    fun `new lease id replaces the previous one without a second yield`() {
        val state = OfficialLeaseState()
        assertTrue(state.acquire("pid:1"))
        assertFalse(state.acquire("pid:2"))
        assertTrue(state.isHeld())
        assertEquals("pid:2", state.current())
    }

    @Test
    fun `release with matching id hands the connection back`() {
        val state = OfficialLeaseState()
        state.acquire("pid:1")

        assertTrue(state.release("pid:1"))
        assertFalse(state.isHeld())
        assertNull(state.current())

        // 重复 release 不再产生恢复信号（避免引擎被触发两次重连）。
        assertFalse(state.release("pid:1"))
    }

    @Test
    fun `stale release from an old lease is ignored`() {
        val state = OfficialLeaseState()
        state.acquire("pid:1")
        state.acquire("pid:2")

        // 旧租约的迟到 release：不得把新租约清掉。
        assertFalse(state.release("pid:1"))
        assertTrue(state.isHeld())
        assertEquals("pid:2", state.current())

        assertTrue(state.release("pid:2"))
        assertFalse(state.isHeld())
    }

    @Test
    fun `release without an id is unconditional`() {
        val state = OfficialLeaseState()
        state.acquire("pid:1")

        // Binder death 路径：官方进程崩溃，来不及带租约身份。
        assertTrue(state.release(null))
        assertFalse(state.isHeld())
    }

    @Test
    fun `release while idle does nothing`() {
        val state = OfficialLeaseState()
        assertFalse(state.release("pid:1"))
        assertFalse(state.release(null))
    }

    @Test
    fun `blank acquire id is rejected`() {
        val state = OfficialLeaseState()
        assertFalse(state.acquire(""))
        assertFalse(state.isHeld())
    }

    @Test
    fun `full handover round trip can repeat`() {
        val state = OfficialLeaseState()

        repeat(3) { round ->
            val id = "pid:$round"
            assertTrue(state.acquire(id))
            assertTrue(state.isHeld())
            assertTrue(state.release(id))
            assertFalse(state.isHeld())
        }
    }
}
