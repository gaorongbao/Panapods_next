package com.panapods.bridge

import android.content.Context
import android.content.SharedPreferences
import com.panapods.utils.PanaLog

/**
 * 「官方 App 让权恢复」豁免标记 —— 快连弹窗的一次性抑制。
 *
 * 用户反馈：打开 Technics Audio Connect 后退出，快连弹窗莫名出现一次。
 * 成因是时序本身：
 * 1. 打开官方 App → [com.panapods.hook.TechnicsHandoverHook] 取得让权租约，
 *    引擎断开自己的 GATT（connected=false）；
 * 2. 退出官方 App → 租约归还，引擎立即 [tryReconnectFromSaved] 重连
 *    （connected=false → true）；
 * 3. [com.panapods.hook.OfficialFastConnectDialogHook] 对每一次「断开→连接」
 *    跳变都弹一张官方卡片 —— 于是恢复这次也被当成新连接弹了卡。
 *
 * 语义：让权期间与归还后的那次重连都是**恢复**而非新连接，不弹卡。
 * 引擎在租约 acquire / release 时 [mark]；快连弹窗 Hook 在「断开→连接」
 * 跳变处 [consume]：窗口内返回 true（抑制这一次）并清掉标记，窗口外视为
 * 过期返回 false，最多只影响一次弹窗。
 *
 * 标记持久化在 SharedPreferences：Hook 经 Provider.call 跨进程消费时读的是
 * App 主进程的同一份文件，引擎进程中途被杀也不丢。时钟判定抽成纯函数
 * [isFresh]，可直接 JVM 单测（见 HandoverResumeTest）。
 */
object HandoverResume {

    private const val TAG = "HandoverResume"
    private const val PREFS_NAME = "handover_resume"
    private const val KEY_MARKED_AT = "marked_at"

    /**
     * 抑制窗口：租约归还后引擎立即重连，首连失败也走数十秒内的退避重试；
     * 超过 2 分钟才连上就当作新连接（重新允许弹卡）。
     */
    const val WINDOW_MS = 120_000L

    /** 引擎侧：官方 App 取得/归还让权租约时打点——下一次连上属于恢复。 */
    fun mark(context: Context?, now: Long = System.currentTimeMillis()) {
        if (context == null) return
        prefs(context).edit().putLong(KEY_MARKED_AT, now).apply()
        PanaLog.i(TAG, "handover marked at $now (next connect skips fast-connect dialog)")
    }

    /**
     * Hook 侧消费（读取即清除）：
     * @return true 表示本次「断开→连接」是让权恢复，快连弹窗必须跳过
     */
    fun consume(context: Context?, now: Long = System.currentTimeMillis()): Boolean {
        if (context == null) return false
        val stored = prefs(context)
        val markedAt = stored.getLong(KEY_MARKED_AT, 0L)
        if (markedAt == 0L) return false
        stored.edit().remove(KEY_MARKED_AT).apply()
        if (!isFresh(markedAt, now)) {
            PanaLog.i(TAG, "handover consume: expired (${now - markedAt}ms), dialog allowed")
            return false
        }
        PanaLog.i(TAG, "handover resume detected, fast-connect dialog suppressed")
        return true
    }

    /**
     * 纯时钟判定（JVM 单测入口）：标记存在且落在 [0, windowMs] 内。
     * 负差值（系统时钟被回拨）判为过期——宁可漏抑一次，也不永久吞掉弹窗。
     */
    fun isFresh(markedAt: Long, now: Long, windowMs: Long = WINDOW_MS): Boolean =
        markedAt > 0L && now - markedAt in 0..windowMs

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
