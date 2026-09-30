package com.panapods.hook

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.panapods.MainActivity
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.bridge.PanaPodsProvider
import com.panapods.headphones.HeadphoneText
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.xzakota.hyper.notification.focus.FocusNotification

/**
 * 焦点通知卡片的「代发者」—— 作用域 `com.xiaomi.bluetooth`（**主进程**）。
 *
 * ## 为什么必须代发（v2.0.13 定案，取代原先「Hook SystemUI 放行授权」的方案）
 *
 * 系统对每条带 `miui.focus.param` 的通知做**两段式**鉴权：
 *
 * 1. **纯本地**：`miui.systemui.notification.focus.SignatureChecker.checkSignatures(pm, pkg, sysUISignatures)`
 *    —— 把**目标包签名**与 **SystemUI 自己的签名**比 SHA256（`getSysUISignature()`）。
 *    **同签名 → 直接放行，根本不联网**。
 * 2. **仅在第 1 段不匹配时**才走 `AuthManager.requestAuth(pkg, scope=20032)` 联网问小米服务端。
 *
 * `com.panapods.next` 是自签名应用（CN=AZ100），第 1 段必然不匹配；而第 2 段服务端按
 * 「包名 + 签名」鉴权，对未登记的应用返回 `scopeInfos=[]` → `-300 scope mismatch`
 * （`apkSigns=null`），且**成功结果永远进不了缓存**，于是每次通知都重新联网判定：
 * 网络通 → -300 → 卡片被丢；网络不通 → -400 → 「失败开放」→ 卡片反而显示。
 * 这就是「插卡就没、不插卡就有」的真身，与 SIM 无关。
 *
 * → **自签名应用无法让焦点卡片显示出来**，这个目标本身不成立。唯一正解是**换发布者**：
 * 在一个**平台签名**的系统应用进程里代发通知，让 `NotificationRecord` 的归属包变成
 * 那个系统包，第 1 段签名比对直接通过。
 *
 * `com.xiaomi.bluetooth` 的 uid 是 **1002**（`android.uid.bluetooth`）。该 uid 靠
 * `sharedUserId` 分配，而跨包共享 sharedUserId 要求签名一致 → 拿到 1002 必然是平台签名。
 * 真机取证（`dumpsys notification`）也印证了参考实现 SonyPods 正是这么做的：
 * 它全部 4 个通知渠道（`BTHeadset*` / `sonypods_focus_island`）都登记在
 * `com.xiaomi.bluetooth (1002)` 名下，它自己的 App 包名下**一条渠道都没有**；
 * 且它的 `scope.list` 里**没有** `com.android.systemui` —— 从不 patch 鉴权逻辑。
 *
 * ## 职责边界
 *
 * 只做一件事：把 App 进程（`PanaBleService`）算好的耳机状态，用本进程（= 系统蓝牙包）的身份
 * 渲染成焦点通知卡片。状态数据经 [PanaBridge.registerStateReceiver] 的隐式广播输入，
 * **与蓝牙/设置/milink 各 Hook 共用同一条通道**，不新增跨进程接口。
 *
 * 按钮点击仍走 App 的 `PanaCommandReceiver`（token 校验），与卡片由谁发布无关。
 */
@Suppress("TooManyFunctions")
object PanaCardPosterHook : HookContext() {

    private const val TAG = "PanaPods/CardPoster"

    /** 代发进程的目标包。必须在 LSPosed `scope.list` 里。 */
    private const val PROXY_PACKAGE = "com.xiaomi.bluetooth"

    /** 本模块包名（跨进程读资源用）。 */
    private const val MODULE_PACKAGE = "com.panapods.next"

    private const val UI_PROCESS_SUFFIX = ":ui"

    /**
     * 卡片渠道 / tag / id。
     *
     * **刻意与官方耳机通知（`BTHeadset<MAC>` + id 10003）区分开**：本模块已 patch 了
     * `checkSupport`，系统认为「这是自家支持 ANC 的耳机」，可能自己也会发那条通知；
     * 若共用 tag/id 会互相覆盖。焦点模板由 `param_v2` 的 `type=FocusTemplate.V3` 指定，
     * 与 tag 无关（参考实现里两条路径用了完全不同的 tag，都能正常出岛）。
     */
    private const val CHANNEL_ID = "panapods_focus_card"
    private const val NOTIFICATION_TAG = "PanaPodsCard"
    private const val NOTIFICATION_ID = 10100

