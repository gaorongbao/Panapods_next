package com.panapods.bridge

import com.panapods.headphones.AncMode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.panapods.utils.PanaLog
import com.panapods.ble.PanaBleService

/**
  * v93 新增：Hook 层命令接收器
 *
  * 融合中心 Hook 层通过广播发送命令到 App 进程：
  * - 用户点击 ANC 控件 → Hook 发送 setAncMode 命令 → App 处理 → 转发给耳机
  * - 用户点击设备卡片 → Hook 发送 activateDevice 命令 → App 更新 UI
 *
  * 目的：解耦 Hook 层与 BLE 层，通过 Broadcast 而不是直接引用
 */
class PanaCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PanaCommandReceiver"

        const val ACTION_COMMAND = PanaBridge.ACTION_COMMAND
        const val EXTRA_COMMAND = PanaBridge.EXTRA_COMMAND
        const val EXTRA_ANC_MODE = PanaBridge.EXTRA_CMD_ANC_MODE  // "cmd_anc_mode" —— 与 Hook 发送方一致
        const val EXTRA_ADDRESS = "device_address"

        const val CMD_SET_ANC_MODE = "set_anc_mode"
        const val CMD_SYNC_ANC_MODE = "sync_anc_mode"
        const val CMD_CYCLE_ANC = PanaBridge.COMMAND_CYCLE_ANC
        const val CMD_ACTIVATE_DEVICE = "activate_device"
        const val CMD_OFFICIAL_APP_ACQUIRE = PanaBridge.COMMAND_OFFICIAL_APP_ACQUIRE
        const val CMD_OFFICIAL_APP_RELEASE = PanaBridge.COMMAND_OFFICIAL_APP_RELEASE
        const val CMD_REPOST_CARD = PanaBridge.COMMAND_REPOST_CARD
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // 命令接收器 exported，拒绝未携带正确 token 的第三方广播。
        if (!PanaBridge.isAuthorizedCommand(intent)) {
            PanaLog.w(TAG, "Rejected unauthorized command broadcast")
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        PanaLog.d(TAG, "onReceive command=$command")

        when (command) {
            CMD_SET_ANC_MODE -> {
                val mode = intent.getIntExtra(EXTRA_ANC_MODE, -1)
                if (!AncMode.isValid(mode)) {
                    PanaLog.w(TAG, "Invalid ANC mode: $mode")
                    return
                }
                PanaLog.i(TAG, "CMD_SET_ANC_MODE: $mode")
                                // 转发给 BLE Service
                PanaBleService.setAncModeFromProvider(mode)
                                // 更新本地缓存（用户点击后立即生效）
                PanaBridge.setCurrentAncMode(mode)
            }

            // 通知栏「切换降噪」按钮：循环顺序由引擎侧读取 ConfigManager 决定，
            // 与 SonyPods 的 CMD_CYCLE_NOISE_CONTROL 同语义（按钮只是触发器）。
            CMD_CYCLE_ANC -> {
                PanaLog.i(TAG, "CMD_CYCLE_ANC")
                PanaBleService.cycleAncModeFromProvider()
            }

            CMD_SYNC_ANC_MODE -> {
                PanaLog.i(TAG, "CMD_SYNC_ANC_MODE: starting service query")
                val serviceIntent = Intent(context, PanaBleService::class.java).apply {
                    action = PanaBleService.ACTION_SYNC_ANC
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Throwable) {
                    PanaLog.w(TAG, "startForegroundService blocked: ${e.message}")
                }
            }

            CMD_ACTIVATE_DEVICE -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                PanaLog.i(TAG, "CMD_ACTIVATE_DEVICE: $address")
                                // v93: 未来扩展，暂时仅记录日志
            }

            // v2.0.12：强制重建焦点卡片（remove → add）。用于岛条目被 HyperOS 从可见
            // 列表移除后原地 notify() 顶不出展开态的场景；也便于诊断。
            CMD_REPOST_CARD -> {
                PanaLog.i(TAG, "CMD_REPOST_CARD")
                PanaBleService.repostFocusCardFromProvider()
            }

            // v2.0：官方 Technics Audio Connect 的连接让权（Provider.call 的广播兜底路径）
            CMD_OFFICIAL_APP_ACQUIRE, CMD_OFFICIAL_APP_RELEASE -> {
                val leaseId = intent.getStringExtra(PanaBridge.EXTRA_OFFICIAL_LEASE_ID)
                val token = intent.extras?.getBinder(PanaBridge.EXTRA_OFFICIAL_LEASE_TOKEN)
                PanaLog.i(TAG, "$command leaseId=$leaseId hasToken=${token != null}")
                if (command == CMD_OFFICIAL_APP_ACQUIRE) {
                    OfficialLease.onAcquire(leaseId, token, "broadcast")
                } else {
                    OfficialLease.onRelease(leaseId, "broadcast")
                }
            }

            else -> PanaLog.w(TAG, "Unknown command: $command")
        }
    }
}
