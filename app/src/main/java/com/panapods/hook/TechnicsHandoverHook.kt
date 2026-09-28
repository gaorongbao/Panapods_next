package com.panapods.hook

import android.app.Activity
import android.app.Application
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.panapods.bridge.PanaBridge
import com.panapods.utils.PanaLog
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * 官方 **Technics Audio Connect**（com.panasonic.technicsaudioconnect）连接让权 Hook。
 *
 * 对标 SonyPods 的 `SoundConnectHandoverHook`：官方 App 的界面、前台保活服务或
 * 真实厂商通道会话任一存在时，向 BLE 引擎申明独占租约（acquire），引擎断开自己的
 * GATT 并停止自动重连；全部持有源消失且过了 2 秒宽限期后释放（release），引擎复位
 * 退避并立即重连。
 *
 * 三个持有源（与 SonyPods 的 Activity / KeepConnectionForegroundService / MDR session
 * 一一对应）：
 * 1. **Activity**：`Application.ActivityLifecycleCallbacks`（preCreated 即介入，抢在
 *    官方 App 自己建立控制会话之前）；
 * 2. **前台服务**：框架 `Service.startForeground` / `Service.onDestroy` —— 只在本进程
 *    生效，因此不需要去逆向官方 App 的具体 Service 类名；
 * 3. **真实会话**：官方 App 自己打开的 `BluetoothGatt` / `BluetoothSocket` —— 无需
 *    DexKit，直接按框架 API 的创建/关闭配对统计。
 *
 * 每次租约都携带本进程创建的 Binder token：官方 App 被强杀或崩溃、来不及走
 * 生命周期回调时，引擎侧的 `linkToDeath` 立即释放，不会把耳机锁死在「官方独占」。
 */
object TechnicsHandoverHook : HookContext() {

    private const val TAG = "PanaPods/Handover"

    /** 官方 Technics Audio Connect 包名（scope.list 与 HookEntry 共用）。 */
    const val OFFICIAL_PACKAGE = "com.panasonic.technicsaudioconnect"

    /** 持有源全部消失后的释放宽限期：容忍官方 App 页面切换/内部会话迁移的瞬时空档。 */
    private const val RELEASE_GRACE_MS = 2_000L

    @Volatile
    private var installed = false
    private var coordinator: LeaseCoordinator? = null
    private var engineReadyReceiver: BroadcastReceiver? = null
    private var installedApplication: Application? = null

    override fun onHook() {
        // 官方 App 的 Application 就绪后再装：ActivityLifecycleCallbacks 需要 Application
        // 实例，前台服务/会话 Hook 也要在任何组件启动前抢装完成。
        val callApplicationOnCreate = findMethodOrNull(
            "android.app.Instrumentation",
            "callApplicationOnCreate",
            Application::class.java,
        )
        if (callApplicationOnCreate == null) {
            logW("Instrumentation.callApplicationOnCreate not found; handover not installed")
            return
        }
        hookBefore(callApplicationOnCreate) {
            val application = args.firstOrNull() as? Application ?: return@hookBefore
            runCatching { install(application) }
                .onFailure { logW("failed to initialize handover: ${it.message}") }
        }
        logD("handover bootstrap hook installed")
    }

