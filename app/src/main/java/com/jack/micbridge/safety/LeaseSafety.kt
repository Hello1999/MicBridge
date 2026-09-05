package com.jack.micbridge.safety

import com.jack.micbridge.data.SafetyTarget

data class LeaseArmResult(
    val armed: Boolean,
    val deadlineEpochMs: Long,
    val deadlineElapsedRealtimeMs: Long,
    val exactAlarmArmed: Boolean,
    val rootWatchdogArmed: Boolean,
    val error: String? = null,
    val persistent: Boolean = false,
)

interface LeaseSafety {
    suspend fun arm(requestId: String, durationSeconds: Int, target: SafetyTarget): LeaseArmResult
    suspend fun armPersistent(requestId: String, target: SafetyTarget): LeaseArmResult =
        arm(requestId, 30, target)
    suspend fun cancel()
    suspend fun loadActiveLease(): ActiveSafetyLease? = null
    suspend fun verifyActiveGuard(requestId: String): Boolean = true
    suspend fun <T> withMutationLock(block: suspend () -> T): T = block()
}
