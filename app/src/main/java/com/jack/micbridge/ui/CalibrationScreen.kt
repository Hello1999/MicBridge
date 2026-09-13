package com.jack.micbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationProgress
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.service.BridgeForegroundService

@Composable
fun CalibrationScreen(
    snapshot: BridgeSnapshot, progress: CalibrationProgress, targetPackage: String,
    dispatchPending: Boolean, leaving: Boolean, onCommand: (String) -> Unit, onStart: () -> Unit, onBlock: () -> Unit,
) {
    // Human evidence is deliberately not saveable or shared between stages/identities.
    // A rotation or a fresh round must never restore unchecked acoustic claims as proof.
    var heardOpen by remember(progress.stage, targetPackage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var heardIsolation by remember(progress.stage, targetPackage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var checkedLocked by remember(progress.stage, targetPackage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var checkedRepeated by remember(progress.stage, targetPackage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    val busy = progress.working || dispatchPending || leaving || snapshot.transitioning
    val available = canAdvanceCalibration(progress.stage, snapshot) && !busy
    val step = progress.stage.ordinal
    val titles = listOf("临时开放", "验证恢复收音", "验证隔离屏蔽", "确认校准结果")
    val description = when (progress.stage) {
        CalibrationStage.IDLE -> "在 ChatGPT 中开启 Voice / Live，保持同一条语音会话。开始后，请在倒计时内完成测试。"
        CalibrationStage.OPEN -> "对 ChatGPT 说一句测试短语，确认当前会话已经恢复收音。下一步将仅使用 Root 门控进行屏蔽。"
        CalibrationStage.ISOLATED -> "AudioManager 保持开放，只有 Root 门控负责屏蔽。请确认此时 ChatGPT 确实听不到测试短语。"
        CalibrationStage.BLOCKED -> "已完成隔离确认与完整屏蔽。请确认以下真机检查也已实际完成，再提交本轮校准。"
    }
    BridgePage {
        if (snapshot.acousticCalibrationValid && progress.stage == CalibrationStage.IDLE && !busy) {
            Notice("当前校准有效", "开始新一轮测试会使旧校准失效。系统、控制器或目标应用变化后，需要重新校准。")
        }
        if (!snapshot.serviceRunning) {
            Notice("请先启动服务", "校准需要在同一次服务会话中完成。")
            PrimaryAction("启动服务", onClick = onStart)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index -> Box(Modifier.weight(1f).height(3.dp).background(if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("第 ${step + 1} 步，共 4 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(titles[step], style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                snapshot.autoBlockAtEpochMs?.let { LeaseCountdown(it) }
                when (progress.stage) {
                    CalibrationStage.IDLE -> Unit
                    CalibrationStage.OPEN -> EvidenceCheck("当前会话已恢复收音", heardOpen, available) { heardOpen = it }
                    CalibrationStage.ISOLATED -> EvidenceCheck("隔离状态下，ChatGPT 确实听不到测试短语", heardIsolation, available) { heardIsolation = it }
                    CalibrationStage.BLOCKED -> {
                        EvidenceCheck("已验证屏蔽后，在锁屏和熄屏状态下都听不到测试短语", checkedLocked, available) { checkedLocked = it }
                        SettingDivider()
                        EvidenceCheck("开放后无需重建会话即可恢复收音，且已连续验证至少 20 次", checkedRepeated, available) { checkedRepeated = it }
                    }
                }
                val (actionLabel, command, acknowledged) = when (progress.stage) {
                    CalibrationStage.IDLE -> Triple("临时开放并开始", BridgeForegroundService.ACTION_CALIBRATION_OPEN, true)
                    CalibrationStage.OPEN -> Triple("仅用 Root 屏蔽", BridgeForegroundService.ACTION_CALIBRATION_ROOT_ISOLATION, heardOpen)
                    CalibrationStage.ISOLATED -> Triple("确认无收音并完整屏蔽", BridgeForegroundService.ACTION_CALIBRATION_CONFIRM_AND_BLOCK, heardIsolation)
                    CalibrationStage.BLOCKED -> Triple("记录校准通过", BridgeForegroundService.ACTION_COMPLETE_CALIBRATION, checkedLocked && checkedRepeated)
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                PrimaryAction(if (busy) "正在确认，请稍候" else actionLabel, available && acknowledged) { onCommand(command) }
                if (!busy && snapshot.serviceRunning && !canAdvanceCalibration(progress.stage, snapshot)) {
                    Text("当前读回不符合本步要求。请先完整屏蔽，再开始新一轮校准。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (snapshot.lastError != null && progress.stage != CalibrationStage.ISOLATED) {
            Notice("当前服务信息", snapshot.lastError, error = true)
        }
        SecondaryAction(if (leaving) "正在完整屏蔽后返回" else "立即屏蔽并重置本轮", snapshot.serviceRunning && !leaving, onBlock)
        Text("每个步骤都以服务实际结果为准。校准仅记录人工验证，MicBridge 本身不会监听声音。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
