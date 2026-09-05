package com.jack.micbridge.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootOpenAuthorizationGuardTest {
    @Test
    fun `root mutation is after marker and boottime checks under the shared flock`() {
        val rawMutation = "cmd sensor_privacy disable 0 microphone"
        val script = RootOpenAuthorizationGuard.command(
            rawMutation,
            OpenAuthorization("request-root-open-01", 123_456L),
        )

        assertTrue(script.contains("exec 0>>/data/adb/micbridge/lease.lock"))
        assertTrue(script.contains("flock -n -x 0"))
        assertTrue(script.contains("CURRENT_REQUEST"))
        assertTrue(script.contains("OPEN_NOW_MS"))
        assertTrue(script.indexOf("CURRENT_REQUEST") < script.lastIndexOf(rawMutation))
        assertTrue(script.indexOf("OPEN_NOW_MS") < script.lastIndexOf(rawMutation))
        assertTrue(script.contains("OPEN_NOW_MS\" -lt \"\$OPEN_VALID_UNTIL_MS"))
        assertTrue(script.contains("$rawMutation </dev/null"))
        assertTrue(script.contains("printf \"%.0f\\n\""))
        assertFalse(script.contains("printf \"%.0f\\\\n\""))
        assertTrue(script.contains("APP_STATE=\${APP_PROC%%\\|*}"))
        assertTrue(script.contains("APP_START=\${APP_PROC#*\\|}"))
        assertTrue(script.contains("BOOT_PID=\${BOOT_RECORD%%\\|*}"))
        assertTrue(script.contains("BOOT_START=\${BOOT_RECORD#*\\|}"))
        assertFalse(script.contains("%%|*"))
        assertFalse(script.contains("#*|"))
        assertTrue(script.contains("trap cleanup_open_guard EXIT"))
        assertTrue(script.contains("trap 'exit 1' HUP INT TERM"))
    }

    @Test
    fun `root open guard script passes an available posix syntax check`() {
        val shell = sequenceOf(
            File("/bin/sh"),
            File("C:/Program Files/Git/bin/sh.exe"),
            File("C:/Program Files/Git/usr/bin/sh.exe"),
        ).firstOrNull(File::isFile) ?: return
        val script = RootOpenAuthorizationGuard.command(
            "cmd appops set --user 0 'com.openai.chatgpt' RECORD_AUDIO allow",
            OpenAuthorization("request-root-open-02", 456_789L),
        )
        val process = ProcessBuilder(shell.absolutePath, "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val error = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(error, 0, process.exitValue())
    }

    @Test
    fun `persistent root open requires zero deadline metadata and skips time expiry`() {
        val rawMutation = "cmd sensor_privacy disable 0 microphone"
        val script = RootOpenAuthorizationGuard.command(
            rawMutation,
            OpenAuthorization(
                requestId = "request-root-persistent-01",
                validUntilElapsedRealtimeMs = 0L,
                persistent = true,
            ),
        )

        assertTrue(script.contains("OPEN_PERSISTENT=1"))
        assertTrue(script.contains("[ \"\$META_BLOCK_AT\" = 0 ]"))
        assertTrue(script.contains("[ \"\$META_DEADLINE\" = 0 ]"))
        assertTrue(script.indexOf("OPEN_PERSISTENT") < script.lastIndexOf(rawMutation))
    }
}
