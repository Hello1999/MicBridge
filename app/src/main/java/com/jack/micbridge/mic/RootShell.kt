package com.jack.micbridge.mic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
) {
    open suspend fun execute(command: String, timeoutMs: Long = defaultTimeoutMs): ShellResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val timeoutSeconds = "%.3f".format(java.util.Locale.US, timeoutMs / 1_000.0)
            val guardedCommand =
                "command -v timeout >/dev/null 2>&1 || exit 125; " +
                    "exec timeout -k 1 $timeoutSeconds sh -c ${quote(command)}"
            val process = try {
                ProcessBuilder("su", "-c", guardedCommand).start()
            } catch (error: Exception) {
                return@withContext audited(command, ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = error.message.orEmpty(),
                    timedOut = false,
                    durationMs = elapsedMs(started),
                ))
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
            try {
                val finished = process.waitFor(timeoutMs + PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
                val exitCode = if (finished) process.exitValue() else -1
                val timedOut = !finished || exitCode == TIMEOUT_EXIT_CODE || exitCode == KILLED_EXIT_CODE
                audited(command, ShellResult(
                    exitCode = exitCode,
                    stdout = completedOutput(stdout),
                    stderr = completedOutput(stderr),
                    timedOut = timedOut,
                    durationMs = elapsedMs(started),
                ))
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
}
