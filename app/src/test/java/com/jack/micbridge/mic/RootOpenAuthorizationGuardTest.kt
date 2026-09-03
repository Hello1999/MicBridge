package com.jack.micbridge.mic

import org.junit.Assert.assertEquals
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
}
