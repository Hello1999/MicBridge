package com.jack.micbridge.safety

import com.jack.micbridge.data.SafetyTarget
import kotlinx.coroutines.sync.Mutex

/**
 * Serializes lease replacement/cancellation with an already-delivered alarm callback.
 *
 * AlarmManager can enqueue an old PendingIntent before cancellation. Merely checking the
 * request id and then releasing a lock leaves a check-to-block race, so the callback keeps
 * this gate across both the comparison and the fail-closed side effect.
 */
class LeaseMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

object ProcessLeaseMutationGate {
    val instance = LeaseMutationGate()
}

enum class LeaseExpiryResult {
    STALE,
    BLOCKED,
    BLOCK_FAILED,
}

suspend fun handleCurrentLeaseExpiry(
    expectedRequestId: String,
    expectedTarget: SafetyTarget,
    loadLease: () -> ActiveSafetyLease?,
    block: suspend (SafetyTarget) -> Boolean,
    gate: LeaseMutationGate = ProcessLeaseMutationGate.instance,
): LeaseExpiryResult = gate.withLock {
    val current = loadLease()
    if (current?.requestId != expectedRequestId || current.target != expectedTarget) {
        LeaseExpiryResult.STALE
    } else {
        if (block(expectedTarget)) LeaseExpiryResult.BLOCKED else LeaseExpiryResult.BLOCK_FAILED
    }
}
