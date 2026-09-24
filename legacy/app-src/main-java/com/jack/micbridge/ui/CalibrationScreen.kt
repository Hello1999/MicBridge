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
    snapshot: BridgeSnapshot, progress: CalibrationProgress,
    dispatchPending: Boolean, leaving: Boolean, preparationReady: Boolean, onSettings: () -> Unit, onCommand: (String) -> Unit, onStart: () -> Unit, onBlock: () -> Unit,
) {
    // Human evidence is deliberately not saveable or shared between stages/identities.
    // A rotation or a fresh round must never restore unchecked acoustic claims as proof.
    var heardOpen by remember(progress.stage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var heardIsolation by remember(progress.stage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var checkedLocked by remember(progress.stage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    var checkedResumed by remember(progress.stage, snapshot.controllerId, snapshot.serviceRunning) { mutableStateOf(false) }
    val busy = progress.working || dispatchPending || leaving || snapshot.transitioning
    val available = canAdvanceCalibration(progress.stage, snapshot) && !busy &&
        (progress.stage != CalibrationStage.IDLE || preparationReady)
    val step = progress.stage.ordinal
    val titles = listOf("准备测试", "确认可以收音", "确认已经屏蔽", "完成设备验证")
    val description = when (progress.stage) {
        CalibrationStage.IDLE -> "打开任意已有麦克风权限的录音或语音应用，并保持它正在收音。开始测试后，切回该应用说一句测试短语，再回来确认结果。"
        CalibrationStage.OPEN -> "切回刚才的应用说一句测试短语，确认能录到声音或识别到说话。不要结束这次录音或语音会话，下一步将测试屏蔽。"
        CalibrationStage.ISOLATED -> "保持刚才的录音或语音会话，分别在亮屏、锁屏和熄屏时说话，确认都无法收到测试短语，再回到这里确认。这一步检查后台保护。"
        CalibrationStage.BLOCKED -> "系统已恢复完整屏蔽。只有以下检查都已在这台真实设备上完成，才能记录验证通过。"
    }
    BridgePage {
        if (snapshot.acousticCalibrationValid && progress.stage == CalibrationStage.IDLE && !busy) {
            Notice("这台设备已通过验证", "开始新的测试会清除上次结果。系统、MicBridge 或控制方案变化后，需要重新验证。")
        }
        if (!snapshot.serviceRunning) {
            Notice("先开启麦克风保护", "开启后会先屏蔽麦克风，再开始测试。")
            PrimaryAction("开启麦克风屏蔽", onClick = onStart)
        }
        if (snapshot.serviceRunning && progress.stage == CalibrationStage.IDLE && !busy) {
            if (!snapshot.controlReadback || snapshot.micAccess == com.jack.micbridge.data.MicAccessState.UNKNOWN) {
                Notice("暂时无法开始测试", "还未确认系统麦克风状态。请在 Root 管理器中允许 MicBridge，回到这里后先按下“屏蔽并重置测试”。仍失败时，请到设置查看问题记录。", error = true)
            } else if (!preparationReady) {
                Notice("先完成使用设置", "请允许状态通知、测试到期自动屏蔽和持续后台运行，再返回开始测试。", action = "前往设置", onAction = onSettings)
            }
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
                    CalibrationStage.OPEN -> EvidenceCheck("刚才的应用已经能收到测试短语", heardOpen, available) { heardOpen = it }
                    CalibrationStage.ISOLATED -> EvidenceCheck("刚才的应用已收不到测试短语", heardIsolation, available) { heardIsolation = it }
                    CalibrationStage.BLOCKED -> {
                        EvidenceCheck("已在锁屏和熄屏时测试，屏蔽后均收不到声音", checkedLocked, available) { checkedLocked = it }
                        SettingDivider()
                        EvidenceCheck("已确认恢复后，原录音或语音会话可以继续收音", checkedResumed, available) { checkedResumed = it }
                    }
                }
                val (actionLabel, command, acknowledged) = when (progress.stage) {
                    CalibrationStage.IDLE -> Triple("开始测试，临时恢复麦克风", BridgeForegroundService.ACTION_CALIBRATION_OPEN, true)
                    CalibrationStage.OPEN -> Triple("下一步：测试屏蔽", BridgeForegroundService.ACTION_CALIBRATION_ROOT_ISOLATION, heardOpen)
                    CalibrationStage.ISOLATED -> Triple("确认无声，恢复完整屏蔽", BridgeForegroundService.ACTION_CALIBRATION_CONFIRM_AND_BLOCK, heardIsolation)
                    CalibrationStage.BLOCKED -> Triple("完成验证", BridgeForegroundService.ACTION_COMPLETE_CALIBRATION, checkedLocked && checkedResumed)
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                PrimaryAction(if (busy) "正在确认，请稍候" else actionLabel, available && acknowledged) { onCommand(command) }
                if (!busy && snapshot.serviceRunning && !canAdvanceCalibration(progress.stage, snapshot)) {
                    Text("这一步尚未得到系统确认。请先屏蔽并重置，再重新测试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (snapshot.lastError != null && progress.stage != CalibrationStage.ISOLATED) {
            Notice("测试尚未完成", "服务未能完成当前操作。请先屏蔽并重置；具体原因可在设置中的问题记录查看。", error = true)
        }
        SecondaryAction(if (leaving) "正在屏蔽后返回" else "屏蔽并重置测试", snapshot.serviceRunning && !leaving, onBlock)
        Text("请只勾选实际完成的检查。MicBridge 不会监听声音，验证结果来自你的实际测试。离开此页前会先尝试恢复屏蔽。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
