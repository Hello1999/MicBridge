package com.jack.micbridge.routing

import com.jack.micbridge.mic.RootShell
import com.jack.micbridge.mic.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRouteControllerTest {
    private val apk = "/data/app/~~AbC==/com.jack.micbridge-1/base.apk"

    @Test
    fun `forcing the built-in mic verifies the readback of every preset`() = runTest {
        val shell = RecordingRootShell(
            ok("preset=1 status=0 devices=15\npreset=6 status=0 devices=15\npreset=7 status=0 devices=15"),
        )

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertTrue(state.verified)
        assertNull(state.error)
        assertEquals(0, state.exitCode)
        assertEquals(3, state.readbacks.size)
        assertEquals(PresetReadback(1, 0, listOf(15)), state.readbacks.first())
        assertEquals(1, shell.commands.size)
        assertEquals(CaptureRouteCommand.setBuiltInMicCommand(apk), shell.commands.single())
        assertEquals(CaptureRouteController.TIMEOUT_MS, shell.timeouts.single())
    }

    @Test
    fun `a readback that kept another device is not verified`() = runTest {
        val shell = RecordingRootShell(
            ok("preset=1 status=0 devices=15\npreset=6 status=0 devices=7\npreset=7 status=0 devices=15"),
        )

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("READBACK_MISMATCH", state.error)
        assertEquals(3, state.readbacks.size)
    }

    @Test
    fun `clearing verifies empty readbacks`() = runTest {
        val shell = RecordingRootShell(
            ok("preset=1 status=0 devices=\npreset=6 status=0 devices=\npreset=7 status=0 devices="),
        )

        val state = CaptureRouteController(shell) { apk }.clear()

        assertTrue(state.verified)
        assertEquals(CaptureRouteCommand.clearCommand(apk), shell.commands.single())
    }

    @Test
    fun `clearing is not verified when a preset is still routed`() = runTest {
        val shell = RecordingRootShell(
            ok("preset=1 status=0 devices=15\npreset=6 status=0 devices=\npreset=7 status=0 devices="),
        )

        assertFalse(CaptureRouteController(shell) { apk }.clear().verified)
    }

    @Test
    fun `reading verifies only that every requested preset was reported`() = runTest {
        val shell = RecordingRootShell(
            ok("preset=1 devices=7\npreset=6 devices=\npreset=7 devices=15"),
        )

        val state = CaptureRouteController(shell) { apk }.read(CapturePreset.DEFAULT_SET)

        assertTrue(state.verified)
        assertEquals(listOf(7), state.readbacks.first().deviceTypes)
        assertEquals(CaptureRouteCommand.getCommand(apk), shell.commands.single())
    }

    @Test
    fun `a narrower preset list only reads those presets`() = runTest {
        val shell = RecordingRootShell(ok("preset=1 devices=15"))

        val state = CaptureRouteController(shell) { apk }.read(listOf(CapturePreset.MIC))

        assertTrue(state.verified)
        assertTrue(shell.commands.single().endsWith("'get' '1'"))
    }

    @Test
    fun `helper exit code 3 reports an unverified helper run`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(3, "preset=1 status=-1 devices=", "", timedOut = false, durationMs = 900),
        )

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("UNVERIFIED", state.error)
        assertEquals(3, state.exitCode)
        assertEquals(900L, state.durationMs)
        assertEquals(1, state.readbacks.size)
    }

    @Test
    fun `helper exit code 4 keeps the sanitized stderr`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(
                4,
                "",
                "error=ClassNotFoundException: android.media.IAudioService\n",
                timedOut = false,
                durationMs = 12,
            ),
        )

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals(
            "HELPER_FAILED: error=ClassNotFoundException: android.media.IAudioService",
            state.error,
        )
    }

    @Test
    fun `usage exit code is reported distinctly`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(2, "", "error=usage", timedOut = false, durationMs = 5),
        )

        assertEquals("USAGE: error=usage", CaptureRouteController(shell) { apk }.read().error)
    }

    @Test
    fun `an unknown exit code is reported verbatim`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(127, "", "", timedOut = false, durationMs = 5),
        )

        assertEquals("EXIT_127", CaptureRouteController(shell) { apk }.read().error)
    }

    @Test
    fun `a timeout is never verified even if stdout looked complete`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(
                124,
                "preset=1 status=0 devices=15\npreset=6 status=0 devices=15\npreset=7 status=0 devices=15",
                "",
                timedOut = true,
                durationMs = 8_100,
            ),
        )

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("TIMEOUT", state.error)
        assertEquals(8_100L, state.durationMs)
    }

    @Test
    fun `garbage stdout is rejected instead of being treated as an empty readback`() = runTest {
        val shell = RecordingRootShell(ok("app_process: not found"))

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("PARSE_FAILED", state.error)
        assertTrue(state.readbacks.isEmpty())
    }

    @Test
    fun `silent success with no readback lines is not verified`() = runTest {
        val shell = RecordingRootShell(ok(""))

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("READBACK_MISMATCH", state.error)
    }

    @Test
    fun `an unusable apk path fails before any root command runs`() = runTest {
        val shell = RecordingRootShell(ok("preset=1 status=0 devices=15"))

        val state = CaptureRouteController(shell) { "/data/app/my app/base.apk" }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals(-1, state.exitCode)
        assertTrue(state.error!!.startsWith("COMMAND_INVALID"))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `a throwing root shell is mapped to an error instead of propagating`() = runTest {
        val shell = ThrowingRootShell(IllegalStateException("su died"))

        val state = CaptureRouteController(shell) { apk }.forceBuiltInMic()

        assertFalse(state.verified)
        assertEquals("SHELL_FAILED: su died", state.error)
        assertEquals(-1, state.exitCode)
    }

    private fun ok(stdout: String): ShellResult =
        ShellResult(0, stdout, "", timedOut = false, durationMs = 1_200)

    private class RecordingRootShell(
        vararg responses: ShellResult,
    ) : RootShell() {
        private val responses = ArrayDeque(responses.toList())
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()

        override suspend fun execute(command: String, timeoutMs: Long): ShellResult {
            commands += command
            timeouts += timeoutMs
            return responses.removeFirst()
        }
    }

    private class ThrowingRootShell(private val failure: Exception) : RootShell() {
        override suspend fun execute(command: String, timeoutMs: Long): ShellResult = throw failure
    }
}
