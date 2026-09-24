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

    /**
     * Runs only the locked Root revoke script for a durable lease whose target controller
     * requires a Root watchdog: block every user's effective gate, verify a fresh BLOCKED
     * readback and replace the lease marker, all under the same root flock that authorizes an
     * OPEN. Returns true only when that script succeeded; false when no such lease exists or
     * the script failed.
     *
     * It deliberately does NOT clear the durable lease store and does NOT cancel the alarms, so
     * a caller that stops here is still protected by every fail-safe it started with. Splitting
     * the script out of [cancel] lets a BLOCK toggle run it before the controller stack, which
     * then observes the already-BLOCKED sensor gate instead of paying for a second
     * block-all-users round trip.
     */
    suspend fun revokeRootLeaseAfterVerifiedBlock(): Boolean = false

    /**
     * [rootLeaseAlreadyRevoked] may only be true when [revokeRootLeaseAfterVerifiedBlock] just
     * returned true for this same lease; the revoke script proved workspace ownership, blocked
     * and verified the gate and replaced the marker under the flock, so repeating it would only
     * add latency. Everything else in cancellation is unchanged. The default implementation
     * ignores the hint and therefore stays on the slower, always-revoke path.
     */
    suspend fun cancel(rootLeaseAlreadyRevoked: Boolean) = cancel()

    suspend fun loadActiveLease(): ActiveSafetyLease? = null
    suspend fun verifyActiveGuard(requestId: String): Boolean = true
    suspend fun <T> withMutationLock(block: suspend () -> T): T = block()
}
