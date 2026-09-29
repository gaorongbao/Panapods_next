package com.panapods.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方 App 让权恢复豁免窗口的纯时钟判定测试。
 *
 * 对应用户反馈：打开 Technics Audio Connect 后退出，快连弹窗莫名出现一次。
 * 覆盖：未打点、窗口边界、窗口过期、时钟回拨四种情况。
 */
class HandoverResumeTest {

    private val window = HandoverResume.WINDOW_MS

    @Test
    fun `no mark is never fresh`() {
        assertFalse(HandoverResume.isFresh(0L, 1_000L, window))
    }

    @Test
    fun `mark is fresh at the moment of marking`() {
        assertTrue(HandoverResume.isFresh(1_000L, 1_000L, window))
    }

    @Test
    fun `resume within the window is suppressed`() {
        // 租约归还后引擎立即重连（数秒内）——必须命中抑制。
        assertTrue(HandoverResume.isFresh(1_000L, 1_000L + 5_000L, window))
        // 退避重试的最晚情形：窗口边界本身仍算新鲜。
        assertTrue(HandoverResume.isFresh(1_000L, 1_000L + window, window))
    }

    @Test
    fun `stale mark no longer suppresses`() {
        assertFalse(HandoverResume.isFresh(1_000L, 1_000L + window + 1L, window))
        // 很久以后的真实新连接（重新开盖）必须照常弹卡。
        assertFalse(HandoverResume.isFresh(1_000L, 1_000L + 600_000L, window))
    }

    @Test
    fun `clock rollback does not suppress forever`() {
        // 标记时间晚于当前时间（系统时钟被回拨）判为过期，避免弹窗被永久吞掉。
        assertFalse(HandoverResume.isFresh(2_000L, 1_000L, window))
    }
}
