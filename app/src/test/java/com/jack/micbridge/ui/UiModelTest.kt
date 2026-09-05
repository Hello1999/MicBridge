package com.jack.micbridge.ui

import com.jack.micbridge.data.AuditEntry
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ReadinessSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
    private val healthy = BridgeSnapshot(
        micAccess = MicAccessState.BLOCKED,
        controlReadback = true,
        acousticCalibrationValid = true,
        serviceRunning = true,
        batteryOptimizationExempt = true,
        serverAddresses = listOf("192.168.43.1:8787"),
        calibrationInvalidReason = null,
        readiness = ReadinessSnapshot(exactAlarmPermitted = true),
    )

    @Test
    fun `shortcut line reports the first failing admission gate`() {
        assertTrue(shortcutAvailability(healthy).second)
        assertEquals(
            "快捷指令可用：http://192.168.43.1:8787/v1/mic/toggle",
            shortcutAvailability(healthy).first,
        )
        assertFalse(shortcutAvailability(healthy.copy(serviceRunning = false)).second)
        assertFalse(shortcutAvailability(healthy.copy(serverAddresses = emptyList())).second)
        val uncalibrated = shortcutAvailability(healthy.copy(calibrationInvalidReason = "ChatGPT 已更新"))
        assertFalse(uncalibrated.second)
        assertTrue(uncalibrated.first.contains("ChatGPT 已更新"))
        assertTrue(uncalibrated.first.contains("3 次振动"))
        assertFalse(shortcutAvailability(healthy.copy(batteryOptimizationExempt = false)).second)
    }

    @Test
    fun `hero title collapses to three user facing states plus transitions`() {
        assertEquals("已静音", heroModel(healthy).title)
        assertEquals(
            "已开放（ChatGPT 可收音）",
            heroModel(healthy.copy(micAccess = MicAccessState.OPEN)).title,
        )
        assertEquals("状态无法确认", heroModel(healthy.copy(controlReadback = false)).title)
        assertEquals("状态无法确认", heroModel(healthy.copy(micAccess = MicAccessState.UNKNOWN)).title)
        assertEquals("切换中…", heroModel(healthy.copy(transitioning = true)).title)
        assertEquals(
            "校准中：仅 Root 屏蔽",
            heroModel(
                healthy.copy(
                    micAccess = MicAccessState.UNKNOWN,
                    controlReadback = false,
                    calibrationIsolationActive = true,
                ),
            ).title,
        )
    }

    @Test
    fun `calibration step follows published state not error text`() {
        val none = CalibrationChecks(false, false, false)
        assertEquals(CalibrationStep.OPEN, currentCalibrationStep(healthy, none))
        val tempOpen = healthy.copy(
            micAccess = MicAccessState.OPEN,
            autoBlockAtEpochMs = 123L,
            leaseRootWatchdogArmed = true,
        )
        assertEquals(CalibrationStep.ISOLATE, currentCalibrationStep(tempOpen, none))
        val isolated = healthy.copy(
            micAccess = MicAccessState.UNKNOWN,
            controlReadback = false,
            calibrationIsolationActive = true,
            lastError = "任意文案",
        )
        assertEquals(CalibrationStep.CONFIRM_BLOCK, currentCalibrationStep(isolated, none))
        assertEquals(
            CalibrationStep.COMMIT,
            currentCalibrationStep(healthy, CalibrationChecks(true, false, false)),
        )
        // A persistent remote OPEN (no deadline) is not a calibration round.
        val persistentOpen = healthy.copy(micAccess = MicAccessState.OPEN, leaseRootWatchdogArmed = true)
        assertEquals(CalibrationStep.OPEN, currentCalibrationStep(persistentOpen, none))
    }

    @Test
    fun `latency summary uses only successful toggles`() {
        fun entry(source: String, latency: Long, verified: Boolean = true, error: String? = null) =
            AuditEntry(0L, source, null, MicAccessState.OPEN, verified, "audio_manager", latency, error, null)
        assertNull(toggleLatencySummary(emptyList()))
        assertNull(toggleLatencySummary(listOf(entry("root-sensor-privacy", 5))))
        val summary = toggleLatencySummary(
            listOf(
                entry("/v1/mic/toggle", 300),
                entry("local-ui", 100),
                entry("/v1/mic/toggle", 900),
                entry("/v1/mic/toggle", 50, verified = false),
                entry("/v1/mic/toggle", 5_000, error = "ROOT_TIMEOUT"),
            ),
        )!!
        assertEquals(3, summary.samples)
        assertEquals(300L, summary.p50Ms)
        assertEquals(900L, summary.maxMs)
    }
}
