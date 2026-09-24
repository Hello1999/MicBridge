package com.jack.micbridge.mic

import com.jack.micbridge.safety.AutoBlockSafety
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
        assertTrue(script.contains("BOOT_PID=\${BOOT_RECORD%%\\|*}"))
        assertTrue(script.contains("BOOT_START=\${BOOT_RECORD#*\\|}"))
        assertFalse(script.contains("%%|*"))
        assertFalse(script.contains("#*|"))
        assertTrue(script.contains("trap cleanup_open_guard EXIT"))
        assertTrue(script.contains("trap 'exit 1' HUP INT TERM"))
    }

    @Test
    fun `every guard exit code and check still appears in the same order`() {
        val rawMutation = "cmd sensor_privacy disable 0 microphone"
        val script = RootOpenAuthorizationGuard.command(
            rawMutation,
            OpenAuthorization("request-root-open-03", 123_456L),
        )

        // 71 missing tool / lock file, 72 lock not acquired, 73 identity, 74 clock, 75 expiry.
        assertTrue(script.contains("command -v flock >/dev/null 2>&1 || exit 71"))
        assertTrue(script.contains("command -v awk >/dev/null 2>&1 || exit 71"))
        assertTrue(script.contains("command -v grep >/dev/null 2>&1 || exit 71"))
        assertTrue(script.contains("command -v tr >/dev/null 2>&1 || exit 71"))
        assertTrue(script.contains("exec 0>>/data/adb/micbridge/lease.lock || exit 71"))
        assertTrue(script.contains("[ \"\$OPEN_LOCKED\" = 1 ] || exit 72"))
        assertTrue(script.contains("case \"\$OPEN_NOW_MS\" in ''|*[!0-9]*) exit 74;; esac"))
        assertTrue(script.contains("|| exit 75"))
        // Unchanged from the forking version: 28 identity checks all fail closed with 73.
        assertEquals(28, script.split("exit 73").size - 1)

        val order = listOf(
            "exec 0>>/data/adb/micbridge/lease.lock",
            "flock -n -x 0",
            "exit 72",
            "CURRENT_REQUEST=",
            "IFS='|' read -r META_REQUEST",
            "OPEN_GENERATION=",
            "APP_START=\${22:-}",
            "kill -0 \"\$META_APP_PID\"",
            "WATCH_PID=",
            "WATCH_STATE=\${3:-}",
            "WATCH_STATUS=",
            "BOOT_RECORD=",
            "BOOT_PROC_START=\${22:-}",
            "OPEN_NOW_MS=",
            rawMutation,
            "flock -u 0\n",
        )
        var previous = -1
        order.forEach { marker ->
            val index = script.indexOf(marker)
            assertTrue("missing or reordered: $marker", index > previous)
            previous = index
        }
    }

    @Test
    fun `guard reads its evidence with shell builtins instead of cat and awk forks`() {
        val script = RootOpenAuthorizationGuard.command(
            "cmd sensor_privacy disable 0 microphone",
            OpenAuthorization("request-root-open-04", 123_456L),
        )

        assertTrue(script.startsWith("set -f\n"))
        assertFalse(script.contains("\$(cat "))
        assertFalse(script.contains("\$(awk "))
        assertFalse(script.contains("awk '"))
        // Only the two NUL cmdline scans may still fork a pipeline.
        assertEquals(2, script.split("tr '\\000' ' '").size - 1)
        assertEquals(2, script.split("grep -Fq").size - 1)

        assertTrue(
            script.contains(
                "CURRENT_REQUEST=; IFS= read -r CURRENT_REQUEST" +
                    " 2>/dev/null < \"/data/adb/micbridge/lease\" || true",
            ),
        )
        assertTrue(
            script.contains(
                "OPEN_GENERATION=; IFS= read -r OPEN_GENERATION" +
                    " 2>/dev/null < \"/data/adb/micbridge/boot-current\" || true",
            ),
        )
        assertTrue(
            script.contains(
                "MB_STAT=; IFS= read -r MB_STAT 2>/dev/null < \"/proc/\$META_APP_PID/stat\"" +
                    " || true; set -- \$MB_STAT; APP_STATE=\${3:-}; APP_START=\${22:-}",
            ),
        )
        assertTrue(
            script.contains(
                "MB_STAT=; IFS= read -r MB_STAT 2>/dev/null < \"/proc/\$WATCH_PID/stat\"" +
                    " || true; set -- \$MB_STAT; WATCH_STATE=\${3:-}",
            ),
        )
        assertTrue(script.contains("OPEN_NOW_MS=\$((MB_INT * 1000 + MB_FRAC * 10))"))
        assertTrue(script.contains("MB_FRAC=\${MB_FRAC#0}"))
    }

    @Test
    fun `root open guard script passes an available posix syntax check`() {
        val shell = availableShell() ?: return
        val scripts = listOf(
            RootOpenAuthorizationGuard.command(
                "cmd appops set --user 0 'com.openai.chatgpt' RECORD_AUDIO allow",
                OpenAuthorization("request-root-open-02", 456_789L),
            ),
            RootOpenAuthorizationGuard.command(
                "cmd sensor_privacy disable 0 microphone",
                OpenAuthorization("request-root-persistent-02", 0L, persistent = true),
            ),
            AutoBlockSafety.verifyActiveGuardScript(
                safeId = "request-verify-syntax",
                appPid = "12345",
                ownerUid = "10234",
            ),
        )

        scripts.forEach { script ->
            val process = ProcessBuilder(shell.absolutePath, "-n").start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            assertTrue("shell syntax check timed out", process.waitFor(5, TimeUnit.SECONDS))
            val error = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(error, 0, process.exitValue())
        }
    }

    @Test
    fun `active guard proof keeps its checks and drops its cat and awk forks`() {
        val script = AutoBlockSafety.verifyActiveGuardScript(
            safeId = "request-verify-01",
            appPid = "4321",
            ownerUid = "10234",
        )

        assertTrue(script.startsWith("set -e\nset -f\n"))
        assertFalse(script.contains("\$(cat "))
        assertFalse(script.contains("\$(awk "))
        assertFalse(script.contains("awk '"))
        assertEquals(2, script.split("tr '\\000' ' '").size - 1)
        assertEquals(2, script.split("grep -Fq").size - 1)
        assertEquals(2, script.split("flock -").size - 1)

        assertTrue(script.contains("test \"\$OWNER_UID\" = '10234'"))
        assertTrue(script.contains("test \"\$CURRENT_REQUEST\" = 'request-verify-01'"))
        assertTrue(script.contains("test \"\$META_APP_PID\" = '4321'"))
        assertTrue(script.contains("NOW_MS=\$((MB_INT * 1000 + MB_FRAC * 10))"))
        assertTrue(script.contains("BOOT_PID=\${BOOT_RECORD%%\\|*}"))
        assertTrue(script.contains("BOOT_START=\${BOOT_RECORD#*\\|}"))

        val order = listOf(
            "exec 0>>/data/adb/micbridge/lease.lock",
            "flock -x 0",
            "OWNER_UID=",
            "CURRENT_REQUEST=",
            "IFS='|' read -r META_REQUEST",
            "case \"\$META_GENERATION\" in ''|*[!A-Za-z0-9._-]*) exit 1;; esac",
            "BOOT_GENERATION=",
            "NOW_MS=",
            "APP_START=\${22:-}",
            "WATCH_PID=",
            "WATCH_STATE=\${3:-}",
            "WATCH_STATUS=",
            "BOOT_RECORD=",
            "BOOT_PROC_START=\${22:-}",
            "flock -u 0",
        )
        var previous = -1
        order.forEach { marker ->
            val index = script.indexOf(marker)
            assertTrue("missing or reordered: $marker", index > previous)
            previous = index
        }
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

    private fun availableShell(): File? = sequenceOf(
        File("/bin/sh"),
        File("C:/Program Files/Git/bin/sh.exe"),
        File("C:/Program Files/Git/usr/bin/sh.exe"),
    ).firstOrNull(File::isFile)
}
