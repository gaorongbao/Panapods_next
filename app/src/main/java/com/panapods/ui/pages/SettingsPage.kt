package com.panapods.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.panapods.BuildConfig
import com.panapods.PanaPodsApp
import com.panapods.ui.components.PageHeader
import com.panapods.ui.components.PreferenceRow
import com.panapods.ui.theme.AppColors
import com.panapods.utils.Async
import com.panapods.utils.PanaLog
import com.panapods.utils.RootKeepAlive
import com.panapods.receivers.KeepAliveScheduler

/**
 * 设置页 — 对应 SonyPods SettingsPage 的 Miuix 偏好列表风格。
 */
@Composable
fun SettingsPage(
    bottomPadding: Dp = 0.dp,
    onOpenDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val config = remember { (context.applicationContext as PanaPodsApp).configManager }
    val logEnabled = remember { mutableStateOf(PanaLog.enabled) }
    val autoConnect = remember { mutableStateOf(config.autoConnect) }
    val hideFromRecents = remember { mutableStateOf(config.hideFromRecents) }
    val rootKeepAlive = remember { mutableStateOf(config.rootKeepAlive) }
    val swapEarSides = remember { mutableStateOf(config.swapEarSides) }
    val connectPopup = remember { mutableStateOf(config.connectPopupEnabled) }
    val ancCycleModes = remember { mutableStateOf(config.ancCycleModes) }
    val showCycleDialog = remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = bottomPadding + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader(title = "设置")
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "🔗",
                    title = "自动连接",
                    summary = "拿出耳机或蓝牙开启后自动连接 Pana",
                    trailing = {
                        Switch(
                            checked = autoConnect.value,
                            onCheckedChange = { enabled ->
                                autoConnect.value = enabled
                                config.autoConnect = enabled
                            }
                        )
                    }
                )
                Divider()
                PreferenceRow(
                    icon = "🐞",
                    title = "调试日志",
                    // v175：文案与实现同步 —— 现在是三路输出：
                    // logcat + 文件（FileLog 滚动落盘）+ LSPosed 模块日志（Hook 侧镜像）。
                    // w/e 始终输出，d/i/v 受开关控制，文案按此如实描述。
                    summary = "开启后详细日志写入 logcat、文件 logs/panapods.log 与 LSPosed 日志；关闭后仅保留警告与错误",
                    trailing = {
                        Switch(
                            checked = logEnabled.value,
                            onCheckedChange = { enabled ->
                                logEnabled.value = enabled
                                PanaLog.setEnabled(context, enabled)
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "🔄",
                    title = "主耳在左（左右对调）",
                    summary = "主耳=手机直连的那只。若某侧电量落错槽位（左槽显示右耳、入仓耳反而显示电量）请切换；AZ100 此副主耳为左耳，需开启",
                    trailing = {
                        Switch(
                            checked = swapEarSides.value,
                            onCheckedChange = { enabled ->
                                swapEarSides.value = enabled
                                config.swapEarSides = enabled
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "🎴",
                    title = "连接时弹出快连弹窗",
                    summary = "耳机连接成功时由系统蓝牙弹出官方设备卡片（显示名称与左右电量），即 SonyPods 的「官方弹窗」",
                    trailing = {
                        Switch(
                            checked = connectPopup.value,
                            onCheckedChange = { enabled ->
                                connectPopup.value = enabled
                                config.connectPopupEnabled = enabled
                            }
                        )
                    }
                )
                Divider()
                PreferenceRow(
                    icon = "🔁",
                    title = "循环切换降噪",
                    summary = "通知栏「切换降噪」按钮的参与模式：已选 ${ancCycleModes.value.size} 项，" +
                        "按 降噪 → 环境声 → 关闭 固定顺序循环",
                    modifier = Modifier.clickable { showCycleDialog.value = true },
                    trailing = {
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = AppColors.textSecondary
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "👻",
                    title = "隐藏后台",
                    summary = "开启后 App 进入后台时从最近任务列表移除（服务仍保持连接）",
                    trailing = {
                        Switch(
                            checked = hideFromRecents.value,
                            onCheckedChange = { enabled ->
                                hideFromRecents.value = enabled
                                config.hideFromRecents = enabled
                            }
                        )
                    }
                )
                Divider()
                PreferenceRow(
                    icon = "🛡️",
                    title = "Root 自动保活",
                    summary = "使用 root 加入电池白名单并周期拉起服务（默认关闭，需已 root）",
                    trailing = {
                        Switch(
                            checked = rootKeepAlive.value,
                            onCheckedChange = { enabled ->
                                rootKeepAlive.value = enabled
                                config.rootKeepAlive = enabled
                                if (enabled) {
                                    KeepAliveScheduler.schedule(context)
                                    Async.run("root-keepalive-apply") {
                                        val ok = RootKeepAlive.apply(context)
                                        if (!ok && !RootKeepAlive.isRootAvailable()) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                Toast.makeText(context, "未检测到可用 root，保活可能无效", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    KeepAliveScheduler.cancel(context)
                                    // v175：对称回收 apply() 加上的 deviceidle 白名单
                                    Async.run("root-keepalive-remove") {
                                        RootKeepAlive.remove(context)
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                PreferenceRow(
                    icon = "📡",
                    title = "协议调试",
                    summary = "查看 BLE Race 协议收发日志",
                    modifier = Modifier.clickable(onClick = onOpenDebug),
                    trailing = {
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = AppColors.textSecondary
                        )
                    }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "关于 PanaPods",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.textPrimary
                )
                Text(
                    text = "为 HyperOS 设备提供系统级松下/Technics EAH-AZ 系列耳机控制",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} · 协议 Airoha Race (Panasonic Pana)",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // v175：原文案对所有用户硬编码"目标 HyperOS 3 (Xiaomi 17 Ultra)"，
                // 与实际运行设备无关，容易被当成"App 只支持这台机器"。改为本机真实信息。
                Text(
                    text = "本机 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                        "Android ${android.os.Build.VERSION.RELEASE}",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Xposed 作用域",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.textPrimary
                )
                Text(
                    text = "默认（与 scope.list 一致，5 个）",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                ScopeItem("com.android.bluetooth", "系统蓝牙 — 电量/ANC 注入, 型号伪装")
                ScopeItem("com.android.settings", "系统设置 — 耳机信息显示")
                ScopeItem("com.milink.service", "MiLink — 融合设备中心")
                ScopeItem("com.xiaomi.bluetooth", "小米蓝牙 — AIVS 探测拦截 + 连接快连弹窗")
                ScopeItem(
                    "com.panasonic.technicsaudioconnect",
                    "官方 Technics Audio Connect — 连接让权（打开官方 App 时引擎自动让出）"
                )
                Text(
                    text = "可选（不在默认作用域，需要时在 LSPosed 手动勾选）",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                ScopeItem("com.android.systemui", "SystemUI — 融合中心卡片渲染端补丁（旧版路径，保留）")
                ScopeItem("com.miui.contentcatcher", "ContentCatcher — 设置页兼容进程（旧版路径，保留）")
            }
        }
    }

    // 循环降噪参与模式勾选（至少保留一个，与 SonyPods AncCycleModesDialog 同语义）
    if (showCycleDialog.value) {
        AlertDialog(
            onDismissRequest = { showCycleDialog.value = false },
            title = { Text(text = "参与循环的降噪模式") },
            text = {
                Column {
                    Text(
                        text = "点通知栏「切换降噪」时按固定顺序（降噪 → 环境声 → 关闭）在勾选的模式间循环；至少保留一个",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(
                        "NOISE_CANCELING" to "降噪",
                        "AMBIENT" to "环境声",
                        "OFF" to "关闭"
                    ).forEach { (key, label) ->
                        val checked = key in ancCycleModes.value
                        val toggle = {
                            val next =
                                if (checked) ancCycleModes.value - key else ancCycleModes.value + key
                            // 至少保留一个；写入时 ConfigManager 会归一成固定顺序
                            if (next.isNotEmpty()) {
                                ancCycleModes.value = next
                                config.ancCycleModes = next
                            } else {
                                Toast.makeText(context, "至少保留一个模式", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle() }
                            )
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                color = AppColors.textPrimary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCycleDialog.value = false }) {
                    Text(text = "完成")
                }
            }
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = 68.dp, end = 16.dp)
            .background(AppColors.divider)
    )
}

@Composable
private fun ScopeItem(pkg: String, desc: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = pkg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.textPrimary
        )
        Text(
            text = desc,
            fontSize = 11.sp,
            color = AppColors.textSecondary
        )
    }
}
