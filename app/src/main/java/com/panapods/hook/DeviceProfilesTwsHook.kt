package com.panapods.hook

import android.bluetooth.BluetoothDevice
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.bridge.PanaBridge

/**
 * 欺骗 HyperOS 把 Pana 识别为小米 TWS 耳机
 *
 * 作用域: com.android.settings / com.miui.contentcatcher
 *
  * HyperOS 的蓝牙详情页 (DeviceProfilesSettings) 通过 isMiHeadset() 判断
  * 是否显示小米原生 TWS 详情 UI。我们把该方法对 Pana 返回 true。
 */
object DeviceProfilesTwsHook : HookContext() {

    private const val TAG = "PanaPods/TWS"

    /** v2.0.2: spoof 日志按设备去重 —— isMiHeadset/isMiWatchDevice 在详情页渲染中被反复调用。 */
    private val panaSpoofLogged = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    override fun onHook() {
        val classLoader = appClassLoader
        PanaLog.i(TAG, "Installing DeviceProfilesSettings TWS spoof hooks...")

        hookIsMiHeadset(classLoader)
        hookIsMiWatchDevice(classLoader)
        hookUpdateDeviceIdFromConfig(classLoader)

        PanaLog.i(TAG, "DeviceProfilesSettings TWS spoof hooks installed ✓")
    }

    /**
     * Hook DeviceProfilesSettings.isMiHeadset(BluetoothDevice)
          * 对 Pana 返回 true，让系统走小米耳机详情页逻辑
     */
    private fun hookIsMiHeadset(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.settings.bluetooth.DeviceProfilesSettings",
                classLoader
            )
            XposedBridge.hookAllMethods(clazz, "isMiHeadset", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val device = param.args.getOrNull(0) as? BluetoothDevice
                    val address = if (device != null) runCatching { device.address }.getOrNull() else null
                    val original = param.result as? Boolean ?: false
                    // v2.0.2: 详情页渲染期间 isMiHeadset 会被反复调用 —— 先走纯内存
                    // 地址缓存，只有缓存未命中才查 device.name（跨进程 Binder）；
                    // 旧实现每次都查 name 并拼字符串打日志，造成设置页滑动掉帧。
                    var isPana = address != null &&
                        (PanaBridge.isPanaByAddress(address) || PanaBridge.isCurrentDevice(address))
                    var name: String? = null
                    if (!isPana) {
                        name = if (device != null) runCatching { device.name }.getOrNull() else null
                        isPana = PanaBridge.isPanaDevice(name)
                        if (address != null) {
                            if (isPana) PanaBridge.addPanaAddress(address)
                            else if (name != null) PanaBridge.addNonPanaAddress(address)
                        }
                    }
                    if (isPana && !original) {
                        if (panaSpoofLogged.add(address ?: name ?: "?")) {
                            PanaLog.i(TAG, "isMiHeadset(${name ?: address}) spoofed: $original →true")
                        }
                        param.result = true
                    }
                }
            })
            PanaLog.i(TAG, "DeviceProfilesSettings.isMiHeadset() hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook isMiHeadset", t)
        }
    }

    /**
     * Hook DeviceProfilesSettings.isMiWatchDevice(BluetoothDevice)
          * 对 Pana 返回 false，避免被识别为手表
     */
    private fun hookIsMiWatchDevice(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.settings.bluetooth.DeviceProfilesSettings",
                classLoader
            )
            XposedBridge.hookAllMethods(clazz, "isMiWatchDevice", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val device = param.args.getOrNull(0) as? BluetoothDevice ?: return
                    val address = runCatching { device.address }.getOrNull()
                    // v2.0.2: 地址缓存优先，只有未命中才查 device.name（跨进程 Binder）。
                    var isPana = address != null &&
                        (PanaBridge.isPanaByAddress(address) || PanaBridge.isCurrentDevice(address))
                    var name: String? = null
                    if (!isPana) {
                        name = runCatching { device.name }.getOrNull()
                        isPana = PanaBridge.isPanaDevice(name)
                        if (isPana && address != null) PanaBridge.addPanaAddress(address)
                    }
                    if (isPana) {
                        val original = param.result as? Boolean ?: false
                        if (original) {
                            if (panaSpoofLogged.add("watch:${address ?: name}")) {
                                PanaLog.i(TAG, "isMiWatchDevice(${name ?: address}) spoofed: $original →false")
                            }
                            param.result = false
                        }
                    }
                }
            })
            PanaLog.i(TAG, "DeviceProfilesSettings.isMiWatchDevice() hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook isMiWatchDevice", t)
        }
    }

    /**
          * Hook updateDeviceIdFromConfig 打印日志，观察 XIAOMICONFIG 对应的设备 ID
     */
    private fun hookUpdateDeviceIdFromConfig(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.settings.bluetooth.DeviceProfilesSettings",
                classLoader
            )
            XposedBridge.hookAllMethods(clazz, "updateDeviceIdFromConfig", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PanaLog.enabled) return
                    val arg = param.args.getOrNull(0)
                    PanaLog.i(TAG, "updateDeviceIdFromConfig called with arg=$arg")
                }
            })
            PanaLog.i(TAG, "DeviceProfilesSettings.updateDeviceIdFromConfig() hooked ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook updateDeviceIdFromConfig", t)
        }
    }
}
