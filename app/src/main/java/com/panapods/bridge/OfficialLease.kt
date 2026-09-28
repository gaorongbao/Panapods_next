package com.panapods.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.panapods.utils.PanaLog

/**
 * 引擎侧（本 App 进程）的「官方 App 连接让权」租约持有者。
 *
 * 对标 SonyPods 的 SonyEngineHost 语义：
 * 1. Hook 侧（com.panasonic.technicsaudioconnect 进程）在 Activity / 前台服务 /
 *    真实 BLE 会话任一存在时发 `official_app_acquire`；
 * 2. 本类进入独占态，通过 [Listener] 让 PanaBleService 断开自己的 GATT、
 *    停止看门狗自动重连与电量轮询；
 * 3. 官方 App 持有源全部消失并过了 2 秒宽限期后发 `official_app_release`，
 *    引擎复位连接退避并立即重连；
 * 4. 每次租约携带官方进程创建的 Binder token，`linkToDeath` 兜底——
 *    官方 App 被强杀/崩溃、来不及发 release 时同样归还连接；
 * 5. 引擎（重新）启动后广播 `engine_ready`，Hook 侧若仍持有租约立即重申，
 *    避免重启窗口内引擎误抢连接。
 *
 * 所有 [Listener] 回调都派发到主线程，Service 可直接改连接状态。
 */
object OfficialLease {

    private const val TAG = "OfficialLease"

    interface Listener {
        /** 官方 App 取得独占：必须让出耳机连接。 */
        fun onOfficialLeaseAcquired(leaseId: String)

        /** 官方 App 归还独占：可以恢复连接。 */
        fun onOfficialLeaseReleased(leaseId: String?)
    }

    private val state = OfficialLeaseState()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var listener: Listener? = null
    @Volatile
    private var token: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** 引擎当前是否处于「官方 App 独占、不得连接」状态。 */
    fun isHeld(): Boolean = state.isHeld()

    fun currentLeaseId(): String? = state.current()

    /** Service 生命周期内注册；若租约早已建立，补发一次 acquired。 */
    fun attach(l: Listener) {
        synchronized(this) {
            listener = l
            val held = state.current()
            if (held != null) {
                handler.post {
                    PanaLog.i(TAG, "attach: replaying active lease id=$held")
                    l.onOfficialLeaseAcquired(held)
                }
            }
        }
    }

    fun detach() {
        synchronized(this) { listener = null }
    }

    /**
     * Hook 侧 acquire / reassert。
     *
     * @param leaseId 租约身份（官方进程 pid + uuid）
     * @param newToken 官方进程创建的 Binder token，可为空（广播兜底路径丢 extra 时）
     */
    @Synchronized
    fun onAcquire(leaseId: String?, newToken: IBinder?, reason: String) {
        if (leaseId.isNullOrBlank()) {
            PanaLog.w(TAG, "acquire ignored: empty lease id ($reason)")
            return
        }
        val becameHeld = state.acquire(leaseId)
        linkToken(leaseId, newToken)
        if (becameHeld) {
            PanaLog.i(TAG, "official app lease acquired id=$leaseId reason=$reason")
            dispatchAcquired(leaseId)
        } else {
            PanaLog.d(TAG, "official app lease reasserted id=$leaseId reason=$reason")
        }
    }

    /** Hook 侧 release（宽限期已结束）或本地兜底释放。 */
    @Synchronized
    fun onRelease(leaseId: String?, reason: String) {
        val changed = state.release(leaseId)
        unlinkToken()
        if (changed) {
            PanaLog.i(TAG, "official app lease released id=$leaseId reason=$reason")
            dispatchReleased(leaseId)
        } else {
            PanaLog.d(TAG, "release ignored (not held / stale lease) id=$leaseId reason=$reason")
        }
    }

    /** 引擎重启后由 PanaBleService 调用：提示 Hook 侧重申租约。 */
    fun broadcastEngineReady(context: Context?) {
        if (context == null) return
        runCatching {
            context.sendBroadcast(Intent(PanaBridge.ACTION_ENGINE_READY).apply {
                putExtra(PanaBridge.EXTRA_STATE_TOKEN, PanaBridge.STATE_TOKEN)
            })
            PanaLog.i(TAG, "engine_ready broadcast sent")
        }.onFailure { e ->
            PanaLog.w(TAG, "engine_ready broadcast failed: ${e.message}")
        }
    }

    // ============ Binder 死亡兜底 ============

    private fun linkToken(leaseId: String, newToken: IBinder?) {
        if (newToken === token) return
        unlinkToken()
        if (newToken == null) return
        val recipient = IBinder.DeathRecipient {
            PanaLog.w(TAG, "official app Binder died, releasing lease id=$leaseId")
            // 死亡回调在 Binder 线程，onRelease 内部会切主线程派发。
            handler.post { onRelease(null, "token-died:$leaseId") }
        }
        try {
            newToken.linkToDeath(recipient, 0)
            token = newToken
            deathRecipient = recipient
        } catch (t: Throwable) {
            PanaLog.w(TAG, "linkToDeath failed: ${t.message}")
        }
    }

    private fun unlinkToken() {
        val current = token
        val recipient = deathRecipient
        token = null
        deathRecipient = null
        if (current != null && recipient != null) {
            runCatching { current.unlinkToDeath(recipient, 0) }
        }
    }

    // ============ 主线程派发 ============

    private fun dispatchAcquired(leaseId: String) {
        val target = listener ?: return
        handler.post { target.onOfficialLeaseAcquired(leaseId) }
    }

    private fun dispatchReleased(leaseId: String?) {
        val target = listener ?: return
        handler.post { target.onOfficialLeaseReleased(leaseId) }
    }

    /**
     * Provider.call 入口（PanaPodsProvider）。
     *
     * @return true 表示租约状态已处理
     */
    fun handleProviderCall(extras: android.os.Bundle?): Boolean {
        if (extras == null) return false
        if (extras.getString(PanaBridge.EXTRA_COMMAND_TOKEN) != PanaBridge.COMMAND_TOKEN) {
            PanaLog.w(TAG, "rejected unauthorized lease call")
            return false
        }
        val command = extras.getString(PanaBridge.EXTRA_COMMAND)
        val leaseId = extras.getString(PanaBridge.EXTRA_OFFICIAL_LEASE_ID)
        val binder = extras.getBinder(PanaBridge.EXTRA_OFFICIAL_LEASE_TOKEN)
        return when (command) {
            PanaBridge.COMMAND_OFFICIAL_APP_ACQUIRE -> {
                onAcquire(leaseId, binder, "provider")
                true
            }
            PanaBridge.COMMAND_OFFICIAL_APP_RELEASE -> {
                onRelease(leaseId, "provider")
                true
            }
            else -> false
        }
    }
}
