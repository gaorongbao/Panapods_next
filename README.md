# PanaPods Next（`panapods_新`）

> HyperOS / MIUI 系统级 **Panasonic / Technics EAH-AZ 系列耳机**控制 —— LSPosed 模块 + 独立 App。
>
> 应用 ID **`com.panapods.next`** · 当前版本 `2.0.2` (versionCode 202) · minSdk 35 (Android 15)
> Kotlin 包名与 `namespace` 仍为 `com.panapods`（只改 applicationId，代码零改动即可并存安装）

本仓库是 **PanaPods（`AZ100Pods`）的架构重组版**：协议层 / BLE 引擎 / UI 原样保留，
只把 Hook 层按 **SonyPods** 的思路重写了一遍 —— 单一入口按作用域分发、Hook 单元统一继承
`HookContext`、并补齐「官方 App 连接让权」这一 SonyPods 有而 PanaPods 没有的能力。
原 `AZ100Pods` 目录未做任何修改，可与本工程并存对照。

---

## 为什么要有这一版

对照分析（详见两套代码）结论：PanaPods 是**生产侧**打补丁 —— 每个 Hook 自己收
`classLoader`、自己判包名、自己找 Application，装在哪、装没装全靠约定；SonyPods 是
**渲染/调度侧**分发 —— 一个 `HookEntry` 持有作用域集合，`onPackageReady` 里
`loadScope(scope)` 决定装什么，Hook 单元只声明 `onHook()`。后者的好处是
「某个进程漏装一半 Hook」这种状态在结构上不可能出现。

本版把 PanaPods 搬到后者的结构上，同时保留前者的协议与功能实现。

### 改动清单

| 维度 | 旧版 PanaPods | 本版 PanaPods Next |
|---|---|---|
| 入口分发 | `onPackageLoaded` 里按包名散装调用 `XxxHook.install(classLoader)` | `HookEntry.supportedScopes` + `onPackageReady` → `loadScope()` |
| Hook 单元 | `object XxxHook { fun install(classLoader) }` | `object XxxHook : HookContext() { override fun onHook() }` |
| Application 时机 | 各自 `currentApplication()` 判空、失败靠轮询重试（部分进程永远注册不上） | `HookEntry` 统一 hook `Instrumentation.callApplicationOnCreate`，派发 `onApplicationReady(app)` |
| 作用域 | 6 个（含 SystemUI / contentcatcher） | **默认 5 个** + 2 个可选渲染端（代码路径保留，不进 `scope.list`） |
| 官方 App | 与官方 Technics Audio Connect 抢连接 | **连接让权租约**：官方 App 活跃时引擎让出 GATT，空闲 2s 后复连 |
| applicationId | `com.panapods` | `com.panapods.next`（与旧版并存安装） |
| Provider authority | `com.panapods.provider` | `com.panapods.next.provider` |
| 广播 action | `com.panapods.bridge.*` | `com.panapods.next.bridge.*` |
| 版本号 | 1.0.185 / 185 | 2.0.0 / 200 |

---

## 作用域布局

### 默认（`META-INF/xposed/scope.list`，5 个）

| 作用域进程 | 注入内容 |
|---|---|
| `com.android.bluetooth` | HyperOS 耳机集成（服务端）：电量/ANC 注入、型号伪装 |
| `com.xiaomi.bluetooth` | 融合中心通知侧 + 阻断 AIVS 对 Pana 的连接风暴探测 |
| `com.android.settings` | 系统设置页 TWS 耳机条目、诊断信息 |
| `com.milink.service` | 融合设备中心：`checkIsMiTWS` / `getDeviceId` / ANC 控件 / 卡片图 |
| `com.panasonic.technicsaudioconnect` | **官方 Technics Audio Connect：连接让权（本版新增）** |

### 可选渲染端（不在 `scope.list`，需要时在 LSPosed 手动勾选）

| 作用域进程 | 说明 |
|---|---|
| `com.android.systemui` | 融合中心卡片**渲染端**补丁（旧版路径，保留） |
| `com.miui.contentcatcher` | 设置页兼容进程（旧版路径，保留） |

按 SonyPods 的取数思路，卡片数据由 milink / 蓝牙侧的**源头注入**供给；若某 ROM 上
融合卡片仍回退到最简版，勾选 `com.android.systemui` 即可恢复旧版渲染端补丁。
两份代码路径都在（`HookEntry.loadScope` 的 `PKG_MILINK, PKG_SYSTEMUI` 分支），
不用改代码。

---

## 官方 App 连接让权（本版新增）

