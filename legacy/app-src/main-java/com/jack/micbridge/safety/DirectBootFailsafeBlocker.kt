package com.jack.micbridge.safety

import android.content.Context
import com.jack.micbridge.data.DirectBootSettings
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.data.SettingsRepository

/**
 * Blocks without opening credential-protected preferences. It is safe to call from
 * LOCKED_BOOT_COMPLETED and never starts the HTTP service.
 */
object DirectBootFailsafeBlocker {
    suspend fun block(context: Context): Boolean {
        val targets = linkedSetOf<SafetyTarget>()
        SafetyLeaseStore(context).load()?.target?.let(targets::add)
        val config = DirectBootSettings(context)
        targets += SafetyTarget(
            controllerId = config.controllerId,
            targetPackage = when (config.controllerId) {
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                SettingsRepository.CONTROLLER_AUDIO_MANAGER -> GLOBAL_MIC_TARGET
                else -> config.targetPackage
            },
            userId = config.userId,
        )

        // Never short-circuit: a stale crash-recovery target and the newly selected target
        // can differ. Both must receive a BLOCK attempt before this boundary is considered safe.
        var allVerified = true
        targets.forEach { target ->
            val verified = runCatching {
                FailsafeBlocker.block(
                    context,
                    target.controllerId,
                    target.targetPackage,
                    target.userId,
                )
            }.getOrDefault(false)
            allVerified = allVerified && verified
        }
        return allVerified
    }

    private const val GLOBAL_MIC_TARGET = "android-global-microphone"
}
