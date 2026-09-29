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
        versionCode = 204
        versionName = "2.0.4"

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