    private const val FOCUS_PIC_KEY = "key_headset"
    private const val FOCUS_ACTION_KEY = "key_anc_cycle"

    private const val CONTENT_INTENT_REQUEST_CODE = 3001
    private const val CYCLE_ACTION_REQUEST_CODE = 3002

    /**
     * remove → add 之间的间隔。
     *
     * HyperOS 会把岛条目从 `DynamicIsland` 可见列表移除但**保留 NotificationRecord**，
     * 此后原地 `notify()` 只更新那条看不见的记录，卡片再也顶不出来；只有真正的
     * remove → add 才算一次「首次出现」。
     *
     * ⚠️ v2.0.16：正因为重发 = 一次「首次出现」，**重发本身会触发自动展开**
     * （`islandFirstFloat` 默认 true）。所以「要不要重发」与「要不要大卡片」是两件事，
     * 必须分别用 [postCard] 的 `repost` 与 `firstFloat` 两个开关表达。
     * 留一点延迟等系统把旧条目摘干净。
     */
    private const val REPOST_DELAY_MS = 150L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 代发进程自己的 Context（`com.xiaomi.bluetooth`）。
     *
     * **建通知、建 PendingIntent 必须用它。** `PendingIntent.getBroadcast(context, …)` 会以
     * `context.getPackageName()` 作为「发送者身份」向 AMS 注册 IntentSender；若传
     * [moduleContext]，包名是 `com.panapods.next`，等于让 uid 1002 冒充本模块，AMS 直接拒：
     * ```
     * Permission Denial: getIntentSender() from pid=…, uid=1002
     *     is not allowed to send as package com.panapods.next
     * ```
     * （真机踩过，见 `cycleAncAction` 的调用点。）
     */
    @Volatile
    private var proxyContext: Context? = null

    /**
     * 本模块的 Context（`createPackageContext(MODULE_PACKAGE)`）。
     *
     * **只用来读资源与字符串**（`R.drawable.pana_headset` / `R.string.*`）——
     * 跨进程下这些只有本模块的 Resources 才解析得到。**绝不能拿去建 PendingIntent**，
     * 原因见 [proxyContext]。
     */
    @Volatile
    private var moduleContext: Context? = null

    private var stateReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null

    /** 连接基线：只有 false → true 的跳变才算新连接，需要 remove → add 触发展开。 */
    @Volatile
    private var connected = false

    /**
     * v2.0.17：播种（后台线程）与基线判定（主线程）的互斥，见 [seedFromProvider] / [onState]。
     *
     * 播种挪到后台后，「先播种再注册接收器」的顺序保证失效了，改由这把锁 + 两个标志守住：
     * 谁先到谁定基线，语义与原先完全一致。
     */
    private val seedLock = Any()

    /** 后台播种是否已定论（成功、失败或广播先到，都算「已定论」）。 */
    private var baselineSeeded = false

    /** 播种完成前已收到过状态广播 → 以广播为基线，Provider 读数更旧、直接丢弃。 */
    private var broadcastArrived = false

    /**
     * v2.0.17：解码好的 logo 缓存。
     *
     * `pana_headset.png` 是 drawable-nodpi 的 176×230 实图（源文件 439KB），原实现
     * **每次状态推送（约 2s 一次）都在 `com.xiaomi.bluetooth` 主线程重新解一遍 PNG 并
     * 分配一张 ~160KB 的 ARGB_8888** —— 白白制造 GC 压力。图不会变，解一次即可；
     * 解锁后 [moduleContext] 重建时清掉重解。
     */
    @Volatile
    private var logoBitmapCache: Bitmap? = null

    private var repostRunnable: Runnable? = null

