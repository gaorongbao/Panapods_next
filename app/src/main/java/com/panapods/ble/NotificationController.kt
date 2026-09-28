package com.panapods.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import com.panapods.MainActivity
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.utils.PanaLog
import com.xzakota.hyper.notification.focus.FocusNotification

/**
 * BLE 前台服务的通知控制器。
 *
 * 从 PanaBleService 中抽出：负责创建通知渠道、构建并刷新常驻通知。
 *
 * v2.1 复刻 SonyPods「循环切换降噪」：连接成功后通知带上「切换降噪」动作按钮，
 * 点击发送带 token 的显式命令广播给 PanaCommandReceiver，引擎侧按
 * ConfigManager.ancCycleModes 的固定顺序循环（降噪 → 环境声 → 关闭）。
 *
 * v2.2 复刻 SonyPods 焦点通知卡片：MIUI 官方文档明确「Actions 只能在大视图时
 * 显示，标准视图不显示 Actions」，所以普通通知的按钮必须长按/下拉展开才看得到。
 * SonyPods 的按钮直接显示在卡片上，靠的是焦点通知 param_v2 的 **textButton**
 * 组件（按钮属于卡片自绘布局，不走 Actions）。这里用同一套 focus-api 构建
 * `miui.focus.param` extras；若本应用没有焦点通知白名单权限，系统按
 * `filterWhenNoPermission=false` 默认策略降级为普通通知（即旧行为），不影响
 * 原有 Action 按钮兜底。
 */
class NotificationController(context: Context) {

    companion object {
        private const val TAG = "PanaPods/NotifyCtl"
        private const val CHANNEL_ID = "panapods_ble_state"
        /** 旧版 LOW 渠道；HyperOS 冻结已有渠道的 importance/sound 更新（实测
         *  重新 createNotificationChannel 不生效），只能换新 ID 首次创建后清理。 */
        private const val LEGACY_CHANNEL_ID = "panapods_ble"
        /** 前台服务通知 ID；Service.startForeground 与 notify 共用。 */
        const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 2001
        private const val CYCLE_ACTION_REQUEST_CODE = 2002
        /** 焦点通知 param_v2 里图片/按钮的引用键，需与 extras Bundle 中的 key 一致。 */
        private const val FOCUS_PIC_KEY = "key_headset"
        private const val FOCUS_ACTION_KEY = "key_anc_cycle"
    }

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    /**
     * 是否展示「切换降噪」按钮：只在耳机连接成功后展示（与 SonyPods 把按钮放在
     * 连接态的耳机通知上一致）；断开时由 PanaBleService 清掉并刷新通知。
     */
    @Volatile
    private var cycleActionVisible = false

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PanaPods BLE Service",
            // 对齐 SonyPods（IMPORTANCE_DEFAULT + 静音）：作为普通卡片展示；
            // 注意 MIUI 标准视图不渲染 Actions 与重要级别无关，见类注释。
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keep BLE connection alive"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
        // 旧 LOW 渠道删除：正在使用它的旧通知会随之失效，紧接着 startForeground
        // 会在新渠道上重发（同一 ID），只在应用冷启动瞬间闪一下。
        runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        queryFocusPermissionAsync()
    }

    /**
     * v2.2 诊断：小米焦点通知按应用白名单授权（canShowFocus）。被拒时 param_v2
     * 不会渲染，通知降级回普通样式（按钮又要长按）。结果用 w 级别输出，
     * 不依赖调试日志开关，方便远程 logcat 确认。
     */
    private fun queryFocusPermissionAsync() {
        Thread {
            val result = runCatching {
                val extras = Bundle().apply { putString("package", appContext.packageName) }
                appContext.contentResolver.call(
                    Uri.parse("content://miui.statusbar.notification.public"),
                    "canShowFocus",
                    null,
                    extras
                )?.getBoolean("canShowFocus")
            }
            val detail = result.getOrNull()
                ?: "error: ${result.exceptionOrNull()?.message}"
            PanaLog.w(TAG, "canShowFocus=$detail")
        }.start()
    }

    /** 连接/断开时调用；下一次 [update] 起生效。 */
    fun setCycleActionVisible(visible: Boolean) {
        cycleActionVisible = visible
    }

    /** 构建常驻通知（供 Service.startForeground 与刷新复用）。 */
    fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle("PanaPods")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(launchAppPendingIntent())
            // 焦点卡片参数：iconTextInfo（产品图 + 标题/状态）+ textButton（切换降噪）
            .addExtras(buildFocusExtras(text))
        if (cycleActionVisible) {
            // 传统 Action 按钮保留：焦点权限被拒降级/长按展开时仍然可用。
            builder.addAction(cycleAncAction())
        }
        return builder.build()
    }

    /**
     * 焦点通知 param_v2 extras。结构对齐 SonyPods MiBluetoothToastHook 的
     * FocusNotification.buildV3 用法（enableFloat/updatable/iconTextInfo/textButton）。
     */
    private fun buildFocusExtras(text: String): Bundle = FocusNotification.buildV3 {
        updatable = true
        enableFloat = true
        ticker = "PanaPods"
        val logo = createPicture(
            FOCUS_PIC_KEY,
            Icon.createWithResource(appContext, R.drawable.pana_headset)
        )
        iconTextInfo {
            animIconInfo {
                type = 0
                src = logo
            }
            title = "PanaPods"
            content = text
        }
        if (cycleActionVisible) {
            textButton {
                addActionInfo {
                    action = createAction(FOCUS_ACTION_KEY, cycleAncAction())
                    actionTitle = runCatching { appContext.getString(R.string.cycle_anc) }
                        .getOrDefault("切换降噪")
                }
            }
        }
    }

    /** 点通知本体打开主界面。 */
    private fun launchAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        CONTENT_INTENT_REQUEST_CODE,
        Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * 「切换降噪」动作：显式广播到本应用 PanaCommandReceiver（exported），
     * 携带 COMMAND_TOKEN——接收器会拒绝无 token 的第三方广播。
     */
    private fun cycleAncAction(): Notification.Action {
        val intent = Intent(PanaBridge.ACTION_COMMAND).apply {
            setClassName(PanaBridge.PACKAGE_NAME, PanaBridge.COMMAND_RECEIVER_CLASS)
            putExtra(PanaBridge.EXTRA_COMMAND, PanaBridge.COMMAND_CYCLE_ANC)
            putExtra(PanaBridge.EXTRA_COMMAND_TOKEN, PanaBridge.COMMAND_TOKEN)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            CYCLE_ACTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = runCatching { appContext.getString(R.string.cycle_anc) }
            .getOrDefault("切换降噪")
        return Notification.Action.Builder(
            android.R.drawable.ic_popup_sync,
            label,
            pendingIntent
        ).build()
    }

    /** 刷新常驻通知文案。 */
    fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
