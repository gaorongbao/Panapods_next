package com.panapods.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.panapods.MainActivity
import com.panapods.R
import com.panapods.bridge.PanaBridge

/**
 * BLE 前台服务的通知控制器。
 *
 * 从 PanaBleService 中抽出：负责创建通知渠道、构建并刷新常驻通知。
 *
 * v2.1 复刻 SonyPods「循环切换降噪」：连接成功后通知带上「切换降噪」动作按钮，
 * 点击发送带 token 的显式命令广播给 PanaCommandReceiver，引擎侧按
 * ConfigManager.ancCycleModes 的固定顺序循环（降噪 → 环境声 → 关闭）。
 *
 * ## v2.0.13：焦点卡片已移出本类
 *
 * 本类**不再**给通知挂 `miui.focus.param`。原因是根因已定案（见
 * [com.panapods.hook.PanaCardPosterHook] 类注释）：系统对焦点通知的第一道判定是
 * **本地签名比对** —— `SignatureChecker.checkSignatures(目标包签名, SystemUI 签名)`，
 * 同签名直接放行、不同签名才联网问 XMS（scope 20032）。本模块是自签名应用
 * （CN=AZ100），两道都过不去，`-300 scope mismatch`，卡片必被丢弃。
 *
 * 而这条前台服务通知受 `FOREGROUND_SERVICE` 约束**不能搬到系统进程**去发，
 * 所以它带着 focus extras 只会得到一个「被授权门挡下的焦点通知」——也就是用户看到的
 * 那条「app 通知是错的」的视觉。现在把它退回**纯普通通知**（只为保活前台服务），
 * 卡片由 `PanaCardPosterHook` 在 `com.xiaomi.bluetooth`（uid 1002 / 平台签名）
 * 进程里以独立通知代发，得以过掉签名门。
 *
 * 保留 [cycleAncAction]：普通通知的 Action 按钮仍作为兜底（长按/下拉展开可见）。
 */
class NotificationController(context: Context) {

    companion object {
        /** 现行渠道 ID；[PanaBleService.postLeAudioRecoveryHint] 也用它发独立 ID 的提示。 */
        const val CHANNEL_ID = "panapods_ble_state"
        /** 旧版 LOW 渠道；HyperOS 冻结已有渠道的 importance/sound 更新（实测
         *  重新 createNotificationChannel 不生效），只能换新 ID 首次创建后清理。 */
        private const val LEGACY_CHANNEL_ID = "panapods_ble"
        /** 前台服务通知 ID；Service.startForeground 与 notify 共用。 */
        const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 2001
        private const val CYCLE_ACTION_REQUEST_CODE = 2002
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

    /*
     * 展开策略（remove → add）**不在这里**，见 com.panapods.hook.PanaCardPosterHook。
     *
     * 那条结论仍然有效（原地 notify() 顶不出展开态，必须做真正的 remove → add），
     * 但它只对**焦点通知**有意义；本类已退回普通通知，即使 stopForeground → startForeground
     * 也只是「重建一条普通通知」，不会展开任何岛卡片。
     */

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
        // v2.0.13：删掉了这里的 canShowFocus 自检。它查的是「本应用有没有焦点通知权限」，
        // 而本应用已不再发布焦点通知，这个值恒定 true 却与卡片是否出现毫无关系
        // （真凶是同签名判定），留着只会把后续排查带偏。
    }

    /** 连接/断开时调用；下一次 [update] 起生效。 */
    fun setCycleActionVisible(visible: Boolean) {
        cycleActionVisible = visible
    }

    /**
     * 构建前台服务常驻通知（保活用）。
     *
     * v2.0.13：**不再挂 `miui.focus.param`**。它受 `FOREGROUND_SERVICE` 约束搬不到系统进程，
     * 带着 focus extras 只会变成一条被签名门挡下的「残缺焦点通知」——正是用户看到的
     * 「app 通知是错的」。卡片改由 [com.panapods.hook.PanaCardPosterHook] 代发。
     */
    fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle("PanaPods")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(launchAppPendingIntent())
        if (cycleActionVisible) {
            // 传统 Action 按钮保留：长按/下拉展开后仍可点，作为卡片之外的兜底入口。
            builder.addAction(cycleAncAction())
        }
        return builder.build()
    }

    /**
     * 重建前台服务通知（`stopForeground(STOP_FOREGROUND_REMOVE)` 之后调用）。
     *
     * v2.0.13：它现在只是「重建一条普通通知」。原先那条「原地 `notify()` 顶不出展开态、
     * 必须 remove → add」的结论只对**焦点通知**成立，已随焦点卡片一起移到
     * [com.panapods.hook.PanaCardPosterHook]（那里的 `postCard(repost = true)` 才是真正
     * 触发岛卡片重新展开的那条路径）。
     */
    fun buildRepostNotification(text: String): Notification = buildNotification(text)

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

    /** 原地刷新前台服务通知文案（普通通知，与岛卡片互不影响）。 */
    fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