    @Synchronized
    private fun install(application: Application) {
        if (installed) return
        val processName = runCatching { Application.getProcessName() }.getOrNull()
        if (processName != OFFICIAL_PACKAGE) {
            logD("ignoring secondary process=$processName")
            return
        }

        val leaseCoordinator = LeaseCoordinator(application)
        application.registerActivityLifecycleCallbacks(leaseCoordinator)
        installServiceHooks(leaseCoordinator)
        installSessionHooks(leaseCoordinator)

        // 引擎（重新）启动后会广播 engine_ready：仍在持有租约时立即重申，
        // 堵住「引擎重启窗口内双方同时抢连接」。
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PanaBridge.ACTION_ENGINE_READY) {
                    leaseCoordinator.onEngineReady("engine-ready")
                }
            }
        }
        application.registerReceiver(
            receiver,
            IntentFilter(PanaBridge.ACTION_ENGINE_READY),
            Context.RECEIVER_EXPORTED,
        )

        coordinator = leaseCoordinator
        engineReadyReceiver = receiver
        installedApplication = application
        installed = true
        logI("handover hooks installed process=$processName")
    }

    /** 前台服务持有源：startForeground 置位，onDestroy 清除（本进程内生效，无需官方类名）。 */
    private fun installServiceHooks(target: LeaseCoordinator) {
        runCatching {
            hookAllMethodsBefore(Service::class.java, "startForeground") {
                target.setServiceActive(instance, active = true, reason = "service-start-foreground")
            }
            hookAllMethodsBefore(Service::class.java, "onDestroy") {
                target.setServiceActive(instance, active = false, reason = "service-destroyed")
            }
            logD("service hold hooks installed")
        }.onFailure { logW("service hold hooks unavailable: ${it.message}") }
    }

    /** 真实会话持有源：官方 App 自己打开的 GATT / 经典 Socket 通道。 */
    private fun installSessionHooks(target: LeaseCoordinator) {
        runCatching {
            val deviceClass = findClassOrNull(BluetoothDevice::class.java.name)
            if (deviceClass != null) {
                hookAllMethodsAfter(deviceClass, "connectGatt") {
                    val gatt = result ?: return@hookAllMethodsAfter
                    target.setSessionActive(gatt, active = true, reason = "gatt-opened")
                }
            }

            val gattClass = findClassOrNull(BluetoothGatt::class.java.name)
            if (gattClass != null) {
                hookAllMethodsBefore(gattClass, "close") {
                    target.setSessionActive(instance, active = false, reason = "gatt-closed")
                }
                hookAllMethodsBefore(gattClass, "disconnect") {
                    target.setSessionActive(instance, active = false, reason = "gatt-disconnected")
                }
            }

            val socketClass = findClassOrNull(BluetoothSocket::class.java.name)
            if (socketClass != null) {
                hookAllMethodsBefore(socketClass, "connect") {
                    target.setSessionActive(instance, active = true, reason = "socket-connecting")
                }
                hookAllMethodsAfter(socketClass, "connect") {
                    if (param.throwable != null) {
                        target.setSessionActive(instance, active = false, reason = "socket-connect-failed")
                    }
                }
                hookAllMethodsBefore(socketClass, "close") {
                    target.setSessionActive(instance, active = false, reason = "socket-closed")
                }
            }
            logD("session hold hooks installed")
        }.onFailure { logW("session hold hooks unavailable: ${it.message}") }
    }

    /**
     * 租约协调器：统计持有源 → reconcile → acquire / release（宽限期）。
     * 所有方法都加锁，因为 Activity 生命周期在主线程、GATT 回调可能在 Binder 线程。
     */
    private class LeaseCoordinator(private val application: Application) :
        Application.ActivityLifecycleCallbacks {

        private val handler = Handler(Looper.getMainLooper())
        private val creatingActivities = identitySet<Activity>()
        private val startedActivities = identitySet<Activity>()
        private val activeServices = identitySet<Any>()
        private val activeSessions = identitySet<Any>()

        private var pendingRelease: Runnable? = null
        private var leaseId: String? = null
        private var leaseToken: IBinder? = null

        fun close() {
            synchronized(this) {
                handler.removeCallbacksAndMessages(null)
                pendingRelease = null
                creatingActivities.clear()
                startedActivities.clear()
                activeServices.clear()
                activeSessions.clear()
                releaseLocked("coordinator-closed")
            }
            application.unregisterActivityLifecycleCallbacks(this)
        }

        // ============ Activity 持有源 ============

        /** onCreate 之前介入：官方 App 还没来得及建立自己的控制会话，租约已经发出。 */
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            synchronized(this) {
                creatingActivities += activity
                reconcileLocked("activity-pre-created:${activity.javaClass.name}")
            }
        }

        override fun onActivityStarted(activity: Activity) {
            synchronized(this) {
                creatingActivities -= activity
                startedActivities += activity
                reconcileLocked("activity-started:${activity.javaClass.name}")
            }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(this) {
                startedActivities -= activity
                reconcileLocked("activity-stopped:${activity.javaClass.name}")
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            synchronized(this) {
                creatingActivities -= activity
                startedActivities -= activity
                reconcileLocked("activity-destroyed:${activity.javaClass.name}")
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        // ============ 前台服务 / 会话持有源 ============

        fun setServiceActive(instance: Any?, active: Boolean, reason: String) {
            if (instance == null) return
            synchronized(this) {
                if (active) activeServices += instance else activeServices -= instance
                reconcileLocked(reason)
            }
        }

        fun setSessionActive(instance: Any?, active: Boolean, reason: String) {
            if (instance == null) return
            synchronized(this) {
                if (active) activeSessions += instance else activeSessions -= instance
                reconcileLocked(reason)
            }
        }

        fun onEngineReady(reason: String) {
            synchronized(this) {
                // 宽限期内的待释放租约仍算持有：引擎重启不能把保护窗口掐断。
                if (hasActiveHoldLocked() || pendingRelease != null) {
                    reassertLocked(reason)
                } else {
                    releaseLocked("$reason-without-active-hold")
                }
            }
        }

        // ============ 租约状态机 ============

        private fun reconcileLocked(reason: String) {
            if (hasActiveHoldLocked()) {
                cancelPendingReleaseLocked()
                acquireLocked(reason)
            } else {
                scheduleReleaseLocked(reason)
            }
        }

        private fun hasActiveHoldLocked(): Boolean =
            creatingActivities.isNotEmpty() ||
                startedActivities.isNotEmpty() ||
                activeServices.isNotEmpty() ||
                activeSessions.isNotEmpty()

        private fun holdSummaryLocked(): String =
            "creating=${creatingActivities.size} started=${startedActivities.size} " +
                "service=${activeServices.size} session=${activeSessions.size}"

        private fun scheduleReleaseLocked(reason: String) {
            if (leaseId == null || pendingRelease != null) return
            val release = object : Runnable {
                override fun run() {
                    synchronized(this@LeaseCoordinator) {
                        if (pendingRelease !== this || hasActiveHoldLocked()) return
                        pendingRelease = null
                        releaseLocked("grace-expired:$reason")
                    }
                }
            }
            pendingRelease = release
            handler.postDelayed(release, RELEASE_GRACE_MS)
            PanaLog.d(TAG, "lease release scheduled reason=$reason ${holdSummaryLocked()}")
        }

        private fun cancelPendingReleaseLocked() {
            val release = pendingRelease ?: return
            handler.removeCallbacks(release)
            pendingRelease = null
            PanaLog.d(TAG, "lease release cancelled ${holdSummaryLocked()}")
        }

        private fun acquireLocked(reason: String) {
            if (leaseId != null && leaseToken != null) return
            val token = Binder()
            val id = "${Process.myPid()}:${UUID.randomUUID()}"
            // Provider 通道确认送达时返回 true；失败则已走显式广播兜底。
            // 两种情况都记录租约身份，后续 reassert / release 需要同一 id。
            val delivered = runCatching {
                PanaBridge.sendOfficialAppLease(application, acquire = true, leaseId = id, token = token)
            }.getOrDefault(false)
            leaseId = id
            leaseToken = token
            PanaLog.i(TAG, "lease acquired id=$id delivered=$delivered reason=$reason ${holdSummaryLocked()}")
        }

        private fun reassertLocked(reason: String) {
            val id = leaseId
            val token = leaseToken
            if (id == null || token == null) {
                acquireLocked(reason)
                return
            }
            runCatching {
                PanaBridge.sendOfficialAppLease(application, acquire = true, leaseId = id, token = token)
            }
            PanaLog.d(TAG, "lease reasserted id=$id reason=$reason ${holdSummaryLocked()}")
        }

        private fun releaseLocked(reason: String) {
            val id = leaseId ?: return
            val token = leaseToken ?: return
            val delivered = runCatching {
                PanaBridge.sendOfficialAppLease(application, acquire = false, leaseId = id, token = token)
            }.getOrDefault(false)
            leaseId = null
            leaseToken = null
            PanaLog.i(TAG, "lease released id=$id delivered=$delivered reason=$reason")
        }

        companion object {
            private fun <T : Any> identitySet(): MutableSet<T> =
                Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        }
    }
}
