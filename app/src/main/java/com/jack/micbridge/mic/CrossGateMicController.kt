package com.jack.micbridge.mic

import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget

/**
 * Requires both independently readable gates to agree at every success boundary. A raw
 * framework readback is not acoustic proof that one particular gate is effective on this ROM,
 * so a mixed BLOCKED/OPEN or BLOCKED/UNKNOWN result must not be advertised as safe.
 */
class CrossGateMicController(
    private val primary: MicController,
    private val openGate: MicController,
    private val gateTarget: (SafetyTarget) -> SafetyTarget,
) : MicController {
    override val id: String = primary.id
    override val displayName: String = "${primary.displayName} + ${openGate.displayName} 读回"

    override suspend fun probe(): ProbeResult {
        val primaryProbe = primary.probe()
        val gateProbe = openGate.probe()
        return ProbeResult(
            available = primaryProbe.available && gateProbe.available,
            stateReadable = primaryProbe.stateReadable && gateProbe.stateReadable,
            worksWhileLocked = when {
                primaryProbe.worksWhileLocked == false || gateProbe.worksWhileLocked == false -> false
                primaryProbe.worksWhileLocked == true && gateProbe.worksWhileLocked == true -> true
                else -> null
            },
            notes = listOfNotNull(primaryProbe.notes, gateProbe.notes).joinToString("；"),
        )
    }

    override suspend fun captureSafetyTarget(): SafetyTarget? = primary.captureSafetyTarget()

    override suspend fun block(target: SafetyTarget?): ControlResult {
        val primaryResult = primary.block(target)
        val primaryBlocked =
            primaryResult.controlReadback &&
            primaryResult.observed == MicAccessState.BLOCKED

        val fixedTarget = target ?: primary.captureSafetyTarget()
            ?: return ControlResult(
                requested = MicAccessState.BLOCKED,
                observed = MicAccessState.UNKNOWN,
                controlReadback = false,
                durationMs = primaryResult.durationMs,
                errorCode = "REDUNDANT_GATE_TARGET_UNKNOWN",
                errorMessage = listOfNotNull(
                    primaryResult.errorMessage,
                    "无法确定安全备用门的固定控制目标",
                ).joinToString("；"),
            )
        val secondaryTarget = gateTarget(fixedTarget)
        val secondaryBefore = openGate.readState(secondaryTarget)
        val secondaryResult = if (secondaryBefore == MicAccessState.BLOCKED) {
            null
        } else {
            openGate.block(secondaryTarget)
        }
        val secondaryBlocked = secondaryBefore == MicAccessState.BLOCKED ||
            (secondaryResult?.controlReadback == true &&
                secondaryResult.observed == MicAccessState.BLOCKED)
        val allBlocked = primaryBlocked && secondaryBlocked
        return ControlResult(
            requested = MicAccessState.BLOCKED,
            observed = if (allBlocked) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
            controlReadback = allBlocked,
            durationMs = primaryResult.durationMs + (secondaryResult?.durationMs ?: 0L),
            errorCode = if (allBlocked) null else "REDUNDANT_GATE_BLOCK_UNVERIFIED",
            errorMessage = if (allBlocked) null else listOfNotNull(
                primaryResult.errorMessage,
                secondaryResult?.errorMessage,
                "主控制层与安全备用门未全部确认屏蔽",
            ).joinToString("；"),
        )
    }

    override suspend fun open(
        target: SafetyTarget,
        authorization: OpenAuthorization,
    ): ControlResult {
        val secondaryTarget = gateTarget(target)
        // The secondary OPEN command already performs a fresh readback under the Root lease
        // authorization lock. A separate read immediately before it adds latency and cannot
        // authorize the later mutation, so always use the atomic mutation+readback boundary.
        val secondaryOpen = openGate.open(secondaryTarget, authorization)
        if (
            (!secondaryOpen.controlReadback || secondaryOpen.observed != MicAccessState.OPEN)
        ) {
            return failedOpenAfterBlockingBoth(
                target = target,
                priorDurationMs = secondaryOpen.durationMs,
                errorCode = secondaryOpen.errorCode ?: "SECONDARY_GATE_OPEN_FAILED",
                errorMessage = secondaryOpen.errorMessage ?: "系统麦克风安全备用门无法开放",
            )
        }

        val primaryResult = primary.open(target, authorization)
        if (!primaryResult.controlReadback || primaryResult.observed != MicAccessState.OPEN) {
            return failedOpenAfterBlockingBoth(
                target = target,
                priorDurationMs = primaryResult.durationMs + secondaryOpen.durationMs,
                errorCode = primaryResult.errorCode ?: "PRIMARY_GATE_OPEN_FAILED",
                errorMessage = primaryResult.errorMessage ?: "主控制层无法确认开放",
            )
        }
        val secondary = openGate.readState(secondaryTarget)
        if (secondary == MicAccessState.OPEN) {
            return primaryResult.copy(
                durationMs = primaryResult.durationMs + secondaryOpen.durationMs,
            )
        }

        return failedOpenAfterBlockingBoth(
            target = target,
            priorDurationMs = primaryResult.durationMs + secondaryOpen.durationMs,
            errorCode = if (secondary == MicAccessState.BLOCKED) {
                "SECONDARY_GATE_BLOCKED"
            } else {
                "SECONDARY_GATE_UNKNOWN"
            },
            errorMessage = if (secondary == MicAccessState.BLOCKED) {
                "系统麦克风隐私层仍为屏蔽；已回滚 AudioManager OPEN"
            } else {
                "系统麦克风隐私层无法确认；已回滚 AudioManager OPEN"
            },
        )
    }

    override suspend fun readState(target: SafetyTarget?): MicAccessState {
        val primaryState = primary.readState(target)
        val fixedTarget = target ?: primary.captureSafetyTarget()
            ?: return MicAccessState.UNKNOWN
        val secondary = openGate.readState(gateTarget(fixedTarget))
        return when {
            primaryState == MicAccessState.BLOCKED && secondary == MicAccessState.BLOCKED ->
                MicAccessState.BLOCKED
            primaryState == MicAccessState.OPEN && secondary == MicAccessState.OPEN -> MicAccessState.OPEN
            else -> MicAccessState.UNKNOWN
        }
    }

    /**
     * An unsuccessful OPEN is never allowed to leave the other writable gate open. The rollback
     * reuses [block], which requires a fresh BLOCKED readback from both gates.
     */
    private suspend fun failedOpenAfterBlockingBoth(
        target: SafetyTarget,
        priorDurationMs: Long,
        errorCode: String,
        errorMessage: String,
    ): ControlResult {
        val rollback = block(target)
        val rollbackVerified =
            rollback.controlReadback && rollback.observed == MicAccessState.BLOCKED
        return ControlResult(
            requested = MicAccessState.OPEN,
            observed = if (rollbackVerified) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
            controlReadback = false,
            durationMs = priorDurationMs + rollback.durationMs,
            errorCode = errorCode,
            errorMessage = listOfNotNull(errorMessage, rollback.errorMessage)
                .joinToString("；"),
        )
    }
}
