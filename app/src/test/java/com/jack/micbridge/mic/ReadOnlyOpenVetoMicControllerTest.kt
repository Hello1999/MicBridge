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

class ReadOnlyOpenVetoMicControllerTest {
    private val target = SafetyTarget("audio_manager", "android-global-microphone", 0)
    private val authorization = OpenAuthorization("request-12345678", Long.MAX_VALUE)

    @Test
    fun `explicit veto refuses OPEN and confirms primary blocked`() = runTest {
        val primary = FakeController(MicAccessState.BLOCKED)
        val controller = wrapped(primary) { MicAccessState.BLOCKED }

        val result = controller.open(target, authorization)

        assertFalse(result.controlReadback)
        assertEquals("APPOPS_EXPLICIT_VETO", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(0, primary.openCount)
        assertEquals(1, primary.blockCount)
    }

    @Test
    fun `unknown veto refuses OPEN and confirms primary blocked`() = runTest {
        val primary = FakeController(MicAccessState.BLOCKED)
        val controller = wrapped(primary) { MicAccessState.UNKNOWN }

        val result = controller.open(target, authorization)

        assertFalse(result.controlReadback)
        assertEquals("APPOPS_VETO_UNKNOWN", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(0, primary.openCount)
        assertEquals(1, primary.blockCount)
    }

    @Test
    fun `allow default and conditional foreground are non-veto modes`() {
        listOf("allow", "default", "foreground").forEach { mode ->
            assertEquals(
                MicAccessState.OPEN,
                AppOpsReadOnlyOpenVeto.modesToVetoState(
                    AppOpsModeReader.Companion.ParsedModes(mode, null),
                ),
            )
        }
        assertEquals(
            MicAccessState.UNKNOWN,
            AppOpsReadOnlyOpenVeto.modesToVetoState(null),
        )
    }

    @Test
    fun `ignore deny and errored veto only when effective at package or UID scope`() {
        listOf("ignore", "deny", "errored").forEach { mode ->
            assertEquals(
                MicAccessState.BLOCKED,
                AppOpsReadOnlyOpenVeto.modesToVetoState(
                    AppOpsModeReader.Companion.ParsedModes("allow", mode),
                ),
            )
            assertEquals(
                MicAccessState.BLOCKED,
                AppOpsReadOnlyOpenVeto.modesToVetoState(
                    AppOpsModeReader.Companion.ParsedModes(mode, "default"),
                ),
            )
        }
    }

    @Test
    fun `non-default UID non-veto overrides package veto without false BLOCKED`() {
        assertEquals(
            MicAccessState.OPEN,
            AppOpsReadOnlyOpenVeto.modesToVetoState(
                AppOpsModeReader.Companion.ParsedModes("ignore", "allow"),
            ),
        )
        assertEquals(
            MicAccessState.OPEN,
            AppOpsReadOnlyOpenVeto.modesToVetoState(
                AppOpsModeReader.Companion.ParsedModes("ignore", "foreground"),
            ),
        )
    }

    @Test
    fun `veto appearing after OPEN rolls selected controller back`() = runTest {
        val primary = FakeController(MicAccessState.BLOCKED)
        var reads = 0
        val controller = wrapped(primary) {
            reads++
            if (reads == 1) MicAccessState.OPEN else MicAccessState.BLOCKED
        }

        val result = controller.open(target, authorization)

        assertFalse(result.controlReadback)
        assertEquals("APPOPS_EXPLICIT_VETO", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, primary.openCount)
        assertEquals(1, primary.blockCount)
        assertEquals(MicAccessState.BLOCKED, primary.state)
    }

    @Test
    fun `AppOps veto cannot claim system BLOCKED when primary rollback fails`() = runTest {
        val primary = FakeController(MicAccessState.OPEN).apply {
            blockResult = MicAccessState.OPEN
        }

        val result = wrapped(primary) { MicAccessState.BLOCKED }.open(target, authorization)

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.UNKNOWN, result.observed)
        assertEquals(1, primary.blockCount)
    }

    @Test
    fun `fresh read exposes AppOps disagreement as unknown`() = runTest {
        val primary = FakeController(MicAccessState.OPEN)

        assertEquals(
            MicAccessState.UNKNOWN,
            wrapped(primary) { MicAccessState.BLOCKED }.readState(target),
        )
    }

    @Test
    fun `non-veto before and after permits verified primary OPEN`() = runTest {
        val primary = FakeController(MicAccessState.BLOCKED)

        val result = wrapped(primary) { MicAccessState.OPEN }.open(target, authorization)

        assertTrue(result.controlReadback)
        assertEquals(MicAccessState.OPEN, result.observed)
        assertEquals(1, primary.openCount)
        assertEquals(0, primary.blockCount)
    }

    @Test
    fun `BLOCK never writes through veto`() = runTest {
        val primary = FakeController(MicAccessState.OPEN)
        var vetoReads = 0
        val controller = wrapped(primary) {
            vetoReads++
            MicAccessState.BLOCKED
        }

        val result = controller.block(target)

        assertTrue(result.controlReadback)
        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, primary.blockCount)
        assertEquals(0, vetoReads)
    }

    private fun wrapped(
        primary: MicController,
        state: suspend (SafetyTarget) -> MicAccessState,
    ) = ReadOnlyOpenVetoMicController(
        primary = primary,
        vetoName = "AppOps",
        veto = ReadOnlyOpenVeto(state),
    )

    private class FakeController(var state: MicAccessState) : MicController {
        override val id = "audio_manager"
        override val displayName = "fake"
        var openCount = 0
        var blockCount = 0
        var blockResult = MicAccessState.BLOCKED

        override suspend fun probe() = ProbeResult(true, true)
        override suspend fun captureSafetyTarget() =
            SafetyTarget(id, "android-global-microphone", 0)

        override suspend fun block(target: SafetyTarget?): ControlResult {
            blockCount++
            state = blockResult
            return result(MicAccessState.BLOCKED)
        }

        override suspend fun open(
            target: SafetyTarget,
            authorization: OpenAuthorization,
        ): ControlResult {
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
