package com.jack.micbridge.mic

import com.jack.micbridge.data.BeginRequestResult
import com.jack.micbridge.data.BridgeMicState
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.IdempotencyStore
import com.jack.micbridge.data.LedgerEntry
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult
import com.jack.micbridge.data.RequestLedger
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.safety.ActiveSafetyLease
import com.jack.micbridge.safety.LeaseSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MicCoordinator(
    private val controller: MicController,
    private val ledger: IdempotencyStore,
    private val leaseSafety: LeaseSafety,
    private val tokenGeneration: () -> Int,
    private val maxOpenSeconds: () -> Int,
    private val calibrationValid: () -> Boolean,
    private val persistentRemoteOpen: () -> Boolean = { false },
    private val openAllowed: () -> Boolean = { true },
    private val remoteOpenAllowed: (Long) -> Boolean = { true },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = nowEpochMs,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onSnapshot: (BridgeSnapshot) -> Unit = {},
    private val onOperation: (String, OperationResult) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private var state: BridgeMicState = BridgeMicState.Starting
    private var activeLeaseRequestId: String? = null
    private var activeLeaseTarget: SafetyTarget? = null
    private var activeLeaseDeadlineEpochMs: Long? = null
    private var activeLeaseDeadlineElapsedRealtimeMs: Long? = null
    private var activeLeaseExactAlarmArmed: Boolean? = null
    private var activeLeaseRootWatchdogArmed: Boolean? = null
    private var activeLeasePersistent = false
    private var calibrationIsolationActive = false

    suspend fun activeRootGuardHealthy(): Boolean = mutex.withLock {
        if (state !is BridgeMicState.Open || activeLeaseRootWatchdogArmed != true) {
            return@withLock true
        }
        val requestId = activeLeaseRequestId ?: return@withLock false
        leaseSafety.verifyActiveGuard(requestId)
    }
    suspend fun initialize(): ControlResult = mutex.withLock {
        restoreActiveLease(runCatching { leaseSafety.loadActiveLease() }.getOrNull())
        val interrupted = ledger.recoverInProgress()
        // Startup is a trust-boundary change, so it never inherits permission to stay OPEN.
        val block = blockThenCancelLeaseIfVerified()
        val now = nowEpochMs()
        interrupted.forEach {
            ledger.complete(
                requestId = it.requestId,
                outcome = block.observed,
                ok = false,
                errorCode = "RECOVERED_INTERRUPTED_REQUEST",
                errorMessage = "服务重启时发现未完成请求，已优先尝试屏蔽",
                nowEpochMs = now,
            )
        }
        updateStateFrom(block, autoBlockAt = null)
        block
    }

    suspend fun readSnapshot(serviceRunning: Boolean, addresses: List<String>): BridgeSnapshot =
        mutex.withLock {
            val observation = observeFailClosed()
            val snapshot = snapshotFor(
                observed = observation.observed,
                readback = observation.readback,
                serviceRunning = serviceRunning,
                addresses = addresses,
                latency = observation.latencyMs,
                error = observation.error,
            )
            onSnapshot(snapshot)
            snapshot
        }

    suspend fun execute(
        endpoint: String,
        requestId: String,
        remoteGeneration: Long = 0L,
    ): OperationResult {
        val operationStartedNanos = nanoTime()
        return mutex.withLock {
        val recordRemote: (OperationResult) -> OperationResult = { result ->
            record(endpoint, result, operationStartedNanos)
        }
        val now = nowEpochMs()
        when (val begin = ledger.begin(requestId, endpoint, tokenGeneration(), now)) {
            is BeginRequestResult.Conflict -> return@withLock recordRemote(
                errorResult(requestId, "REQUEST_ID_CONFLICT", begin.reason),
            )
            is BeginRequestResult.Existing -> return@withLock recordRemote(
                replay(begin.entry, requestId),
            )
            BeginRequestResult.New -> Unit
        }

        val previous = controller.readState(activeLeaseTarget)
        val target = when (endpoint) {
            ENDPOINT_TOGGLE -> when (previous) {
                MicAccessState.BLOCKED -> MicAccessState.OPEN
                MicAccessState.OPEN -> MicAccessState.BLOCKED
                MicAccessState.UNKNOWN -> MicAccessState.BLOCKED
            }
            ENDPOINT_OPEN -> MicAccessState.OPEN
            ENDPOINT_BLOCK -> MicAccessState.BLOCKED
            else -> {
                val result = errorResult(requestId, "UNKNOWN_OPERATION", "未知操作")
                persist(requestId, result)
                return@withLock recordRemote(result)
            }
        }

        if (target == MicAccessState.OPEN && !remoteOpenAllowed(remoteGeneration)) {
            val blocked = blockThenCancelLeaseIfVerified()
            val result = operationFrom(
                control = blocked,
                previous = previous,
                requestId = requestId,
                forcedOk = false,
                overrideCode = "REMOTE_OPEN_DISABLED",
                overrideMessage = "网络监听正在停止或重绑定；已拒绝开放并优先尝试屏蔽",
            )
            updateStateFrom(blocked, null)
            persist(requestId, result)
            return@withLock recordRemote(result)
        }

        if (target == MicAccessState.OPEN && previous == MicAccessState.UNKNOWN) {
            val blocked = blockThenCancelLeaseIfVerified()
            val result = operationFrom(
                control = blocked,
                previous = previous,
                requestId = requestId,
                forcedOk = false,
                overrideCode = "OPEN_PRECONDITION_UNKNOWN",
                overrideMessage = "开放前无法确认当前状态；已拒绝开放并优先尝试屏蔽",
            )
            updateStateFrom(blocked, null)
            persist(requestId, result)
            return@withLock recordRemote(result)
        }

        val result = transition(
            previous,
            target,
            requestId,
            requireCalibration = true,
            remoteGeneration = remoteGeneration,
            persistentOpen = endpoint == ENDPOINT_TOGGLE &&
                target == MicAccessState.OPEN && persistentRemoteOpen(),
        )
        persist(requestId, result)
        recordRemote(result)
        }
    }

    suspend fun localBlock(sourceId: String = "local-${UUID.randomUUID()}"): OperationResult =
        mutex.withLock {
            val previous = controller.readState(activeLeaseTarget)
            record(
                auditSource(sourceId),
                transition(
                    previous,
                    MicAccessState.BLOCKED,
                    sourceId,
                    requireCalibration = false,
                    remoteGeneration = null,
                    persistentOpen = false,
                ),
            )
        }

    suspend fun calibrationOpen(): OperationResult = mutex.withLock {
        // A freshly initialized BLOCKED coordinator already owns a verified observation. The
        // guarded OPEN transition will block and verify again before arming its fail-safe lease,
        // so another Root read here only delays the user's explicit calibration action.
        val previous = if (state is BridgeMicState.Blocked) {
            MicAccessState.BLOCKED
        } else {
            controller.readState(activeLeaseTarget)
        }
        record(
            "calibration",
            transition(
                previous,
                MicAccessState.OPEN,
                "cal-${UUID.randomUUID()}",
                requireCalibration = false,
                remoteGeneration = null,
                persistentOpen = false,
            ),
        )
    }

    /**
     * Proves the persistent Root fail-safe independently from AudioManager. The normal combined
     * OPEN first establishes a guarded lease. This step then blocks sensor_privacy and explicitly
     * re-opens AudioManager while retaining that lease. A ROM on which the two controls are merely
     * aliases cannot pass: both fresh readbacks must remain BLOCKED/OPEN respectively.
     *
     * The combined state is deliberately published as UNKNOWN while this calibration-only split
     * state exists. The caller must acoustically verify silence and immediately finish with the
     * ordinary full BLOCK, which cancels the retained lease only after both gates read BLOCKED.
     */
    suspend fun calibrationIsolateRootFailsafe(
        rootGate: MicController,
        audioGate: MicController,
        appOpsVeto: ReadOnlyOpenVeto,
        immediateRootBlock: suspend () -> Boolean = { true },
    ): ControlResult = mutex.withLock {
        val requestId = activeLeaseRequestId
        val deadlineElapsed = activeLeaseDeadlineElapsedRealtimeMs
        val leaseHealthy = state is BridgeMicState.Open && hasHealthyActiveLease()
        if (
            !leaseHealthy || requestId == null || deadlineElapsed == null
        ) {
            return@withLock failCalibrationIsolation(
                "CALIBRATION_OPEN_LEASE_REQUIRED",
                "必须先完成带 Root 监督器的临时 OPEN；已优先恢复全局屏蔽",
            )
        }

        val leaseTarget = activeLeaseTarget ?: return@withLock failCalibrationIsolation(
            "CALIBRATION_TARGET_UNKNOWN",
            "校准租约目标无法确认；已优先恢复全局屏蔽",
        )
        if (!immediateRootBlock()) {
            return@withLock failCalibrationIsolation(
                "CALIBRATION_IMMEDIATE_ROOT_BLOCK_FAILED",
                "无法立即执行当前用户的 Root 屏蔽；已优先恢复全局屏蔽",
            )
        }
        val rootTarget = rootGate.captureSafetyTarget()
        val audioTarget = audioGate.captureSafetyTarget()
        if (
            rootTarget == null || audioTarget == null ||
            rootTarget.userId != leaseTarget.userId || audioTarget.userId != leaseTarget.userId
        ) {
            return@withLock failCalibrationIsolation(
                "CALIBRATION_TARGET_CHANGED",
                "隔离校准的 Android 用户与活动租约不一致；已优先恢复全局屏蔽",
            )
        }

        state = BridgeMicState.Transitioning(MicAccessState.OPEN, MicAccessState.BLOCKED)
        try {
            onSnapshot(
                snapshotFor(
                    observed = MicAccessState.UNKNOWN,
                    readback = false,
                    serviceRunning = true,
                    addresses = emptyList(),
                    error = "正在建立 Root-only 隔离校准边界",
                    calibrationOverride = false,
                ),
            )
        } catch (error: Throwable) {
            return@withLock failCalibrationIsolation(
                "CALIBRATION_PUBLICATION_FAILED",
                "隔离校准状态无法发布（${error.javaClass.simpleName}）；已恢复全局屏蔽",
            )
        }

        val isolated = try {
            leaseSafety.withMutationLock {
                if (!hasValidActiveLease()) {
                    return@withMutationLock controlFailure(
                        MicAccessState.BLOCKED,
                        "CALIBRATION_LEASE_EXPIRED",
                        "隔离校准开始前安全租约已到期",
                    )
                }
                if (appOpsVeto.readState(leaseTarget) != MicAccessState.OPEN) {
                    return@withMutationLock controlFailure(
                        MicAccessState.BLOCKED,
                        "CALIBRATION_APPOPS_NOT_OPEN",
                        "ChatGPT RECORD_AUDIO AppOps 不是可确认的允许状态",
                    )
                }
                val rootBlocked = rootGate.block(rootTarget)
                if (
                    !rootBlocked.controlReadback ||
                    rootBlocked.observed != MicAccessState.BLOCKED
                ) {
                    return@withMutationLock rootBlocked.copy(
                        errorCode = rootBlocked.errorCode ?: "CALIBRATION_ROOT_BLOCK_FAILED",
                        errorMessage = rootBlocked.errorMessage ?: "Root sensor_privacy 无法确认屏蔽",
                    )
                }

                // Some ROMs mirror sensor privacy into AudioManager. Re-issuing AudioManager
                // OPEN here is intentional: the acoustic test must isolate the Root gate alone.
                val audioOpened = audioGate.open(
                    audioTarget,
                    OpenAuthorization(requestId, deadlineElapsed),
                )
                val rootFresh = rootGate.readState(rootTarget)
                val audioFresh = audioGate.readState(audioTarget)
                val appOpsFresh = appOpsVeto.readState(leaseTarget)
                val verified =
                    audioOpened.controlReadback &&
                        audioOpened.observed == MicAccessState.OPEN &&
                        rootFresh == MicAccessState.BLOCKED &&
                        audioFresh == MicAccessState.OPEN &&
                        appOpsFresh == MicAccessState.OPEN &&
                        hasValidActiveLease()
                ControlResult(
                    requested = MicAccessState.BLOCKED,
                    observed = if (verified) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
                    controlReadback = verified,
                    durationMs = rootBlocked.durationMs + audioOpened.durationMs,
                    errorCode = if (verified) null else "CALIBRATION_ROOT_NOT_ISOLATED",
                    errorMessage = if (verified) null else listOfNotNull(
                        audioOpened.errorMessage,
                        "无法同时确认 Root sensor_privacy=BLOCKED、AudioManager=OPEN 且 AppOps=允许",
                    ).joinToString("；"),
                )
            }
        } catch (error: Exception) {
            controlFailure(
                MicAccessState.BLOCKED,
                "CALIBRATION_ISOLATION_EXCEPTION",
                "Root 隔离校准异常：${error.javaClass.simpleName}",
            )
        }

        val guardStillHealthy = isolated.controlReadback && hasHealthyActiveLease()
        if (!guardStillHealthy) {
            return@withLock failCalibrationIsolation(
                isolated.errorCode ?: "CALIBRATION_GUARD_UNHEALTHY",
                isolated.errorMessage ?: "隔离校准后 Root 监督器健康证明丢失；已恢复全局屏蔽",
            )
        }

        calibrationIsolationActive = true
        state = BridgeMicState.ErrorUnverified(
            MicAccessState.BLOCKED,
            "Root 隔离校准中：仅 sensor_privacy 已屏蔽，AudioManager 保持开放",
        )
        try {
            onSnapshot(
                snapshotFor(
                    observed = MicAccessState.UNKNOWN,
                    readback = false,
                    serviceRunning = true,
                    addresses = emptyList(),
                    latency = isolated.durationMs,
                    error = "隔离校准模式：请确认 ChatGPT 已听不到声音，然后立即确认并完整屏蔽",
                    calibrationOverride = false,
                ),
            )
        } catch (error: Throwable) {
            calibrationIsolationActive = false
            return@withLock failCalibrationIsolation(
                "CALIBRATION_PUBLICATION_FAILED",
                "隔离状态发布失败（${error.javaClass.simpleName}）；已恢复全局屏蔽",
            )
        }
        isolated
    }

    /** Freshly acknowledges the human acoustic observation only while the split state exists. */
    suspend fun confirmRootIsolationAndBlock(
        rootGate: MicController,
        audioGate: MicController,
        appOpsVeto: ReadOnlyOpenVeto,
    ): ControlResult = mutex.withLock {
        val leaseTarget = activeLeaseTarget
        val rootTarget = rootGate.captureSafetyTarget()
        val audioTarget = audioGate.captureSafetyTarget()
        val splitVerified = try {
            calibrationIsolationActive &&
                leaseTarget != null && rootTarget != null && audioTarget != null &&
                rootTarget.userId == leaseTarget.userId &&
                audioTarget.userId == leaseTarget.userId &&
                hasHealthyActiveLease() &&
                rootGate.readState(rootTarget) == MicAccessState.BLOCKED &&
                audioGate.readState(audioTarget) == MicAccessState.OPEN &&
                appOpsVeto.readState(leaseTarget) == MicAccessState.OPEN
        } catch (_: Throwable) {
            false
        }
        if (!splitVerified) {
            return@withLock failCalibrationIsolation(
                "CALIBRATION_ISOLATION_NOT_CURRENT",
                "确认时 Root-only 隔离状态、AppOps 或监督器已变化；本轮证据作废",
            )
        }

        val blocked = blockThenCancelLeaseIfVerified()
        updateStateFrom(blocked, null, calibrationOverride = false)
        val complete = blocked.controlReadback &&
            blocked.observed == MicAccessState.BLOCKED &&
            blocked.errorCode != "LEASE_CANCEL_FAILED"
        if (complete) {
            blocked
        } else {
            blocked.copy(
                controlReadback = false,
                errorCode = blocked.errorCode ?: "CALIBRATION_FINAL_BLOCK_UNVERIFIED",
                errorMessage = blocked.errorMessage
                    ?: "Root-only 声学确认已接收，但最终完整屏蔽无法确认",
            )
        }
    }

    private suspend fun failCalibrationIsolation(
        code: String,
        message: String,
    ): ControlResult {
        calibrationIsolationActive = false
        val rollback = blockThenCancelLeaseIfVerified()
        updateStateFrom(rollback, null, calibrationOverride = false)
        return ControlResult(
            requested = MicAccessState.BLOCKED,
            observed = rollback.observed,
            controlReadback = false,
            durationMs = rollback.durationMs,
            errorCode = code,
            errorMessage = listOfNotNull(message, rollback.errorMessage).joinToString("；"),
        )
    }

    private suspend fun transition(
        previous: MicAccessState,
        target: MicAccessState,
        requestId: String,
        requireCalibration: Boolean,
        remoteGeneration: Long?,
        persistentOpen: Boolean,
    ): OperationResult {
        calibrationIsolationActive = false
        state = BridgeMicState.Transitioning(previous, target)
        onSnapshot(
            snapshotFor(
                observed = MicAccessState.UNKNOWN,
                readback = false,
                serviceRunning = true,
                addresses = emptyList(),
            ),
        )
        val lifecycleAllowedAtEntry = target != MicAccessState.OPEN || openAllowed()
        if (!lifecycleAllowedAtEntry) {
            return failedOpen(
                previous,
                requestId,
                "OPEN_LIFECYCLE_DISABLED",
                "服务正在停止或安全权限不可用；已拒绝开放并优先尝试屏蔽",
            )
        }
        if (target == MicAccessState.OPEN && requireCalibration && !calibrationValid()) {
            val safetyBlock = blockThenCancelLeaseIfVerified()
            updateStateFrom(safetyBlock, null)
            return operationFrom(
                control = safetyBlock,
                previous = previous,
                requestId = requestId,
                forcedOk = false,
                overrideCode = "ACOUSTIC_CALIBRATION_REQUIRED",
                overrideMessage = "当前固件、控制器与 ChatGPT 版本尚未通过声学校准",
            )
        }

        // Explicit /open while an OPEN lease is already live is a state assertion, not a
        // renewal. Replacing generation A before generation B is durably guarded creates a
        // crash window with no independent watcher, and repeated calls could extend the
        // nominal 30-second maximum indefinitely.
        if (target == MicAccessState.OPEN && previous == MicAccessState.OPEN) {
            val lifecycleStillAllowedBeforeRead = openAllowed()
            val generationStillAllowedBeforeRead =
                remoteGeneration == null || remoteOpenAllowed(remoteGeneration)
            val leaseHealthyBeforeRead = hasHealthyActiveLease()
            val freshState = controller.readState(activeLeaseTarget)
            val lifecycleStillAllowed = lifecycleStillAllowedBeforeRead && openAllowed()
            val generationStillAllowed = generationStillAllowedBeforeRead &&
                (remoteGeneration == null || remoteOpenAllowed(remoteGeneration))
            val leaseStillHealthy = leaseHealthyBeforeRead && hasHealthyActiveLease()
            if (
                !lifecycleStillAllowed || !generationStillAllowed || !leaseStillHealthy ||
                freshState != MicAccessState.OPEN
            ) {
                return failedOpen(
                    previous,
                    requestId,
                    if (!lifecycleStillAllowed) {
                        "OPEN_LIFECYCLE_DISABLED"
                    } else if (!generationStillAllowed) {
                        "REMOTE_GENERATION_REVOKED"
                    } else if (freshState != MicAccessState.OPEN) {
                        "OPEN_ASSERTION_NOT_OPEN"
                    } else {
                        "OPEN_WITHOUT_VALID_LEASE"
                    },
                    "OPEN 断言的新鲜控制读回或安全租约健康证明不成立；已优先尝试屏蔽",
                )
            }
            val calibrationAtCommit = !requireCalibration || calibrationValid()
            if (!calibrationAtCommit) {
                return failedOpen(
                    previous,
                    requestId,
                    "ACOUSTIC_CALIBRATION_REVOKED",
                    "声学校准在 OPEN 状态确认期间失效；已立即优先尝试屏蔽",
                )
            }
            val deadline = activeLeaseDeadlineEpochMs.takeUnless { activeLeasePersistent }
            val confirmed = ControlResult(
                requested = MicAccessState.OPEN,
                observed = MicAccessState.OPEN,
                controlReadback = true,
                durationMs = 0L,
            )
            updateStateFrom(
                confirmed,
                deadline,
                calibrationOverride = if (requireCalibration) calibrationAtCommit else null,
            )
            return operationFrom(
                confirmed,
                previous,
                requestId,
                autoBlockAt = deadline,
                calibrationOverride = if (requireCalibration) calibrationAtCommit else null,
            )
        }

        if (target == MicAccessState.OPEN) {
            val frozenTarget = controller.captureSafetyTarget()
                ?: return failedOpen(
                    previous,
                    requestId,
                    "CONTROL_CONTEXT_UNKNOWN",
                    "无法冻结控制目标；已拒绝开放并优先尝试屏蔽",
                )
            val existingTarget = activeLeaseTarget
            if (existingTarget != null && existingTarget != frozenTarget) {
                val safetyBlock = blockThenCancelLeaseIfVerified()
                updateStateFrom(safetyBlock, null)
                return operationFrom(
                    safetyBlock,
                    previous,
                    requestId,
                    forcedOk = false,
                    overrideCode = "CONTROL_CONTEXT_CHANGED",
                    overrideMessage = "已有安全租约属于另一控制目标；已拒绝开放并优先尝试屏蔽",
                )
            }

            // These fields describe a durable lease, not an in-flight arm attempt. Publishing
            // the target before `arm` commits would make rollback call `cancel()` even when the
            // guard rejected the request before writing a lease (for example, after ownership
            // recovery). Reconcile from the durable store on every failed/exceptional arm.
            val lease = try {
                if (persistentOpen) {
                    leaseSafety.armPersistent(requestId, frozenTarget)
                } else {
                    leaseSafety.arm(requestId, maxOpenSeconds(), frozenTarget)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reconcileDurableLeaseAfterFailedArm(
                    requestId = requestId,
                    target = frozenTarget,
                )
                return failedOpen(
                    previous,
                    requestId,
                    "LEASE_GUARD_EXCEPTION",
                    "安全租约布防异常：${error.javaClass.simpleName}",
                    fallbackTarget = frozenTarget,
                )
            }
            if (!lease.armed) {
                reconcileDurableLeaseAfterFailedArm(
                    requestId = requestId,
                    target = frozenTarget,
                    deadlineEpochMs = lease.deadlineEpochMs,
                    deadlineElapsedRealtimeMs = lease.deadlineElapsedRealtimeMs,
                    exactAlarmArmed = lease.exactAlarmArmed,
                    rootWatchdogArmed = lease.rootWatchdogArmed,
                    persistent = lease.persistent,
                )
                val safetyBlock = blockThenCancelLeaseIfVerified(fallbackTarget = frozenTarget)
                updateStateFrom(safetyBlock, null)
                return operationFrom(
                    control = safetyBlock,
                    previous = previous,
                    requestId = requestId,
                    forcedOk = false,
                    overrideCode = "LEASE_GUARD_NOT_ARMED",
                    overrideMessage = lease.error ?: if (persistentOpen) {
                        "持续开放安全监督器未布防"
                    } else {
                        "临时开放安全租约未布防"
                    },
                )
            }
            activeLeaseTarget = frozenTarget
            activeLeaseRequestId = requestId
            activeLeaseDeadlineEpochMs = lease.deadlineEpochMs.takeIf { it > 0L }
            activeLeaseDeadlineElapsedRealtimeMs = lease.deadlineElapsedRealtimeMs.takeIf { it > 0L }
            activeLeaseExactAlarmArmed = lease.exactAlarmArmed
            activeLeaseRootWatchdogArmed = lease.rootWatchdogArmed
            activeLeasePersistent = lease.persistent

            // Do not create even a brief capture window when a guard reports internally
            // inconsistent flags, or when setup consumed the entire monotonic lease. The
            // same check runs again after OPEN to close the concurrent-expiry boundary.
            val lifecycleAllowedBeforeOpen = openAllowed()
            val generationAllowedBeforeOpen =
                remoteGeneration == null || remoteOpenAllowed(remoteGeneration)
            val leaseValidBeforeOpen = hasValidActiveLease()
            if (!lifecycleAllowedBeforeOpen || !generationAllowedBeforeOpen || !leaseValidBeforeOpen) {
                val safetyBlock = blockThenCancelLeaseIfVerified()
                updateStateFrom(safetyBlock, null)
                return operationFrom(
                    control = safetyBlock,
                    previous = previous,
                    requestId = requestId,
                    forcedOk = false,
                    overrideCode = if (!lifecycleAllowedBeforeOpen) {
                        "OPEN_LIFECYCLE_DISABLED"
                    } else if (!generationAllowedBeforeOpen) {
                        "REMOTE_GENERATION_REVOKED"
                    } else {
                        "LEASE_INVALID_BEFORE_OPEN"
                    },
                    overrideMessage = "开放许可、租约期限或保护标志在执行前已失效；已拒绝开放",
                )
            }

            val openValidUntilElapsed = if (activeLeasePersistent) {
                0L
            } else {
                activeLeaseDeadlineElapsedRealtimeMs
                    ?: return failedOpen(
                        previous,
                        requestId,
                        "LEASE_INVALID_BEFORE_OPEN",
                        "安全租约缺少 OPEN 授权截止时间；已拒绝开放",
                    )
            }
            val authorization = OpenAuthorization(
                requestId,
                openValidUntilElapsed,
                persistent = activeLeasePersistent,
            )
            val opened = try {
                leaseSafety.withMutationLock {
                    val lifecycleAllowedAtDispatch = openAllowed()
                    val generationAllowedAtDispatch =
                        remoteGeneration == null || remoteOpenAllowed(remoteGeneration)
                    val calibrationAllowedAtDispatch =
                        !requireCalibration || calibrationValid()
                    if (
                        !lifecycleAllowedAtDispatch ||
                        !generationAllowedAtDispatch ||
                        !calibrationAllowedAtDispatch ||
                        !hasValidActiveLease()
                    ) {
                        controlFailure(
                            MicAccessState.OPEN,
                            if (!lifecycleAllowedAtDispatch) {
                                "OPEN_LIFECYCLE_DISABLED"
                            } else if (!generationAllowedAtDispatch) {
                                "REMOTE_GENERATION_REVOKED"
                            } else if (!calibrationAllowedAtDispatch) {
                                "ACOUSTIC_CALIBRATION_REVOKED"
                            } else {
                                "LEASE_INVALID_BEFORE_OPEN"
                            },
                            "OPEN 指令排队期间授权窗口已关闭",
                        )
                    } else {
                        val candidate = controller.open(frozenTarget, authorization)
                        val lifecycleAllowedAfterOpen = openAllowed()
                        val generationAllowedAfterOpen =
                            remoteGeneration == null || remoteOpenAllowed(remoteGeneration)
                        val calibrationAllowedAfterOpen =
                            !requireCalibration || calibrationValid()
                        val leaseAllowedAfterOpen = hasValidActiveLease()
                        val rootGuardAllowedAfterOpen =
                            activeLeaseRootWatchdogArmed != true ||
                                leaseSafety.verifyActiveGuard(requestId)
                        if (
                            candidate.controlReadback &&
                            candidate.observed == MicAccessState.OPEN &&
                            (!lifecycleAllowedAfterOpen ||
                                !generationAllowedAfterOpen ||
                                !calibrationAllowedAfterOpen ||
                                !leaseAllowedAfterOpen ||
                                !rootGuardAllowedAfterOpen)
                        ) {
                            // Keep the process-wide alarm/lease gate until a late OPEN has
                            // synchronously been rolled back. A delivered alarm cannot BLOCK
                            // first and then be undone by this in-flight OPEN.
                            val rollback = controller.block(frozenTarget)
                            ControlResult(
                                requested = MicAccessState.OPEN,
                                observed = rollback.observed,
                                controlReadback = false,
                                durationMs = candidate.durationMs + rollback.durationMs,
                                errorCode = if (!lifecycleAllowedAfterOpen) {
                                    "OPEN_LIFECYCLE_DISABLED"
                                } else if (!generationAllowedAfterOpen) {
                                    "REMOTE_GENERATION_REVOKED"
                                } else if (!calibrationAllowedAfterOpen) {
                                    "ACOUSTIC_CALIBRATION_REVOKED"
                                } else if (!rootGuardAllowedAfterOpen) {
                                    "LEASE_GUARD_UNHEALTHY"
                                } else {
                                    "LEASE_EXPIRED_DURING_OPEN"
                                },
                                errorMessage = "OPEN 在授权失效后才完成；已在同一安全闸内回滚",
                            )
                        } else {
                            candidate
                        }
                    }
                }
            } catch (error: Exception) {
                controlFailure(
                    MicAccessState.OPEN,
                    "OPEN_GUARD_EXCEPTION",
                    "OPEN 安全闸异常：${error.javaClass.simpleName}",
                )
            }
            if (
                opened.errorCode != null ||
                !opened.controlReadback ||
                opened.observed != MicAccessState.OPEN
            ) {
                val safetyBlock = blockThenCancelLeaseIfVerified()
                updateStateFrom(safetyBlock, null)
                return operationFrom(
                    control = safetyBlock,
                    previous = previous,
                    requestId = requestId,
                    forcedOk = false,
                    overrideCode = opened.errorCode ?: "OPEN_FAILED",
                    overrideMessage = opened.errorMessage ?: "开放失败，已尝试重新屏蔽",
                )
            }

            val generationStillAllowed =
                remoteGeneration == null || remoteOpenAllowed(remoteGeneration)
            val deadlineStillValid = hasValidActiveLease()
            val lifecycleStillAllowed = openAllowed()
            val calibrationAtCommit = !requireCalibration || calibrationValid()
            val rootGuardStillHealthy =
                activeLeaseRootWatchdogArmed != true ||
                    leaseSafety.verifyActiveGuard(requestId)
            if (
                !lifecycleStillAllowed ||
                !generationStillAllowed ||
                !calibrationAtCommit ||
                !deadlineStillValid ||
                !rootGuardStillHealthy
            ) {
                val safetyBlock = blockThenCancelLeaseIfVerified()
                updateStateFrom(safetyBlock, null)
                return operationFrom(
                    control = safetyBlock,
                    previous = previous,
                    requestId = requestId,
                    forcedOk = false,
                    overrideCode = if (!lifecycleStillAllowed) {
                        "OPEN_LIFECYCLE_DISABLED"
                    } else if (!generationStillAllowed) {
                        "REMOTE_GENERATION_REVOKED"
                    } else if (!calibrationAtCommit) {
                        "ACOUSTIC_CALIBRATION_REVOKED"
                    } else if (!rootGuardStillHealthy) {
                        "LEASE_GUARD_UNHEALTHY"
                    } else {
                        "LEASE_EXPIRED_DURING_OPEN"
                    },
                    overrideMessage = if (!lifecycleStillAllowed) {
                        "服务生命周期开放许可已撤销；开放后已立即优先尝试屏蔽"
                    } else if (!generationStillAllowed) {
                        "HTTP 监听代次已失效；开放后已立即优先尝试屏蔽"
                    } else if (!calibrationAtCommit) {
                        "声学校准在开放确认前失效；已立即优先尝试屏蔽"
                    } else if (!rootGuardStillHealthy) {
                        "Root 租约监督器在开放确认前失去健康证明；已立即优先尝试屏蔽"
                    } else {
                        "安全租约在开放确认前已到期；已立即优先尝试屏蔽"
                    },
                )
            }

            updateStateFrom(
                opened,
                lease.deadlineEpochMs.takeUnless { lease.persistent },
                calibrationOverride = if (requireCalibration) calibrationAtCommit else null,
            )
            return operationFrom(
                opened,
                previous,
                requestId,
                autoBlockAt = lease.deadlineEpochMs.takeUnless { lease.persistent },
                calibrationOverride = if (requireCalibration) calibrationAtCommit else null,
            )
        }

        val blocked = blockThenCancelLeaseIfVerified()
        updateStateFrom(blocked, null)
        return operationFrom(blocked, previous, requestId)
    }

    private suspend fun failedOpen(
        previous: MicAccessState,
        requestId: String,
        code: String,
        message: String,
        fallbackTarget: SafetyTarget? = null,
    ): OperationResult {
        val safetyBlock = blockThenCancelLeaseIfVerified(fallbackTarget)
        updateStateFrom(safetyBlock, null)
        return operationFrom(
            safetyBlock,
            previous,
            requestId,
            forcedOk = false,
            overrideCode = code,
            overrideMessage = message,
        )
    }

    private suspend fun replay(entry: LedgerEntry, requestId: String): OperationResult {
        if (entry.status == RequestLedger.STATUS_IN_PROGRESS) {
            val blocked = blockThenCancelLeaseIfVerified()
            updateStateFrom(blocked, null)
            val result = operationFrom(
                blocked,
                previous = MicAccessState.UNKNOWN,
                requestId = requestId,
                forcedOk = false,
                overrideCode = "INTERRUPTED_REQUEST",
                overrideMessage = "原请求未完成；已优先尝试屏蔽且未再次切换",
            ).copy(replayed = true, originalOutcome = entry.outcome)
            persist(requestId, result)
            return result
        }
        val observation = observeFailClosed()
        publishObservation(observation)
        val current = observation.observed
        val safe = observation.error == null && observation.readback
        return OperationResult(
            ok = entry.ok == true && safe && calibrationValid(),
            commandSucceeded = false,
            micAccess = current,
            previous = current,
            controlReadback = observation.readback,
            acousticCalibrationValid = calibrationValid(),
            controllerId = controller.id,
            requestId = requestId,
            autoBlockAtEpochMs = activeAutoBlockAtEpochMs(),
            latencyMs = observation.latencyMs,
            leaseExactAlarmArmed = activeLeaseExactAlarmArmed,
            leaseRootWatchdogArmed = activeLeaseRootWatchdogArmed,
            errorCode = when {
                observation.error != null -> "REPLAY_SAFETY_RECONCILIATION"
                current == MicAccessState.UNKNOWN -> "REPLAY_STATE_UNKNOWN"
                else -> entry.errorCode
            },
            errorMessage = observation.error
                ?: if (current == MicAccessState.UNKNOWN) {
                    "重复请求未再次执行，当前状态无法读取"
                } else {
                    entry.errorMessage
                },
            replayed = true,
            originalOutcome = entry.outcome,
        )
    }

    private fun operationFrom(
        control: ControlResult,
        previous: MicAccessState,
        requestId: String,
        autoBlockAt: Long? = null,
        forcedOk: Boolean? = null,
        overrideCode: String? = null,
        overrideMessage: String? = null,
        calibrationOverride: Boolean? = null,
    ): OperationResult {
        val calibrated = calibrationOverride ?: calibrationValid()
        val verified = control.controlReadback && calibrated
        return OperationResult(
            ok = forcedOk ?: (control.errorCode == null && verified),
            commandSucceeded = control.errorCode == null && overrideCode == null,
            micAccess = control.observed,
            previous = previous,
            controlReadback = control.controlReadback,
            acousticCalibrationValid = calibrated,
            controllerId = controller.id,
            requestId = requestId,
            autoBlockAtEpochMs = autoBlockAt,
            latencyMs = control.durationMs,
            leaseExactAlarmArmed = activeLeaseExactAlarmArmed,
            leaseRootWatchdogArmed = activeLeaseRootWatchdogArmed,
            errorCode = overrideCode ?: control.errorCode
                ?: if (!calibrated) "ACOUSTIC_CALIBRATION_REQUIRED" else null,
            errorMessage = overrideMessage ?: control.errorMessage
                ?: if (!calibrated) "声学校准已失效或尚未完成" else null,
        )
    }

    private suspend fun errorResult(
        requestId: String,
        code: String,
        message: String,
    ): OperationResult {
        val observation = observeFailClosed()
        publishObservation(observation)
        return OperationResult(
            ok = false,
            commandSucceeded = false,
            micAccess = observation.observed,
            previous = observation.observed,
            controlReadback = observation.readback,
            acousticCalibrationValid = calibrationValid(),
            controllerId = controller.id,
            requestId = requestId,
            autoBlockAtEpochMs = activeAutoBlockAtEpochMs(),
            latencyMs = observation.latencyMs,
            leaseExactAlarmArmed = activeLeaseExactAlarmArmed,
            leaseRootWatchdogArmed = activeLeaseRootWatchdogArmed,
            errorCode = code,
            errorMessage = if (observation.error == null) message else "$message；${observation.error}",
        )
    }

    private fun persist(requestId: String, result: OperationResult) {
        ledger.complete(
            requestId = requestId,
            outcome = result.micAccess,
            ok = result.ok,
            errorCode = result.errorCode,
            errorMessage = result.errorMessage,
            nowEpochMs = nowEpochMs(),
        )
    }

    private fun record(
        source: String,
        result: OperationResult,
        startedNanos: Long? = null,
    ): OperationResult {
        val measured = if (startedNanos == null) result else result.copy(
            latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                nanoTime() - startedNanos,
            ),
        )
        if (!measured.ok || measured.errorCode != null) {
            // A successful fail-closed rollback must not erase the failure that caused it.
            // Keep Android's notification/UI honest even when the final physical state is the
            // safer BLOCKED state and the HTTP caller already received ok=false.
            onSnapshot(
                snapshotFor(
                    observed = measured.micAccess,
                    readback = measured.controlReadback,
                    serviceRunning = true,
                    addresses = emptyList(),
                    latency = measured.latencyMs,
                    error = measured.errorMessage ?: measured.errorCode,
                ),
            )
        }
        onOperation(source, measured)
        return measured
    }

    private fun auditSource(sourceId: String): String = when {
        sourceId.startsWith("in-process-timeout") -> "timer"
        sourceId.startsWith("notification-") -> "notification"
        sourceId.startsWith("network-") -> "network"
        sourceId.startsWith("permission-") -> "permission"
        sourceId.startsWith("audio-drift-") -> "control-drift"
        sourceId.startsWith("http-") -> "http-failsafe"
        sourceId.startsWith("task-") -> "task-removal"
        sourceId.startsWith("service-") -> "service-lifecycle"
        sourceId.startsWith("calibration-") -> "calibration"
        else -> sourceId.substringBefore('-').take(64)
    }

    private fun updateStateFrom(
        result: ControlResult,
        autoBlockAt: Long?,
        calibrationOverride: Boolean? = null,
    ) {
        val now = nowEpochMs()
        state = when {
            result.controlReadback && result.observed == MicAccessState.BLOCKED ->
                BridgeMicState.Blocked(now)
            result.controlReadback && result.observed == MicAccessState.OPEN -> {
                if (autoBlockAt != null) activeLeaseDeadlineEpochMs = autoBlockAt
                BridgeMicState.Open(autoBlockAt, now)
            }
            else -> BridgeMicState.ErrorUnverified(
                result.requested,
                result.errorMessage ?: "状态无法确认",
            )
        }
        onSnapshot(
            snapshotFor(
                observed = result.observed,
                readback = result.controlReadback,
                serviceRunning = true,
                addresses = emptyList(),
                latency = result.durationMs,
                error = result.errorMessage,
                calibrationOverride = calibrationOverride,
            ),
        )
    }

    private fun snapshotFor(
        observed: MicAccessState,
        readback: Boolean,
        serviceRunning: Boolean,
        addresses: List<String>,
        latency: Long? = null,
        error: String? = null,
        calibrationOverride: Boolean? = null,
    ) = BridgeSnapshot(
        micAccess = observed,
        transitioning = state is BridgeMicState.Transitioning,
        controlReadback = readback,
        acousticCalibrationValid = calibrationOverride ?: calibrationValid(),
        controllerId = controller.id,
        serviceRunning = serviceRunning,
        serverAddresses = addresses,
        autoBlockAtEpochMs = activeAutoBlockAtEpochMs(),
        leaseExactAlarmArmed = activeLeaseExactAlarmArmed,
        leaseRootWatchdogArmed = activeLeaseRootWatchdogArmed,
        lastError = error,
        lastLatencyMs = latency,
        observedAtEpochMs = nowEpochMs(),
    )

    /**
     * Replays and request-conflict paths can reconcile an externally changed state or
     * synchronously BLOCK an unsafe OPEN. They still have to update the UI/notification;
     * returning the fresh state only to the HTTP client would leave Android's visible state
     * source stale until a later status request or timer.
     */
    private fun publishObservation(observation: SafetyObservation) {
        onSnapshot(
            snapshotFor(
                observed = observation.observed,
                readback = observation.readback,
                serviceRunning = true,
                addresses = emptyList(),
                latency = observation.latencyMs,
                error = observation.error,
            ),
        )
    }

    /**
     * Never disarm a fail-safe until the exact persisted target has a fresh BLOCKED readback.
     */
    private suspend fun blockThenCancelLeaseIfVerified(
        fallbackTarget: SafetyTarget? = null,
    ): ControlResult {
        calibrationIsolationActive = false
        if (activeLeaseTarget == null) {
            restoreActiveLease(runCatching { leaseSafety.loadActiveLease() }.getOrNull())
        }
        val leaseWasActive = activeLeaseTarget != null
        val target = activeLeaseTarget ?: fallbackTarget ?: controller.captureSafetyTarget()
            ?: return controlFailure(
                MicAccessState.BLOCKED,
                "CONTROL_CONTEXT_UNKNOWN",
                "无法确定需要屏蔽的控制目标；保留已有安全租约",
            )
        val blocked = controller.block(target)
        if (
            leaseWasActive && blocked.controlReadback &&
            blocked.observed == MicAccessState.BLOCKED
        ) {
            val cancelError = runCatching { leaseSafety.cancel() }.exceptionOrNull()
            if (cancelError == null) {
                clearActiveLeaseFields()
            } else {
                return blocked.copy(
                    errorCode = "LEASE_CANCEL_FAILED",
                    errorMessage = "已确认屏蔽，但安全租约清理失败；将保留保护：${cancelError.javaClass.simpleName}",
                )
            }
        }
        return blocked
    }

    /**
     * Rebuilds the in-memory lease marker after an arm that did not report success. A failed
     * arm may have stopped either before its first durable write or after saving a provisional
     * lease. Only the durable store can distinguish those cases. If the store itself cannot be
     * read, retain a conservative marker so rollback will attempt cancellation rather than
     * silently assuming that no guard exists.
     */
    private suspend fun reconcileDurableLeaseAfterFailedArm(
        requestId: String,
        target: SafetyTarget,
        deadlineEpochMs: Long = 0L,
        deadlineElapsedRealtimeMs: Long = 0L,
        exactAlarmArmed: Boolean = false,
        rootWatchdogArmed: Boolean = false,
        persistent: Boolean = false,
    ) {
        clearActiveLeaseFields()
        val loaded = try {
            leaseSafety.loadActiveLease()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            activeLeaseRequestId = requestId
            activeLeaseTarget = target
            activeLeaseDeadlineEpochMs = deadlineEpochMs.takeIf { it > 0L }
            activeLeaseDeadlineElapsedRealtimeMs =
                deadlineElapsedRealtimeMs.takeIf { it > 0L }
            activeLeaseExactAlarmArmed = exactAlarmArmed
            activeLeaseRootWatchdogArmed = rootWatchdogArmed
            activeLeasePersistent = persistent
            return
        }
        restoreActiveLease(loaded)
    }

    /** Any OPEN without a live monotonic lease is an incident and is synchronously blocked. */
    private suspend fun observeFailClosed(): SafetyObservation {
        val target = activeLeaseTarget
        val observed = controller.readState(target)
        if (observed == MicAccessState.OPEN && hasHealthyActiveLease()) {
            val deadline = activeLeaseDeadlineEpochMs.takeUnless { activeLeasePersistent }
            state = BridgeMicState.Open(deadline, nowEpochMs())
            return SafetyObservation(observed, true, null, 0L)
        }
        if (observed == MicAccessState.BLOCKED) {
            if (target != null) {
                val cancelError = runCatching { leaseSafety.cancel() }.exceptionOrNull()
                if (cancelError != null) {
                    state = BridgeMicState.ErrorUnverified(
                        MicAccessState.BLOCKED,
                        "已屏蔽，但无法清理安全租约",
                    )
                    return SafetyObservation(
                        MicAccessState.BLOCKED,
                        true,
                        "已确认屏蔽，但安全租约清理失败；保护仍保留",
                        0L,
                    )
                }
                clearActiveLeaseFields()
            }
            state = BridgeMicState.Blocked(nowEpochMs())
            return SafetyObservation(MicAccessState.BLOCKED, true, null, 0L)
        }

        val reason = if (observed == MicAccessState.OPEN) {
            "检测到没有有效安全租约或租约已到期的 OPEN"
        } else {
            "状态读取失败"
        }
        val blocked = blockThenCancelLeaseIfVerified()
        val confirmed = blocked.controlReadback && blocked.observed == MicAccessState.BLOCKED
        val message = if (confirmed) {
            "$reason；已重新屏蔽"
        } else {
            "$reason；屏蔽状态无法确认${blocked.errorMessage?.let { "：$it" }.orEmpty()}"
        }
        state = if (confirmed) {
            BridgeMicState.Blocked(nowEpochMs())
        } else {
            BridgeMicState.ErrorUnverified(MicAccessState.BLOCKED, message)
        }
        return SafetyObservation(
            observed = blocked.observed,
            readback = confirmed,
            error = message,
            latencyMs = blocked.durationMs,
        )
    }

    private fun hasValidActiveLease(): Boolean {
        val target = activeLeaseTarget ?: return false
        if (!activeLeasePersistent) {
            val deadline = activeLeaseDeadlineElapsedRealtimeMs ?: return false
            if (deadline <= elapsedRealtimeMs()) return false
            if (activeLeaseExactAlarmArmed != true) return false
        }
        val rootWatchdogRequired = target.controllerId in setOf(
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_APP_OPS,
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        )
        return !rootWatchdogRequired || activeLeaseRootWatchdogArmed == true
    }

    private fun activeAutoBlockAtEpochMs(): Long? =
        activeLeaseDeadlineEpochMs.takeUnless { activeLeasePersistent }

    /**
     * An in-memory `armed=true` bit is historical, not current proof. Every externally visible
     * OPEN observation (status, replay, or an already-OPEN assertion) revalidates the exact
     * PID-bound Root guard before it can be described as verified.
     */
    private suspend fun hasHealthyActiveLease(): Boolean {
        if (!hasValidActiveLease()) return false
        val target = activeLeaseTarget ?: return false
        val rootWatchdogRequired = target.controllerId in setOf(
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_APP_OPS,
            com.jack.micbridge.data.SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        )
        if (!rootWatchdogRequired) return true
        val requestId = activeLeaseRequestId ?: return false
        return runCatching { leaseSafety.verifyActiveGuard(requestId) }.getOrDefault(false)
    }

    private fun restoreActiveLease(lease: ActiveSafetyLease?) {
        if (lease == null) return
        activeLeaseRequestId = lease.requestId
        activeLeaseTarget = lease.target
        activeLeaseDeadlineEpochMs = lease.deadlineEpochMs
        activeLeaseDeadlineElapsedRealtimeMs = lease.deadlineElapsedRealtimeMs
        activeLeaseExactAlarmArmed = lease.exactAlarmArmed
        activeLeaseRootWatchdogArmed = lease.rootWatchdogArmed
        activeLeasePersistent = lease.persistent
    }

    private fun clearActiveLeaseFields() {
        activeLeaseRequestId = null
        activeLeaseTarget = null
        activeLeaseDeadlineEpochMs = null
        activeLeaseDeadlineElapsedRealtimeMs = null
        activeLeaseExactAlarmArmed = null
        activeLeaseRootWatchdogArmed = null
        activeLeasePersistent = false
    }

    private fun controlFailure(
        requested: MicAccessState,
        code: String,
        message: String,
    ) = ControlResult(
        requested = requested,
        observed = MicAccessState.UNKNOWN,
        controlReadback = false,
        durationMs = 0L,
        errorCode = code,
        errorMessage = message,
    )

    private data class SafetyObservation(
        val observed: MicAccessState,
        val readback: Boolean,
        val error: String?,
        val latencyMs: Long,
    )

    companion object {
        const val ENDPOINT_TOGGLE = "/v1/mic/toggle"
        const val ENDPOINT_OPEN = "/v1/mic/open"
        const val ENDPOINT_BLOCK = "/v1/mic/block"
    }
}
