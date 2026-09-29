package com.panapods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.bridge.PanaPodsProvider
import java.lang.reflect.Method

/**
 * 官方快连大弹窗 —— 连接耳机时由 com.xiaomi.bluetooth 弹出小米标准设备卡片。
 *
 * 移植自 SonyPods `dev.sonypods.hook.OfficialFastConnectDialogHook`（约 1855 行），
 * 架构不变：
 *
 * 1. **主进程（com.xiaomi.bluetooth）**：监听 PanaBridge 状态广播，在
 *    「断开 → 连接」跳变且设置开关打开时，用伪造的 AirDots 载荷启动系统自己的
 *    `MiuiFastConnectActivity`（快连 Activity + `FAST_CONNECT_DEVICE` action），
 *    Intent 附带 [MODULE_DIALOG_MARKER] 标记与地址，保证只接管本模块发起的卡片。
 * 2. **:ui 进程（com.xiaomi.bluetooth:ui）**：Hook 该 Activity 与 Controller 的
 *    `modifyView` / `updateConnectSuccessDilog`，把真实名称、左右电量、电量图标、
 *    产品图注入到卡片视图；[installBatteryTextGuard] 拦截官方渲染器写入的 0% 兜底，
 *    [installHandlerDispatchGuard] 拦截官方的延迟失败自检消息，卡片才不会闪一下就关。
 *
 * 与 SonyPods 原版的差异（PanaPods 没有对应基础设施，按语义裁剪）：
 * - HookStateMirror/ContentObserver → PanaBridge 隐式广播 + Provider 首次播种；
 * - SonyStateSnapshot → [PanaSnapshot]（connected/address/name/电量四项）；
 * - 连接判定改为「断开 → 跳变」：注册时用 Provider 播种基线，避免蓝牙进程
 *   中途重启时对着已连接的耳机补弹一次；
 * - 配置读取：ConfigManager 是 App 进程私有 SharedPreferences，Hook 侧通过
 *   PanaPodsProvider.call(METHOD_GET_CONNECT_POPUP) 读开关；
 * - PopupDndPolicy 用户黑白名单 → 仅保留 ROM 规则（游戏/横屏不弹）；
 * - 去掉热重载生命周期、DexKit 符号束、云端图兜底与 Instrumentation 启动拦截
 *   （PanaPods 无对应场景）；Tandem 换成自家语义的
 *   [com.panapods.bridge.HandoverResume] 让权恢复抑制——官方 Technics
 *   Audio Connect 退出后的重连不算新连接，不弹卡；
 * - 产品图：模块资源 `R.drawable.pana_headset`（createPackageContext 加载，
 *   失败则保留官方 AirDots 图，不影响功能）。
 *
 * 作用域：com.xiaomi.bluetooth（主进程 + :ui 进程都会进 [onHook]/[onApplicationReady]）。
 */
@SuppressLint("MissingPermission")
object OfficialFastConnectDialogHook : HookContext() {

    private const val XIAOMI_PACKAGE = "com.xiaomi.bluetooth"
    private const val MODULE_PACKAGE = "com.panapods.next"
    private const val UI_PROCESS_SUFFIX = ":ui"
    private const val FAST_CONNECT_ACTIVITY =
        "com.android.bluetooth.ble.app.MiuiFastConnectActivity"
    private const val FAST_CONNECT_ACTIVITY_VARIANT =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
    private const val FAST_CONTROLLER_CLASS =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectController"
    private const val FAST_CONNECT_ACTION = "com.android.bluetooth.FAST_CONNECT_DEVICE"

    /**
     * 该 Activity 类同时被小米自家快连流程使用，类名匹配会劫持所有官方耳机弹窗。
     * 标记只由 [launchOfficialActivity] 写入，且必须与地址一起通过
     * [managedOfficialAddress] 校验，校验不过的 Activity 一律放行（bypass）。
     */
    private const val EXTRA_MODULE_DIALOG_MARKER =
        "com.panapods.next.extra.OFFICIAL_FAST_CONNECT_DIALOG"
    private const val EXTRA_MODULE_DIALOG_ADDRESS =
        "com.panapods.next.extra.OFFICIAL_FAST_CONNECT_ADDRESS"
    private const val MODULE_DIALOG_MARKER = "panapods_official_fast_connect"
    private const val SINGLE_IMAGE_SCALE = 1.4f

    /** 卡片所需的最小状态快照（对标 SonyStateSnapshot 的裁剪子集）。 */
    data class PanaSnapshot(
        val connected: Boolean,
        val deviceAddress: String?,
        val deviceName: String?,
        val batteryLeft: Int?,
        val batteryRight: Int?,
        val batteryCradle: Int?,
    ) {
        /** 至少一侧电量已知才把卡片推进到成功态（否则保持连接中，不注入 0%）。 */
        val essentialValuesReady: Boolean
            get() = batteryLeft != null || batteryRight != null || batteryCradle != null
    }

    // ============ 进程侧状态 ============

    private var mainReceiver: BroadcastReceiver? = null
    private var uiReceiver: BroadcastReceiver? = null

    /** 主进程侧的连接基线（注册时 Provider 播种，之后跟随广播更新）。 */
    @Volatile
    private var mainConnected = false

    @Volatile
    private var latestSnapshot: PanaSnapshot? = null

    private var activeActivity: Activity? = null
    private var activeController: Any? = null
    private var activeView: View? = null
    private var activeAddress: String? = null
    private var connectingSent = false
    private var successSent = false

    private var batteryViewDumped = false
    private var batteryTextHookInstalled = false
    private val batteryTextRewriteDepth = ThreadLocal.withInitial { false }
    private var officialHandler: Handler? = null
    private var handlerDispatchGuardInstalled = false
    private var frameworkActivityHookInstalled = false
    private val fastControllerHookedClasses = mutableSetOf<String>()
    private val fastSuccessHookedClasses = mutableSetOf<String>()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val isUiProcess: Boolean
        get() = runCatching { Application.getProcessName() }
            .getOrNull()
            ?.endsWith(UI_PROCESS_SUFFIX) == true

    // ============ 生命周期 ============

    override fun onHook() {
        if (!isUiProcess) {
            // 主进程侧只负责「何时弹」，全部依赖 Application 就绪后的状态接收器。
            return
        }
        logI("installing official fast-connect dialog UI hooks")
        // Qigsaw feature 里的 Activity 类在 package-ready 时可能还不可见：
        // 具体类 Hook 失败不致命，Framework Activity 生命周期是兜底观察者。
        installFrameworkActivityHooks()
        installBatteryTextGuard(appClassLoader)
        installHandlerDispatchGuard(appClassLoader)
        val rootHooked = installActivityHooks(FAST_CONNECT_ACTIVITY, "root")
        val variantHooked = installActivityHooks(FAST_CONNECT_ACTIVITY_VARIANT, "fast")
        if (!rootHooked && !variantHooked) {
            logW(
                "no official Activity class hooked ($FAST_CONNECT_ACTIVITY, " +
                    "$FAST_CONNECT_ACTIVITY_VARIANT); framework Activity lifecycle is the fallback"
            )
        }
    }

