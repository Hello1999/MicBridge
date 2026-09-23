package com.jack.micbridge.ui

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState
import org.junit.Assert.*
import org.junit.Test

class BridgePresentationTest {
    private val verified = BridgeSnapshot(micAccess = MicAccessState.BLOCKED, controlReadback = true, acousticCalibrationValid = true, serviceRunning = true, batteryOptimizationExempt = true)

    @Test fun `stored calibration cannot turn unreadable or unknown state into safe UI`() {
        assertEquals(StatusTone.ERROR, presentMic(verified.copy(controlReadback = false)).tone)
        assertEquals(StatusTone.ERROR, presentMic(verified.copy(micAccess = MicAccessState.UNKNOWN)).tone)
        assertEquals(StatusTone.ERROR, presentMic(verified.copy(serviceRunning = false, controlReadback = false)).tone)
    }

    @Test fun `transition does not retain the previous successful title`() {
        assertEquals("正在确认", presentMic(verified.copy(transitioning = true)).title)
        assertNotEquals(StatusTone.SAFE, presentMic(verified.copy(transitioning = true)).tone)
    }

    @Test fun `uncalibrated blocked and temporary open remain distinct from regular operation`() {
        assertEquals(StatusTone.ATTENTION, presentMic(verified.copy(acousticCalibrationValid = false)).tone)
        assertEquals("测试中临时开放", presentMic(verified.copy(micAccess = MicAccessState.OPEN, autoBlockAtEpochMs = 20_000)).title)
        assertEquals("麦克风可使用", presentMic(verified.copy(micAccess = MicAccessState.OPEN)).title)
        val unguarded = presentMic(verified.copy(micAccess = MicAccessState.OPEN, acousticCalibrationValid = false))
        assertEquals("麦克风仍可使用", unguarded.title)
        assertEquals(StatusTone.ERROR, unguarded.tone)
    }

    @Test fun `first launch explains setup without claiming blocked`() {
        val state = presentMic(BridgeSnapshot())
        assertEquals("尚未开启保护", state.title)
        assertEquals(StatusTone.NEUTRAL, state.tone)
        assertNotEquals(StatusTone.SAFE, state.tone)
    }

    @Test fun `stopped service only describes last observed blocking`() {
        val state = presentMic(verified.copy(serviceRunning = false))
        assertEquals("上次已屏蔽", state.title)
        assertNotEquals(StatusTone.SAFE, state.tone)
    }

    @Test fun `daily restore requires live blocked state and completed preparation`() {
        assertEquals(ControlAction.START, nextControlAction(BridgeSnapshot(), true, true))
        assertEquals(ControlAction.BLOCK, nextControlAction(verified.copy(micAccess = MicAccessState.OPEN), true, true))
        assertEquals(ControlAction.BLOCK, nextControlAction(verified.copy(controlReadback = false), true, true))
        assertEquals(ControlAction.BLOCK, nextControlAction(verified.copy(transitioning = true), true, true))
        assertEquals(ControlAction.PREPARE, nextControlAction(verified, false, true))
        assertEquals(ControlAction.RESTORE, nextControlAction(verified, true, false))
        assertEquals(ControlAction.PREPARE, nextControlAction(verified.copy(acousticCalibrationValid = false), true, false))
        assertEquals(ControlAction.PREPARE, nextControlAction(verified.copy(batteryOptimizationExempt = false), true, true))
        assertEquals(ControlAction.VERIFY, nextControlAction(verified.copy(acousticCalibrationValid = false), true, true))
        // Local microphone use does not require an iPhone or a listening network address.
        assertEquals(ControlAction.RESTORE, nextControlAction(verified.copy(serverAddresses = emptyList()), true, true))
    }

    @Test fun `verification cannot begin from unreadable or already open state`() {
        assertTrue(canAdvanceCalibration(CalibrationStage.IDLE, verified))
        assertFalse(canAdvanceCalibration(CalibrationStage.IDLE, verified.copy(controlReadback = false)))
        assertFalse(canAdvanceCalibration(CalibrationStage.IDLE, verified.copy(micAccess = MicAccessState.OPEN)))
        assertFalse(canAdvanceCalibration(CalibrationStage.IDLE, verified.copy(autoBlockAtEpochMs = Long.MAX_VALUE)))
    }

    @Test fun `calibration steps require both service stage and compatible readback`() {
        val guardedOpen = verified.copy(micAccess = MicAccessState.OPEN, autoBlockAtEpochMs = Long.MAX_VALUE, leaseRootWatchdogArmed = true, leaseExactAlarmArmed = true)
        assertTrue(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen))
        assertFalse(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen.copy(leaseRootWatchdogArmed = false)))
        assertFalse(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen.copy(controlReadback = false)))
        assertFalse(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen.copy(serviceRunning = false)))
        assertFalse(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen.copy(micAccess = MicAccessState.UNKNOWN, lastError = "隔离校准模式")))
        assertFalse(canAdvanceCalibration(CalibrationStage.BLOCKED, verified.copy(autoBlockAtEpochMs = 20_000)))
        assertFalse(canAdvanceCalibration(CalibrationStage.BLOCKED, verified.copy(transitioning = true)))
        assertFalse(canAdvanceCalibration(CalibrationStage.OPEN, guardedOpen.copy(autoBlockAtEpochMs = 20_000), nowEpochMs = 20_000))
        assertFalse(canAdvanceCalibration(CalibrationStage.ISOLATED, guardedOpen.copy(micAccess = MicAccessState.UNKNOWN, leaseRootWatchdogArmed = false)))
    }
}
