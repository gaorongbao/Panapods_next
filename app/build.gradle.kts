plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.panapods"
    compileSdk = 35

    // 发布签名仅在仓库根目录存在 keystore.jks 时生效；缺失时 assembleRelease 仍可产出
    // 未签名 APK（debug 构建完全不受影响），避免新工作区因缺密钥而构建失败。
    val releaseKeystore = file("../keystore.jks")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "123456"
                keyAlias = "az100release"
                keyPassword = "123456"
            }
        }
    }

    defaultConfig {
        // 与旧版 PanaPods (com.panapods) 并存安装：Provider authority、广播 action、
        // PanaBridge.PACKAGE_NAME 均按新包名同步（见 PanaBridge / AndroidManifest）。
        applicationId = "com.panapods.next"
        minSdk = 35
        targetSdk = 35
        // 重构版本独立计数：从 200 起跳，避免与旧版 185 的 versionCode 冲突。
        // 201：接入连接快连弹窗 + 通知栏循环切换降噪。
        // 202：僵尸 GATT 写死自愈 + 纯 LE agent 升级 DUAL（修复左耳电量不显示）。
        // 203：融合中心锚点统一（LE 活动地址广播落盘 → 首点不再「设备可能不在附近」）
        //      + 同名磁贴去重与 device 行不变式（恰好一行且 id == 活地址）。
        // 204：官方 App 让权恢复不弹快连卡（HandoverResume 一次性豁免），
        //      退出 Technics Audio Connect 不再莫名弹一张连接卡片。
        // 205：代码审查修复——手动断开补广播 connected=false、充电盒电量缓存守卫、
        //      isConnected/isConnecting 加 @Volatile、initSession 世代号防重复下发、
        //      GATT 写 busy 重试可取消、RacePacket.putShort 溢出截断。
        // 206：第二轮审查修复——TWS 详情页防重入死守卫→时间窗节流、诊断 hook 去重叠加、
        //      updateAncUi 继承链重复 hook、HyperOSHeadsetHook.isPana 地址缓存快路径、
        //      Provider 查询移出主线程、Bridge 三条写入路径的 LC3 副地址语义统一、
        //      Settings 进程 Provider 守卫、以及若干热路径/日志清理。
        // 207：修复 LC3 模式下「动不动就重连 / 单耳没声」——真机取证发现
        //      isLeAudioConnected() 在 LE Audio 已连接（LeAudioStateMachine=Connected）
        //      时仍恒返回 false，导致整层 LC3 保护成为死代码。重写判据：新增
        //      「按设备查 LE_AUDIO 连接状态」来源、AudioManager 按产品名兜底、
        //      LE_AUDIO_ACTIVE_DEVICE_CHANGED 广播缓存（原先只打日志就 return，
        //      地址被丢弃），并补诊断输出；profile 代理由「一次性申请」改为限频补申请。
        // 208：修复焦点通知卡片「不插卡才弹一次就收、插卡完全看不到」——真机截图对比
        //      确认卡片一直在，缺的是展开态（「切换降噪」按钮只在展开态渲染）。
        // 209：真机取证发现 208 的「一次性放行」被连接后紧跟的 300ms 防抖状态刷新
        //      覆盖成 enableFloat=false，展开请求等于白给。改为 1.5s 放行窗口。
        // 210：放弃「窗口」思路，改用参考实现（SonyPods）验证过的 HyperOS 修法。真机
        //      抓包确认记录健康（focusType=PARAMS、extras 齐全）却渲染成普通通知，
        //      与 SonyPods 注释所述「岛条目被移出可见列表后 direct notify() 再也顶不出来，
        //      必须做真正的 remove -> add」完全吻合。故：update() 一律原地刷新
        //      （enableFloat=false，不再每次抢展开位），连接跳变时走
        //      stopForeground(REMOVE) + startForeground(islandFirstFloat=true) 重发。
        //      另修：LE Audio 恢复提示发往已被删除的 LEGACY 渠道 "panapods_ble"，
        //      该提示实际从未显示过，改用现行渠道。
        // 211：v210 的 enableFloat=false 实测「保持不住」——只在连接瞬间请求一次展开，
        //      被系统收起后再也回不来。改回 enableFloat 恒 true，即 SonyPods「toast」那套
        //      语义（它的 MiBluetoothToastHook 正是 enableFloat=true + updatable=true，
        //      产出的就是用户认可的「两颗耳机图 + 横跨全宽『切换降噪』」浮动卡片）。
        //      状态刷新每 2~15s 一条、每条都请求展开 → 收起后几秒内自动回到展开态。
        //      并**补上一直缺失的 param_island（岛数据）**：小米文档标注必选，SonyPods 的
        //      常驻卡与浮动卡两条路径都带 island{ bigIslandArea{ imageTextInfoLeft/Right } }，
        //      结构照抄 MiBluetoothToastHook:248-266（我们此前只有焦点组件、岛载荷整块缺省）。
        //      保留 v210 的 remove→add（重建被 HyperOS 移出可见列表的岛条目）与 reopen="reopen"。
        // 212：v211 实测仍出不来大卡片。真机取证（10:31-10:34，SIM 已插、音乐暂停、桌面）：
        //      用 adb 触发「切换降噪」制造一次**真实的通知更新**（ANC 已切到 2、publishState
        //      已广播），桌面上的焦点卡片纹丝不动 —— 证明「原地 notify()（含 enableFloat=true）
        //      顶不出展开态」是硬限制，与 v209 的结论一致。
        //      据此把 buildFocusExtras **整块退回 v2.0.7 的参数集**（那套在用户 09:12 截图里
        //      产出的正是目标卡片）：删掉 v211 的 islandFirstFloat=false（语义是「首次出现
        //      **不**展开」，与需求正好相反）、reopen="reopen"、以及未经真机验证的
        //      param_island（islandProperty + bigIslandArea 不完整；而「整块缺省」在 09:12
        //      是能出卡的）。islandFirstFloat 交回系统默认 true = 首次出现自动展开。
        //      另加 token 保护的 COMMAND_REPOST_CARD（"repost_card"）命令，可从外部/诊断
        //      按需强制一次 remove → add 重建卡片。
        // 213：根因定案 —— 卡片的卡点是 SystemUI 的「同签名放行」判定，不是参数、不是岛位。
        //      反编译 /product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk 得到
        //      miui.systemui.notification.focus.SignatureChecker（**日志 tag 叫 SignatureUtils，
        //      与类名不一致**，此前按 tag 搜类名一直搜空）：checkSignatures(pm, pkg,
        //      sysUISignatures) 把目标包签名与 SystemUI 自身签名比 SHA256，**同签名直接放行、
        //      不联网**；不匹配才落到 AuthManager.requestAuth(pkg, scope=20032) 联网问小米服务端，
        //      自签名包答 scopeInfos=[]（apkSigns=null）→ -300 scope mismatch → 卡片被丢，
        //      且成功结果永远进不了缓存，于是每次通知都重新联网判定 —— 网络通就 -300，
        //      网络不通 -400「失败开放」反而显示，这才是「插卡就没、不插卡就有」的真身。
        //      真机铁证：dumpsys notification 里 SonyPods 的全部渠道（BTHeadset* /
        //      sonypods_focus_island）都登记在 com.xiaomi.bluetooth (uid 1002) 名下，它自己的
        //      App 包一条渠道都没有；其 scope.list 也不含 com.android.systemui —— 它是靠
        //      「借平台签名系统包的壳代发通知」过门的（uid 1002 靠 sharedUserId 分配，
        //      跨包共享要求签名一致 → 必然是平台签名）。
        //      据此放弃原先「在 systemui 作用域 Hook 授权判定」的方案，改为：
        //      - 新增 PanaCardPosterHook，在 com.xiaomi.bluetooth 主进程代发焦点卡片
        //        （零新增作用域、零用户手动勾选、不碰 MIUI 内部鉴权实现）；
        //      - 图片改用 Icon.createWithBitmap（跨进程读不到模块资源，createWithResource 会白图）；
        //      - 卡片用独立 tag/id/channel（PanaPodsCard / 10100 / panapods_focus_card），
        //        不抢官方 BTHeadset<MAC>+10003（本模块已 patch checkSupport，系统可能自己发那条）；
        //      - 前台服务通知退回纯普通通知（去掉 focus extras），消除「app 通知是错的」视觉；
        //      - 新增 ACTION_REPOST_CARD，让已有的 repost_card 命令同时驱动新卡片路径。
        // 214：v2.0.13 上线后焦点卡片「一直最大化、收不起来」。原因是把更新也当成了「请求展开」：
        //      MIUI 焦点通知的展开有两套开关，且是**各自独立**的 ——
        //      - islandFirstFloat（默认 true）：通知**首次出现**时自动展开成大卡片。
        //        连接跳变走 remove → add 重发 = 一次「首次出现」，这条留着不动，大卡片照旧。
        //      - enableFloat（默认 false）：通知**更新**时是否再次请求展开。
        //        v2.0.13 里被设成了 true，而状态推送每 2s 一条、原地 notify 一次算一次更新，
        //        等于每 2s 就把卡片重新顶回展开态 → 永远收不起来（实测 120 次原地更新、0 次重发）。
        //      改为 enableFloat = false：更新只改文案，不再请求展开，交给系统自己的岛生命周期
        //      把它收成小条；首次出现仍然展开，点一下仍能重新放大。
        // 215：修复「下滑/点击焦点卡片打不开 App」。v2.0.13 把卡片搬到 com.xiaomi.bluetooth
        //      代发后，卡片上的 contentIntent 也变成由蓝牙进程（uid 1002）创建 —— 而
        //      PendingIntent 的「创建者身份」直接决定 Android 后台启动限制（BAL）怎么判：
        //        callingPackage: com.xiaomi.bluetooth; callingUid: 1002;
        //        originatingPendingIntent: PendingIntentRecord{com.xiaomi.bluetooth startActivity};
        //        balAllowedByPiCreator: BSP.NONE  → (BAL_BLOCK) result code=102
        //      MIUI 在「下滑展开态卡片」时触发这次启动，被系统直接拦掉，`Displayed
        //      com.panapods.next` 全程不出现 —— App 根本起不来（旧版卡片由 App 自己发布，
        //      PI 创建者是 App 且带前台服务，所以那条路可用）。
        //      修法：还原旧版作者身份 —— App 通过 Provider 暴露一个自己创建的
        //      PendingIntent（METHOD_GET_LAUNCH_INTENT），代发进程启动时取回并当作
        //      contentIntent；本进程自建的 PI 只作取不到时的兜底。
        // 216：修复「每次解锁都弹大卡片」。v2.0.14 只压住了 `enableFloat`（更新时别抢展开位），
        //      漏了 `islandFirstFloat`（首次出现要不要展开）。而 unlock 补渲染走的是
        //      remove → add 重发 —— 在 MIUI 眼里**重发就=一次「首次出现」**，于是
        //      islandFirstFloat 拿到系统默认 true，每次解锁都把卡片当新卡片自动展开。
        //      真机链路：user unlocked → card re-posted (remove -> add) →
        //      DynamicIslandWindowViewImpl: expanded = true。
        //      据此把「要不要重发」与「要不要大卡片」拆成两个独立开关：
        //      postCard(repost, firstFloat) → buildCard(..., firstFloat) →
        //      islandFirstFloat = firstFloat。只有两条路径给 true（= 大卡片）：
        //        - 断开 → 连接跳变（首次连接）；
        //        - ACTION_REPOST_CARD（设置页/诊断/`repost_card` 命令的显式「要展开」请求）。
        //      解锁补渲染给 (repost=true, firstFloat=false)：卡片必须重发才出得来，但只出小条；
        //      2s 状态原地更新给 (repost=false, firstFloat=false)。
        //      放大交给 MIUI 自己的岛手势：点一下小条由系统展开成大卡片（含「切换降噪」按钮）。
        //      注意 `islandFirstFloat=false` 的语义有历史实证 —— v211 用过，当时卡片照常
        //      出现在岛上、只是不自动展开（v212 注记「实测仍出不来大卡片」即此）。
        // 217：代码审查（第三轮）修复：
        //      ① PanaBridge.publishState / publishStateToCache 的「断开即清 LC3 副地址」
        //         顺序写反了 —— 先 clear 后赋值，而 publishBridgeState 断开时传进来的
        //         lc3Addr 正是当前缓存值（非空且 ≠ addr），刚清掉立刻被原样写回，
        //         v206 想修的「换耳机后旧副地址仍参与 isCurrentDevice 命中」实际从未
        //         生效过；两处改为先赋值、后清空（与 updateCacheFromIntent 一致）。
        //      ② 焦点卡片代发 Hook 在 com.xiaomi.bluetooth 主线程同步 query/call Provider：
        //         App 未运行时会按需冷启动 com.panapods.next 并等它发布 Provider
        //         （超时上限约 10s），开机即卡住蓝牙进程 —— 与 HyperOSHeadsetHook v206
        //         修过的同款问题（refreshStateFromProviderAsync），v2.0.13 加这里时漏了。
        //         改为 Async 后台播种 + seedLock 守基线（谁先到谁定基线，语义不变）。
        //         ⚠️ 已知遗留：OfficialFastConnectDialogHook.onApplicationReady 仍是同步
        //         query（v204 起既有，且排在本 Hook 之前执行），整条启动路径要彻底干净
        //         需一并改它 —— 它的 :ui 分支还要喂 latestSnapshot，本轮未动，见审查记录。
        //      ③ 卡片每条状态推送（约 2s 一次）都在蓝牙进程主线程重新解码 439KB 的
        //         logo PNG 并分配 ~160KB 位图 → 加静态缓存，解锁重建 Context 时失效。
        //      ④ 新增 PanaBridgeLc3Test（6 例）锁住 ① 的顺序语义。
        //      ⑤ 设置页掉帧定案（上一轮遗留的 A/B 复测）：beyondViewportPageCount 1→2。
        //         同协议 6 次切页重进，真机 120Hz：=1 janky 4.15%、p95 61ms / p99 97ms /
        //         最差 150ms、丢 7 次 VSync；=2 janky 0.82~1.32%（连跑 4 轮）、丢 0 次 VSync。
        //         代价：冷启动 Displayed 中位 277ms → 343ms（+66ms）。数字见 PanaPodsUI 注释。
        versionCode = 217
        versionName = "2.0.17"

        // minSdk >= 21 时系统原生支持 multidex，无需 multiDexEnabled / multiDexKeepProguard。
        // Xposed 入口类 (HookEntry) 由 proguard-rules.pro 的 -keep 规则保护，不会被 R8 裁掉。
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        // 启用 BuildConfig：设置页「关于」直接读 BuildConfig.VERSION_NAME，
        // 从此版本号与实际构建版本永远一致，不再手写硬编码字符串。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // 小米焦点通知/超级岛 param_v2 构建器（通知卡片上直接渲染 textButton 按钮）
    implementation(libs.focus.api)

    // Xposed 传统 API 兼容（项目内自带实现，桥接 LSPosed 现代 API）

    // LSPosed modern API (io.github.libxposed)
    compileOnly("io.github.libxposed:api:102.0.0")

    // JVM 单元测试
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// 非 ASCII 项目路径（panapods_新）下的 JVM 单元测试适配
//
// 现象：`testDebugUnitTest` 里**每个**测试类都 ClassNotFoundException
// （报告里表现为 initializationError），哪怕测试类本身零依赖。
// 排查结论：
//   * 同一条 classpath 用 `java -cp <该目录> org.junit.runner.JUnitCore ...`
//     直接跑，测试类加载正常、用例可执行 → 磁盘、JDK、类文件都没问题；
//   * 失败只发生在 Gradle 把 classpath 交给 test worker 的环节：工作目录含非 ASCII
//     字符时该条目被解码坏，worker 的 classloader 里就没有这个目录。
//     （gradle.properties 的 android.overridePathCheck=true 只放过 AGP 的路径检查，
//       assembleDebug 不受影响，管不到这里的传输。）
// 对策：
//   1. 把「项目内的 classpath 条目」（测试类 / javac 测试输出 / 测试 res /
//      主类 classes.jar）复制到纯 ASCII 的临时目录；
//   2. 交给 Test 任务时，用这批中转件替换掉原项目路径条目。
// 依赖缓存路径本来就是 ASCII（C:\Users\...\.gradle\...），原样保留。
// 若哪天 Gradle 修好这条路径，删掉下面 stageTestClasspath 与 classpath 过滤两段即可。
// ---------------------------------------------------------------------------
val asciiStagingDir = File(System.getProperty("java.io.tmpdir"), "panapods-test-classpath")

val stageTestClasspath = tasks.register<Copy>("stageTestClasspathToAscii") {
    group = "verification"
    description = "把项目内的测试 classpath 条目复制到纯 ASCII 目录（非 ASCII 项目路径规避）"
    // 显式依赖产出这些目录的任务，否则 Gradle 8.12 会报 implicit dependency 校验失败。
    dependsOn(
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
        "processDebugUnitTestJavaRes",
        "bundleDebugClassesToRuntimeJar",
        "processDebugJavaRes",
        "processDebugResources",
    )
    into(asciiStagingDir)
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")) { into("kotlin-test-classes") }
    from(layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")) {
        into("javac-test-classes")
    }
    from(layout.buildDirectory.dir("intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out")) {
        into("test-java-res")
    }
    from(layout.buildDirectory.dir("intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar")) {
        into("main")
    }
    from(layout.buildDirectory.dir("intermediates/java_res/debug/processDebugJavaRes/out")) { into("main-java-res") }
    from(layout.buildDirectory.dir("intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar")) {
        into("main")
    }
}

afterEvaluate {
    // 必须放在 afterEvaluate 里：AGP 会在它的 afterEvaluate 中重新给 test 任务
    // 设置 classpath，写在脚本体里的修改会被覆盖掉。
    tasks.withType<Test>().configureEach {
        // 报告与控制台里的中文按 UTF-8 处理。
        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Dnative.encoding=UTF-8",
        )

        val projectDirPrefix = rootProject.projectDir.absolutePath
        val staged = files(
            File(asciiStagingDir, "kotlin-test-classes"),
            File(asciiStagingDir, "javac-test-classes"),
            File(asciiStagingDir, "test-java-res"),
            File(asciiStagingDir, "main/classes.jar"),
            File(asciiStagingDir, "main/R.jar"),
            File(asciiStagingDir, "main-java-res"),
        )
        dependsOn(stageTestClasspath)
        // 只剔除落在项目目录内的条目（它们全在非 ASCII 路径下），其余依赖顺序保持不变。
        classpath = classpath.filter { !it.absolutePath.startsWith(projectDirPrefix) } + staged
    }
}