    override fun onApplicationReady(application: Application) {
        // HookEntry 从 Instrumentation.callApplicationOnCreate 派发，早于任何
        // Activity.onCreate —— :ui 进程被小米原生快连拉起时也不会漏掉注册时机。
        val appContext = application.applicationContext ?: application
        if (isUiProcess) {
            registerUiStateReceiver(appContext)
            refreshStateFromProvider(appContext)
            findExistingManagedActivity()?.let(::onOfficialActivityCreated)
        } else {
            // 先播种连接基线再注册接收器：中途重启的进程对着已连接耳机不会补弹。
            refreshStateFromProvider(appContext)
            registerMainStateReceiver(appContext)
        }
    }

    // ============ 状态输入 ============

    private fun snapshotFromBridge(): PanaSnapshot = PanaSnapshot(
        connected = PanaBridge.isConnected(),
        deviceAddress = PanaBridge.getMacAddress()?.takeIf { it.isNotBlank() },
        deviceName = PanaBridge.getDeviceName(),
        batteryLeft = PanaBridge.normalizeBatteryOrNull(PanaBridge.getLeftBattery()),
        batteryRight = PanaBridge.normalizeBatteryOrNull(PanaBridge.getRightBattery()),
        batteryCradle = PanaBridge.normalizeBatteryOrNull(PanaBridge.getCradleBattery()),
    )

