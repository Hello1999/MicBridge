package com.jack.micbridge.service

import com.jack.micbridge.data.AcousticCalibrationIdentity
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult

/** In-memory proof that one immutable environment exercised the full calibration sequence. */
internal class CalibrationSession {
    private var state = State.IDLE
    private var frozenIdentity: AcousticCalibrationIdentity? = null

    /**
     * Every new attempt invalidates all proof from an older round, even if identity capture or
     * OPEN later fails. The caller must capture this before dispatching the OPEN operation.
     */
    fun beginOpenAttempt(identity: AcousticCalibrationIdentity?): Boolean {
        reset()
        frozenIdentity = identity ?: return false
        return true
    }

    /**
     * Checks the environment again after OPEN. A mismatch invalidates the entire round instead
     * of allowing later steps to combine evidence from different app/controller/system builds.
     */
    fun observeOpen(
        currentIdentity: AcousticCalibrationIdentity?,
        result: OperationResult,
    ) {
        if (
            identityStillMatches(currentIdentity) &&
            result.controllerId == frozenIdentity?.controllerId &&
            result.controlReadback &&
            result.micAccess == MicAccessState.OPEN &&
            result.leaseExactAlarmArmed == true &&
            result.leaseRootWatchdogArmed == true
        ) {
            state = State.OPEN
        } else reset()
    }

    /** Fail-closed preflight for callers before they dispatch the next calibration operation. */
    fun matchesCurrentIdentity(currentIdentity: AcousticCalibrationIdentity?): Boolean {
        val matches = state != State.IDLE && identityStillMatches(currentIdentity)
        if (!matches) reset()
        return matches
    }

    fun observeRootFailsafeIsolation(
        currentIdentity: AcousticCalibrationIdentity?,
        verified: Boolean,
    ) {
        if (state == State.OPEN && identityStillMatches(currentIdentity) && verified) {
            state = State.ISOLATED
        } else reset()
    }

    fun observeIsolationConfirmationAndBlock(
        currentIdentity: AcousticCalibrationIdentity?,
        verified: Boolean,
    ) {
        if (state == State.ISOLATED && identityStillMatches(currentIdentity) && verified) {
            state = State.BLOCKED
        } else reset()
    }

    /**
     * Returns the original frozen identity to persist. It never returns the newly-read identity,
     * which prevents a target update at submission time from being recorded as if it were tested.
     */
    fun identityForCommit(
        currentIdentity: AcousticCalibrationIdentity?,
        finalBoundarySafe: Boolean,
    ): AcousticCalibrationIdentity? {
        val identity = frozenIdentity
        val valid = state == State.BLOCKED &&
            finalBoundarySafe &&
            identity != null &&
            identity == currentIdentity
        // Submission is one-shot even when the subsequent durable write fails. Reusing proof
        // after a failed commit could bridge an unobserved permission/package transition.
        reset()
        return identity.takeIf { valid }
    }

    fun reset() {
        state = State.IDLE
        frozenIdentity = null
    }

    private fun identityStillMatches(currentIdentity: AcousticCalibrationIdentity?): Boolean =
        frozenIdentity != null && frozenIdentity == currentIdentity

    private enum class State { IDLE, OPEN, ISOLATED, BLOCKED }
}
