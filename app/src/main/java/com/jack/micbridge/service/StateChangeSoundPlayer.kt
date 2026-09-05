package com.jack.micbridge.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

internal enum class StateChangeCue {
    OPENED,
    BLOCKED,
}

internal fun stateChangeCueFor(result: OperationResult): StateChangeCue? {
    if (!result.ok || result.replayed || !result.controlReadback) return null
    return when {
        result.previous == MicAccessState.BLOCKED && result.micAccess == MicAccessState.OPEN ->
            StateChangeCue.OPENED
        result.previous == MicAccessState.OPEN && result.micAccess == MicAccessState.BLOCKED ->
            StateChangeCue.BLOCKED
        else -> null
    }
}

internal fun thirtyPercentStreamVolume(maxVolume: Int, minVolume: Int = 0): Int {
    if (maxVolume <= minVolume) return maxVolume
    return (maxVolume * STATE_CUE_VOLUME_FRACTION).roundToInt().coerceIn(minVolume, maxVolume)
}

/** Plays compact, speech-free state cues without making sound delivery part of the safety result. */
internal class StateChangeSoundPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playbackMutex = Mutex()

    suspend fun play(cue: StateChangeCue) = playbackMutex.withLock {
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audioManager.getStreamVolume(stream)
        val targetVolume = thirtyPercentStreamVolume(
            maxVolume = audioManager.getStreamMaxVolume(stream),
            minVolume = audioManager.getStreamMinVolume(stream),
        )
        var volumeWasForced = false
        var generator: ToneGenerator? = null
        try {
            if (!audioManager.isVolumeFixed && originalVolume != targetVolume) {
                audioManager.setStreamVolume(stream, targetVolume, 0)
                volumeWasForced = audioManager.getStreamVolume(stream) == targetVolume
            }
            // The stream is temporarily fixed at 30%; use full generator gain so the cue is
            // stable instead of applying another 30% attenuation on top of the stream volume.
            generator = ToneGenerator(stream, if (volumeWasForced || originalVolume == targetVolume) 100 else 30)
            when (cue) {
                StateChangeCue.OPENED -> {
                    playTone(generator, ToneGenerator.TONE_DTMF_1, 90)
                    delay(35L)
                    playTone(generator, ToneGenerator.TONE_DTMF_6, 150)
                }
                StateChangeCue.BLOCKED -> {
                    playTone(generator, ToneGenerator.TONE_DTMF_6, 100)
                    delay(35L)
                    playTone(generator, ToneGenerator.TONE_DTMF_1, 170)
                }
            }
        } finally {
            generator?.stopTone()
            generator?.release()
            // Do not overwrite a volume adjustment the user made while the short cue played.
            if (volumeWasForced && audioManager.getStreamVolume(stream) == targetVolume) {
                audioManager.setStreamVolume(stream, originalVolume, 0)
            }
        }
    }

    private suspend fun playTone(generator: ToneGenerator, tone: Int, durationMs: Int) {
        generator.startTone(tone, durationMs)
        delay(durationMs.toLong())
    }
}

private const val STATE_CUE_VOLUME_FRACTION = 0.30f
