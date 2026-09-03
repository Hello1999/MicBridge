package com.jack.micbridge.safety

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jack.micbridge.MainActivity
import com.jack.micbridge.R

/** Device-protected evidence that a boot/package-replace BLOCK could not be verified. */
class BootIncidentStore(context: Context) {
    private val appContext = context.applicationContext
    private val deviceContext = appContext.createDeviceProtectedStorageContext()
    private val preferences = deviceContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(message: String): Boolean = preferences.edit()
        .putString(KEY_MESSAGE, message.take(512))
        .putLong(KEY_EPOCH_MS, System.currentTimeMillis())
        .commit()

    fun message(): String? = preferences.getString(KEY_MESSAGE, null)

    fun clear(): Boolean {
        val cleared = preferences.edit().clear().commit()
        if (cleared) {
            runCatching {
                appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }
        return cleared
    }

    fun postFailureNotification() {
        val message = message() ?: return
        runCatching {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MicBridge 开机安全告警",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "开机或应用更新后无法确认麦克风屏蔽状态"
                },
            )
            val contentIntent = PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_micbridge)
                    .setContentTitle("MicBridge：开机屏蔽无法确认")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setContentIntent(contentIntent)
                    .build(),
            )
        }
    }

    companion object {
        private const val PREFERENCES = "micbridge_boot_incident"
        private const val KEY_MESSAGE = "message"
        private const val KEY_EPOCH_MS = "epoch_ms"
        private const val CHANNEL_ID = "micbridge_boot_incident"
        private const val NOTIFICATION_ID = 8785
    }
}
