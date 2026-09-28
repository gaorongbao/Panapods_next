package com.panapods.hook

import android.app.Application
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 单个作用域内 Hook 单元的基类（对标 SonyPods 的 dev.sonypods.hook.HookContext）。
 *
 * [HookEntry] 按作用域分发，把「这个进程用哪个 ClassLoader、装哪些 Hook」集中在一个
 * `loadScope(...)` 里；每个 Hook 单元只声明 [onHook]，不再各自接收 classLoader 参数、
 * 各自判断包名，避免出现「某个进程漏装一半 Hook」的状态。
 *
 * 生命周期：
 * ```
 * HookEntry.onPackageReady → appClassLoader/packageName 赋值 → onHook()
 *                            → Application 就绪 → onApplicationReady(application)
 * ```
 * 需要 Application 实例的 Hook 优先覆写 [onApplicationReady]（由 HookEntry 统一从
 * `Instrumentation.callApplicationOnCreate` 派发一次）；需要「Application.onCreate 之前」
 * 这种更早时机的，再自行 hook `callApplicationOnCreate`（见 [TechnicsHandoverHook]）。
 */
abstract class HookContext {

    companion object {
        /** HookContext 自身日志用的 tag；子类的 `TAG` 不受影响。 */
        const val HOOK_TAG = "PanaPods/Hook"
    }

    /** 作用域进程的应用 ClassLoader（由 HookEntry 在 onPackageReady 注入）。 */
    lateinit var appClassLoader: ClassLoader

    /** 作用域包名。 */
    var packageName: String = ""

    /** 安装入口；子类覆写并调用 hook* 家族方法（由 HookEntry 调用，故为 public）。 */
    open fun onHook() {}

    /**
     * Application 就绪回调（主线程，`Instrumentation.callApplicationOnCreate` 执行之前）。
     *
     * `onPackageReady` 阶段 `ActivityThread.currentApplication()` 在冷启动路径上可能仍是
     * null（SonyPods 同款时序），所以「拿 Context 注册广播 / 拉 Provider」这类逻辑应放这里，
     * 而不是在 [onHook] 里静默失败再靠轮询重试。
     */
    open fun onApplicationReady(application: Application) {}

    // ============ 反射定位 ============

    fun findClassOrNull(className: String): Class<*>? =
        runCatching { XposedHelpers.findClass(className, appClassLoader) }
            .onFailure { logD("findClass failed $className: ${it.message}") }
            .getOrNull()

    fun findClass(className: String): Class<*> =
        requireNotNull(findClassOrNull(className)) { "class not found in $packageName: $className" }

    fun findMethodOrNull(clazz: Class<*>, methodName: String, vararg params: Class<*>): Method? =
        runCatching { clazz.getDeclaredMethod(methodName, *params) }
            .onFailure {
                if (it is NoSuchMethodException) {
                    logD("findMethod failed ${clazz.name}.$methodName(${params.joinToString { p -> p.simpleName }})")
                } else {
                    PanaLog.w(HOOK_TAG, "findMethod ${clazz.name}.$methodName failed", it)
                }
            }
            .getOrNull()

    fun findMethodOrNull(
        className: String,
        methodName: String,
        vararg params: Class<*>,
    ): Method? {
        val clazz = findClassOrNull(className) ?: return null
        return findMethodOrNull(clazz, methodName, *params)
    }

    fun findMethod(clazz: Class<*>, methodName: String, vararg params: Class<*>): Method =
        requireNotNull(findMethodOrNull(clazz, methodName, *params)) {
            "method not found: ${clazz.name}.$methodName"
        }

    fun findMethod(className: String, methodName: String, vararg params: Class<*>): Method =
        requireNotNull(findMethodOrNull(className, methodName, *params)) {
            "method not found: $className.$methodName"
        }

    // ============ Hook 安装 ============

    /**
     * 前置 Hook。给 [HookScope.result] 赋值会同时置 returnEarly ——
     * 与传统 Xposed `setResult()` 语义一致：跳过原方法并返回该值。
     */
    fun hookBefore(method: Method, body: HookScope.() -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                HookScope(param).safeRun(body)
            }
        })
    }

    /** 后置 Hook：可覆盖原方法返回值，也可读取参数与实例。 */
    fun hookAfter(method: Method, body: HookScope.() -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                HookScope(param).safeRun(body)
            }
        })
    }

    /**
     * Hook 某个类（含父类）上**所有同名重载**的前置回调。
     * 用于框架类这种重载多、签名随版本变化的方法（如 BluetoothDevice.connectGatt）。
     */
    fun hookAllMethodsBefore(clazz: Class<*>, methodName: String, body: HookScope.() -> Unit) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Method, Boolean>())
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == methodName && seen.add(method)) hookBefore(method, body)
            }
            current = current.superclass
        }
    }

    /** [hookAllMethodsBefore] 的后置版本。 */
    fun hookAllMethodsAfter(clazz: Class<*>, methodName: String, body: HookScope.() -> Unit) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Method, Boolean>())
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == methodName && seen.add(method)) hookAfter(method, body)
            }
            current = current.superclass
        }
    }

    /** 按类名 + 方法名装前置 Hook；类或方法缺失时仅记日志，不影响其余 Hook。 */
    fun hookBefore(className: String, methodName: String, body: HookScope.() -> Unit) {
        val method = findMethodOrNull(className, methodName) ?: return
        hookBefore(method, body)
    }

    /** 按类名 + 方法名 + 参数签名装前置 Hook（用于区分重载）。 */
    fun hookBeforeSignature(
        className: String,
        methodName: String,
        vararg params: Class<*>,
        body: HookScope.() -> Unit,
    ) {
        val method = findMethodOrNull(className, methodName, *params) ?: return
        hookBefore(method, body)
    }

    /** Hook 体内的任何异常都不允许击穿被 Hook 的系统进程。 */
    private fun HookScope.safeRun(body: HookScope.() -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            PanaLog.e(HOOK_TAG, "hook body threw in ${param.method}", t)
        }
    }

    // ============ 日志 ============

    fun logI(message: String) = PanaLog.i(HOOK_TAG, "[$packageName] $message")
    fun logD(message: String) = PanaLog.d(HOOK_TAG, "[$packageName] $message")
    fun logW(message: String) = PanaLog.w(HOOK_TAG, "[$packageName] $message")
    fun logE(message: String, tr: Throwable? = null) {
        if (tr == null) PanaLog.e(HOOK_TAG, "[$packageName] $message")
        else PanaLog.e(HOOK_TAG, "[$packageName] $message", tr)
    }
}

/**
 * 单次 Hook 回调的作用域对象。
 *
 * 与传统 Xposed `MethodHookParam` 的差异：
 * - [result] 赋值 = 短路原方法（before 钩子返回该值 / after 钩子覆盖返回值）；
 * - [param] 仍可访问，供需要 `throwable` 等低层字段的旧逻辑使用。
 */
class HookScope internal constructor(val param: XC_MethodHook.MethodHookParam) {
    val method: Member? get() = param.method
    val instance: Any? get() = param.thisObject
    val args: Array<Any?> get() = param.args ?: emptyArray()

    var result: Any?
        get() = param.result
        set(value) {
            param.result = value
            // 短路原方法：before 钩子必须同时置 returnEarly，否则 proceed() 的返回值
            // 会覆盖这里写入的 result（旧版踩过的坑，见 SettingsHooks 的注释）。
            param.returnEarly = true
        }
}
