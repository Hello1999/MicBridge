package com.jack.micbridge.ui

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState

enum class StatusTone { SAFE, ATTENTION, ERROR, NEUTRAL }

data class MicPresentation(val title: String, val description: String, val tone: StatusTone, val symbol: BridgeSymbol)

fun presentMic(snapshot: BridgeSnapshot): MicPresentation = when {
    snapshot.transitioning -> MicPresentation("正在确认", "等待控制器读回后更新状态。", StatusTone.NEUTRAL, BridgeSymbol.CLOCK)
    !snapshot.controlReadback || snapshot.micAccess == MicAccessState.UNKNOWN ->
        MicPresentation("状态待确认", "当前无法确认麦克风访问状态。请查看诊断，确认原因后重试屏蔽。", StatusTone.ERROR, BridgeSymbol.WARNING)
    snapshot.micAccess == MicAccessState.BLOCKED && !snapshot.serviceRunning ->
        MicPresentation("已屏蔽", "服务已停止。启动时会先屏蔽并重新确认状态。", StatusTone.SAFE, BridgeSymbol.MIC_OFF)
    snapshot.micAccess == MicAccessState.BLOCKED && !snapshot.acousticCalibrationValid ->
        MicPresentation("已屏蔽，待校准", "控制层已读回屏蔽状态。完成声学校准后，才能远程开放。", StatusTone.ATTENTION, BridgeSymbol.MIC_OFF)
    snapshot.micAccess == MicAccessState.BLOCKED ->
        MicPresentation("已屏蔽", "麦克风访问已屏蔽。使用 iPhone 快捷指令切换。", StatusTone.SAFE, BridgeSymbol.MIC_OFF)
    snapshot.autoBlockAtEpochMs != null || !snapshot.acousticCalibrationValid ->
        MicPresentation("临时开放", "正在进行校准测试。请在时限内完成检查。", StatusTone.ATTENTION, BridgeSymbol.MIC)
    else -> MicPresentation("已开放", "再次按下 iPhone Action Button，或在这里立即屏蔽。", StatusTone.ATTENTION, BridgeSymbol.MIC)
}

/** These are presentation gates only. Every command is still verified by the service. */
fun canAdvanceCalibration(stage: CalibrationStage, snapshot: BridgeSnapshot, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
    if (!snapshot.serviceRunning || snapshot.transitioning) return false
    val leaseActive = snapshot.autoBlockAtEpochMs?.let { it > nowEpochMs } == true
    return when (stage) {
        CalibrationStage.IDLE -> true
        CalibrationStage.OPEN -> snapshot.controlReadback && snapshot.micAccess == MicAccessState.OPEN &&
            leaseActive && snapshot.leaseRootWatchdogArmed == true && snapshot.leaseExactAlarmArmed == true
        CalibrationStage.ISOLATED -> snapshot.micAccess == MicAccessState.UNKNOWN && leaseActive &&
            snapshot.leaseRootWatchdogArmed == true && snapshot.leaseExactAlarmArmed == true
        CalibrationStage.BLOCKED -> snapshot.controlReadback && snapshot.micAccess == MicAccessState.BLOCKED &&
            snapshot.autoBlockAtEpochMs == null
    }
}
