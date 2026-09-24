package com.jack.micbridge.safety

import android.content.Context
import android.media.AudioManager
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.mic.AudioManagerMicController
import com.jack.micbridge.mic.ControllerFactory
import com.jack.micbridge.mic.RootShell
import com.jack.micbridge.mic.SensorPrivacyRootController
import kotlinx.coroutines.CancellationException

object FailsafeBlocker {
    suspend fun block(context: Context): Boolean {
        val settings = SettingsRepository(context)
        val shell = RootShell()
        val controller = ControllerFactory.create(context, settings, shell)
        return controller.block().controlReadback
    }

    /** Block the immutable control target captured when the lease was armed. */
    suspend fun block(
        context: Context,
        controllerId: String,
        targetPackage: String,
        userId: Int,
    ): Boolean {
        if (userId < 0) return false
        val shell = RootShell()
        val audio = AudioManagerMicController(
            context.getSystemService(AudioManager::class.java),
        )
        val sensor = SensorPrivacyRootController(shell)
        return executeImmutableFailsafeBlockPlan(controllerId) { gate ->
            when (gate) {
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> {
                    val result = sensor.block(
                        SafetyTarget(
                            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                            GLOBAL_MIC_TARGET,
                            userId,
                        ),
                    )
                    result.controlReadback && result.observed == MicAccessState.BLOCKED
                }
                SettingsRepository.CONTROLLER_AUDIO_MANAGER -> {
                    val result = audio.block(
                        SafetyTarget(
                            SettingsRepository.CONTROLLER_AUDIO_MANAGER,
                            GLOBAL_MIC_TARGET,
                            userId,
                        ),
                    )
                    result.controlReadback && result.observed == MicAccessState.BLOCKED
                }
                else -> false
            }
        }
    }

    private const val GLOBAL_MIC_TARGET = "android-global-microphone"
}

/** Every configured gate must freshly confirm BLOCKED; exceptions remain local to that gate. */
internal suspend fun executeImmutableFailsafeBlockPlan(
    controllerId: String,
    blockGate: suspend (String) -> Boolean,
): Boolean {
    val plan = immutableFailsafeBlockPlan(controllerId)
    if (plan.isEmpty()) return false
    var allVerifiedBlocked = true
    for (gate in plan) {
        val blocked = try {
            blockGate(gate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!blocked) allVerifiedBlocked = false
    }
    return allVerifiedBlocked
}

/** Mirrors ControllerFactory's fail-closed gate order without consulting mutable settings. */
internal fun immutableFailsafeBlockPlan(controllerId: String): List<String> = when (controllerId) {
    SettingsRepository.CONTROLLER_AUDIO_MANAGER -> listOf(
        SettingsRepository.CONTROLLER_AUDIO_MANAGER,
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
    )
    SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> listOf(
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        SettingsRepository.CONTROLLER_AUDIO_MANAGER,
    )
    SettingsRepository.CONTROLLER_APP_OPS -> listOf(
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        SettingsRepository.CONTROLLER_AUDIO_MANAGER,
    )
    else -> emptyList()
}
