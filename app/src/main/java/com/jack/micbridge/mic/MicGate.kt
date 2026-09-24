package com.jack.micbridge.mic

import android.media.AudioManager
import android.os.Process
import com.jack.micbridge.MuteMethod

/** One way of muting the whole system microphone. */
interface MicGate {
    val method: MuteMethod

    /** Applies the state; returns true when the readback (or command) confirms it. */
    fun setOpen(open: Boolean): Boolean

    /** Current state if it can be read cheaply, else null. */
    fun isOpen(): Boolean?

    fun close() {}

    companion object {
        fun create(method: MuteMethod, audioManager: AudioManager): MicGate = when (method) {
            MuteMethod.AUDIO_MANAGER -> AudioManagerGate(audioManager)
            MuteMethod.SENSOR_PRIVACY -> SensorPrivacyGate(RootShell())
        }
    }
}

/** AudioManager.setMicrophoneMute: global, instant, needs only MODIFY_AUDIO_SETTINGS. */
class AudioManagerGate(private val audioManager: AudioManager) : MicGate {
    override val method = MuteMethod.AUDIO_MANAGER

    override fun setOpen(open: Boolean): Boolean {
        runCatching { audioManager.isMicrophoneMute = !open }
        return isOpen() == open
    }

    override fun isOpen(): Boolean? = runCatching { !audioManager.isMicrophoneMute }.getOrNull()
}

/** Android 12+ microphone sensor privacy toggle, driven through a persistent root shell. */
class SensorPrivacyGate(private val shell: RootShell) : MicGate {
    override val method = MuteMethod.SENSOR_PRIVACY
    private val userId = Process.myUid() / 100_000
    private var lastApplied: Boolean? = null

    override fun setOpen(open: Boolean): Boolean {
        val verb = if (open) "disable" else "enable"
        val ok = shell.run("cmd sensor_privacy $verb $userId microphone") == 0
        lastApplied = if (ok) open else null
        return ok
    }

    // dumpsys parsing is slow and format varies across releases; trust the command exit code.
    override fun isOpen(): Boolean? = lastApplied

    override fun close() = shell.close()
}
