package com.jack.micbridge.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Executes the generated shell idioms under the host `/bin/sh` and compares them against the
 * `cat`/`awk` constructs they replace. The Root scripts themselves cannot be executed here, so
 * these tests pin the one thing that actually changed: the text that reads the evidence.
 */
class RootShellIdiomsTest {
    private lateinit var shell: File
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val candidate = File("/bin/sh")
        assumeTrue(candidate.isFile)
        shell = candidate
        workDir = newWorkDir()
    }

    // (a) single-line file reads -------------------------------------------------------------

    @Test
    fun `single line file without a trailing newline is read verbatim`() {
        val file = write("lease", "request-root-open-01")
        assertEquals(
            "[request-root-open-01]",
            run(RootShellIdioms.readSingleLineFile("V", file.path) + "\nprintf '[%s]' \"\$V\""),
        )
    }

    @Test
    fun `single line file with a trailing newline drops only the newline`() {
        val file = write("boot-g1.pid", "1234|987654\n")
        assertEquals(
            "[1234|987654]",
            run(RootShellIdioms.readSingleLineFile("V", file.path) + "\nprintf '[%s]' \"\$V\""),
        )
    }

    @Test
    fun `empty file yields an empty value`() {
        val file = write("watch.status", "")
        assertEquals(
            "[]",
            run(RootShellIdioms.readSingleLineFile("V", file.path) + "\nprintf '[%s]' \"\$V\""),
        )
    }

    @Test
    fun `missing file yields an empty value exactly like the cat substitution`() {
        val missing = File(workDir, "absent").path
        assertEquals(
            "[]",
            run(RootShellIdioms.readSingleLineFile("V", missing) + "\nprintf '[%s]' \"\$V\""),
        )
        assertEquals("[]", run("V=\$(cat \"$missing\" 2>/dev/null)\nprintf '[%s]' \"\$V\""))
    }

    @Test
    fun `leading and trailing blanks are preserved`() {
        val file = write("owner-uid", "  10234 \t ")
        assertEquals(
            "[  10234 \t ]",
            run(RootShellIdioms.readSingleLineFile("V", file.path) + "\nprintf '[%s]' \"\$V\""),
        )
    }

    @Test
    fun `read redirection does not disturb the flock descriptor on fd 0`() {
        // Both scripts hold the lease flock on fd 0 while these reads run, so the temporary
        // `< file` redirection must leave fd 0 pointing at the still-open lock file.
        val lock = write("lease.lock", "")
        val data = write("lease", "request-root-open-01")
        val script = buildString {
            append("exec 0>>\"${lock.path}\"\n")
            append(RootShellIdioms.readSingleLineFile("V", data.path)).append('\n')
            append("printf 'fd0-alive' >&0\n")
            append("printf '[%s]' \"\$V\"\n")
        }
        assertEquals("[request-root-open-01]", run(script))
        assertEquals("fd0-alive", lock.readText())
    }

    // (b) /proc/<pid>/stat field extraction ---------------------------------------------------

    @Test
    fun `proc stat state and start match the awk fields they replace`() {
        val stat = write("stat", SAMPLE_STAT)

        val extracted = run(
            RootShellIdioms.procStatStateAndStart("APP_STATE", "APP_START", stat.path) +
                "\nprintf '%s|%s' \"\$APP_STATE\" \"\$APP_START\"",
        )
        assertEquals("S|987654", extracted)

        val awk = run("awk '{print \$3 \"|\" \$22}' \"${stat.path}\"").trim()
        assertEquals(awk, extracted)
    }

    @Test
    fun `proc stat state only matches the single field awk it replaces`() {
        val stat = write("stat", SAMPLE_STAT)

        val extracted = run(
            RootShellIdioms.procStatState("WATCH_STATE", stat.path) +
                "\nprintf '%s' \"\$WATCH_STATE\"",
        )
        assertEquals("S", extracted)
        assertEquals(run("awk '{print \$3}' \"${stat.path}\"").trim(), extracted)
    }

    @Test
    fun `missing proc stat yields empty state and start`() {
        val missing = File(workDir, "no-such-stat").path
        assertEquals(
            "[][]",
            run(
                RootShellIdioms.procStatStateAndStart("APP_STATE", "APP_START", missing) +
                    "\nprintf '[%s][%s]' \"\$APP_STATE\" \"\$APP_START\"",
            ),
        )
    }

    // (c) monotonic uptime milliseconds -------------------------------------------------------

    @Test
    fun `uptime milliseconds match the awk conversion they replace`() {
        val expected = mapOf(
            "12345.67 23456.78\n" to "12345670",
            "5.05 1.00\n" to "5050",
            // 08 and 09 are the fractions that $(( )) would reject as invalid octal, so the
            // generated single leading-zero strip has to survive them.
            "7.08 1.00\n" to "7080",
            "7.09 1.00\n" to "7090",
            "0.00 0.00\n" to "0",
            "99999.99 1.00\n" to "99999990",
        )
        expected.forEach { (content, millis) ->
            val uptime = write("uptime", content)
            assertEquals(
                content,
                "[$millis]",
                run("set -e\n" + uptimeMillisFrom(uptime.path) + "\nprintf '[%s]' \"\$NOW_MS\""),
            )
            val awk = run("awk '{printf \"%.0f\\n\", \$1 * 1000}' \"${uptime.path}\"").trim()
            assertEquals(content, awk, millis)
        }
    }

    @Test
    fun `garbage or missing uptime yields an empty value that the caller rejects`() {
        val garbage = write("uptime-garbage", "not-a-clock\n")
        assertEquals(
            "[]",
            run("set -e\n" + uptimeMillisFrom(garbage.path) + "\nprintf '[%s]' \"\$NOW_MS\""),
        )

        val missing = File(workDir, "no-uptime").path
        assertEquals(
            "[]",
            run("set -e\n" + uptimeMillisFrom(missing) + "\nprintf '[%s]' \"\$NOW_MS\""),
        )

        // Both scripts keep this guard immediately after the conversion.
        assertEquals(
            "rejected",
            run(
                "set -e\n" + uptimeMillisFrom(missing) + "\n" +
                    "case \"\$NOW_MS\" in ''|*[!0-9]*) printf rejected; exit 0;; esac\n" +
                    "printf accepted",
            ),
        )
    }

    /** The generator hard-codes `/proc/uptime`; point it at a fixture for the host run. */
    private fun uptimeMillisFrom(path: String): String =
        RootShellIdioms.uptimeMillis("NOW_MS").replace("\"/proc/uptime\"", "\"$path\"")

    private fun write(name: String, content: String): File =
        File(workDir, name).apply { writeText(content, StandardCharsets.UTF_8) }

    private fun run(script: String): String {
        val process = ProcessBuilder(shell.absolutePath, "-c", script)
            .redirectError(ProcessBuilder.Redirect.to(File(workDir, "stderr.log")))
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue("shell run timed out", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(File(workDir, "stderr.log").readText(), 0, process.exitValue())
        return output
    }

    private fun newWorkDir(): File =
        java.nio.file.Files.createTempDirectory("micbridge-idioms").toFile().apply {
            deleteOnExit()
        }

    private companion object {
        const val SAMPLE_STAT =
            "1234 (m.jack.micbridge) S 1 1234 1234 0 -1 4194560 100 0 0 0 5 3 0 0 20 0 30 0 " +
                "987654 1000000 200 18446744073709551615 1 2 3 4 5 6 7 8 9 10 11 12 13 14\n"
    }
}
