package com.panapods.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 唯一的 libxposed 102 入口与作用域分发器（对标 SonyPods 的 `dev.sonypods.hook.HookEntry`）。
 *
 * 与旧版（v1.x 直接在 [onPackageLoaded] 里按包名散装调用 `XxxHook.install(classLoader)`）
 * 的差别：
 *
 * 1. **作用域集合显式化**：[supportedScopes] 是唯一真源，[scope.list] 与之对应；
 *    作用域外的包在 [onPackageLoaded] 就被挡掉，日志里能一眼看出「进没进作用域」。
 * 2. **安装时机后移到 [onPackageReady]**：此时拿到的是最终 Application ClassLoader，
 *    冷启动时 `ActivityThread.currentApplication()` 仍可能为 null（见
 *    [installApplicationReadyGate]），依赖 Context 的 Hook 统一在 Application 就绪
 *    回调里补注册，而不是各自在 install 时静默失败再靠轮询重试。
 * 3. **Hook 单元统一继承 [HookContext]**：入口只负责注入 `appClassLoader` / `packageName`
 *    后调用 `onHook()`，Hook 单元不再各自收 classLoader 参数、各自判包名。
 * 4. **每进程只装一套**（[installedScope]），避免同包多进程或重复回调装两遍。
 *
 * 默认作用域（= `META-INF/xposed/scope.list`，5 个）：
 * `com.android.bluetooth` / `com.android.settings` / `com.milink.service` /
 * `com.xiaomi.bluetooth` / `com.panasonic.technicsaudioconnect`（官方 App 连接让权）。
 *
 * 可选渲染端作用域（[optionalScopes]，2 个，**不在 scope.list**）：
 * `com.android.systemui` / `com.miui.contentcatcher`。按 SonyPods 的取数思路，
 * 卡片渲染所需数据由 milink/蓝牙侧的「源头注入」供给；若某 ROM 上融合卡片仍回退
 * 到最简版，可在 LSPosed 中手动勾选 `com.android.systemui` 恢复旧版渲染端补丁，
 * 两份代码路径都保留，无需改代码。
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "PanaPods"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_MILINK = "com.milink.service"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_XIAOMI_BT = "com.xiaomi.bluetooth"
        const val PKG_CONTENTCATCHER = "com.miui.contentcatcher"
        const val PKG_OFFICIAL_AUDIO_CONNECT = TechnicsHandoverHook.OFFICIAL_PACKAGE
    }

    /** 默认作用域（与 scope.list 一致）。 */
    private val defaultScopes = setOf(
        PKG_BLUETOOTH,
        PKG_SETTINGS,
        PKG_MILINK,
        PKG_XIAOMI_BT,
        PKG_OFFICIAL_AUDIO_CONNECT,
    )

    /** 可选渲染端作用域：不在 scope.list，用户在 LSPosed 手动勾选后 [onPackageReady] 才装。 */
    private val optionalScopes = setOf(PKG_SYSTEMUI, PKG_CONTENTCATCHER)

    private val supportedScopes = defaultScopes + optionalScopes

    private var processName: String = "unknown"

    /** 本进程已安装的作用域；null 表示还没装。每进程一套，不做热替换。 */
    @Volatile
    private var installedScope: String? = null

    /** 等待 Application 就绪回调的 Hook 单元。 */
    private val applicationReadyHooks = mutableListOf<HookContext>()

    @Volatile
    private var applicationDispatched = false
    private var applicationGateInstalled = false

    @Volatile
    private var settingsHooksInstalled = false

    /** 设置作用域的 ClassLoader（延迟补装诊断 Hook 时要用）。 */
    @Volatile
    private var settingsClassLoader: ClassLoader? = null

    @Volatile
    private var settingsDiagnosticsInstalled = false

    @Volatile
    private var logSwitchInitialized = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        XposedBridge.init(this)
        // v175：先把日志镜像挂到 LSPosed 模块日志，后续所有 PanaLog 输出
        // （w/e 始终、d/i/v 开关开启时）都能在 LSPosed Manager 日志页看到。
        attachLspLogger()
        PanaLog.i(TAG, "onModuleLoaded: process=$processName isSystemServer=${param.isSystemServer}")
        if (apiVersion < XposedInterface.API_102) {
            PanaLog.e(TAG, "libxposed API $apiVersion is below required 102; scope dispatch disabled")
        }
    }

    /**
     * v175：挂接 LSPosed 日志出口。
     * 现代 API XposedInterface.log(priority, tag, msg)，priority 与
     * android.util.Log 常量一致（PanaLog 已按此传入）。
     * 挂接/调用失败仅降级为 logcat + 文件输出，不影响 Hook 逻辑。
     */
    private fun attachLspLogger() {
        try {
            PanaLog.attachLspLogger { prio, tag, msg -> log(prio, tag, msg) }
            PanaLog.i(TAG, "LSPosed log mirror attached ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "attach LSPosed log mirror failed: ${t.message}")
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val pkg = param.packageName
        PanaLog.i(
            TAG,
            "onPackageLoaded: pkg=$pkg first=${param.isFirstPackage} process=$processName api=$apiVersion",
        )
        if (apiVersion < XposedInterface.API_102) return
        if (pkg !in supportedScopes) return

        // contentcatcher 进程不在 Provider 白名单中，无法查询日志开关，跳过 initLogSwitch
        if (pkg != PKG_CONTENTCATCHER && processName != PKG_CONTENTCATCHER) initLogSwitch()

        // 类发现与安装统一延后到 onPackageReady()：那里才是最终的 Application ClassLoader。
        // （SonyPods 同款做法；旧版在这里直接装，部分依赖 Context 的注册会静默失败。）
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val scope = param.packageName
        PanaLog.i(
            TAG,
            "onPackageReady: pkg=$scope process=$processName api=$apiVersion loader=${param.classLoader.javaClass.name}",
        )
        if (apiVersion < XposedInterface.API_102) return
        if (scope !in supportedScopes) return

        processName = runCatching { Application.getProcessName() }.getOrDefault(processName)

        val active = installedScope
        if (active != null) {
            // 一个进程只装一套。同包多进程各自是独立 VM/实例，互不影响。
            if (active != scope) {
                PanaLog.w(TAG, "scope $scope loaded in process=$processName already owned by $active; skipping")
            } else {
                PanaLog.d(TAG, "scope $scope already installed process=$processName; skipping")
            }
            return
        }

        installedScope = scope
        try {
            installApplicationReadyGate(param.classLoader)
            loadScope(scope, param.classLoader)
            PanaLog.i(TAG, "scope installed scope=$scope process=$processName")
        } catch (t: Throwable) {
            // 不把异常抛回系统进程：模块自身的问题不该拖垮蓝牙/设置/milink。
            // 异常细节 + 堆栈打进 LSPosed 日志，足以定位。
            PanaLog.e(TAG, "failed to install scope=$scope process=$processName", t)
        }
    }

    // ============ 作用域分发 ============

    private fun loadScope(scope: String, classLoader: ClassLoader) {
        when (scope) {
            PKG_BLUETOOTH -> {
                PanaLog.i(TAG, "[$scope] installing HyperOS integration (service side)")
                loadHook(HyperOSHeadsetHook, classLoader, scope)
            }

            PKG_SETTINGS -> {
                PanaLog.i(TAG, "[$scope] installing settings hooks (direct)")
                installSettingsHooks(classLoader, scope)
            }

            PKG_CONTENTCATCHER -> {
                // 设置页兼容进程：Settings 的 ClassLoader 要从 createPackageContext 拿，
                // 且必须等主线程跑起来。保留旧版的延迟安装路径（可选作用域，默认不勾）。
                PanaLog.i(TAG, "[$scope] deferring settings hooks to main thread")
                deferSettingsHooks(classLoader, scope)
            }

            PKG_MILINK, PKG_SYSTEMUI -> {
                // SystemUI 渲染融合中心耳机卡片，ANC/音量区块的 gating 在 SystemUI 本地执行。
                // milink 侧数据与 SystemUI 渲染端各装一份 MiLink 伪装（checkIsMiTWS/getDeviceId/
                // HeadsetInfo getter），按需启用。
                PanaLog.i(TAG, "[$scope] installing MiLink hooks")
                loadHook(MiLinkServiceHook, classLoader, scope)
                // 融合中心卡片图：系统 fallback 的 circulate_* 头戴/索尼样式图替换为 Pana 图。
                loadHook(MiLinkCardArtHook, classLoader, scope)
            }

            PKG_XIAOMI_BT -> {
                PanaLog.i(TAG, "[$scope] installing Xiaomi bluetooth hooks")
                loadHook(HyperOSHeadsetHook, classLoader, scope)
                // AIVS 对 Pana 反复 connectGatt/SPP 探测（AF06 永远找不到），
                // 连接风暴会诱发耳机端主动断联，这里阻断对 Pana 的探测连接。
                loadHook(AivsConnectionBlockHook, classLoader, scope)
                // 连接时的系统级快连大弹窗：主进程负责「何时弹」，
                // :ui 进程负责把真实电量/名称/产品图注入卡片（进程内自判）。
                loadHook(OfficialFastConnectDialogHook, classLoader, scope)
                // v2.0.13：焦点通知卡片的「代发者」。本包 uid=1002（平台签名），
                // 代发出去的通知归属包就是它 → 直接过掉 SystemUI 的「同签名放行」判定，
                // 不必新增 systemui 作用域、也不必 Hook 授权逻辑。详见该类注释。
                loadHook(PanaCardPosterHook, classLoader, scope)
            }

            PKG_OFFICIAL_AUDIO_CONNECT -> {
                PanaLog.i(TAG, "[$scope] installing official-app connection handover")
                loadHook(TechnicsHandoverHook, classLoader, scope)
            }

            else -> throw IllegalArgumentException("unsupported PanaPods scope: $scope")
        }
    }

    /**
     * 注入作用域上下文后调用 [HookContext.onHook]。
     *
     * `applicationReadyHooks` 先登记再安装：[installApplicationReadyGate] 的回调可能在
     * 任意一个 Hook 装完之后立刻触发（Application 就绪），登记必须先于 `onHook()`。
     */
    private fun loadHook(hook: HookContext, classLoader: ClassLoader, scope: String) {
        PanaLog.i(
            TAG,
            "loadHook type=${hook.javaClass.name} scope=$scope process=$processName " +
                "loader=${classLoader.javaClass.name}",
        )
        hook.appClassLoader = classLoader
        hook.packageName = scope
        synchronized(applicationReadyHooks) { applicationReadyHooks += hook }
        hook.onHook()
        PanaLog.d(TAG, "loadHook complete type=${hook.javaClass.name} scope=$scope")
    }

    // ============ Application 就绪回调 ============

    /**
     * `onPackageReady()` 与 `ActivityThread.currentApplication()` 之间存在冷启动时序差：
     * SonyPods 在 SoundConnectHandoverHook 里也记录了「package-load 回调早于
     * currentApplication()」。这里统一 hook `Instrumentation.callApplicationOnCreate`，
     * 把 Application 一次性派发给所有登记过的 Hook 单元，
     * 替代旧版「install 时拿不到 Context → 静默失败 → 各自轮询重试」的散装逻辑。
     */
    private fun installApplicationReadyGate(classLoader: ClassLoader) {
        synchronized(this) {
            if (applicationGateInstalled || applicationDispatched) return
            val instrumentation = runCatching {
                Class.forName("android.app.Instrumentation", false, classLoader)
            }.getOrNull()
            val method = instrumentation?.let {
                runCatching { it.getDeclaredMethod("callApplicationOnCreate", Application::class.java) }.getOrNull()
            }
            if (method == null) {
                // 兜底：如果 Application 已经存在（时序与预期相反），直接派发。
                val existing = currentApplication()
                if (existing != null) {
                    dispatchApplicationReady(existing)
                    return
                }
                PanaLog.w(TAG, "Application ready gate unavailable; context-bound hooks rely on their own retries")
                return
            }
            applicationGateInstalled = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = param.args.firstOrNull() as? Application ?: return
                    dispatchApplicationReady(application)
                }
            })
            PanaLog.d(TAG, "Application ready gate installed process=$processName")
        }
    }

    private fun dispatchApplicationReady(application: Application) {
        if (applicationDispatched) return
        applicationDispatched = true

        // ① Application 就绪后才可能真正读到日志开关（onPackageLoaded 那次必然失败）。
        readLogSwitchOnce()
        // ② 开关读到了 → 补装依赖它的诊断 Hook（设置作用域已装时才有意义）。
        settingsClassLoader?.let { loadSettingsDiagnosticsIfEnabled(it, PKG_SETTINGS) }
        // ③ 再通知各 Hook 单元：Context 相关的注册可以安全进行。
        val pending = synchronized(applicationReadyHooks) {
            applicationReadyHooks.toList().also { applicationReadyHooks.clear() }
        }
        PanaLog.d(TAG, "Application ready: notifying ${pending.size} hook(s)")
        pending.forEach { hook ->
            runCatching { hook.onApplicationReady(application) }
                .onFailure { PanaLog.e(TAG, "onApplicationReady failed type=${hook.javaClass.name}", it) }
        }
    }

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()

    // ============ 设置页作用域 ============

    private fun installSettingsHooks(classLoader: ClassLoader, scope: String = PKG_SETTINGS) {
        if (settingsHooksInstalled) {
            PanaLog.i(TAG, "Settings hooks already installed, skipping")
            return
        }
        settingsHooksInstalled = true
        settingsClassLoader = classLoader
        loadHook(SettingsHeadsetHook, classLoader, scope)
        loadHook(DeviceProfilesTwsHook, classLoader, scope)
        loadHook(MiuiBluetoothSettingsTwsHook, classLoader, scope)
        // Diagnostics hooks are reflection/stack-trace heavy; install them only
        // when protocol logging is enabled so release builds stay lean.
        // 此刻 PanaLog.enabled 几乎必然还是 false——initLogSwitch 的首读在 onPackageLoaded，
        // 当时 currentApplication()==null 直接返回。Application 就绪后
        // [dispatchApplicationReady] 会再补一次判断，别在这里就下「永远不装」的结论。
        loadSettingsDiagnosticsIfEnabled(classLoader, scope)
    }

    /** 诊断 Hook 只装一次；开关未打开时静默跳过，等 [dispatchApplicationReady] 复查。 */
    private fun loadSettingsDiagnosticsIfEnabled(classLoader: ClassLoader, scope: String) {
        if (settingsDiagnosticsInstalled || !PanaLog.enabled) return
        settingsDiagnosticsInstalled = true
        PanaLog.i(TAG, "[$scope] installing settings diagnostics hooks (debug log on)")
        loadHook(SettingsDiagnosticsHook, classLoader, scope)
    }

    /** contentcatcher 路径：等主线程起来再用 createPackageContext 拿 Settings ClassLoader。 */
    private fun deferSettingsHooks(defaultClassLoader: ClassLoader, scope: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val app = currentApplication()
                val classLoader = getSettingsClassLoader(app, defaultClassLoader)
                PanaLog.i(TAG, "[$scope] installing settings hooks (deferred) classLoader=$classLoader")
                installSettingsHooks(classLoader, PKG_SETTINGS)
            } catch (t: Throwable) {
                PanaLog.e(TAG, "Failed to install deferred settings hooks", t)
            }
        }
    }

    private fun getSettingsClassLoader(app: Context?, fallback: ClassLoader): ClassLoader {
        if (app == null) {
            PanaLog.w(TAG, "Application not available yet, using default classloader")
            return fallback
        }
        return try {
            val ctx = app.createPackageContext(PKG_SETTINGS, Context.CONTEXT_INCLUDE_CODE)
            PanaLog.i(TAG, "Got Settings classLoader via createPackageContext: ${ctx.classLoader}")
            ctx.classLoader
        } catch (e: Throwable) {
            PanaLog.w(TAG, "createPackageContext failed, using app classloader: ${e.message}")
            app.classLoader
        }
    }

    // ============ 日志开关 ============

    private fun initLogSwitch() {
        if (logSwitchInitialized) return
        logSwitchInitialized = true
        // v110：onPackageLoaded 阶段 currentApplication() 常为 null（Application 尚未创建），
        // 旧逻辑直接 return，导致 Hook 进程永远读不到日志开关（PanaLog 恒为关）。
        // 改为延迟重试：等 Application 就绪后再查 Provider，最多重试 5 次。
        // （Application 就绪那一刻 [dispatchApplicationReady] 还会同步读一次。）
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val retry = object : Runnable {
            override fun run() {
                if (PanaLog.enabled || attempt >= 5) return
                attempt++
                readLogSwitchOnce()
                handler.postDelayed(this, 2000L * attempt)
            }
        }
        readLogSwitchOnce()
        handler.postDelayed(retry, 2000L)
    }

    /** 从本 App 的 Provider 读一次调试日志开关；Application 未就绪或 Provider 不可用时静默返回。 */
    private fun readLogSwitchOnce() {
        try {
            val app = currentApplication() ?: return
            val bundle = app.contentResolver.call(
                android.net.Uri.parse("content://${com.panapods.bridge.PanaPodsProvider.AUTHORITY}"),
                "get_log_enabled", null, null
            )
            if (bundle != null) {
                PanaLog.enabled = bundle.getBoolean("enabled", false)
            }
        } catch (_: Throwable) {
            // Provider unavailable, retry later
        }
    }
}
