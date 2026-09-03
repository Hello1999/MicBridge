package com.jack.micbridge.mic

import android.media.AudioManager
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class AudioManagerMicController(
    private val audioManager: AudioManager,
) : MicController {
    override val id: String = "audio_manager"
    override val displayName: String = "AudioManager（系统全局）"

    override suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val state = readState()
        ProbeResult(
            available = state != MicAccessState.UNKNOWN,
            stateReadable = state != MicAccessState.UNKNOWN,
            notes = "平台 API 只提供控制读回，仍需 ChatGPT Live 声学校准",
        )
    }

    override suspend fun captureSafetyTarget() = SafetyTarget(
        id,
        "android-global-microphone",
        android.os.Process.myUid() / 100_000,
    )

    override suspend fun block(target: SafetyTarget?): ControlResult {
        if (target != null && target.controllerId != id) {
            return contextMismatch(MicAccessState.BLOCKED)
        }
        return setMuted(true)
    }

    override suspend fun open(
        target: SafetyTarget,
        authorization: OpenAuthorization,
    ): ControlResult {
        if (target.controllerId != id) {
            return contextMismatch(MicAccessState.OPEN)
        }
        return setMuted(false)
    }

    override suspend fun readState(target: SafetyTarget?): MicAccessState = withContext(Dispatchers.IO) {
        if (target != null && target.controllerId != id) return@withContext MicAccessState.UNKNOWN
        runCatching {
            if (audioManager.isMicrophoneMute) MicAccessState.BLOCKED else MicAccessState.OPEN
        }.getOrDefault(MicAccessState.UNKNOWN)
    }

    private suspend fun setMuted(muted: Boolean): ControlResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val requested = if (muted) MicAccessState.BLOCKED else MicAccessState.OPEN
        val commandError = runCatching { audioManager.isMicrophoneMute = muted }.exceptionOrNull()
        val observed = readState()
        val verified = commandError == null && observed == requested
        ControlResult(
            requested = requested,
            observed = observed,
            controlReadback = verified,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            errorCode = when {
                commandError != null -> "AUDIO_MANAGER_FAILED"
                !verified -> "STATE_UNVERIFIED"
                else -> null
            },
            errorMessage = commandError?.message ?: if (!verified) "AudioManager 读回不一致" else null,
        )
    }

    private fun contextMismatch(requested: MicAccessState) = ControlResult(
        requested,
        MicAccessState.UNKNOWN,
        false,
        0,
        "CONTROL_CONTEXT_MISMATCH",
        "安全租约目标与 AudioManager 不一致",
    )
}
