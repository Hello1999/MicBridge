package com.jack.micbridge.mic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SensorPrivacyRootProtocolTest {
    @Test
    fun `framework commands detach the inherited lease lock from stdin`() {
        val discovery = SensorPrivacyRootProtocol.discoverUsersAttempt()
        val block = SensorPrivacyRootProtocol.blockAllUsersAttempt()

        assertTrue(discovery.contains("timeout -k 0.1 0.5 pm list users </dev/null 2>/dev/null"))
        assertTrue(discovery.contains("timeout -k 0.1 0.5 am get-current-user </dev/null 2>/dev/null"))
        assertTrue(
            block.contains(
                "timeout -k 0.1 0.5 cmd sensor_privacy enable \"\$MB_USER\" " +
                    "microphone </dev/null >/dev/null 2>&1",
            ),
        )
        assertTrue(
            block.contains(
                "timeout -k 0.1 0.5 dumpsys sensor_privacy </dev/null 2>/dev/null",
            ),
        )
    }

    @Test
    fun `block gates the frozen foreground user before authoritative discovery`() {
        val block = SensorPrivacyRootProtocol.blockAllUsersAttempt()
        val fastBlock = block.indexOf(
            "cmd sensor_privacy enable \"\$USER_ID\" microphone",
        )
        val discovery = block.indexOf("pm list users")

        assertTrue(fastBlock >= 0)
        assertTrue(discovery > fastBlock)
        assertTrue(block.contains("TARGET_FAST_BLOCKED=1"))
        assertTrue(
            block.contains(
                "[ \"\$MB_USER\" = \"\$USER_ID\" ] && " +
                    "[ \"\$TARGET_FAST_BLOCKED\" = 1 ]",
            ),
        )
    }

    @Test
    fun `target preflight blocks and independently reads only the frozen user`() {
        val preflight = SensorPrivacyRootProtocol.blockTargetUserAttempt()

        assertTrue(preflight.contains("sensor_privacy enable \"\$USER_ID\" microphone"))
        assertTrue(preflight.contains("dumpsys sensor_privacy"))
        assertTrue(preflight.contains("MB_USER=\$USER_ID"))
        assertTrue(preflight.contains("BLOCK_VERIFIED=1"))
        assertFalse(preflight.contains("pm list users"))
        assertFalse(preflight.contains("for MB_USER in \$USER_IDS"))
    }

    @Test
    fun `root readback rejects an unscoped pre-user state`() {
        val output = """
            sensor=1
            state_type=1
            users={
              user_id=0
              sensors={
                sensor=1
                toggles={
                  state_type=2
                }
              }
            }
        """.trimIndent()

        assertFalse(blockedReadback(output, userId = 0))
    }

    @Test
    fun `root readback cannot carry user scope into another section`() {
        val output = """
            users={
              user_id=0
              sensors={
                sensor=2
                toggles={
                  state_type=2
                }
              }
            }
            unrelated={
              sensor=1
              state_type=1
            }
        """.trimIndent()

        assertFalse(blockedReadback(output, userId = 0))
    }

    @Test
    fun `root readback rejects conflicting global all-sensor states`() {
        val output = """
            {
              is_enabled=true
              is_enabled=false
              users={
                user_id=0
                sensors={
                  sensor=1
                  toggles={
                    state_type=2
                  }
                }
              }
            }
        """.trimIndent()

        assertFalse(blockedReadback(output, userId = 0))
    }

    @Test
    fun `root readback accepts exact nested microphone block`() {
        val output = """
            users={
              user_id=0
              sensors={
                sensor=1
                toggles={
                  state_type=1
                }
              }
              user_id=10
              sensors={
                sensor=1
                toggles={
                  state_type=2
                }
              }
            }
        """.trimIndent()

        assertTrue(blockedReadback(output, userId = 0))
        assertFalse(blockedReadback(output, userId = 10))
    }

    @Test
    fun `root readback accepts scoped Android 12 is-enabled block`() {
        val output = """
            individual_enabled_sensor {
              user_id=10
              sensor=1
              is_enabled=true
            }
        """.trimIndent()

        assertTrue(blockedReadback(output, userId = 10))
        assertFalse(blockedReadback(output, userId = 0))
    }

    private fun blockedReadback(output: String, userId: Int): Boolean {
        val shell = sequenceOf(
            File("/bin/sh"),
            File("C:/Program Files/Git/bin/sh.exe"),
            File("C:/Program Files/Git/usr/bin/sh.exe"),
        ).firstOrNull(File::isFile)
        assumeTrue("POSIX shell unavailable", shell != null)
        val script = """
            OUT=${shellQuote(output)}
            MB_USER=${shellQuote(userId.toString())}
            if ${SensorPrivacyRootProtocol.blockedReadback("OUT", "MB_USER")}; then
              exit 0
            fi
            exit 1
        """.trimIndent()
        val process = ProcessBuilder(shell!!.absolutePath).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        assertTrue("shell readback timed out", process.waitFor(5, TimeUnit.SECONDS))
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        check(process.exitValue() in 0..1) {
            "shell readback failed with ${process.exitValue()}: $stderr\n$script"
        }
        return process.exitValue() == 0
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
