package com.jack.micbridge.routing

import com.jack.micbridge.mic.RootShell
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of one [CaptureRouteController] round trip.
 *
 * [verified] is the only field a caller may act on: it is true exclusively when the root shell
 * itself succeeded (exit 0, no timeout) *and* the helper's readback matched what was requested.
 * Everything else is diagnostic.
 */
data class CaptureRouteState(
    val verified: Boolean,
    val readbacks: List<PresetReadback>,
    val exitCode: Int,
    val durationMs: Long,
    val error: String?,
)

/**
 * Runs [CaptureRouteMain] through the existing root shell and verifies its readback.
 *
 * Stage 1 only: nothing in the service, coordinator, settings or UI calls this, and it is not part
 * of — and cannot delay — the fail-closed microphone BLOCK path. A failure here leaves capture
 * routing exactly as the system had it.
 *
 * @param apkPath supplies this app's base APK (later `context.applicationInfo.sourceDir`); it is
 *   a lambda so no `Context` is needed here and the path is re-read on every call.
 */
class CaptureRouteController(
    private val shell: RootShell,
    private val apkPath: () -> String,
) {
    suspend fun read(
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): CaptureRouteState = run(presets, CaptureRouteCommand::getCommand, CaptureRouteCommand::allReported)

    suspend fun forceBuiltInMic(
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): CaptureRouteState =
        run(presets, CaptureRouteCommand::setBuiltInMicCommand, CaptureRouteCommand::allBuiltInMic)

    suspend fun clear(
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): CaptureRouteState = run(presets, CaptureRouteCommand::clearCommand, CaptureRouteCommand::allCleared)

    /**
     * Never throws for a failed command: an invalid APK path, a dead root shell, a non-zero exit or
     * unparsable output all become `verified = false` plus an [CaptureRouteState.error] code.
     * Coroutine cancellation is deliberately re-thrown so structured concurrency still works.
     */
    private suspend fun run(
        presets: List<CapturePreset>,
        build: (String, List<CapturePreset>) -> String,
        verify: (List<PresetReadback>, List<CapturePreset>) -> Boolean,
    ): CaptureRouteState {
        val command = try {
            build(apkPath(), presets)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failed("COMMAND_INVALID", error)
        }

        val result = try {
            shell.execute(command, TIMEOUT_MS)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failed("SHELL_FAILED", error)
        }

        val readbacks = CaptureRouteCommand.parse(result.stdout)
        if (result.timedOut) {
            return CaptureRouteState(
                verified = false,
                readbacks = readbacks.orEmpty(),
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                error = "TIMEOUT",
            )
        }
        if (result.exitCode != 0) {
            return CaptureRouteState(
                verified = false,
                readbacks = readbacks.orEmpty(),
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                error = exitError(result.exitCode, result.stderr),
            )
        }
        if (readbacks == null) {
            return CaptureRouteState(
                verified = false,
                readbacks = emptyList(),
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                error = "PARSE_FAILED",
            )
        }
        val verified = verify(readbacks, presets)
        return CaptureRouteState(
            verified = verified,
            readbacks = readbacks,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            error = if (verified) null else "READBACK_MISMATCH",
        )
    }

    private fun exitError(exitCode: Int, stderr: String): String {
        val code = when (exitCode) {
            CaptureRouteCommand.EXIT_USAGE -> "USAGE"
            CaptureRouteCommand.EXIT_UNVERIFIED -> "UNVERIFIED"
            CaptureRouteCommand.EXIT_FAILURE -> "HELPER_FAILED"
            else -> "EXIT_$exitCode"
        }
        val detail = RootShell.sanitizeStderr(stderr)
        return if (detail.isEmpty()) code else "$code: $detail"
    }

    private fun failed(code: String, error: Exception): CaptureRouteState {
        val message = error.message.orEmpty().replace('\n', ' ').trim()
        return CaptureRouteState(
            verified = false,
            readbacks = emptyList(),
            exitCode = -1,
            durationMs = 0L,
            error = if (message.isEmpty()) code else "$code: $message",
        )
    }

    companion object {
        /** `app_process` has to start a fresh ART VM, which is far slower than a plain shell call. */
        const val TIMEOUT_MS: Long = 8_000L
    }
}
