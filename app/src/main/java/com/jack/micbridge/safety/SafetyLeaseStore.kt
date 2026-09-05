package com.jack.micbridge.safety

import android.content.Context
import com.jack.micbridge.data.SafetyTarget

data class ActiveSafetyLease(
    val requestId: String,
    val target: SafetyTarget,
    val deadlineEpochMs: Long,
    val deadlineElapsedRealtimeMs: Long,
    val exactAlarmArmed: Boolean,
    val rootWatchdogArmed: Boolean,
    val persistent: Boolean = false,
)

/** Device-protected, non-secret crash recovery state for an active safety lease. */
class SafetyLeaseStore(context: Context) {
    private val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(lease: ActiveSafetyLease): Boolean = preferences.edit()
        .putString(KEY_REQUEST_ID, lease.requestId)
        .putString(KEY_CONTROLLER, lease.target.controllerId)
        .putString(KEY_PACKAGE, lease.target.targetPackage)
        .putInt(KEY_USER, lease.target.userId)
        .putLong(KEY_DEADLINE_EPOCH, lease.deadlineEpochMs)
        .putLong(KEY_DEADLINE_ELAPSED, lease.deadlineElapsedRealtimeMs)
        .putBoolean(KEY_EXACT, lease.exactAlarmArmed)
        .putBoolean(KEY_ROOT, lease.rootWatchdogArmed)
        .putBoolean(KEY_PERSISTENT, lease.persistent)
        .commit()

    fun load(): ActiveSafetyLease? {
        val requestId = preferences.getString(KEY_REQUEST_ID, null) ?: return null
        val controller = preferences.getString(KEY_CONTROLLER, null) ?: return null
        val packageName = preferences.getString(KEY_PACKAGE, null) ?: return null
        val userId = preferences.getInt(KEY_USER, -1)
        val epoch = preferences.getLong(KEY_DEADLINE_EPOCH, 0L)
        val elapsed = preferences.getLong(KEY_DEADLINE_ELAPSED, 0L)
        val persistent = preferences.getBoolean(KEY_PERSISTENT, false)
        if (userId < 0 || (!persistent && (epoch <= 0L || elapsed <= 0L))) return null
        if (persistent && (epoch != 0L || elapsed != 0L)) return null
        return ActiveSafetyLease(
            requestId,
            SafetyTarget(controller, packageName, userId),
            epoch,
            elapsed,
            preferences.getBoolean(KEY_EXACT, false),
            preferences.getBoolean(KEY_ROOT, false),
            persistent,
        )
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        private const val PREFERENCES = "micbridge_active_safety_lease"
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_CONTROLLER = "controller"
        private const val KEY_PACKAGE = "target_package"
        private const val KEY_USER = "user_id"
        private const val KEY_DEADLINE_EPOCH = "deadline_epoch"
        private const val KEY_DEADLINE_ELAPSED = "deadline_elapsed"
        private const val KEY_EXACT = "exact"
        private const val KEY_ROOT = "root"
        private const val KEY_PERSISTENT = "persistent"
    }
}