官方 **Technics Audio Connect**（`com.panasonic.technicsaudioconnect`，实测 v4.4.0）
与本引擎是两条互不知情的 BLE 通道：两边同时连同一副耳机时会互相挤掉对方的 GATT、
诱发耳机端主动断连。SonyPods 对 Sony Sound Connect 的做法是「官方 App 活跃时引擎让位」，
本版按同样语义实现，**全程不需要逆向官方 App，也不需要 DexKit**：

```
官方 App 进程（Hook 侧）                    本 App 进程（引擎侧）
─────────────────────────                  ─────────────────────────
持有源 ① Activity 生命周期                 PanaPodsProvider.call("officialLease")
        ② Service.startForeground / onDestroy   │  或显式广播兜底
        ③ BluetoothDevice.connectGatt /         ▼
          BluetoothGatt.close / Socket.connect  OfficialLease（状态机 + linkToDeath）
   │                                          │
   └── 任一存在 → acquire（带 Binder token）──┘──► PanaBleService 让出：
                                                  teardownClient + 取消自动重连 + 关通知
   └── 全部消失 + 2s 宽限期 → release ──────────► 复位退避 + tryReconnectFromSaved
   └── 进程被杀 / 崩溃 → Binder death ──────────► 无条件 release（不会锁死）
   └── 收到 engine_ready → 仍持有则 reassert ──► 防止引擎重启窗口内双方抢连接
```

- 三个持有源 → `TechnicsHandoverHook.LeaseCoordinator`（Activity / Service / Session 集合，
  所有方法加锁，主线程 + Binder 线程并发安全）
- 租约身份 `pid:uuid`，重复 acquire 幂等、迟到 release 丢弃（`OfficialLeaseState`，9 个单测）
- 引擎侧：`OfficialLease` → `PanaBleService.officialLeaseListener` → `connect()` 与
  `isSuppressAutoReconnect()` 双重闸门
- 宽限期 2s（`TechnicsHandoverHook.RELEASE_GRACE_MS`），容忍官方 App 页面切换/内部会话迁移

---

## 运行要求

- **HyperOS / MIUI（Android 15+，实测 HyperOS 2）**
- **LSPosed**（现代 Xposed API，`minApiVersion=102`，静态作用域）
- 蓝牙、通知运行时权限；Root 用于保活 / 作用域重启等增强功能

## 安装与激活

1. 安装 APK：`app/build/outputs/apk/debug/app-debug.apk`（或自签 release）
2. LSPosed → 模块 → 启用 **PanaPods Next**，作用域勾选：
   `com.android.bluetooth`、`com.android.settings`、`com.milink.service`、
   `com.xiaomi.bluetooth`、`com.panasonic.technicsaudioconnect`
3. 重启作用域进程（App 内「重启作用域应用」需 Root），或重启手机
4. 打开 PanaPods Next，授权蓝牙 / 通知权限，连接耳机

> **与旧版 PanaPods 并存**：两个 APK 的包名、Provider authority、广播 action 全部不同，
> 数据通道不会串。但两边的模块会向**同一批系统进程**注入代码，请只在 LSPosed 里启用
> 其中一个模块（或把另一个的作用域清空），避免重复 Hook。

## 构建

环境：**JDK 21** + Android SDK Platform 35（AGP 8.9.0 / Kotlin 2.1.0 / Jetpack Compose）

```powershell
# Windows
.\gradlew.bat testDebugUnitTest assembleDebug
# Linux / macOS
./gradlew testDebugUnitTest assembleDebug
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 版本号唯一来源：`app/build.gradle.kts` 的 `versionCode` / `versionName`
- **签名密钥不入库**：把 `keystore.jks` 放到仓库根目录才会启用 release 签名配置；
  缺失时 `assembleRelease` 产出未签名 APK，`assembleDebug` 完全不受影响

### ⚠️ 项目目录含中文（`panapods_新`）的两个坑

Windows + ANSI 代码页（GBK）下，AGP / Gradle 对非 ASCII 路径不友好，本工程已就地规避：

1. **打包**：`gradle.properties` 里 `android.overridePathCheck=true` —— 否则 AGP 直接
   拒绝配置工程（b/95744）。`assembleDebug` / `assembleRelease` 实测正常。
2. **单元测试**：Gradle 把 project 目录内的 classpath 条目交给 test worker 时会把它解码坏，
   表现为**每个**测试类都 `ClassNotFoundException`（`initializationError`）。`app/build.gradle.kts`
   里加了 `stageTestClasspathToAscii`：先把 project 内的 classpath 条目复制到
   `%TEMP%\panapods-test-classpath`（纯 ASCII），再替换掉 Test 任务的对应条目。
   若哪天 Gradle 修好，删掉 `stageTestClasspath` 与 `afterEvaluate` 里的 classpath 过滤两段即可。
   （已验证：同一条路径用 `java -cp` 直接跑完全正常，问题只在 Gradle → worker 这一段。）

> 目录改名成纯 ASCII（如 `panapods_new`）即可去掉以上两条 workaround。

## 调试日志

设置页打开「调试日志」开关（`debug_log_enabled`）后，日志三路输出：

| 出口 | 位置 |
|---|---|
| logcat | `adb logcat \| findstr PanaPods`（Windows）/ `grep PanaPods`（Linux） |
| 文件 | `/sdcard/Android/data/com.panapods.next/files/logs/panapods.log` |
| LSPosed | LSPosed Manager → 日志页（模块日志镜像） |

W/E 级始终输出，D/I 级需开关开启。Hook 进程在开关打开后需重启进程（或作用域）才生效读取。

关键 tag：`PanaPods`（入口分发）、`PanaPods/Hook`（Hook 单元）、`PanaPods/Handover`（让权）、
`OfficialLease`（引擎侧租约）、`OfficialLeaseState`/`PanaBleService`。

---

## 架构

```
Technics AZ100 耳机
      │  BLE GATT · 私有 RacePacket 协议（AirohaUuid）
      ▼
