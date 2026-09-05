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
    /** True only while the calibration-only Root isolation split state exists. */
    val calibrationIsolationActive: Boolean = false,
    /** Human-readable reason the acoustic calibration is not usable; null when valid. */
    val calibrationInvalidReason: String? = null,
    /** Number of Root shell round-trips consumed by the most recent coordinator operation. */
    val rootRoundTripsLastOperation: Int? = null,
    val readiness: ReadinessSnapshot = ReadinessSnapshot(),
)

/**
 * Per-gate readiness for the UI checklist. Every field mirrors one admission condition the
 * service evaluates before it allows a remote OPEN; the snapshot is informational and never
 * substitutes for the fresh checks performed inside the coordinator.
 */
data class ReadinessSnapshot(
    val rootAvailable: Boolean? = null,
    val notificationPermitted: Boolean? = null,
    val localNetworkPermitted: Boolean? = null,
    val exactAlarmPermitted: Boolean? = null,
    val reliableModeReady: Boolean? = null,
    val userForeground: Boolean? = null,
    val rootBootGuardInstalled: Boolean? = null,
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
