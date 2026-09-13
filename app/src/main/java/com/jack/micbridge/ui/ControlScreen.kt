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
fun ControlScreen(snapshot: BridgeSnapshot, onStart: () -> Unit, onBlock: () -> Unit, onConnect: () -> Unit, onCalibrate: () -> Unit, onDiagnose: () -> Unit) {
    val state = presentMic(snapshot)
    val colors = MaterialTheme.colorScheme
    val statusColors = LocalBridgeStatusColors.current
    val (accent, fill) = when (state.tone) {
        StatusTone.SAFE -> statusColors.safe to statusColors.safeContainer
        StatusTone.ATTENTION -> statusColors.attention to statusColors.attentionContainer
        StatusTone.ERROR -> colors.error to colors.errorContainer
        StatusTone.NEUTRAL -> colors.primary to colors.primaryContainer
    }
    BridgePage {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("麦克风访问", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Box(Modifier.size(48.dp).background(fill, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                        BridgeIcon(state.symbol, Modifier.size(25.dp), accent)
                    }
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.headlineLarge)
                    Text(state.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                SettingDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine("控制读回", if (snapshot.controlReadback) "已确认" else "待确认", snapshot.controlReadback)
                    StatusLine("声学校准", if (snapshot.acousticCalibrationValid) "有效" else "未完成或已失效", snapshot.acousticCalibrationValid)
                }
                snapshot.autoBlockAtEpochMs?.let { LeaseCountdown(it) }
                if (!snapshot.serviceRunning) {
                    PrimaryAction("启动服务", symbol = BridgeSymbol.POWER, onClick = onStart)
                } else {
                    // BLOCK remains reachable during a transition; a visual busy state must
                    // never remove the user's emergency blocking action.
                    PrimaryAction("立即屏蔽", symbol = BridgeSymbol.MIC_OFF, onClick = onBlock)
                }
            }
        }
        if (snapshot.lastError != null && !snapshot.transitioning) {
            Notice("需要检查", "服务尚有未解决的问题，打开诊断查看原因与处理入口。", error = true, action = "查看诊断", onAction = onDiagnose)
        }
        SettingsGroup("连接与准备") {
            SettingRow("iPhone 快捷指令", if (snapshot.serverAddresses.isEmpty()) "尚无可用的局域网请求地址" else "本地服务已监听；iPhone 连通性需实际确认", BridgeSymbol.LINK, onClick = onConnect)
            SettingDivider()
            SettingRow("声学校准", if (snapshot.acousticCalibrationValid) "当前环境的校准有效" else "验证屏蔽与恢复收音", BridgeSymbol.SHIELD, onClick = onCalibrate)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BridgeIcon(BridgeSymbol.SHIELD, Modifier.size(14.dp), colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("不录音，不保存音频", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusLine(label: String, value: String, verified: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BridgeIcon(if (verified) BridgeSymbol.CHECK else BridgeSymbol.INFO, Modifier.size(16.dp), if (verified) LocalBridgeStatusColors.current.safe else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
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
            Text(if (seconds > 0) "临时开放剩余 $seconds 秒" else "时限已到，等待屏蔽读回", style = MaterialTheme.typography.labelMedium, color = status.attention)
        }
    }
}