    /**
     * v2.0.15：「打开主界面」的 PendingIntent，**必须由 App 进程创建**后经 Provider 交过来。
     *
     * 代发进程（uid=1002）自己建的 PI 过不了 Android 的后台启动限制：MIUI 在「下滑展开态
     * 卡片」时会用这个 PI 启动 MainActivity，判定 `callingUid=1002`、`balAllowedByPiCreator
     * = BSP.NONE` → `(BAL_BLOCK)`，App 起不来（真机日志实证，`Displayed` 全程不出现）。
     * PI 创建者换成 App（带前台服务）就回到 v2.0.12 那套可用的作者身份。
     */
    @Volatile
    private var appLaunchIntent: PendingIntent? = null

    private val isUiProcess: Boolean
        get() = runCatching { Application.getProcessName() }
            .getOrNull()
            ?.endsWith(UI_PROCESS_SUFFIX) == true

    // ============ 生命周期 ============

    override fun onHook() {
        // 主进程负责发布；:ui 进程由官方快连 Activity 按需拉起，发布会让卡片双发。
        if (isUiProcess) {
            logD("skip: ui process")
            return
        }
        logI("installing focus card poster in $PROXY_PACKAGE")
    }

    override fun onApplicationReady(application: Application) {
        if (isUiProcess) return
        val appContext = application.applicationContext ?: application
        proxyContext = appContext
        // 先拿模块 Context：跨进程读资源失败时不要直接放弃，注册 unlock 接收器补一次。
        moduleContext = runCatching {
            appContext.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { logW("module context unavailable: ${it.message}") }.getOrNull()

        createChannel(appContext)
        // v2.0.17：播种 + PI 索取挪到后台线程。
        //
        // 这两个都是**跨进程** ContentProvider 调用，App 未运行时会按需冷启动
        // com.panapods.next 并一直等到它把 Provider 发布出来（超时上限约 10s）；而本回调
        // 跑在 com.xiaomi.bluetooth 的主线程上（Instrumentation.callApplicationOnCreate），
        // 同步等就等于开机会把蓝牙进程卡住——HyperOSHeadsetHook 在 v2.0.6 修过同款问题
        // （refreshStateFromProviderAsync 注释原话：「会把 Bluetooth 栈主线程卡住」），
        // 这里是同一类调用，之前漏掉了。
        //
        // 顺序因此从「先播种再注册接收器」改成「先注册（纯本地、不跨进程）再播种」：
        // 原先那个顺序是为了防进程中途重启时对着已连接的耳机误判成「新连接」而多发一次
        // remove → add，现在由 [onState] 的 !baselineSeeded 分支以同一把锁守住。
        registerStateReceiver(appContext)
        registerUnlockReceiver(appContext)
        Async.run("card-seed") {
            seedFromProvider(appContext)
            // 取 App 创建的「打开界面」PI。拿不到时卡片会挂本进程（uid=1002）的兜底 PI，
            // 那条过不了 BAL、下滑打不开 App；但下一次状态推送（约 2s 后）重建卡片时
            // 会自动换成刚取到的真 PI，所以不必在这里同步等。
            fetchAppLaunchIntent(appContext)
        }
    }

    /**
     * 通过 Provider 向 App 进程索取它创建的「打开主界面」PendingIntent。
     *
     * Provider 调用会按需拉起 App 进程（ContentProvider 组件的标准行为），所以即使 App
     * 当前没在跑也能拿到。失败时保留 null，[buildCard] 会退化到本地兜底 PI。
     */
    private fun fetchAppLaunchIntent(context: Context) {
        runCatching {
            val bundle = context.contentResolver.call(
                PanaPodsProvider.CONTENT_URI,
                PanaPodsProvider.METHOD_GET_LAUNCH_INTENT,
                null,
                null,
            )
            val pi = bundle?.getParcelable(
                PanaPodsProvider.EXTRA_LAUNCH_INTENT,
                PendingIntent::class.java,
            )
            appLaunchIntent = pi
            if (pi != null) {
                logI("launch PendingIntent acquired from app process")
            } else {
                logW("launch PendingIntent unavailable; card will use local fallback")
            }
        }.onFailure { logW("fetchAppLaunchIntent failed: ${it.message}") }
    }

    // ============ 通道与资源 ============

    private fun createChannel(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "PanaPods",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "PanaPods focus card"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            logI("channel ready: $CHANNEL_ID in ${context.packageName}")
        }.onFailure { logW("createChannel failed: ${it.message}") }
    }

