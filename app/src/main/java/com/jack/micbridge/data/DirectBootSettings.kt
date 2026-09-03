package com.jack.micbridge.data

import android.content.Context
import android.os.Process

/** Minimal, non-secret configuration needed only to block before first unlock. */
class DirectBootSettings(context: Context) {
    private val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val controllerId: String
        get() = preferences.getString(KEY_CONTROLLER, SettingsRepository.CONTROLLER_AUDIO_MANAGER)
            ?: SettingsRepository.CONTROLLER_AUDIO_MANAGER

    val targetPackage: String
        get() = preferences.getString(
            KEY_TARGET_PACKAGE,
            SettingsRepository.DEFAULT_CHATGPT_PACKAGE,
        ) ?: SettingsRepository.DEFAULT_CHATGPT_PACKAGE

    val userId: Int
        get() = preferences.getInt(KEY_USER_ID, appUserId())

    fun mirror(
        controllerId: String,
        targetPackage: String,
        userId: Int = appUserId(),
    ): Boolean =
        preferences.edit()
            .putString(KEY_CONTROLLER, controllerId)
            .putString(KEY_TARGET_PACKAGE, targetPackage)
            .putInt(KEY_USER_ID, userId)
            .commit()

    // Android allocates application UIDs in fixed 100000-wide per-user ranges.
    private fun appUserId(): Int = Process.myUid() / PER_USER_RANGE

    companion object {
        private const val PREFERENCES = "micbridge_direct_boot"
        private const val KEY_CONTROLLER = "controller"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_USER_ID = "user_id"
        private const val PER_USER_RANGE = 100_000
    }
}
