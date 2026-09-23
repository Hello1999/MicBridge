package com.jack.micbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import kotlinx.coroutines.delay

@Composable
fun ControlScreen(
    snapshot: BridgeSnapshot, runtimePermissionsGranted: Boolean, exactAlarmGranted: Boolean, restorePending: Boolean,
    onStart: () -> Unit, onBlock: () -> Unit, onRestore: () -> Unit, onConnect: () -> Unit,
    onCalibrate: () -> Unit, onSettings: () -> Unit, onDiagnose: () -> Unit,
) {
    val state = presentMic(snapshot)
    val action = nextControlAction(snapshot, runtimePermissionsGranted, exactAlarmGranted)
    val colors = MaterialTheme.colorScheme
    val statusColors = LocalBridgeStatusColors.current
    val (accent, fill) = when (state.tone) {
        StatusTone.SAFE -> statusColors.safe to statusColors.safeContainer
        StatusTone.ATTENTION -> statusColors.attention to statusColors.attentionContainer
        StatusTone.ERROR -> colors.error to colors.errorContainer
        StatusTone.NEUTRAL -> colors.primary to colors.primaryContainer
    }
    BridgePage {
        PageIntro("系统麦克风", "统一控制这台设备的麦克风，无需选择应用。")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(56.dp).background(fill, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                    BridgeIcon(state.symbol, Modifier.size(28.dp), accent)
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.headlineLarge)
                    Text(state.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                snapshot.autoBlockAtEpochMs?.let { LeaseCountdown(it) }
                if (snapshot.transitioning) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (action) {
                    ControlAction.START -> PrimaryAction("开启麦克风屏蔽", symbol = BridgeSymbol.POWER, onClick = onStart)
                    // Blocking stays reachable during transitions and unknown states.
                    ControlAction.BLOCK -> PrimaryAction("立即屏蔽", symbol = BridgeSymbol.MIC_OFF, onClick = onBlock)
                    ControlAction.PREPARE -> {
                        PrimaryAction("完成使用设置", symbol = BridgeSymbol.SETTINGS, onClick = onSettings)
                        Text(
                            when {
                                !runtimePermissionsGranted -> "请先允许状态通知，以便随时查看麦克风状态。"
                                !snapshot.batteryOptimizationExempt -> "请允许持续后台运行，避免保护服务被系统中断。"
                                else -> "首次设备验证需要允许测试到期自动屏蔽。"
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                    ControlAction.VERIFY -> PrimaryAction("验证屏蔽与恢复", symbol = BridgeSymbol.SHIELD, onClick = onCalibrate)
                    ControlAction.RESTORE -> PrimaryAction(if (restorePending) "正在请求恢复" else "恢复麦克风", enabled = !restorePending, symbol = BridgeSymbol.MIC, onClick = onRestore)
                }
            }
        }
        if (snapshot.lastError != null && !snapshot.transitioning) {
            if (snapshot.lastError.contains("未获得 Root 权限")) {
                Notice("需要 Root 授权", "请打开设备的 Root 管理器，允许 MicBridge 使用 Root，然后重新检查。当前还不能确认麦克风已屏蔽。", error = true, action = "已授权，重新检查", onAction = onStart)
            } else {
                Notice("有问题需要处理", "打开问题详情，查看原因并重新检查。", error = true, action = "查看问题", onAction = onDiagnose)
            }
        }
        SettingsGroup("更多操作") {
            SettingRow("用 iPhone 遥控", "可选：通过快捷指令切换屏蔽与恢复", BridgeSymbol.LINK, onClick = onConnect)
            SettingDivider()
            SettingRow("设备验证", if (snapshot.acousticCalibrationValid) "已完成这台设备的收音测试" else "首次使用时，确认屏蔽与恢复确实有效", BridgeSymbol.SHIELD, onClick = onCalibrate)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BridgeIcon(BridgeSymbol.SHIELD, Modifier.size(14.dp), colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("MicBridge 不录音，也不保存音频", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun LeaseCountdown(deadline: Long) {
    var now by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (now < deadline) { delay(500); now = System.currentTimeMillis() }
    }
    val seconds = ((deadline - now + 999L) / 1000L).coerceAtLeast(0L)
    val status = LocalBridgeStatusColors.current
    Surface(color = status.attentionContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BridgeIcon(BridgeSymbol.CLOCK, Modifier.size(18.dp), status.attention)
            Text(if (seconds > 0) "临时开放剩余 $seconds 秒" else "时间已到，正在确认重新屏蔽", style = MaterialTheme.typography.labelMedium, color = status.attention)
        }
    }
}
