package com.jack.micbridge.service

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRuntimeTest {
    @Test
    fun `verified shutdown publishes blocked state and clears lease diagnostics`() {
        ServiceRuntime.publish(
            BridgeSnapshot(
                micAccess = MicAccessState.OPEN,
                controlReadback = true,
                serviceRunning = true,
                serverAddresses = listOf("192.168.43.1:8787"),
                autoBlockAtEpochMs = 123L,
                leaseExactAlarmArmed = true,
                leaseRootWatchdogArmed = true,
                lastError = "old",
            ),
        )

        ServiceRuntime.markStopped()

        val stopped = ServiceRuntime.snapshot.value
        assertEquals(MicAccessState.BLOCKED, stopped.micAccess)
        assertTrue(stopped.controlReadback)
        assertFalse(stopped.serviceRunning)
        assertTrue(stopped.serverAddresses.isEmpty())
        assertNull(stopped.autoBlockAtEpochMs)
        assertNull(stopped.leaseExactAlarmArmed)
        assertNull(stopped.leaseRootWatchdogArmed)
        assertNull(stopped.lastError)
    }
}
