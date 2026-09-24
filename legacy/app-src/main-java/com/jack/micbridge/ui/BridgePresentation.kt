package com.jack.micbridge.ui

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState

enum class StatusTone { SAFE, ATTENTION, ERROR, NEUTRAL }

data class MicPresentation(val title: String, val description: String, val tone: StatusTone, val symbol: BridgeSymbol)

fun presentMic(snapshot: BridgeSnapshot): MicPresentation = when {
    snapshot.transitioning -> MicPresentation("正在确认", "正在检查系统麦克风，请以确认后的状态为准。", StatusTone.NEUTRAL, BridgeSymbol.CLOCK)
    !snapshot.serviceRunning && snapshot.observedAtEpochMs == null && snapshot.lastError == null &&
        !snapshot.controlReadback && snapshot.micAccess == MicAccessState.UNKNOWN ->
        MicPresentation("尚未开启保护", "开启后先屏蔽系统麦克风，再确认结果。首次使用需要授予 Root 权限。", StatusTone.NEUTRAL, BridgeSymbol.SHIELD)
    !snapshot.controlReadback || snapshot.micAccess == MicAccessState.UNKNOWN ->
        MicPresentation("无法确认状态", "尚不能确认麦克风已被屏蔽。请重新屏蔽，或查看问题原因。", StatusTone.ERROR, BridgeSymbol.WARNING)
    snapshot.micAccess == MicAccessState.BLOCKED && !snapshot.serviceRunning ->
        MicPresentation("上次已屏蔽", "保护服务已停止。重新开启后会检查当前状态。", StatusTone.NEUTRAL, BridgeSymbol.MIC_OFF)
    snapshot.micAccess == MicAccessState.BLOCKED && !snapshot.acousticCalibrationValid ->
        MicPresentation("系统已屏蔽", "系统已确认屏蔽。首次恢复前，还需在这台设备上验证实际收音效果。", StatusTone.ATTENTION, BridgeSymbol.MIC_OFF)
    snapshot.micAccess == MicAccessState.BLOCKED ->
        MicPresentation("麦克风已屏蔽", "系统已确认屏蔽。恢复后，获准使用麦克风的应用可以重新收音。", StatusTone.SAFE, BridgeSymbol.MIC_OFF)
    snapshot.autoBlockAtEpochMs != null ->
        MicPresentation("测试中临时开放", "请在倒计时内完成收音检查，到期会重新屏蔽。", StatusTone.ATTENTION, BridgeSymbol.MIC)
    !snapshot.acousticCalibrationValid ->
        MicPresentation("麦克风仍可使用", "尚未完成设备验证。请立即屏蔽，再检查当前状态。", StatusTone.ERROR, BridgeSymbol.WARNING)
    else -> MicPresentation("麦克风可使用", "获准使用麦克风的应用可以收音。按下按钮即可统一屏蔽。", StatusTone.ATTENTION, BridgeSymbol.MIC)
}

enum class ControlAction { START, BLOCK, VERIFY, PREPARE, RESTORE }

/** UI guidance only. The service independently authorizes and verifies every command. */
fun nextControlAction(snapshot: BridgeSnapshot, permissionsGranted: Boolean, exactAlarmGranted: Boolean): ControlAction = when {
    !snapshot.serviceRunning -> ControlAction.START
    snapshot.transitioning || !snapshot.controlReadback || snapshot.micAccess != MicAccessState.BLOCKED -> ControlAction.BLOCK
    !permissionsGranted || !snapshot.batteryOptimizationExempt -> ControlAction.PREPARE
    !snapshot.acousticCalibrationValid && !exactAlarmGranted -> ControlAction.PREPARE
    !snapshot.acousticCalibrationValid -> ControlAction.VERIFY
    else -> ControlAction.RESTORE
}

/** These are presentation gates only. Every command is still verified by the service. */
fun canAdvanceCalibration(stage: CalibrationStage, snapshot: BridgeSnapshot, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
    if (!snapshot.serviceRunning || snapshot.transitioning) return false
    val leaseActive = snapshot.autoBlockAtEpochMs?.let { it > nowEpochMs } == true
    return when (stage) {
        CalibrationStage.IDLE -> snapshot.controlReadback && snapshot.micAccess == MicAccessState.BLOCKED &&
            snapshot.autoBlockAtEpochMs == null
        CalibrationStage.OPEN -> snapshot.controlReadback && snapshot.micAccess == MicAccessState.OPEN &&
            leaseActive && snapshot.leaseRootWatchdogArmed == true && snapshot.leaseExactAlarmArmed == true
        CalibrationStage.ISOLATED -> snapshot.micAccess == MicAccessState.UNKNOWN && leaseActive &&
            snapshot.leaseRootWatchdogArmed == true && snapshot.leaseExactAlarmArmed == true
        CalibrationStage.BLOCKED -> snapshot.controlReadback && snapshot.micAccess == MicAccessState.BLOCKED &&
            snapshot.autoBlockAtEpochMs == null
    }
}