    /**
     * 把模块的 `R.drawable.pana_headset` 夹成 Bitmap。
     *
     * **必须用 `Icon.createWithBitmap`，不能用 `Icon.createWithResource`**：
     * 卡片由 SystemUI 渲染，而这里是在 `com.xiaomi.bluetooth` 进程；`createWithResource`
     * 只登记一个「包名 + 资源 id」引用，SystemUI 侧解析不到本模块的资源，图片会空白。
     * 参考实现同款做法（注释原文：「使用 createWithBitmap 直接嵌入图片数据，
     * SystemUI 无需再访问模块资源」）。
     */
    private fun loadLogoBitmap(): Bitmap? = runCatching {
        val context = moduleContext ?: return null
        val drawable = context.getDrawable(R.drawable.pana_headset) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_LOGO_SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_LOGO_SIZE
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
        }
    }.onFailure { logW("logo bitmap failed: ${it.message}") }.getOrNull()

    private const val DEFAULT_LOGO_SIZE = 144

    /**
     * v2.0.17：取缓存的 logo；没有才解码一次并记住。
     *
     * 状态推送约 2s 一条，原实现每条都走一遍 [loadLogoBitmap]（解 PNG + 新建 ~160KB 位图），
     * 全部落在 `com.xiaomi.bluetooth` 主线程上。图是静态的，解一次用到底。
     */
    private fun logoBitmap(): Bitmap? {
        logoBitmapCache?.let { return it }
        return loadLogoBitmap()?.also { logoBitmapCache = it }
    }

    // ============ 状态输入 ============

    private fun seedFromProvider(context: Context) {
        runCatching {
            context.contentResolver
                .query(PanaPodsProvider.CONTENT_URI, null, null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val left = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_LEFT))
                    val right = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_RIGHT))
                    val cradle = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CRADLE))
                    val anc = cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ANC))
                    val connectedFlag =
                        cursor.getInt(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_CONNECTED)) != 0
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_NAME))
                    val addr = cursor.getString(cursor.getColumnIndexOrThrow(PanaPodsProvider.COLUMN_ADDRESS))
                    // v2.0.17：与 [onState] 同一把锁 —— 播种在后台线程、基线判定在主线程，
                    // 谁先到谁定基线。广播先到就以广播为准（它的时间戳更新），Provider 读数
                    // 直接丢弃，避免用一份更旧的状态把广播定下的基线覆盖回去。
                    synchronized(seedLock) {
                        if (broadcastArrived) {
                            logD("provider seed skipped: broadcast arrived first (connected=$connected)")
                        } else {
                            PanaBridge.publishStateToCache(left, right, cradle, anc, name, addr, connectedFlag)
                            connected = connectedFlag
                            logD("provider seed: connected=$connectedFlag addr=$addr")
                        }
                    }
                }
        }.onFailure { logW("provider seed failed: ${it.message}") }
        // 无论查询成败都要定论：失败时保持初值 connected=false（等下一条广播修正）。
        // 这一行漏了的话，[onState] 会把之后每条广播都当成「基线」而不发卡 —— 卡片永远出不来。
        synchronized(seedLock) { baselineSeeded = true }
    }

    private fun registerStateReceiver(context: Context) {
        if (stateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    // 「强制重建」信号：只表达重发意图、不带状态，**不能**走
                    // updateCacheFromIntent —— 缺字段的 Intent 会把缓存刷成默认值
                    // （尤其 isConnected 会被读成 false）。同样校验 token。
                    PanaBridge.ACTION_REPOST_CARD -> {
                        if (!PanaBridge.isAuthorizedCommand(intent)) return
                        // v2.0.16：这条是「我要看大卡片」的显式请求（设置页按钮 / `repost_card`
                        // 诊断命令 / 用户点卡片后的主动展开），所以给 firstFloat=true。
                        if (PanaBridge.isConnected()) postCard(repost = true, firstFloat = true)
                    }

                    else -> {
                        // 同一个 token 校验：第三方 App 伪造的 STATE_UPDATED 会被拒。
                        if (!PanaBridge.updateCacheFromIntent(intent)) return
                        onState(PanaBridge.isConnected())
                    }
                }
            }
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(PanaBridge.ACTION_STATE_UPDATED).apply {
                    addAction(PanaBridge.ACTION_REPOST_CARD)
                },
                Context.RECEIVER_EXPORTED,
            )
            stateReceiver = receiver
            logI("state receiver registered in ${context.packageName}")
        }.onFailure { logW("state receiver registration failed: ${it.message}") }
    }

    /**
     * 用户解锁 / 开机完成后补渲染一次。
     *
     * 解锁前本模块的 ContentProvider 与资源不可达（"user not unlocked"），
     * 此时 [loadLogoBitmap] 会拿到 null；而状态只在变化时推送，之后不会自己重来一遍，
     * 卡片就会一直缺图。参考实现用同款做法（`ACTION_USER_UNLOCKED` 等做一次回复）。
     *
     * ⚠️ v2.0.16：这里走 `repost = true`（**必须**，因为解锁前卡片压根没发出去，
     * 原地 notify 顶不出来），但 `firstFloat = false` —— 重发会被系统算成一次
     * 「首次出现」，若不同时压住 `islandFirstFloat`，就变成「每次解锁自动弹大卡片」。
     * 现在解锁只把卡片补出来（小条），要放大得用户自己去点。
     */
    private fun registerUnlockReceiver(context: Context) {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                logD("user unlocked (${intent?.action}); re-rendering card")
                // 解锁后资源可达性/Context 都可能变化，logo 缓存跟着作废重解一次。
                logoBitmapCache = null
                moduleContext = runCatching {
                    context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                }.getOrNull()
                // 解锁后 App 进程通常已就绪，顺手把「打开界面」的 PI 再取一次（首次可能失败）。
                if (appLaunchIntent == null) fetchAppLaunchIntent(context)
                if (PanaBridge.isConnected()) postCard(repost = true, firstFloat = false)
            }
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                },
                Context.RECEIVER_EXPORTED,
            )
            unlockReceiver = receiver
        }.onFailure { logW("unlock receiver registration failed: ${it.message}") }
    }

    // ============ 卡片发布 ============

    private fun onState(isConnected: Boolean) {
        synchronized(seedLock) {
            // v2.0.17：播种在后台进行（见 onApplicationReady），首条广播可能先于它到达。
            // 这时把广播本身当基线、**不判跳变**：它和 Provider 读的是同一份状态，否则
            // 进程中途重启对着已连着的耳机会多发一次 remove → add，卡片无谓地重新展开
            // ——正是原先「先播种再注册接收器」要防的那件事，改异步后由这里守住。
            if (!baselineSeeded) {
                connected = isConnected
                logD("baseline from broadcast (seed pending): connected=$isConnected")
                return
            }
            val wasConnected = connected
            connected = isConnected
            when {
                !isConnected -> cancelCard()
                // 断开 → 连接跳变：只有这条路径需要 remove → add 才能拿到展开态。
                // v2.0.16：也是**唯一**主动要出大卡片的自动路径（用户要求「只有第一次连接
                // 才是大卡片」）。
                !wasConnected -> postCard(repost = true, firstFloat = true)
                // 原地更新不会把已展开的卡片收起（实测），所以普通电量刷新走轻量路径。
                // firstFloat=false：若这次 notify 恰好要新建记录（例如蓝牙进程重启后卡片不在
                // 了），也按小条出场 —— 不是「首次连接」，不该自动展开。
                else -> postCard(repost = false, firstFloat = false)
            }
        }
    }

    /**
     * 发布/更新卡片。
     *
     * @param repost `true` = 真正的 remove → add 重发（岛条目被移出可见列表后唯一能重新
     *               顶出来的方式，见类注释）；`false` = 原地 `notify()` 只改文案。
     * @param firstFloat v2.0.16：是否允许本次「首次出现」自动展开成大卡片，见 [buildCard]。
     *                   ⚠️ 与 [repost] **正交**：重发不等于要大卡片。解锁补渲染就是
     *                   「要重发（因为解锁前资源不可达、卡片根本没发出去）但不要大卡片」。
     */
    private fun postCard(repost: Boolean, firstFloat: Boolean) {
        // 注意：这里是**代发进程自己的** Context，不是 moduleContext（见 proxyContext 注释）。
        val context = proxyContext ?: return
        mainHandler.post {
            runCatching {
                val bitmap = logoBitmap()
                if (bitmap == null) {
                    // 资源暂不可达（多为解锁前）。不发布半成品，等下一次状态变化或解锁回调重试。
                    logW("logo bitmap unavailable; skip posting card")
                    return@post
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = buildCard(context, bitmap, firstFloat)
                if (repost) {
                    // 真正的 remove → add：先摘掉旧条目，延迟一点再发，让它算一次「首次出现」。
                    runCatching { manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID) }
                    repostRunnable?.let(mainHandler::removeCallbacks)
                    val task = Runnable {
                        repostRunnable = null
                        runCatching { manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification) }
                            .onFailure { logE("card repost failed", it) }
                        // firstFloat 必须打进日志：真机验证时全靠它区分「该出大卡片还是小条」。
                        logI("card re-posted (remove -> add), islandFirstFloat=$firstFloat")
                    }
                    repostRunnable = task
                    mainHandler.postDelayed(task, REPOST_DELAY_MS)
                } else {
                    manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
                    logD("card updated in place, islandFirstFloat=$firstFloat")
                }
            }.onFailure { logE("postCard failed", it) }
        }
    }

    private fun cancelCard() {
        mainHandler.post {
            repostRunnable?.let(mainHandler::removeCallbacks)
            repostRunnable = null
            runCatching {
                val context = proxyContext ?: return@post
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
                logI("card cancelled (disconnected)")
            }.onFailure { logW("cancelCard failed: ${it.message}") }
        }
    }

    /**
     * @param context **代发进程自己的** Context（建通知与 PendingIntent 用），
     *                资源字符串一律走 [moduleContext]，见 [proxyContext] 注释。
     * @param firstFloat v2.0.16：本次发布是否要求「首次出现就展开成大卡片」。
     *                   直接落到 `islandFirstFloat`（`FocusTemplateV3` 上是
     *                   `java.lang.Boolean`，`IExtraV3Param` 也声明了该属性）。
     *                   - `true`：首次连接跳变、以及点击/诊断强制的重发 → 大卡片。
     *                   - `false`：解锁补渲染、2s 状态原地更新 → 只出小条（指示灯条），
     *                     用户点一下由 MIUI 自己的岛手势展开成大卡片。
     */
    private fun buildCard(context: Context, logoBitmap: Bitmap, firstFloat: Boolean): Notification {
        val text = statusText()
        val logo = Icon.createWithBitmap(logoBitmap)
        val extras = FocusNotification.buildV3 {
            // 展开/收起策略（三件事分开控制，缺一不可；v2.0.16 补齐第 1 条）：
            //   「何时展开」→ islandFirstFloat：通知**首次出现**时是否自动展开成大卡片。
            //     ⚠️ v2.0.13~v2.0.15 一直没设这个值（= 系统默认 true），而**任何一次
            //     remove → add 重发都算一次「首次出现」** —— 解锁补渲染正是走重发，
            //     于是每次解锁都自动弹出大卡片（用户实测反馈，v2.0.16 修）。
            //     现在按发布原因显式传入：只有「首次连接」和「点击/诊断强制重发」给 true。
            //     语义取自 v211/v212 的真机记录：islandFirstFloat=false 时卡片照常出现在
            //     岛上、只是**不**自动展开（当时是因为需求正好相反才被撤掉的）。
            //   「何时收起」→ enableFloat = **false**：通知**更新**时不再请求展开。
            //     状态推送每 2s 一条，若 enableFloat=true 就等于每 2s 把卡片重新顶回展开态 ——
            //     用户看到的「一直最大化、收不起来」正是这个原因（v2.0.13 之前的实测现象）。
            //     现在更新只原地改文案，交由系统自己的岛生命周期把卡片收成小条。
            updatable = true
            enableFloat = false
            islandFirstFloat = firstFloat
            ticker = "PanaPods"
            val picture = createPicture(FOCUS_PIC_KEY, logo)
            iconTextInfo {
                animIconInfo {
                    type = 0
                    src = picture
                }
                title = "PanaPods"
                content = text
            }
            textButton {
                addActionInfo {
                    action = createAction(FOCUS_ACTION_KEY, cycleAncAction(context))
                    actionTitle = moduleString(R.string.cycle_anc, "切换降噪")
                }
            }
        }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PanaPods")
            .setContentText(text)
            .setTicker("PanaPods")
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(appLaunchIntent ?: launchAppPendingIntent(context))
            .addExtras(extras)
            .build()
    }

    /**
     * 读本模块的字符串资源。解锁前 [moduleContext] 可能为 null（模块资源不可达），
     * 此时退化成内置默认值——宁可文案是英文/默认，也不要因此不发布卡片。
     */
    private fun moduleString(resId: Int, fallback: String): String =
        runCatching { moduleContext?.getString(resId) }.getOrNull() ?: fallback

    /**
     * 状态文案：`L:56% R:57% C:70% | 环境声`，与前台服务通知同一套格式与措辞
     * （`R.string.notification_status_format` + `HeadphoneText.ancModeText`）。
     */
    private fun statusText(): String {
        fun slot(value: Int): String = if (value in 0..100) "$value%" else "-"
        val left = slot(PanaBridge.getLeftBattery())
        val right = slot(PanaBridge.getRightBattery())
        val cradle = slot(PanaBridge.getCradleBattery())
        val context = moduleContext
        val anc = if (context != null) {
            runCatching { HeadphoneText.ancModeText(context, PanaBridge.getAncMode()) }.getOrDefault("")
        } else {
            ""
        }
        val formatted = context?.let {
            runCatching {
                it.getString(R.string.notification_status_format, left, right, cradle, anc)
            }.getOrNull()
        }
        return formatted ?: "L:$left R:$right C:$cradle | $anc"
    }

    /**
     * 兜底：点卡片打开本模块主界面 —— ⚠️ 这条路径**在 MIUI 下拉卡片时会被系统拦掉**。
     *
     * 它是本进程（`com.xiaomi.bluetooth`，uid 1002）创建的 PI，创建者身份没有后台启动资质：
     * MIUI 触发 contentIntent 时判定 `callingUid=1002`、`balAllowedByPiCreator=BSP.NONE`
     * → `(BAL_BLOCK)`，MainActivity 起不来。正常路径是 [appLaunchIntent]（App 进程创建，
     * 经 Provider 取回）；只有取不到时才退到这里 —— 此时至少在「App 已在前台」等
     * 本来就不需要豁免的场景下仍然可用。
     */
    private fun launchAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_INTENT_REQUEST_CODE,
        Intent().setClassName(MODULE_PACKAGE, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 「切换降噪」按钮：显式广播到本模块的 `PanaCommandReceiver`，携带 token。
     * 按钮的 PendingIntent 由本进程（系统蓝牙包）创建，但广播是显式指向本模块接收器的，
     * 接收器按 token 鉴权，与发布者身份无关。
     */
    private fun cycleAncAction(context: Context): Notification.Action {
        val intent = Intent(PanaBridge.ACTION_COMMAND).apply {
            setClassName(PanaBridge.PACKAGE_NAME, PanaBridge.COMMAND_RECEIVER_CLASS)
            putExtra(PanaBridge.EXTRA_COMMAND, PanaBridge.COMMAND_CYCLE_ANC)
            putExtra(PanaBridge.EXTRA_COMMAND_TOKEN, PanaBridge.COMMAND_TOKEN)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            CYCLE_ACTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = moduleString(R.string.cycle_anc, "切换降噪")
        // 图标用 android 系统资源（任何进程都可达）。textButton 的实际观感由卡片布局按
        // actionTitle 渲染，这个 Icon 只是 Notification.Action 的必填字段。
        return Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_popup_sync),
            label,
            pendingIntent,
        ).build()
    }

    /** 诊断用：本 Hook 是否已就绪（接收器已注册 + 两个 Context 都拿到）。 */
    fun isReady(): Boolean = stateReceiver != null && proxyContext != null && moduleContext != null
}
