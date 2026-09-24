package com.jack.micbridge

import com.jack.micbridge.ble.ButtonProtocol
import kotlinx.coroutines.flow.MutableStateFlow

enum class MuteMethod(val label: String, val hint: String) {
    AUDIO_MANAGER(
        "系统静音（无需 Root）",
        "AudioManager 全局麦克风静音，切换最快、无弹窗。少数机型只在通话中生效，请先用下方测试确认。",
    ),
    SENSOR_PRIVACY(
        "隐私开关（Root）",
        "Android 12+ 系统级麦克风隐私开关，所有应用都收到静音。应用在静音时开始录音会弹出系统提示。",
    ),
}

enum class LinkState { NO_DEVICE, CONNECTING, CONNECTED }

data class PttStatus(
    val running: Boolean = false,
    val link: LinkState = LinkState.NO_DEVICE,
    val buttonPressed: Boolean = false,
    /** Readback of the gate; null = unknown. */
    val micOpen: Boolean? = null,
    val lastLatencyMs: Long? = null,
    val error: String? = null,
)

/** Inputs to the push-to-talk decision, owned by the service's state thread. */
data class PttInputs(
    val linkReady: Boolean = false,
    val blePressed: Boolean = false,
    val lastPacketAtMs: Long = 0,
    val screenPressed: Boolean = false,
)

object PttPolicy {
    /** A held button counts only while its heartbeat is fresh; a silent button is a released one. */
    fun blePressed(inputs: PttInputs, nowMs: Long): Boolean =
        inputs.linkReady && inputs.blePressed &&
            nowMs - inputs.lastPacketAtMs <= ButtonProtocol.PRESS_TIMEOUT_MS

    fun micShouldBeOpen(inputs: PttInputs, nowMs: Long): Boolean =
        blePressed(inputs, nowMs) || inputs.screenPressed
}

/** In-process bridge between the UI and the service. */
object PttRuntime {
    val status = MutableStateFlow(PttStatus())
    val screenPressed = MutableStateFlow(false)
}
