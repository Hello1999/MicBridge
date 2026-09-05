package com.jack.micbridge.service

import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateChangeSoundPlayerTest {
    @Test
    fun `verified blocked to open transition selects rising cue`() {
        assertEquals(
            StateChangeCue.OPENED,
            stateChangeCueFor(result(previous = MicAccessState.BLOCKED, current = MicAccessState.OPEN)),
        )
    }

    @Test
    fun `verified open to blocked transition selects falling cue`() {
        assertEquals(
            StateChangeCue.BLOCKED,
            stateChangeCueFor(result(previous = MicAccessState.OPEN, current = MicAccessState.BLOCKED)),
        )
    }

    @Test
    fun `failure replay and no-op never select a cue`() {
        assertNull(
            stateChangeCueFor(
                result(previous = MicAccessState.BLOCKED, current = MicAccessState.OPEN).copy(ok = false),
            ),
        )
        assertNull(
            stateChangeCueFor(
                result(previous = MicAccessState.BLOCKED, current = MicAccessState.OPEN)
                    .copy(replayed = true),
            ),
        )
        assertNull(
            stateChangeCueFor(result(previous = MicAccessState.OPEN, current = MicAccessState.OPEN)),
        )
        assertNull(
            stateChangeCueFor(
                result(previous = MicAccessState.UNKNOWN, current = MicAccessState.BLOCKED),
            ),
        )
    }

    @Test
    fun `stream target rounds to thirty percent and respects range`() {
        assertEquals(5, thirtyPercentStreamVolume(maxVolume = 15))
        assertEquals(8, thirtyPercentStreamVolume(maxVolume = 25))
        assertEquals(4, thirtyPercentStreamVolume(maxVolume = 10, minVolume = 4))
    }

    private fun result(
        previous: MicAccessState,
        current: MicAccessState,
    ) = OperationResult(
        ok = true,
        commandSucceeded = true,
        micAccess = current,
        previous = previous,
        controlReadback = true,
        acousticCalibrationValid = true,
        controllerId = "test",
        requestId = "request-12345678",
        autoBlockAtEpochMs = null,
        latencyMs = 1L,
    )
}
