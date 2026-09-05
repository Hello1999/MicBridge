package com.jack.micbridge.data

enum class MicAccessState(val wireValue: String) {
    BLOCKED("blocked"),
    OPEN("open"),
    UNKNOWN("unknown")
}

data class ProbeResult(
    val available: Boolean,
    val stateReadable: Boolean,
    val worksWhileLocked: Boolean? = null,
    val notes: String? = null,
)

data class ControlResult(
    val requested: MicAccessState,
    val observed: MicAccessState,
    val controlReadback: Boolean,
    val durationMs: Long,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val commandSucceeded: Boolean
        get() = errorCode == null
}

data class SafetyTarget(
    val controllerId: String,
    val targetPackage: String,
    val userId: Int,
)

sealed interface BridgeMicState {
    data object Starting : BridgeMicState
    data class Blocked(val observedAtEpochMs: Long) : BridgeMicState
    data class Open(
        val autoBlockAtEpochMs: Long?,
        val observedAtEpochMs: Long,
    ) : BridgeMicState
    data class Transitioning(
        val from: MicAccessState,
        val to: MicAccessState,
    ) : BridgeMicState
    data class ErrorUnverified(
        val lastRequested: MicAccessState,
        val message: String,
    ) : BridgeMicState
}

data class BridgeSnapshot(
    val micAccess: MicAccessState = MicAccessState.UNKNOWN,
    val transitioning: Boolean = false,
    val controlReadback: Boolean = false,
    val acousticCalibrationValid: Boolean = false,
    val controllerId: String = "none",
    val controllerProbe: String? = null,
    val serviceRunning: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val serverAddresses: List<String> = emptyList(),
    val autoBlockAtEpochMs: Long? = null,
    val leaseExactAlarmArmed: Boolean? = null,
    val leaseRootWatchdogArmed: Boolean? = null,
    val lastError: String? = null,
    val lastLatencyMs: Long? = null,
    val observedAtEpochMs: Long? = null,
)

data class OperationResult(
    val ok: Boolean,
    val commandSucceeded: Boolean = false,
    val micAccess: MicAccessState,
    val previous: MicAccessState,
    val controlReadback: Boolean,
    val acousticCalibrationValid: Boolean,
    val controllerId: String,
    val requestId: String?,
    val autoBlockAtEpochMs: Long?,
    val latencyMs: Long,
    val leaseExactAlarmArmed: Boolean? = null,
    val leaseRootWatchdogArmed: Boolean? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val replayed: Boolean = false,
    val originalOutcome: MicAccessState? = null,
)
