package com.panapods.ble

import com.panapods.protocol.PanaProtocolEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateTrackerTest {

    @Test
    fun `dual ear maps agent to right by default`() {
        // v169：EAH-AZ100 的主耳（agent，dumpsys 里持有 BR/EDR 的 DUAL 地址）是**右耳**
        // （adb 实测：修好副耳电量后左槽曾显示右耳的值）。故双耳在位时左槽应为 partner。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        assertTrue(tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80))
        assertTrue(tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60))

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(60, left)
        assertEquals(80, right)
    }

    @Test
    fun `unknown presence falls back to the model default agent side`() {
        // 两侧探测都没结果时，沿用机型默认（agent=右），不能凭空把 agent 放到左槽。
        val tracker = BatteryStateTracker()
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(60, left)
        assertEquals(80, right)
    }

    @Test
    fun `agentIsLeft can be overridden for units with the opposite convention`() {
        val tracker = BatteryStateTracker()
        tracker.agentIsLeft = true
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(80, left)
        assertEquals(60, right)
    }

    @Test
    fun `agentIsLeft moves the agent battery into the left slot`() {
        // 原为 `swapEarSides ...`（v1.0 遗留）。v181 之后「对调开关」不再由
        // tracker 自己的 swapEarSides 承载，而是 ConfigManager.swapEarSides →
        // refreshAgentSide(staticIsLeft) → agentIsLeft；这里按现行实现断言等价效果。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        tracker.agentIsLeft = true
        assertEquals(80 to 60, tracker.computeDisplayBatteries())
    }

    @Test
    fun `swapped agent side still maps into a present single-ear slot`() {
        // 单耳：左耳在位、对调后 agent 落到左槽 → 左槽显示 70、右槽隐藏。
        val tracker = BatteryStateTracker()
        tracker.agentIsLeft = true
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 70)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(70, left)
        assertNull(right)
    }

    @Test
    fun `presence gate still hides the slot after the sides are swapped`() {
        // v181 规则：对调只改角色→物理侧的映射，「明确不在位」的闸门不变——
        // 右耳在位但 agent 被对调到左槽时，两个槽都不能凭空显示数据。
        val tracker = BatteryStateTracker()
        tracker.agentIsLeft = true
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 70)

        assertEquals(null to null, tracker.computeDisplayBatteries())
    }

    @Test
    fun `single right ear maps agent to right`() {
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 70)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(70, right)
    }

    @Test
    fun `single left ear shows the agent battery after sibling-follow inference`() {
        // v183：只在「恰好一侧在位」时用 refreshAgentSide 现场判定 agent 侧
        // （PanaBleService 收到在位/电量后按 config.swapEarSides 调用它）。
        // 左耳在位、副耳 relay 电量为空 ⇒ agent 就在左耳，电量落左槽。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 55)
        tracker.refreshAgentSide(staticIsLeft = false)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(55, left)
        assertNull(right)
    }

    @Test
    fun `probe failure keeps known battery visible`() {
        // 两侧都报 false 表示探测失败；不能把已知 agent 电量隐藏。
        // 现有映射规则：leftPresent == false 时 agent 推断为右侧，因此显示在右耳。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 90)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(90, right)
    }

    @Test
    fun `right unknown left absent maps agent to right`() {
        // 边界：左耳明确不在（false）、右耳探测未知（null）。排除法 → agent 只可能在右侧。
        // 左槽应隐藏（null），右槽显示 agent。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(80, right)
    }

    @Test
    fun `absent side stays hidden after v181 removed presence inference`() {
        // 原为 `left unknown right absent maps agent to left`（v181 之前的旧规则：
        // 在位探测反推 agent 侧）。v181 明确删除该反推——「主耳入仓但保持直连」时
        // 反推会颠倒、电量落错槽位（见 BatteryStateTracker 注释与用户缺陷单）。
        // 现行规则：角色→物理侧只由 agentIsLeft（静态 swap + v183 动态同步）决定，
        // 在位探测只做显示闸门 ⇒ 右耳明确不在、左耳未知时不再凭空把 agent 挪到左槽。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.refreshAgentSide(staticIsLeft = false)

        assertEquals(null to null, tracker.computeDisplayBatteries())
    }

    @Test
    fun `partner response flag lifecycle`() {
        val tracker = BatteryStateTracker()
        assertFalse(tracker.partnerBatteryResponded)

        tracker.onPartnerBatteryReceived(66)
        assertTrue(tracker.partnerBatteryResponded)
        assertEquals(66, tracker.partnerBattery)

        tracker.startRefreshCycle()
        assertFalse(tracker.partnerBatteryResponded)

        tracker.clearPartnerBattery()
        assertNull(tracker.partnerBattery)
    }

    @Test
    fun `reset clears all session state`() {
        val tracker = BatteryStateTracker()
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)

        tracker.reset()

        assertNull(tracker.agentBattery)
        assertNull(tracker.partnerBattery)
        assertNull(tracker.leftPresent)
        assertNull(tracker.rightPresent)
        assertFalse(tracker.partnerBatteryResponded)
        assertEquals(null to null, tracker.computeDisplayBatteries())
    }

    @Test
    fun `unknown battery target is rejected`() {
        val tracker = BatteryStateTracker()
        assertFalse(tracker.onBatteryReceived(9, 50))
        assertNull(tracker.agentBattery)
        assertNull(tracker.partnerBattery)
    }

    @Test
    fun `battery target is compared as unsigned byte`() {
        // v174 回归：target 是 0..255 无符号，>=128 时旧实现 toByte() 会翻成负数
        // 而匹配不上常量，导致主耳电量永远不上报。
        val tracker = BatteryStateTracker()
        assertTrue(tracker.onBatteryReceived(0x00, 80))
        assertTrue(tracker.onBatteryReceived(0x01, 60))
        assertFalse(tracker.onBatteryReceived(0x81, 50))
        assertEquals(80, tracker.agentBattery)
        assertEquals(60, tracker.partnerBattery)
    }
}
