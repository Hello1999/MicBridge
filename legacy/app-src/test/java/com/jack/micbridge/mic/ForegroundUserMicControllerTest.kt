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

class ForegroundUserMicControllerTest {
    private val target = SafetyTarget("audio_manager", "android-global-microphone", 0)
    private val authorization = OpenAuthorization("request-12345678", Long.MAX_VALUE)

    @Test
    fun `foreground user can open system microphone without an application package query`() = runTest {
        val primary = FakeController()
        val users = mutableListOf<Int>()
        val controller = ForegroundUserMicController(primary) { users += it; true }
        val result = controller.open(target, authorization)
        assertTrue(result.controlReadback)
        assertEquals(MicAccessState.OPEN, result.observed)
        assertEquals(listOf(0, 0), users)
        assertEquals(1, primary.openCount)
        assertEquals(0, primary.blockCount)
    }

    @Test
    fun `unconfirmed foreground user refuses open and blocks`() = runTest {
        val primary = FakeController()
        val result = ForegroundUserMicController(primary) { false }.open(target, authorization)
        assertFalse(result.controlReadback)
        assertEquals("FOREGROUND_USER_CHANGED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(0, primary.openCount)
        assertEquals(1, primary.blockCount)
    }

    @Test
    fun `user switch during open rolls back both system gates through the primary`() = runTest {
        val primary = FakeController()
        var reads = 0
        val result = ForegroundUserMicController(primary) { ++reads == 1 }.open(target, authorization)
        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, primary.openCount)
        assertEquals(1, primary.blockCount)
    }

    @Test
    fun `foreground check failure or invalid target refuses open`() = runTest {
        val primary = FakeController()
        val controller = ForegroundUserMicController(primary) { error("unavailable") }
        assertFalse(controller.open(target, authorization).controlReadback)
        assertFalse(controller.open(target.copy(userId = -1), authorization).controlReadback)
        assertEquals(0, primary.openCount)
    }

    @Test
    fun `failed rollback never reports a verified block`() = runTest {
        val primary = FakeController().apply { blockResult = MicAccessState.OPEN }
        val result = ForegroundUserMicController(primary) { false }.open(target, authorization)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
        assertFalse(result.controlReadback)
    }

    @Test
    fun `unknown user cannot publish open and cannot prevent block`() = runTest {
        val primary = FakeController().apply { state = MicAccessState.OPEN }
        val controller = ForegroundUserMicController(primary) { false }
        assertEquals(MicAccessState.UNKNOWN, controller.readState(target))
        assertTrue(controller.block(target).controlReadback)
        assertEquals(MicAccessState.BLOCKED, controller.readState(target))
    }

    private class FakeController : MicController {
        override val id = "audio_manager"
        override val displayName = "fake"
        var state = MicAccessState.BLOCKED
        var openCount = 0
        var blockCount = 0
        var blockResult = MicAccessState.BLOCKED
        override suspend fun probe() = ProbeResult(true, true)
        override suspend fun captureSafetyTarget() = SafetyTarget(id, "android-global-microphone", 0)
        override suspend fun block(target: SafetyTarget?): ControlResult {
            blockCount++
            state = blockResult
            return result(MicAccessState.BLOCKED)
        }
        override suspend fun open(target: SafetyTarget, authorization: OpenAuthorization): ControlResult {
            openCount++
            state = MicAccessState.OPEN
            return result(MicAccessState.OPEN)
        }
        override suspend fun readState(target: SafetyTarget?) = state
        private fun result(requested: MicAccessState) = ControlResult(
            requested = requested,
            observed = state,
            controlReadback = requested == state,
            durationMs = 1L,
        )
    }
}
