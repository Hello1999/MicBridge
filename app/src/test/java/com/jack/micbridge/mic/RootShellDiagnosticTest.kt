package com.jack.micbridge.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellDiagnosticTest {
    @Test
    fun `command categories expose purpose but never the command body`() {
        val cases = linkedMapOf(
            "cmd sensor_privacy enable 10 microphone" to "sensor-privacy",
            "cmd appops get --user 10 com.openai.chatgpt RECORD_AUDIO" to "appops",
            "printf tag > /sys/power/wake_lock" to "lease-watchdog",
            "test -f /data/adb/micbridge/lease" to "safety-script",
            "  id -u  " to "root-probe",
            " am get-current-user\n" to "user-probe",
            "command -v timeout" to "command",
        )

        cases.forEach { (command, expected) ->
            val category = RootShell.categorize(command)
            assertEquals(expected, category)
            assertFalse(category.contains(command.trim()))
        }
    }

    @Test
    fun `specific command category wins over embedded safety path`() {
        assertEquals(
            "appops",
            RootShell.categorize(
                "cmd appops get com.openai.chatgpt RECORD_AUDIO; test -f /data/adb/micbridge/lease",
            ),
        )
        assertEquals(
            "sensor-privacy",
            RootShell.categorize(
                "cmd sensor_privacy enable 0 microphone; test -f /data/adb/micbridge/lease",
            ),
        )
    }

    @Test
    fun `stderr sanitizer removes controls collapses whitespace and redacts long opaque values`() {
        val secret = "AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789-token"
        val sanitized = RootShell.sanitizeStderr(
            "  permission\u0000\u0007 denied\n\t token=$secret   retry  ",
        )

        assertEquals("permission denied token=[redacted] retry", sanitized)
        assertFalse(sanitized.contains(secret))
        assertFalse(sanitized.any { it.code < 0x20 })
    }

    @Test
    fun `stderr sanitizer redacts at exact threshold but preserves short diagnostic words`() {
        val twentyThree = "a".repeat(23)
        val twentyFour = "b".repeat(24)

        val sanitized = RootShell.sanitizeStderr("short-code $twentyThree $twentyFour")

        assertEquals("short-code $twentyThree [redacted]", sanitized)
    }

    @Test
    fun `stderr sanitizer applies a hard bounded output length`() {
        val sanitized = RootShell.sanitizeStderr(List(100) { "error" }.joinToString(" "))

        assertEquals(240, sanitized.length)
        assertTrue(sanitized.startsWith("error error"))
    }
}