PanaBleService（前台服务，2s 自适应轮询，音乐/空闲自动放缓）
      │   ▲  OfficialLease：官方 App 独占时断开并停止自动重连
      │  广播 com.panapods.next.bridge.STATE_UPDATED + ContentProvider com.panapods.next.provider
      ▼
PanaBridge（各系统进程内缓存，广播/provider 双通道）
      ├─► milink :ui / :core（可选 systemui）—— 融合控制中心卡片（图 / ANC / 电量）
      ├─► xiaomi.bluetooth / android.bluetooth —— 耳机详情页状态 CSV、AIVS 探测拦截
      ├─► settings（可选 contentcatcher）—— 系统设置 TWS 条目
      └─► com.panasonic.technicsaudioconnect —— 连接让权租约（acquire/release/死亡兜底）
      ▲
      └── 反向命令通道 PanaCommandReceiver（ANC 切换等回传 App）

HookEntry（唯一 libxposed 入口）
  onPackageLoaded ── 作用域外直接挡掉，读日志开关
  onPackageReady  ── supportedScopes 分发 → loadScope(scope)
                       ├─ installApplicationReadyGate() → callApplicationOnCreate → onApplicationReady()
                       └─ loadHook(hook)：注入 appClassLoader/packageName → hook.onHook()
```

### 目录结构

```
app/src/main/java/com/panapods/
├── ble/         # BLE 服务、GATT 写队列、电量映射、重连策略
├── protocol/    # RacePacket 编解码、RaceId、协议引擎
├── hook/        # HookEntry（分发）+ HookContext（基类）+ 各作用域 Hook 单元
│                #   TechnicsHandoverHook = 官方 App 连接让权（本版新增）
├── bridge/      # 跨进程桥：广播、Provider、命令接收
│                #   OfficialLease / OfficialLeaseState = 引擎侧租约（本版新增）
├── ui/          # Compose 界面（主页 / 详情 / 设置 / 协议调试）
├── receivers/   # 蓝牙事件、开机、保活接收器
├── config/      # SharedPreferences 配置
└── utils/       # 日志（FileLog/PanaLog）、Root、作用域工具
```

## 测试

```powershell
.\gradlew.bat testDebugUnitTest      # 39 个用例，全绿
```

| 测试 | 覆盖 |
|---|---|
| `RacePacketTest` | 协议编解码 |
| `ConnectBackoffTest` | 重连指数退避 |
| `BatteryStateTrackerTest` | 电量/在位映射（含 v181/v183 规则） |
| `OfficialLeaseStateTest` | 让权租约：首次独占、幂等重申、迟到 release、Binder 死亡兜底 |

> `BatteryStateTrackerTest` 里 3 个用例是上游遗留的过期断言（v181 删除「在位探测反推
> agent 侧」后，旧断言与实现相反；`swapEarSides` 也早已改由
> `ConfigManager.swapEarSides → refreshAgentSide() → agentIsLeft` 承载）。已按现行实现
> 重写并补注来源，**主代码未改动**。

## 免责声明

- 个人项目，与 Panasonic、Technics、小米无关；仅供学习研究使用，刷机 / Hook 系统进程风险自负。
- HyperOS OTA 后系统类结构可能变化，Hook 可能失效或需要适配。
- 官方 Technics Audio Connect 的 APK 不入库（`base.apk` 由使用者自行提供，仅用于验证让权行为）。
- 仓库不含签名密钥与构建产物（`*.jks` / `*.apk` 已被 `.gitignore` 排除）。
