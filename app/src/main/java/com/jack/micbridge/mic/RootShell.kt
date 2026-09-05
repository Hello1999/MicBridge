package com.jack.micbridge.mic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
) {
    val succeeded: Boolean
        get() = !timedOut && exitCode == 0
}

data class RootCommandDiagnostic(
    val category: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val sanitizedStderr: String,
)

open class RootShell(
    private val defaultTimeoutMs: Long = 2_500L,
    private val onDiagnostic: ((RootCommandDiagnostic) -> Unit)? = null,
    private val processFactory: () -> Process = {
        ProcessBuilder("su", "-c", "exec sh").start()
    },
) {
    private val sessionLock = ReentrantLock()
    private val sessionReaders = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MicBridge-root-session-output").apply { isDaemon = true }
    }
    private var session: RootSession? = null

    open suspend fun execute(command: String, timeoutMs: Long = defaultTimeoutMs): ShellResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val result = sessionLock.withLock {
                val active = runCatching { ensureSession() }.getOrNull()
                    ?: return@withLock executeOneShot(command, timeoutMs, started)
                executeInSession(active, command, timeoutMs, started)
            }
            audited(command, result)
        }

    private fun ensureSession(): RootSession {
        session?.takeIf { it.process.isAlive }?.let { return it }
        session?.let(::invalidateSession)
        return RootSession(processFactory()).also { session = it }
    }

    /**
     * Executes through one long-lived `su` shell. Every command still runs in its own timed child
     * shell, so `exit`, traps, fd rewiring, and failures cannot mutate the parent session. Random
     * stdout/stderr markers frame the result without trusting command output. A framing failure
     * invalidates the session and is never retried because the command may already have mutated
     * microphone state.
     */
    private fun executeInSession(
        active: RootSession,
        command: String,
        timeoutMs: Long,
        started: Long,
    ): ShellResult {
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val stdoutMarker = "__MICBRIDGE_OUT_${token}__"
        val stderrMarker = "__MICBRIDGE_ERR_${token}__"
        val timeoutSeconds = "%.3f".format(java.util.Locale.US, timeoutMs / 1_000.0)
        val wrapper = """
            if command -v timeout >/dev/null 2>&1; then
              timeout -k 1 $timeoutSeconds sh -c ${quote(command)}
              MB_SESSION_RC=${'$'}?
            else
              MB_SESSION_RC=$TIMEOUT_MISSING_EXIT_CODE
            fi
            printf '\n%s:%s\n' ${quote(stdoutMarker)} "${'$'}MB_SESSION_RC"
            printf '\n%s\n' ${quote(stderrMarker)} >&2
        """.trimIndent() + "\n"
        val stdoutFuture = sessionReaders.submit<FramedOutput> {
            readUntilMarker(active.stdout, stdoutMarker, hasExitCode = true)
        }
        val stderrFuture = sessionReaders.submit<FramedOutput> {
            readUntilMarker(active.stderr, stderrMarker, hasExitCode = false)
        }
        return try {
            active.stdin.write(wrapper)
            active.stdin.flush()
            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMs + PROCESS_GRACE_MS)
            val stdout = stdoutFuture.get(remainingMs(deadline), TimeUnit.MILLISECONDS)
            val stderr = stderrFuture.get(remainingMs(deadline), TimeUnit.MILLISECONDS)
            val exitCode = stdout.exitCode ?: -1
            ShellResult(
                exitCode = exitCode,
                stdout = stdout.content,
                stderr = stderr.content,
                timedOut = exitCode == TIMEOUT_EXIT_CODE || exitCode == KILLED_EXIT_CODE,
                durationMs = elapsedMs(started),
            )
        } catch (error: Exception) {
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            invalidateSession(active)
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = error.message.orEmpty(),
                timedOut = error is TimeoutException,
                durationMs = elapsedMs(started),
            )
        }
    }

    private fun readUntilMarker(
        reader: BufferedReader,
        marker: String,
        hasExitCode: Boolean,
    ): FramedOutput {
        val output = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: throw EOFException("Root session closed")
            if (line.startsWith(marker)) {
                val exitCode = if (hasExitCode) {
                    line.removePrefix(marker).removePrefix(":").toIntOrNull()
                } else {
                    null
                }
                return FramedOutput(output.toString().removeSuffix("\n"), exitCode)
            }
            if (output.length < MAX_OUTPUT_CHARS) {
                val remaining = MAX_OUTPUT_CHARS - output.length
                output.append(line.take(remaining))
                if (output.length < MAX_OUTPUT_CHARS) output.append('\n')
            }
        }
    }

    private fun invalidateSession(active: RootSession) {
        if (session === active) session = null
        runCatching { active.stdin.close() }
        runCatching { active.process.destroy() }
        runCatching { active.stdout.close() }
        runCatching { active.stderr.close() }
        if (active.process.isAlive) runCatching { active.process.destroyForcibly() }
    }

    private fun executeOneShot(
        command: String,
        timeoutMs: Long,
        started: Long,
    ): ShellResult {
            val timeoutSeconds = "%.3f".format(java.util.Locale.US, timeoutMs / 1_000.0)
            val guardedCommand =
                "command -v timeout >/dev/null 2>&1 || exit 125; " +
                    "exec timeout -k 1 $timeoutSeconds sh -c ${quote(command)}"
            val process = try {
                ProcessBuilder("su", "-c", guardedCommand).start()
            } catch (error: Exception) {
                return ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = error.message.orEmpty(),
                    timedOut = false,
                    durationMs = elapsedMs(started),
                )
            }

            val readers = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "MicBridge-root-output").apply { isDaemon = true }
            }
            val stdout = readers.submit<String> {
                runCatching {
                    process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_CHARS) }
                }.getOrDefault("")
            }
            val stderr = readers.submit<String> {
                runCatching {
                    process.errorStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_CHARS) }
                }.getOrDefault("")
            }
            return try {
                val finished = process.waitFor(timeoutMs + PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
                val exitCode = if (finished) process.exitValue() else -1
                val timedOut = !finished || exitCode == TIMEOUT_EXIT_CODE || exitCode == KILLED_EXIT_CODE
                ShellResult(
                    exitCode = exitCode,
                    stdout = completedOutput(stdout),
                    stderr = completedOutput(stderr),
                    timedOut = timedOut,
                    durationMs = elapsedMs(started),
                )
            } finally {
                readers.shutdownNow()
            }
    }

    private fun audited(command: String, result: ShellResult): ShellResult = result.also {
        val diagnostic = RootCommandDiagnostic(
            category = categorize(command),
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            durationMs = result.durationMs,
            sanitizedStderr = sanitizeStderr(result.stderr),
        )
        runCatching { onDiagnostic?.invoke(diagnostic) }
    }

    suspend fun hasRoot(): Boolean {
        val result = execute("id -u")
        return result.succeeded && result.stdout.trim() == "0"
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 16_384
        private const val OUTPUT_GRACE_MS = 500L
        private const val PROCESS_GRACE_MS = 1_500L
        private const val TIMEOUT_MISSING_EXIT_CODE = 125
        private const val TIMEOUT_EXIT_CODE = 124
        private const val KILLED_EXIT_CODE = 137

        private fun completedOutput(future: Future<String>): String = runCatching {
            future.get(OUTPUT_GRACE_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault("")

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun requirePackageName(value: String): String {
            require(PACKAGE_PATTERN.matches(value)) { "Invalid package name" }
            return value
        }

        fun requireOpaqueId(value: String): String {
            require(OPAQUE_ID_PATTERN.matches(value)) { "Invalid identifier" }
            require(RESERVED_LEASE_PREFIXES.none(value::startsWith)) {
                "Identifier uses a reserved lease prefix"
            }
            return value
        }

        private fun elapsedMs(startedNanos: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        private fun remainingMs(deadlineNanos: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1L)

        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val OPAQUE_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,128}")
        private val RESERVED_LEASE_PREFIXES = listOf("cancel-", "expired-", "removed-")

        internal fun categorize(command: String): String = when {
            "sensor_privacy" in command -> "sensor-privacy"
            "appops" in command -> "appops"
            "/sys/power/wake_" in command -> "lease-watchdog"
            "/data/adb/micbridge" in command -> "safety-script"
            command.trim() == "id -u" -> "root-probe"
            command.trim() == "am get-current-user" -> "user-probe"
            else -> "command"
        }

        internal fun sanitizeStderr(value: String): String = value
            .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), " ")
            .replace(Regex("[A-Za-z0-9_-]{24,}"), "[redacted]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
    }

    private data class RootSession(
        val process: Process,
        val stdin: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream)),
        val stdout: BufferedReader = process.inputStream.bufferedReader(),
        val stderr: BufferedReader = process.errorStream.bufferedReader(),
    )

    private data class FramedOutput(
        val content: String,
        val exitCode: Int?,
    )
}
