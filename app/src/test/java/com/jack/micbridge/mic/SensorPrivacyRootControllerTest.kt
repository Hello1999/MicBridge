package com.jack.micbridge.mic

import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.SafetyTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorPrivacyRootControllerTest {
    private val target = SafetyTarget(
        controllerId = "root_sensor_privacy",
        targetPackage = "android-global-microphone",
        userId = 0,
    )

    @Test
    fun `successful all-user block reuses the fragment readback`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(0, "", "", timedOut = false, durationMs = 1),
        )

        val result = SensorPrivacyRootController(shell).block(target)

        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertTrue(result.controlReadback)
        assertEquals(1, shell.commands.size)
        assertTrue(shell.commands.single().contains("BLOCK_VERIFIED"))
    }

    @Test
    fun `successful open reads privacy state inside the authorized root command`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(
                0,
                """
                    users={
                      user_id=0
                      sensors={
                        sensor=1
                        toggles={
                          state_type=2
                        }
                      }
                    }
                """.trimIndent(),
                "",
                timedOut = false,
                durationMs = 1,
            ),
        )

        val result = SensorPrivacyRootController(shell).open(
            target,
            OpenAuthorization("request-sensor-open-01", 30_000L),
        )

        assertEquals(MicAccessState.OPEN, result.observed)
        assertTrue(result.controlReadback)
        assertEquals(1, shell.commands.size)
        assertTrue(shell.commands.single().contains("am get-current-user </dev/null"))
        assertTrue(shell.commands.single().contains("dumpsys sensor_privacy </dev/null"))
    }

    @Test
    fun `immediate block mutates current user in one root command`() = runTest {
        val shell = RecordingRootShell(
            ShellResult(0, "0\n", "", timedOut = false, durationMs = 1),
        )

        val blocked = SensorPrivacyRootController(shell).blockCurrentUserImmediately()

        assertTrue(blocked)
        assertEquals(1, shell.commands.size)
        assertTrue(shell.commands.single().contains("sensor_privacy enable"))
        assertTrue(shell.commands.single().contains("am get-current-user"))
    }

    private class RecordingRootShell(
        vararg responses: ShellResult,
    ) : RootShell() {
        private val responses = ArrayDeque(responses.toList())
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return responses.removeFirst()
        }
    }
}
