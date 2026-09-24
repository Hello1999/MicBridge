package com.jack.micbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.safety.FailsafeBlocker
import com.jack.micbridge.safety.DirectBootFailsafeBlocker
import com.jack.micbridge.safety.BootIncidentStore
import com.jack.micbridge.service.BridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val userUnlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (
            action in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_USER_SWITCHED) &&
            userUnlocked
        ) {
            // A replacement can change controller semantics while retaining the same ID.
            // Never let an acoustic assertion from an older APK authorize the new build.
            runCatching { SettingsRepository(context).clearCalibration() }
        }
        val incidentStore = BootIncidentStore(context)
        incidentStore.record("$action 后正在确认麦克风访问已屏蔽")

        // Start the foreground service inside the boot broadcast allowance. It publishes
        // UNKNOWN immediately and will not bind HTTP until BLOCKED is freshly confirmed.
        val serviceStartAccepted = if (userUnlocked && SettingsRepository(context).autoStart) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(BridgeForegroundService.ACTION_START),
                )
            }.isSuccess
        } else false
        // initialize() owns the unlocked startup BLOCK boundary. Starting a second blocker
        // here would race the first authenticated OPEN after the service publishes HTTP and
        // could turn a truthful one-pulse success into an immediately BLOCKED state.
        if (serviceStartAccepted) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val blocked = withTimeoutOrNull(RECEIVER_WORK_TIMEOUT_MS) {
                    if (!userUnlocked) {
                        DirectBootFailsafeBlocker.block(context.applicationContext)
                    } else {
                        FailsafeBlocker.block(context.applicationContext)
                    }
                }
                when (blocked) {
                    true -> incidentStore.clear()
                    false -> {
                        incidentStore.record("$action 后无法确认麦克风访问已屏蔽；请检查系统隐私开关")
                        incidentStore.postFailureNotification()
                    }
                    null -> {
                        incidentStore.record("$action 后屏蔽流程超时；麦克风状态未知")
                        incidentStore.postFailureNotification()
                    }
                }
            } catch (_: Throwable) {
                incidentStore.record("$action 后屏蔽流程异常；麦克风状态未知")
                incidentStore.postFailureNotification()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_USER_SWITCHED,
        )
        private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
        private const val RECEIVER_WORK_TIMEOUT_MS = 8_000L
    }
}
