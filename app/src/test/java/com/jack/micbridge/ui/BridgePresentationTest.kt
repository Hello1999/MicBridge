package com.jack.micbridge.ui

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState
import org.junit.Assert.*
import org.junit.Test

class BridgePresentationTest {
    private val verified = BridgeSnapshot(micAccess = MicAccessState.BLOCKED, controlReadback = true, acousticCalibrationValid = true, serviceRunning = true)

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
        assertEquals("临时开放", presentMic(verified.copy(micAccess = MicAccessState.OPEN, autoBlockAtEpochMs = 20_000)).title)
        assertEquals("已开放", presentMic(verified.copy(micAccess = MicAccessState.OPEN)).title)
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