    /** 启动时从 Provider 拉一次状态（App 未运行时 connected=false，基线为未连接）。 */
    private fun refreshStateFromProvider(context: Context) {
        runCatching {
            context.contentResolver
                .query(PanaPodsProvider.CONTENT_URI, null, null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val left = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_LEFT))
                    val right = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_RIGHT))
                    val cradle = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CRADLE))
                    val anc = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ANC))
                    val connected = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CONNECTED)) != 0
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_NAME))
                    val addr = cursor.getString(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ADDRESS))
                    PanaBridge.publishStateToCache(left, right, cradle, anc, name, addr, connected)
                    val snapshot = snapshotFromBridge()
                    latestSnapshot = snapshot
                    if (!isUiProcess) mainConnected = snapshot.connected
                    logD("provider seed: connected=$connected addr=${snapshot.deviceAddress}")
                }
        }.onFailure { logW("provider seed failed: ${it.message}") }
    }

    private fun registerMainStateReceiver(context: Context) {
        if (mainReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!PanaBridge.updateCacheFromIntent(intent)) return
                onMainState(context, snapshotFromBridge())
            }
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(PanaBridge.ACTION_STATE_UPDATED),
                Context.RECEIVER_EXPORTED,
            )
            mainReceiver = receiver
            logI("official dialog main-process state receiver registered")
        }.onFailure { logW("official dialog state receiver registration failed: ${it.message}") }
    }

    /**
     * 只在「断开 → 连接」跳变时弹一次。[mainConnected] 由注册时的 Provider 播种，
     * 因此蓝牙进程中途重启（对已连接会话补读到 connected=true）不会补弹；
     * 真正的连接建立必然经过 false → true。
     */
    private fun onMainState(context: Context, snapshot: PanaSnapshot) {
        val wasConnected = mainConnected
        mainConnected = snapshot.connected
        if (!snapshot.connected || snapshot.deviceAddress.isNullOrBlank()) return
        if (wasConnected) return
        if (!shouldLaunchOfficialDialog(context)) return
        if (launchOfficialActivity(context, snapshot)) {
            logI("official dialog launched address=${snapshot.deviceAddress}")
        }
    }

    private fun shouldLaunchOfficialDialog(context: Context): Boolean {
        if (!connectPopupEnabled(context)) {
            logD("official dialog skipped: disabled in PanaPods settings")
            return false
        }
        // 官方 App（Technics Audio Connect）让权归还后的重连是「恢复」而非新连接：
        // 否则每次退出官方 App 都会莫名弹一张卡（用户反馈）。读取即消费，只抑制这一次。
        if (consumeHandoverResume(context)) {
            logI("official dialog skipped: handover resume (official app released lease)")
            return false
        }
        suppressReason(context)?.let { reason ->
            logD("official dialog skipped: $reason")
            return false
        }
        return true
    }

    /**
     * 经白名单 Provider 消费「让权恢复」豁免标记。App 未运行时 call 会拉起进程，
     * 读到的是 SharedPreferences 里引擎打的点；调用失败一律不抑制（保持旧行为）。
     */
    private fun consumeHandoverResume(context: Context): Boolean = runCatching {
        context.contentResolver.call(
            PanaPodsProvider.CONTENT_URI,
            PanaPodsProvider.METHOD_CONSUME_HANDOVER_RESUME,
            null,
            null,
        )?.getBoolean(PanaPodsProvider.EXTRA_HANDOVER_RESUME_SUPPRESSED, false) ?: false
    }.getOrElse {
        logW("handover resume query failed: ${it.message}")
        false
    }

    /** 开关存放在模块 App 的私有配置里，经白名单 Provider 读取（失败时默认开启）。 */
    private fun connectPopupEnabled(context: Context): Boolean = runCatching {
        context.contentResolver.call(
            PanaPodsProvider.CONTENT_URI,
            PanaPodsProvider.METHOD_GET_CONNECT_POPUP,
            null,
            null,
        )?.getBoolean(PanaPodsProvider.EXTRA_CONNECT_POPUP_ENABLED, true) ?: true
    }.getOrDefault(true)

    /**
     * 复刻 Bluetooth Extension 自己的 `preCheckStartProductActivity` 前置条件：
     * 游戏运行中、手机横屏时不弹（平板豁免横屏判定）。ROM 规则保序：先游戏后横屏。
     */
    private fun suppressReason(context: Context): String? {
        if (isGameRunning(context)) return "game"
        if (isPhoneLandscape(context)) return "landscape"
        return null
    }

    /** 与系统快连同一份 sticky 广播；注册 null 接收器同步取最近值。 */
    private fun isGameRunning(context: Context): Boolean = runCatching {
        context.registerReceiver(null, IntentFilter("com.xiaomi.joyose.GAME_START"))
            ?.getBooleanExtra("start", false) == true
    }.getOrDefault(false)

    private fun isPhoneLandscape(context: Context): Boolean = runCatching {
        if (isPad(context)) return@runCatching false
        val rotation = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: return@runCatching false
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }.getOrDefault(false)

    /** ROM 的 is_pad 标志；FeatureParser 不可用时按屏宽兜底，宁可判成平板也不误拦。 */
    private fun isPad(context: Context): Boolean = runCatching {
        Class.forName("android.util.FeatureParser")
            .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "is_pad", false) as Boolean
    }.getOrElse {
        context.resources.configuration.smallestScreenWidthDp >= 600
    }

    // ============ 官方 Activity 启动 ============

    private fun launchOfficialActivity(context: Context, snapshot: PanaSnapshot): Boolean {
        val address = snapshot.deviceAddress ?: return false
        val device = runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: run {
            logW("cannot create BluetoothDevice for official dialog address=$address")
            return false
        }
        logD(
            "launching official fast-connect dialog address=$address " +
                "name=${snapshot.deviceName.orEmpty()}"
        )
        val intent = Intent().apply {
            // 当前的快连卡片是 feature-module Activity；根 Activity 是遗留实现，
            // 启动它会让 :ui 侧的 Controller/视图 Hook 落空。
            setClassName(XIAOMI_PACKAGE, FAST_CONNECT_ACTIVITY_VARIANT)
            action = FAST_CONNECT_ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("android.bluetooth.device.extra.DEVICE", device)
            snapshot.deviceName?.takeIf { it.isNotBlank() }?.let {
                putExtra("device_name_over_write", it)
            }
            // 01010200 是内置 Redmi AirDots 控制器：提供稳定的官方 TWS 布局，
            // 而真实设备/电量随后由 :ui 侧 Hook 注入。
            putExtra("headset_miui_data", fakeAirDotsData(snapshot))
            putExtra("headset_adv_row_bytes", fakeAdvRowData(snapshot))
            putExtra(
                "headset_addresses",
                arrayOf(
                    address,
                    "00:00:00:00:00:00",
                    snapshot.deviceName?.takeIf { it.isNotBlank() } ?: address,
                ),
            )
            putExtra("type_layout_hid_fastconnect", 1)
            // C3323r4.Z() 把 0x02/0x08 视作左右电量有效位；保持清零时官方成功渲染
            // 器会显示禁用/0% 态，真实电量由 refreshBatteryText/Icons 覆盖。
            putExtra("headset_extra_data", intArrayOf(0, 0x0F, 0, 1, 0))
            putExtra("current_a2dp_devices", 0)
            // 模块标记绑定到确切地址：:ui 侧所有变更入口都据此 fail-closed。
            putExtra(EXTRA_MODULE_DIALOG_MARKER, MODULE_DIALOG_MARKER)
            putExtra(EXTRA_MODULE_DIALOG_ADDRESS, address)
        }
        return runCatching {
            context.startActivity(intent)
            logD("official PairingDialog Activity launched address=$address")
            true
        }.onFailure {
            // 这里是硬失败：不允许静默回落到模块自己的弹窗，问题必须留在日志里。
            logE("official PairingDialog Activity launch failed", it)
        }.getOrDefault(false)
    }

    private fun fakeAirDotsData(snapshot: PanaSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        return ByteArray(24).apply {
            this[0] = 0x16
            this[1] = 0x01
            this[2] = 0x02
            this[3] = 0x00
            this[4] = 0x00
            this[5] = level(snapshot.batteryLeft).toByte()
            this[6] = level(snapshot.batteryRight).toByte()
            this[7] = level(snapshot.batteryCradle).toByte()
            // 地址字节保持非零：C3287o5 的本地地址解析不会把载荷当成空设备。
            this[8] = 0x01
            this[9] = 0x02
            this[10] = 0x03
            this[11] = 0x04
            this[12] = 0x05
            this[13] = 0x06
            this[14] = 0x07
            this[15] = 0x08
            this[16] = 0x09
        }
    }

    /**
     * 官方实现传入原始 BLE 扫描记录。空数组在旧版本能被接受，但 feature
     * Controller 会把它按 ScanRecord 解析，解析失败就立即 finish Activity。
     * 这里给一份最小合法的 manufacturer-data 记录；真实电量/名称由下方注入。
     */
    private fun fakeAdvRowData(snapshot: PanaSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        val name = snapshot.deviceName?.takeIf { it.isNotBlank() } ?: "PanaPods"
        val nameBytes = name.toByteArray(Charsets.UTF_8).take(20).toByteArray()
        val manufacturerPayload = byteArrayOf(
            0x16, 0x01, 0x02, 0x00, 0x00,
            level(snapshot.batteryLeft).toByte(),
            level(snapshot.batteryRight).toByte(),
            level(snapshot.batteryCradle).toByte(),
        )
        return byteArrayOf(
            (manufacturerPayload.size + 3).toByte(),
            0xFF.toByte(),
            0x01,
            0x02,
        ) + manufacturerPayload + byteArrayOf(
            (nameBytes.size + 1).toByte(),
            0x09,
        ) + nameBytes
    }

    // ============ :ui 侧：Activity 生命周期 ============

    private fun registerUiStateReceiver(context: Context) {
        if (uiReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!PanaBridge.updateCacheFromIntent(intent)) return
                val snapshot = snapshotFromBridge()
                latestSnapshot = snapshot
                if (activeActivity == null) findExistingManagedActivity()?.let(::onOfficialActivityCreated)
                applySnapshot(snapshot)
            }
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(PanaBridge.ACTION_STATE_UPDATED),
                Context.RECEIVER_EXPORTED,
            )
            uiReceiver = receiver
            logI("official dialog UI-process state receiver registered")
        }.onFailure { logW("official dialog UI state receiver failed: ${it.message}") }
    }

    /**
     * Framework 兜底：feature Activity 由 Qigsaw 延迟加载时，具体类 Hook 在
     * package-ready 时找不到类；onCreate/onDestroy 生命周期一定经过 Framework 类。
     */
    private fun installFrameworkActivityHooks() {
        if (frameworkActivityHookInstalled) return
        runCatching {
            hookAfter(findMethodWithLoader("android.app.Activity", appClassLoader, "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityCreated(activity)
            }
            hookAfter(findMethodWithLoader("android.app.Activity", appClassLoader, "onDestroy")) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityDestroyed(activity)
            }
            frameworkActivityHookInstalled = true
            logD("official framework Activity fallback hook installed")
        }.onFailure { logW("official framework Activity fallback unavailable: ${it.message}") }
    }

    /** 返回 true 表示具体 Activity 类可见且 Hook 成功（Qigsaw 场景失败是常态）。 */
    private fun installActivityHooks(className: String, role: String): Boolean =
        runCatching {
            hookAfter(findMethod(className, "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityCreated(activity)
            }
            hookAfter(findMethod(className, "onDestroy")) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityDestroyed(activity)
            }
            logD("official Activity hooks installed class=$className")
            true
        }.getOrElse {
            logD("official Activity hooks unavailable class=$className: ${it.message}")
            false
        }

    private fun isOfficialActivity(activity: Activity): Boolean =
        activity.javaClass.name == FAST_CONNECT_ACTIVITY ||
            activity.javaClass.name == FAST_CONNECT_ACTIVITY_VARIANT

    /**
     * 返回本模块合成卡片携带的地址。标记必须与全部地址载体一致：小米自己的
     * 官方 Intent 不能因为指向同一个 Activity 类就被当成我们的卡片。
     */
    private fun managedOfficialAddress(activity: Activity): String? {
        if (!isOfficialActivity(activity)) return null
        val intent = activity.intent ?: return null
        if (intent.getStringExtra(EXTRA_MODULE_DIALOG_MARKER) != MODULE_DIALOG_MARKER) return null

        val taggedAddress = intent.getStringExtra(EXTRA_MODULE_DIALOG_ADDRESS)
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?: return null
        val headsetAddress = intent.getStringArrayExtra("headset_addresses")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?: return null
        if (!taggedAddress.equals(headsetAddress, ignoreCase = true)) return null

        val deviceAddress = runCatching {
            intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                android.bluetooth.BluetoothDevice::class.java,
            )
        }.getOrNull()
        if (deviceAddress != null && !taggedAddress.equals(deviceAddress.address, ignoreCase = true)) {
            return null
        }
        return taggedAddress
    }

    /** 仅本模块为该地址发起的 Activity 才算接管对象。 */
    private fun isManagedOfficialActivity(activity: Activity?): Boolean {
        val address = activity?.let(::managedOfficialAddress) ?: return false
        val active = activeAddress
        return active == null || active.equals(address, ignoreCase = true)
    }

    /**
     * 变更时刻闸门：进程里可能同时存在另一副耳机的旧快照，这不能阻止当前
     * Activity 注册，但必须阻止全局 Controller/TextView/Handler 回调把旧设备
     * 的数据写进这张卡片。
     */
    private fun isManagedOfficialTarget(activity: Activity?): Boolean {
        val address = activity?.let(::managedOfficialAddress) ?: return false
        val active = activeAddress
        if (active != null && !active.equals(address, ignoreCase = true)) return false
        val snapshotAddress = latestSnapshot?.deviceAddress
        return snapshotAddress == null || snapshotAddress.equals(address, ignoreCase = true)
    }

    private fun isBluetoothAddress(value: String): Boolean =
        value.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"))

    private fun onOfficialActivityCreated(activity: Activity) {
        val dialogAddress = managedOfficialAddress(activity) ?: run {
            logD("bypass official Activity without PanaPods marker/address class=${activity.javaClass.name}")
            return
        }
        if (activeActivity === activity && activeController != null) return
        activeActivity = activity
        // Application 级注册已由 onApplicationReady 完成；这里是 Activity 这个
        // 首个可靠 Context 之上的补装（电池文本守卫要 Activity 的 ClassLoader）。
        installBatteryTextGuard(activity.classLoader)
        activeController = findActivityController(activity)
        activeView = activeController?.let { controller -> findControllerView(controller) }
        officialHandler = activeController?.let(::findControllerHandler)
        installFastControllerHooks(activeController, activity.classLoader)
        activeAddress = dialogAddress
        connectingSent = false
        successSent = false
        latestSnapshot?.let { applySnapshot(it) }
        // 从 Framework Activity.onCreate 回调进来时，子类还没赋值 Controller 字段；
        // 子类 onCreate 跑完后再重绑一次，成功态刷新 Hook 才拿得到 Controller 与视图。
        uiHandler.post {
            if (activeActivity !== activity) return@post
            findActivityController(activity)?.let { controller ->
                activeController = controller
                activeView = findControllerView(controller)
                officialHandler = findControllerHandler(controller)
                installFastControllerHooks(controller, activity.classLoader)
                applyOfficialIdentity(latestSnapshot)
                activeView?.let { view ->
                    replaceOfficialImages(view)
                    latestSnapshot?.let {
                        refreshBatteryText(view, it)
                        refreshBatteryIcons(view, it)
                    }
                }
                latestSnapshot?.let { applySnapshot(it) }
                logD("official Activity controller rebound class=${controller.javaClass.name}")
            }
        }
        logI(
            "official Activity created class=${activity.javaClass.name} " +
                "address=$activeAddress controller=${activeController?.javaClass?.name}"
        )
    }

    private fun onOfficialActivityDestroyed(activity: Activity?) {
        if (activity == null || activity === activeActivity) {
            activeActivity = null
            activeController = null
            activeView = null
            officialHandler = null
            activeAddress = null
            connectingSent = false
            successSent = false
        }
    }

    /**
     * 官方控制器在成功回调后会投递一条延迟失败自检；对合成载荷而言这是假阴性，
     * 约一秒后会把卡片关掉。按 Handler 运行时类型识别（不依赖混淆类/字段名），
     * 只压制 message 3/6，且仅当 Bridge 仍报告该地址已连接；其余消息原样放行。
     */
    private fun installHandlerDispatchGuard(classLoader: ClassLoader) {
        if (handlerDispatchGuardInstalled) return
        runCatching {
            val dispatch = Class.forName("android.os.Handler", false, classLoader)
                .getDeclaredMethod("dispatchMessage", Message::class.java)
                .apply { isAccessible = true }
            hookBefore(dispatch) {
                val handler = instance as? Handler ?: return@hookBefore
                val message = args.firstOrNull() as? Message ?: return@hookBefore
                val snapshot = latestSnapshot ?: return@hookBefore
                if (!isManagedOfficialTarget(activeActivity) ||
                    handler !== officialHandler ||
                    message.what !in setOf(3, 6) ||
                    !snapshot.connected ||
                    !snapshot.deviceAddress.equals(activeAddress, ignoreCase = true)
                ) {
                    return@hookBefore
                }
                logD(
                    "official dialog ignored automatic close/check " +
                        "what=${message.what} address=${snapshot.deviceAddress}"
                )
                result = null
            }
            handlerDispatchGuardInstalled = true
            logD("official dialog failure-check Handler guard installed")
        }.onFailure { logW("official dialog failure-check guard unavailable: ${it.message}") }
    }

    // ============ :ui 侧：Controller / 视图注入 ============

    private fun installFastControllerHooks(controller: Any?, fallbackLoader: ClassLoader) {
        val loader = controller?.javaClass?.classLoader ?: fallbackLoader
        // 同时 Hook 稳定的现代基类与具体 feature Controller：后者可能覆写
        // modifyView，基类 Hook 就永远不会被触发。
        installFastControllerHook(FAST_CONTROLLER_CLASS, loader)
        installFastSuccessHook(FAST_CONTROLLER_CLASS, loader)
        controller?.javaClass?.name
            ?.takeIf { it != FAST_CONTROLLER_CLASS }
            ?.let {
                installFastControllerHook(it, loader)
                installFastSuccessHook(it, loader)
            }
    }

    private fun installFastControllerHook(className: String, classLoader: ClassLoader) {
        if (className in fastControllerHookedClasses) return
        runCatching {
            hookAfter(
                findMethodWithLoader(
                    className,
                    classLoader,
                    "modifyView",
                    View::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                )
            ) {
                onOfficialViewRefreshed(args.firstOrNull() as? View, instance)
            }
            fastControllerHookedClasses += className
            logD("official fast controller hook installed class=$className")
        }.onFailure { logW("official fast controller hook unavailable class=$className: ${it.message}") }
    }

    private fun installFastSuccessHook(className: String, classLoader: ClassLoader) {
        if (className in fastSuccessHookedClasses) return
        runCatching {
            val method = Class.forName(className, false, classLoader)
                .declaredMethods
                .first { it.name == "updateConnectSuccessDilog" }
                .apply { isAccessible = true }
            hookAfter(method) {
                val view = args.filterIsInstance<View>().firstOrNull() ?: activeView
                    ?: activeController?.let(::findControllerView)
                onOfficialViewRefreshed(view, instance)
            }
            fastSuccessHookedClasses += className
            logD(
                "official fast success hook installed class=$className " +
                    "method=${method.name} params=${method.parameterTypes.size}"
            )
        }.onFailure { logW("official fast success hook unavailable class=$className: ${it.message}") }
    }

    private fun onOfficialViewRefreshed(view: View?, controller: Any? = null) {
        view ?: return
        if (!isManagedOfficialTarget(activeActivity) ||
            (controller != null && controller !== activeController)
        ) {
            return
        }
        activeView = view
        applyOfficialIdentity(latestSnapshot)
        replaceOfficialImages(view)
        latestSnapshot?.let {
            refreshBatteryText(view, it)
            refreshBatteryIcons(view, it)
        }
    }

    private fun findMethodWithLoader(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Class<*>,
    ): Method = Class.forName(className, false, classLoader)
        .getDeclaredMethod(methodName, *parameterTypes)
        .apply { isAccessible = true }

    /** 按运行时字段内容识别 Controller（类名含 controller + fastconnect），不依赖混淆名。 */
    private fun findActivityController(activity: Activity): Any? {
        var type: Class<*>? = activity.javaClass
        val candidates = ArrayList<Any>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(activity)
                }.getOrNull()?.let { value ->
                    val name = value.javaClass.name.lowercase()
                    if (name.contains("controller") && name.contains("fastconnect")) {
                        candidates += value
                    }
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    /** 扫 ActivityThread.mActivities 找仍在运行的本模块卡片（进程复用场景）。 */
    private fun findExistingManagedActivity(): Activity? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
            ?: return@runCatching null
        var type: Class<*>? = thread.javaClass
        var activities: Any? = null
        while (type != null && activities == null) {
            activities = runCatching {
                type.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread)
            }.getOrNull()
            type = type.superclass
        }
        val records = (activities as? Map<*, *>)?.values.orEmpty()
        records.asSequence()
            .mapNotNull { record ->
                var recordType: Class<*>? = record?.javaClass
                var activity: Any? = null
                while (recordType != null && activity == null) {
                    activity = runCatching {
                        recordType.getDeclaredField("activity").apply { isAccessible = true }.get(record)
                    }.getOrNull()
                    recordType = recordType.superclass
                }
                activity as? Activity
            }
            .firstOrNull(::isManagedOfficialActivity)
    }.getOrNull()

    private fun findControllerView(controller: Any): View? {
        var type: Class<*>? = controller.javaClass
        val candidates = ArrayList<View>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value is View) candidates += value
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    private fun findControllerHandler(controller: Any): Handler? {
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                if (!Handler::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? Handler
                }.getOrNull()?.let { handler ->
                    return handler
                }
            }
            type = type.superclass
        }
        return null
    }

    // ============ 状态应用 ============

    private fun applySnapshot(snapshot: PanaSnapshot) {
        val activity = activeActivity ?: return
        if (!isManagedOfficialActivity(activity)) return
        val address = snapshot.deviceAddress ?: activeAddress ?: return
        if (activeAddress != null && !address.equals(activeAddress, ignoreCase = true)) return

        applyOfficialIdentity(snapshot)

        if (!snapshot.connected) {
            // PanaPods 的断开即真实 BLE 断开，这张合成卡片没有保留价值。
            logD("official dialog dismissed after disconnect address=$address")
            if (!activity.isFinishing) activity.finish()
            return
        }
        if (!connectingSent || !address.equals(activeAddress, ignoreCase = true)) {
            activeAddress = address
            connectingSent = true
            successSent = false
            logD("official dialog state=connecting address=$address")
        }

        // 成功态是电量读数：能力表之外还要求值本身已回来（过早注入会显示空档）。
        if (!snapshot.essentialValuesReady) return
        activeView?.let { view ->
            refreshBatteryText(view, snapshot)
            refreshBatteryIcons(view, snapshot)
            replaceOfficialImages(view)
        }
        successSent = true
        logD(
            "official dialog state=success address=$address " +
                "battery=${snapshot.batteryLeft}/${snapshot.batteryRight}/${snapshot.batteryCradle}"
        )
    }

    /** 设置语义字段 + 实际 PairingDialog 标题与标题 TextView，双路径覆盖两代混淆。 */
    private fun applyOfficialIdentity(snapshot: PanaSnapshot?) {
        val activity = activeActivity ?: return
        val name = snapshot?.deviceName?.trim()?.takeIf { it.isNotBlank() }
            ?: activity.intent?.getStringArrayExtra("headset_addresses")?.getOrNull(2)
                ?.takeIf { it.isNotBlank() && !it.equals(activeAddress, ignoreCase = true) }
            ?: return

        val controller = activeController
        listOf(
            "mDeviceNameOverWrite",
            "deviceNameOverWrite",
            "mDeviceNameForDialog",
            "mDeviceNameForDialogLocal",
            "deviceNameForDialog",
        ).forEach { field -> setObjectField(controller, field, name) }

        var titleViews = 0
        findPairingDialog(controller)?.let { dialog ->
            runCatching {
                (callMethod(dialog, "getTitleView") as? TextView)?.let { it.text = name; titleViews++ }
            }
            runCatching { callMethod(dialog, "setTitle", name) }
        }

        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        roots.flatMap(::allViews)
            .filterIsInstance<TextView>()
            .filter { textView ->
                val resourceName = resourceEntryName(activity, textView.id).lowercase()
                resourceName.contains("title") ||
                    resourceName.contains("pairing") ||
                    textView.text?.toString() == "Air 2s"
            }
            .forEach { textView ->
                textView.text = name
                titleViews++
            }
        logD("official dialog name applied name=$name titleViews=$titleViews")
    }

    private fun findPairingDialog(controller: Any?): Any? {
        controller ?: return null
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(controller) ?: return@runCatching
                    if (value.javaClass.name.contains("PairingDialog")) return value
                }
            }
            type = type.superclass
        }
        return null
    }

    // ============ 电量文本 ============

    private fun refreshBatteryText(view: View, snapshot: PanaSnapshot) {
        val activity = activeActivity ?: return
        var updated = 0
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)

        fun resourceId(resourceName: String): Int {
            val packages = listOf<String?>(XIAOMI_PACKAGE, activity.packageName, null)
            return packages.asSequence()
                .map { packageName ->
                    runCatching {
                        activity.resources.getIdentifier(resourceName, "id", packageName)
                    }.getOrDefault(0)
                }
                .firstOrNull { it != 0 } ?: 0
        }

        fun showPathToRoot(view: View) {
            var current: View? = view
            while (current != null) {
                current.visibility = View.VISIBLE
                if (roots.any { it === current }) break
                current = current.parent as? View
            }
        }

        fun setPercent(resourceName: String, value: Int?) {
            val id = resourceId(resourceName)
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<TextView>()
                .filter { textView ->
                    textView.id == id ||
                        resourceEntryName(activity, textView.id).equals(resourceName, ignoreCase = true)
                }
                .distinct()
            targets.forEach { textView ->
                if (value == null) {
                    // 该侧不在位/未知：不留上一次的百分比在屏上。
                    textView.visibility = View.GONE
                    (textView.parent as? View)?.visibility = View.GONE
                } else {
                    textView.text = "${value.coerceIn(0, 100)}%"
                    when (resourceName) {
                        "textViewHeadsetLBatteryPercent" -> showHeadsetSideRow(roots, activity, "L")
                        "textViewHeadsetRBatteryPercent" -> showHeadsetSideRow(roots, activity, "R")
                    }
                    showPathToRoot(textView)
                    updated++
                }
            }
            if (value == null) {
                when (resourceName) {
                    "textViewHeadsetLBatteryPercent" -> hideHeadsetSideRow(roots, activity, "L")
                    "textViewHeadsetRBatteryPercent" -> hideHeadsetSideRow(roots, activity, "R")
                }
            }
            if (id == 0) {
                logD("official dialog battery id missing name=$resourceName")
            } else if (targets.isEmpty()) {
                logD("official dialog battery view missing name=$resourceName id=$id")
            }
        }

        fun dumpBatteryTextViews() {
            if (batteryViewDumped) return
            batteryViewDumped = true
            roots.flatMap(::allViews)
                .filterIsInstance<TextView>()
                .distinct()
                .forEach { textView ->
                    logD(
                        "official dialog TextView id=${textView.id} " +
                            "name=${resourceEntryName(activity, textView.id)} " +
                            "text=${textView.text}"
                    )
                }
        }

        setPercent("textViewHeadsetLBatteryPercent", snapshot.batteryLeft)
        setPercent("textViewHeadsetRBatteryPercent", snapshot.batteryRight)
        setPercent("textViewBoxBatteryPercent", snapshot.batteryCradle)
        if (updated == 0) dumpBatteryTextViews()
        logD(
            "official dialog battery text applied updated=$updated " +
                "values=${snapshot.batteryLeft}/${snapshot.batteryRight}/${snapshot.batteryCradle}"
        )
    }

    /**
     * 官方渲染器按其私有模型挑电量图集，模型里目前是 0，会选到空/禁用图标。
     * 这里仍用官方数组与官方加载器，只把选中的档位换成 Bridge 快照的真实电量。
     */
    private fun refreshBatteryIcons(view: View, snapshot: PanaSnapshot) {
        val activity = activeActivity ?: return
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)
        val controller = activeController
        val arrays = controller?.let { findOfficialBatteryArrays(it, activity) }
        var updated = 0

        fun setIcon(resourceName: String, value: Int?) {
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<ImageView>()
                .filter { imageView ->
                    resourceEntryName(activity, imageView.id).equals(resourceName, ignoreCase = true)
                }
                .distinct()
            if (value == null) {
                targets.forEach { imageView ->
                    imageView.visibility = View.GONE
                    (imageView.parent as? View)?.visibility = View.GONE
                }
                return
            }
            when (resourceName) {
                "imageViewHeadsetLBattery" -> showHeadsetSideRow(roots, activity, "L")
                "imageViewHeadsetRBattery" -> showHeadsetSideRow(roots, activity, "R")
            }
            val drawable = if (controller != null && arrays != null) {
                officialBatteryDrawable(controller, arrays, value)
            } else {
                null
            } ?: return
            targets.forEach { imageView ->
                // Drawable 实例不能在多个 ImageView 间共享：用官方 constantState 复制。
                imageView.setImageDrawable(drawable.constantState?.newDrawable() ?: drawable)
                imageView.visibility = View.VISIBLE
                (imageView.parent as? View)?.visibility = View.VISIBLE
                updated++
            }
        }

        if (arrays == null) logD("official dialog battery drawable arrays not found")

        setIcon("imageViewHeadsetLBattery", snapshot.batteryLeft)
        setIcon("imageViewHeadsetRBattery", snapshot.batteryRight)
        setIcon("imageViewBoxBattery", snapshot.batteryCradle)
        logD(
            "official dialog stock battery icons applied updated=$updated " +
                "values=${snapshot.batteryLeft}/${snapshot.batteryRight}/${snapshot.batteryCradle}"
        )
    }

    private fun hideHeadsetSideRow(roots: List<View>, activity: Activity, side: String) {
        val sideLower = side.lowercase()
        val exactNames = listOf(
            "textViewHeadset$side",
            "textViewHeadset${side}Battery",
            "textViewHeadset${side}BatteryPercent",
            "imageViewHeadset${side}Battery",
        )
        val targets = exactNames.mapNotNull {
            findViewByResourceName(roots, activity, it)
        }.distinct()
        if (targets.isEmpty()) return

        val root = roots.firstOrNull { containsView(it, targets.first()) } ?: return
        val row = ancestors(targets.first())
            .filterIsInstance<ViewGroup>()
            .firstOrNull { candidate ->
                if (candidate === root) return@firstOrNull false
                val descendants = allViews(candidate)
                val names = descendants.map { resourceEntryName(activity, it.id).lowercase() }
                val hasThisSide = names.any { it.contains("headset$sideLower") }
                val hasOtherSide = names.any {
                    it.contains("headset") &&
                        (if (sideLower == "l") it.contains("headsetr") else it.contains("headsetl"))
                }
                hasThisSide && !hasOtherSide
            }
        row?.visibility = View.GONE
        targets.forEach { it.visibility = View.GONE }
    }

    private fun showHeadsetSideRow(roots: List<View>, activity: Activity, side: String) {
        val exactNames = listOf(
            "textViewHeadset$side",
            "textViewHeadset${side}Battery",
            "textViewHeadset${side}BatteryPercent",
            "imageViewHeadset${side}Battery",
        )
        val targets = exactNames.mapNotNull {
            findViewByResourceName(roots, activity, it)
        }.distinct()
        if (targets.isEmpty()) return

        val sideLower = side.lowercase()
        val root = roots.firstOrNull { containsView(it, targets.first()) }
        val row = ancestors(targets.first())
            .filterIsInstance<ViewGroup>()
            .firstOrNull { candidate ->
                if (root != null && candidate === root) return@firstOrNull false
                val descendants = allViews(candidate)
                val names = descendants.map { resourceEntryName(activity, it.id).lowercase() }
                val hasThisSide = names.any { it.contains("headset$sideLower") }
                val hasOtherSide = names.any {
                    it.contains("headset") &&
                        (if (sideLower == "l") it.contains("headsetr") else it.contains("headsetl"))
                }
                hasThisSide && !hasOtherSide
            }
        row?.visibility = View.VISIBLE
        targets.forEach { it.visibility = View.VISIBLE }
        hideOfficialChargingIndicators(roots, activity)
    }

    /**
     * 合成载荷只有电量没有充电态：隐藏官方充电角标，避免把默认/陈旧状态
     * 当成真实充电表现。
     */
    private fun hideOfficialChargingIndicators(roots: List<View>, activity: Activity) {
        listOf(
            "imageViewHeadsetLCharge",
            "imageViewHeadsetRCharge",
            "imageViewBoxCharge",
        ).mapNotNull { findViewByResourceName(roots, activity, it) }
            .distinct()
            .forEach { it.visibility = View.GONE }
    }

    private fun ancestors(view: View): List<View> {
        val result = ArrayList<View>()
        var current: View? = view.parent as? View
        while (current != null) {
            result += current
            current = current.parent as? View
        }
        return result
    }

    private data class OfficialBatteryArrays(
        val light: IntArray,
        val dark: IntArray,
    )

    /** 按数组包含的资源名在 Controller 字段里找官方明/暗两套电量图集。 */
    private fun findOfficialBatteryArrays(controller: Any, activity: Activity): OfficialBatteryArrays? {
        val arrays = ArrayList<IntArray>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.type != IntArray::class.java
                ) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? IntArray
                }.getOrNull()?.let { value ->
                    if (value.isNotEmpty()) arrays += value
                }
            }
            type = type.superclass
        }

        fun isBatteryArray(value: IntArray, dark: Boolean): Boolean {
            val names = value.map { id -> resourceEntryName(activity, id).lowercase() }
            val batteryNames = names.filter { it.contains("battery") }
            if (batteryNames.size < 4) return false
            if (dark && batteryNames.none { it.contains("dark") }) return false
            if (!dark && batteryNames.any { it.contains("dark") }) return false
            return batteryNames.any { it.contains("_0") } &&
                batteryNames.any { it.contains("100") }
        }

        val light = arrays.firstOrNull { isBatteryArray(it, dark = false) }
        val dark = arrays.firstOrNull { isBatteryArray(it, dark = true) }
        return if (light != null && dark != null) OfficialBatteryArrays(light, dark) else null
    }

    private fun officialBatteryDrawable(
        controller: Any,
        arrays: OfficialBatteryArrays,
        value: Int,
    ): Drawable? {
        val activity = activeActivity ?: return null
        // 与官方模型相同的分档：min((percent + 19) / 20, 5)。
        val bucket = ((value.coerceIn(0, 100) + 19) / 20).coerceAtMost(5)
        val resourceId = (if (isDarkMode(activity)) arrays.dark else arrays.light)
            .getOrNull(bucket) ?: return null
        return invokeOfficialDrawableLoader(controller, resourceId)
            ?: runCatching { activity.resources.getDrawable(resourceId, activity.theme) }.getOrNull()
    }

    /**
     * 按运行时签名（Drawable <- int）定位官方资源加载方法，而不是混淆名，
     * 远程资源处理保持在官方路径上。
     */
    private fun invokeOfficialDrawableLoader(controller: Any, resourceId: Int): Drawable? {
        val candidates = ArrayList<Any>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value !is android.content.res.Resources && value !is View) candidates += value
                }
            }
            type = type.superclass
        }

        candidates.forEach { candidate ->
            var candidateType: Class<*>? = candidate.javaClass
            while (candidateType != null) {
                candidateType.declaredMethods.forEach { method ->
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers) ||
                        method.parameterTypes.size != 1 ||
                        method.parameterTypes[0] != Int::class.javaPrimitiveType ||
                        !Drawable::class.java.isAssignableFrom(method.returnType)
                    ) return@forEach
                    runCatching {
                        method.isAccessible = true
                        method.invoke(candidate, resourceId) as? Drawable
                    }.getOrNull()?.let { return it }
                }
                candidateType = candidateType.superclass
            }
        }
        return null
    }

    private fun isDarkMode(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and 0x30) == 0x20

    /**
     * 官方渲染器是否打印某侧数值由其私有模型决定；Feature 构建上模型形状不稳定，
     * 不去碰它。改在 Android 文本边界守护语义电量 TextView：官方写入兜底 0% 时，
     * 用 Bridge 已验证的电量覆盖该标签。
     */
    private fun installBatteryTextGuard(classLoader: ClassLoader) {
        if (batteryTextHookInstalled) return
        runCatching {
            val setText = Class.forName("android.widget.TextView", false, classLoader)
                .getDeclaredMethod("setText", CharSequence::class.java)
                .apply { isAccessible = true }
            hookAfter(setText) {
                val textView = instance as? TextView ?: return@hookAfter
                if (batteryTextRewriteDepth.get() == true) return@hookAfter
                val snapshot = latestSnapshot ?: return@hookAfter
                if (!isManagedOfficialTarget(activeActivity) ||
                    !isActiveBatteryTextView(textView)
                ) return@hookAfter
                val value = batteryValueForTextView(textView, snapshot) ?: return@hookAfter
                val current = textView.text?.toString()?.trim()
                if (current != "0%" && current != "0") return@hookAfter
                batteryTextRewriteDepth.set(true)
                try {
                    textView.text = "${value.coerceIn(0, 100)}%"
                    logD(
                        "official dialog battery fallback corrected " +
                            "resource=${resourceEntryName(activeActivity ?: return@hookAfter, textView.id)} " +
                            "value=$value"
                    )
                } finally {
                    batteryTextRewriteDepth.set(false)
                }
            }
            batteryTextHookInstalled = true
            logD("official dialog semantic battery TextView guard installed")
        }.onFailure { logW("official dialog battery TextView guard unavailable: ${it.message}") }
    }

    private fun isActiveBatteryTextView(textView: TextView): Boolean {
        val activity = activeActivity ?: return false
        if (!isManagedOfficialTarget(activity)) return false
        val resourceName = resourceEntryName(activity, textView.id).lowercase()
        if (!resourceName.contains("battery") ||
            (!resourceName.contains("percent") && !resourceName.contains("level"))
        ) return false
        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        return roots.any { root -> allViews(root).any { it === textView } }
    }

    private fun batteryValueForTextView(textView: TextView, snapshot: PanaSnapshot): Int? {
        val activity = activeActivity ?: return null
        val name = resourceEntryName(activity, textView.id).lowercase()
        return when {
            name.contains("left") || name.contains("headsetl") -> snapshot.batteryLeft
            name.contains("right") || name.contains("headsetr") -> snapshot.batteryRight
            name.contains("box") || name.contains("case") || name.contains("cradle") -> snapshot.batteryCradle
            else -> snapshot.batteryLeft ?: snapshot.batteryRight ?: snapshot.batteryCradle
        }
    }

    // ============ 产品图 ============

    private fun replaceOfficialImages(view: View) {
        val activity = activeActivity ?: return
        val address = activeAddress ?: return
        // 官方 TWS 布局有独立的耳机图与充电仓图槽位；PanaPods 只有一张产品图，
        // 收敛成单图：折叠仓图槽位并在真实父容器里居中。
        val drawable = runCatching {
            val module = activity.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            module.getDrawable(R.drawable.pana_headset)
        }.getOrNull()

        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        val imageViews = roots.flatMap(::allViews).filterIsInstance<ImageView>().distinct()
        val namedHeadset = imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewHeadset") }
        val namedBox = imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewBox") }
        val semantic = imageViews.filter { image ->
            val name = resourceEntryName(activity, image.id).lowercase()
            (name.contains("headset") || name.contains("ear") || name.contains("box") ||
                name.contains("device") || name.contains("product")) &&
                !name.contains("battery") && !name.contains("charge") &&
                !name.contains("find") && !name.contains("background")
        }
        val fallback = imageViews.filter { image ->
            val name = resourceEntryName(activity, image.id).lowercase()
            !name.contains("battery") && !name.contains("charge") &&
                !name.contains("find") && !name.contains("background") &&
                !name.contains("harman") && !name.contains("lossless") &&
                !name.contains("audio")
        }
        val headset = namedHeadset ?: semantic.firstOrNull() ?: fallback.getOrNull(0)
        val box = namedBox ?: semantic.firstOrNull { it !== headset } ?: fallback.getOrNull(1)

        val single = headset ?: box
        if (single != null) {
            drawable?.let { single.setImageDrawable(it) }
            single.visibility = View.VISIBLE
            single.scaleX = SINGLE_IMAGE_SCALE
            single.scaleY = SINGLE_IMAGE_SCALE
            collapseOfficialBoxImage(roots, activity, single, box)
            centerOfficialImage(view, single)
        }
        logD(
            "official dialog single image applied hasImage=${drawable != null} " +
                "views=${imageViews.size} address=$address"
        )
    }

    private fun collapseOfficialBoxImage(roots: List<View>, activity: Activity, single: View, box: View?) {
        val boxLayout = findViewByResourceName(roots, activity, "imageViewBoxLayout")
        if (boxLayout != null && boxLayout !== single && !containsView(boxLayout, single)) {
            boxLayout.visibility = View.GONE
        } else if (box != null && box !== single) {
            box.visibility = View.GONE
        }
    }

    private fun centerOfficialImage(root: View, image: View) {
        // 官方 ImageView 可能嵌套在多层布局包装里：逐级向上居中每个直接子项，
        // 只居中 imageViewHeadsetLayout 在包装层被其它容器定位时不够。
        var child: View? = image
        while (child != null && child !== root) {
            centerViewInParent(child)
            child = child.parent as? View
        }
    }

    private fun centerViewInParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val params = view.layoutParams ?: return
        when (params) {
            is LinearLayout.LayoutParams -> {
                if (params.weight > 0f) {
                    params.weight = 0f
                    if (params.width == 0) params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                params.gravity = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or
                    Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }

            is RelativeLayout.LayoutParams -> {
                params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_START, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_END, 0)
                view.layoutParams = params
            }

            is FrameLayout.LayoutParams -> {
                params.gravity = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or
                    Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }
        }
        // gravity/规则对自定义或嵌套的官方容器不总生效：post 布局纠偏相对直接
        // 父容器计算，在每一层嵌套（含混淆 ViewGroup）上都有效。
        view.post {
            val parentWidth = parent.width
            val childWidth = view.width
            if (parentWidth > 0 && childWidth > 0) {
                view.translationX = (parentWidth - childWidth) / 2f - view.left
            }
        }
        parent.requestLayout()
    }

    // ============ 视图工具 ============

    private fun allViews(root: View): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun resourceEntryName(activity: Activity, id: Int): String =
        if (id == View.NO_ID) "" else runCatching {
            activity.resources.getResourceEntryName(id)
        }.getOrDefault("")

    private fun findViewByResourceName(roots: List<View>, activity: Activity, name: String): View? =
        roots.flatMap(::allViews).firstOrNull {
            resourceEntryName(activity, it.id).equals(name, ignoreCase = true)
        }

    private fun containsView(root: View, target: View): Boolean {
        if (root === target) return true
        if (root !is ViewGroup) return false
        for (index in 0 until root.childCount) {
            if (containsView(root.getChildAt(index), target)) return true
        }
        return false
    }

    private fun imageResourceMatches(activity: Activity, view: ImageView, entryName: String): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        val expected = listOf(
            activity.resources.getIdentifier(entryName, "id", XIAOMI_PACKAGE),
            activity.resources.getIdentifier(entryName, "id", activity.packageName),
        ).filter { it != 0 }
        return id in expected || resourceEntryName(activity, id).equals(entryName, ignoreCase = true)
    }

    // ============ 反射工具 ============

    /** 沿类层级写字段；目标字段在任一层命中即停（官方字段常在父类）。 */
    private fun setObjectField(instance: Any?, name: String, value: Any?) {
        val target = instance ?: return
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                true
            }.onSuccess { return }
            type = type.superclass
        }
    }

    /** 沿类层级调用第一个匹配方法名的重载；全部失败返回 null。 */
    private fun callMethod(target: Any?, name: String, vararg args: Any?): Any? {
        val obj = target ?: return null
        var type: Class<*>? = obj.javaClass
        while (type != null) {
            for (method in type.declaredMethods) {
                if (method.name != name) continue
                runCatching {
                    method.isAccessible = true
                    return method.invoke(obj, *args)
                }
            }
            type = type.superclass
        }
        return null
    }
}
