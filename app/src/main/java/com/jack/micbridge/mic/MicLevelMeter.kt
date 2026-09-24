package com.jack.micbridge.mic

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Local level meter for verifying that muting really silences capture on this phone.
 * Audio is read, reduced to one number, and discarded.
 */
class MicLevelMeter(private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = runCatching {
            // Same source voice-chat apps (ChatGPT Live) use, so the test matches their path.
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        running = true
        thread(name = "mic-level") {
            val buf = ShortArray(rate / 20) // 50 ms
            runCatching { record.startRecording() }
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                // Map -60..0 dBFS to 0..1.
                val db = if (rms < 1) -90.0 else 20 * log10(rms / 32768.0)
                onLevel(((db + 60) / 60).toFloat().coerceIn(0f, 1f))
            }
            runCatching { record.stop() }
            record.release()
            onLevel(0f)
        }
        return true
    }

    fun stop() {
        running = false
    }
}
