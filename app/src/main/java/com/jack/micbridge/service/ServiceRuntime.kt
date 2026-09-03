package com.jack.micbridge.service

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceRuntime {
    private val mutableSnapshot = MutableStateFlow(BridgeSnapshot())
    val snapshot: StateFlow<BridgeSnapshot> = mutableSnapshot.asStateFlow()

    fun publish(value: BridgeSnapshot) {
        mutableSnapshot.value = value
    }

    /** Called only after the shutdown path freshly verifies BLOCKED and clears every lease. */
    fun markStopped() {
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
