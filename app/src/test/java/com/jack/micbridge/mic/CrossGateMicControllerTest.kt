package com.jack.micbridge.mic

import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossGateMicControllerTest {
    private val primaryTarget = SafetyTarget("audio_manager", "android-global-microphone", 0)
    private val gateTarget = SafetyTarget("root_sensor_privacy", "android-global-microphone", 0)

    @Test
    fun `blocked secondary gate is opened under the same authorization before primary`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.BLOCKED, primaryTarget)
        val secondary = FakeController("root_sensor_privacy", MicAccessState.BLOCKED, gateTarget)
        val controller = wrapped(primary, secondary)

        val result = controller.open(primaryTarget, authorization())

        assertTrue(result.controlReadback)
        assertEquals(null, result.errorCode)
        assertEquals(MicAccessState.OPEN, result.observed)
        assertEquals(MicAccessState.OPEN, primary.state)
        assertEquals(1, primary.openCount)
        assertEquals(0, primary.blockCount)
        assertEquals(1, secondary.openCount)
    }

    @Test
    fun `OPEN is reported only when both gates read OPEN`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.BLOCKED, primaryTarget)
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget)

        val result = wrapped(primary, secondary).open(primaryTarget, authorization())

        assertTrue(result.controlReadback)
        assertEquals(MicAccessState.OPEN, result.observed)
        assertEquals(0, primary.blockCount)
    }

    @Test
    fun `fresh read treats a mixed gate state as unknown`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.OPEN, primaryTarget)
        val secondary = FakeController("root_sensor_privacy", MicAccessState.BLOCKED, gateTarget)

        assertEquals(MicAccessState.UNKNOWN, wrapped(primary, secondary).readState(primaryTarget))
    }

    @Test
    fun `failed primary block actively blocks the safety gate`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.OPEN, primaryTarget).apply {
            blockResult = MicAccessState.OPEN
        }
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget)

        val result = wrapped(primary, secondary).block(primaryTarget)

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
        assertEquals(1, secondary.blockCount)
        assertEquals(MicAccessState.BLOCKED, secondary.state)
        assertEquals("REDUNDANT_GATE_BLOCK_UNVERIFIED", result.errorCode)
    }

    @Test
    fun `successful primary block still closes an open safety gate`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.OPEN, primaryTarget)
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget)

        val result = wrapped(primary, secondary).block(primaryTarget)

        assertTrue(result.controlReadback)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, primary.blockCount)
        assertEquals(1, secondary.blockCount)
        assertEquals(MicAccessState.BLOCKED, primary.state)
        assertEquals(MicAccessState.BLOCKED, secondary.state)
    }

    @Test
    fun `confirmed primary block is not enough when redundant gate fails`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.OPEN, primaryTarget)
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget).apply {
            blockResult = MicAccessState.OPEN
            blockErrorCode = "SECONDARY_FAILED"
            blockErrorMessage = "secondary did not block"
        }

        val result = wrapped(primary, secondary).block(primaryTarget)

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
        assertEquals(1, secondary.blockCount)
        assertEquals("REDUNDANT_GATE_BLOCK_UNVERIFIED", result.errorCode)
    }

    @Test
    fun `already blocked safety gate cannot mask a primary block failure`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.OPEN, primaryTarget).apply {
            blockResult = MicAccessState.OPEN
            blockErrorCode = "PRIMARY_FAILED"
            blockErrorMessage = "primary did not block"
        }
        val secondary = FakeController("root_sensor_privacy", MicAccessState.BLOCKED, gateTarget)

        val result = wrapped(primary, secondary).block(primaryTarget)

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
        assertEquals("REDUNDANT_GATE_BLOCK_UNVERIFIED", result.errorCode)
        assertEquals(0, secondary.blockCount)
    }

    @Test
    fun `primary OPEN failure closes both gates even when secondary started open`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.BLOCKED, primaryTarget).apply {
            openResult = MicAccessState.BLOCKED
        }
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget)

        val result = wrapped(primary, secondary).open(primaryTarget, authorization())

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, primary.blockCount)
        assertEquals(1, secondary.blockCount)
    }

    @Test
    fun `failed OPEN rollback reports unknown unless both gates block`() = runTest {
        val primary = FakeController("audio_manager", MicAccessState.BLOCKED, primaryTarget).apply {
            openResult = MicAccessState.BLOCKED
        }
        val secondary = FakeController("root_sensor_privacy", MicAccessState.OPEN, gateTarget).apply {
            blockResult = MicAccessState.OPEN
        }

        val result = wrapped(primary, secondary).open(primaryTarget, authorization())

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
    }

    private fun wrapped(primary: MicController, secondary: MicController) = CrossGateMicController(
        primary,
        secondary,
        gateTarget = { gateTarget.copy(userId = it.userId) },
    )

    private class FakeController(
        override val id: String,
        var state: MicAccessState,
        private val target: SafetyTarget,
    ) : MicController {
        override val displayName = id
        var openCount = 0
        var blockCount = 0
        var blockResult = MicAccessState.BLOCKED
        var openResult = MicAccessState.OPEN
        var blockErrorCode: String? = null
        var blockErrorMessage: String? = null

        override suspend fun probe() = ProbeResult(true, true)
        override suspend fun captureSafetyTarget() = target
        override suspend fun readState(target: SafetyTarget?) = state
        override suspend fun block(target: SafetyTarget?): ControlResult {
            blockCount++
            state = blockResult
            return result(MicAccessState.BLOCKED).copy(
                errorCode = blockErrorCode,
                errorMessage = blockErrorMessage,
            )
        }

        override suspend fun open(
            target: SafetyTarget,
            authorization: OpenAuthorization,
        ): ControlResult {
            openCount++
            state = openResult
            return result(MicAccessState.OPEN)
        }

        private fun result(requested: MicAccessState) = ControlResult(
            requested,
            state,
            requested == state,
            1L,
        )
    }

    private fun authorization() = OpenAuthorization("request-crossgate-01", 30_000L)
}
