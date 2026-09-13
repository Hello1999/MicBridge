package com.jack.micbridge.service

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.CalibrationProgress
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ServiceRuntime {
    private val mutableSnapshot = MutableStateFlow(BridgeSnapshot())
    val snapshot: StateFlow<BridgeSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableCalibration = MutableStateFlow(CalibrationProgress())
    val calibration: StateFlow<CalibrationProgress> = mutableCalibration.asStateFlow()

    internal fun publishCalibrationStage(stage: CalibrationStage) {
        mutableCalibration.update { it.copy(stage = stage) }
    }

    internal fun publishCalibrationWorking(working: Boolean) {
        mutableCalibration.update { it.copy(working = working) }
    }

    internal fun resetCalibrationProgress() {
        mutableCalibration.value = CalibrationProgress()
    }

    fun publish(value: BridgeSnapshot) {
        mutableSnapshot.value = value
    }

    /** Called only after the shutdown path freshly verifies BLOCKED and clears every lease. */
    fun markStopped() {
        resetCalibrationProgress()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            micAccess = MicAccessState.BLOCKED,
            transitioning = false,
            controlReadback = true,
            serviceRunning = false,
            serverAddresses = emptyList(),
            autoBlockAtEpochMs = null,
            leaseExactAlarmArmed = null,
            leaseRootWatchdogArmed = null,
            lastError = null,
            observedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
