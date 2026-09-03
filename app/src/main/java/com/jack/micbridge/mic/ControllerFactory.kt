package com.jack.micbridge.mic

import android.content.Context
import android.media.AudioManager
import com.jack.micbridge.data.SettingsRepository

object ControllerFactory {
    fun create(
        context: Context,
        settings: SettingsRepository,
        rootShell: RootShell,
    ): MicController {
        val audio = AudioManagerMicController(
            context.getSystemService(AudioManager::class.java),
        )
        val sensorPrivacy = SensorPrivacyRootController(rootShell)
        fun globalTarget(controllerId: String, userId: Int) =
            com.jack.micbridge.data.SafetyTarget(
                controllerId,
                "android-global-microphone",
                userId,
            )

        val selected = when (settings.controllerId) {
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> CrossGateMicController(
                primary = sensorPrivacy,
                openGate = audio,
                gateTarget = { globalTarget(SettingsRepository.CONTROLLER_AUDIO_MANAGER, it.userId) },
            )
            else -> CrossGateMicController(
                primary = audio,
                openGate = sensorPrivacy,
                gateTarget = {
                    globalTarget(SettingsRepository.CONTROLLER_SENSOR_PRIVACY, it.userId)
                },
            )
        }
        return ReadOnlyOpenVetoMicController(
            primary = selected,
            vetoName = "ChatGPT AppOps",
            veto = AppOpsReadOnlyOpenVeto(rootShell, settings),
        )
    }
}
