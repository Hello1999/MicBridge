package com.jack.micbridge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jack.micbridge.MainActivity
import com.jack.micbridge.R
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.AuditLogRepository
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult
import com.jack.micbridge.data.RequestLedger
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.mic.AppOpsModeReader
import com.jack.micbridge.mic.AppOpsReadOnlyOpenVeto
import com.jack.micbridge.mic.AudioManagerMicController
import com.jack.micbridge.mic.ControllerFactory
import com.jack.micbridge.mic.MicController
import com.jack.micbridge.mic.MicCoordinator
import com.jack.micbridge.mic.RootShell
import com.jack.micbridge.mic.SensorPrivacyRootController
import com.jack.micbridge.network.NetworkAddressProvider
import com.jack.micbridge.safety.AutoBlockSafety
import com.jack.micbridge.safety.BootIncidentStore
import com.jack.micbridge.server.ApiRouter
import com.jack.micbridge.server.LocalHttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

class BridgeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkMutex = Mutex()
    private val taskRemovalMutex = Mutex()
    private val calibrationMutex = Mutex()
    private val remoteRequestMutex = Mutex()
    private lateinit var settings: SettingsRepository
    private lateinit var rootShell: RootShell
    private lateinit var controller: MicController
    private lateinit var safety: AutoBlockSafety
    private lateinit var coordinator: MicCoordinator
    private lateinit var auditLog: AuditLogRepository
    private lateinit var bootIncidentStore: BootIncidentStore
    private lateinit var stateChangeSoundPlayer: StateChangeSoundPlayer
    @Volatile
    private var controllerProbeSummary: String? = null
    private var maintenanceGlobalBlockTarget: SafetyTarget? = null
    private val allHttpServers = CopyOnWriteArraySet<LocalHttpServer>()
    private val remoteGenerationCounter = AtomicLong(0L)
    private val networkRefreshEvents = LatestEventMutex()
    private val serverBoundaryCounter = AtomicLong(0L)
    private val taskRemovalCounter = AtomicLong(0L)
    private val micTransitionEventCounter = AtomicLong(0L)
    private val micAsyncClaimedEvent = AtomicLong(0L)
    private val micRemoteVerifiedEvent = AtomicLong(0L)
    private val coordinatorSnapshotEpoch = AtomicLong(0L)
    private val taskRemovalLock = Any()
    private val maintenanceBoundaryLock = Any()
    private var maintenanceBoundaryGeneration = 0L
    private var maintenanceOperationsInFlight = 0
    private val calibrationBoundaryLock = Any()
    private var calibrationBoundaryGeneration = 0L
    private var calibrationOperationsInFlight = 0
    private val safetyIncidentBoundaryLock = Any()
    private var safetyIncidentBoundaryGeneration = 0L
    private val publicationLock = Any()
    private val lifecycleRegistrationLock = Any()
    private val permissionMonitorWakeups = Channel<Unit>(Channel.CONFLATED)
    private val networkAddressMonitorWakeups = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    private var boundAddresses: List<String> = emptyList()
    private var autoBlockJob: Job? = null
    private var wakeLockRenewJob: Job? = null
    private var permissionMonitorJob: Job? = null
    private var networkAddressMonitorJob: Job? = null
    private var stateChangeSoundJob: Job? = null
    @Volatile
    private var tetheringChangeMonitor: TetheringChangeMonitor.Monitor? = null
    private val observedNetworkLock = Any()
    private val observedNetworks = mutableMapOf<Network, NetworkObservation>()
    private val permissionStateLock = Any()
    @Volatile
    private var lastObservedLanAddresses: Set<String> = emptySet()
    private var scheduledDeadline: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val calibrationSession = CalibrationSession()
    @Volatile
    private var initialized = false
    @Volatile
    private var networkMonitoringReady = false
    @Volatile
    private var networkAddressMonitoringReady = false
    @Volatile
    private var tetheringMonitoringReady = false
    @Volatile
    private var permissionMonitoringReady = false
    @Volatile
    private var micMuteMonitoringReady = false
    @Volatile
    private var permissionsUsable = false
    @Volatile
    private var activeRemoteGeneration = NO_REMOTE_GENERATION
    @Volatile
    private var shuttingDown = false
    @Volatile
    private var taskRemovalBoundaryActive = false
    @Volatile
    private var maintenanceBoundaryActive = false
    @Volatile
    private var remoteRequestInFlight = false
    @Volatile
    private var remoteMutationFenceActive = false
    @Volatile
    private var calibrationBoundaryActive = false
    @Volatile
    private var safetyIncidentBoundaryActive = false
    @Volatile
    private var stopCommittedBoundaryActive = false
    @Volatile
    private var terminalPublication = false

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val micMuteChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_MICROPHONE_MUTE_CHANGED) return
            val snapshot = ServiceRuntime.snapshot.value
            if (!initialized || shuttingDown) return
            if (snapshot.transitioning) {
                micTransitionEventCounter.incrementAndGet()
                return
            }
            val actualBlocked = runCatching { audioManager.isMicrophoneMute }.getOrNull() ?: run {
                revokeAndCloseHttpServers()
                scope.launch {
                    containSafetyCoroutineFailure(
                        sourceId = "audio-drift-unreadable",
                        context = "AudioManager 漂移状态无法读取",
                    )
                }
                return
            }
            val expectedBlocked = snapshot.micAccess == MicAccessState.BLOCKED
            val expectedOpen = snapshot.micAccess == MicAccessState.OPEN
            if ((expectedBlocked && !actualBlocked) || (expectedOpen && actualBlocked)) {
                // A control gate changed outside the serialized transition. Revoke authority
                // synchronously, then close every writable gate before rebinding.
                scheduleNetworkRefresh(blockIfOpen = expectedOpen)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(observedNetworkLock) {
                observedNetworks.putIfAbsent(network, NetworkObservation())
            }
            handleNetworkIdentityEvent()
        }

        override fun onLost(network: Network) {
            synchronized(observedNetworkLock) { observedNetworks.remove(network) }
            handleNetworkIdentityEvent()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            synchronized(observedNetworkLock) {
                observedNetworks.getOrPut(network, ::NetworkObservation).capabilities =
                    networkCapabilities
            }
            handleNetworkIdentityEvent()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) {
            synchronized(observedNetworkLock) {
                observedNetworks.getOrPut(network, ::NetworkObservation).linkProperties =
                    linkProperties
            }
            handleNetworkIdentityEvent()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            synchronized(observedNetworkLock) {
                observedNetworks.getOrPut(network, ::NetworkObservation).blocked = blocked
            }
            handleNetworkIdentityEvent()
        }
    }

    override fun onCreate() {
        super.onCreate()
        shuttingDown = false
        maintenanceBoundaryActive = false
        calibrationBoundaryActive = false
        safetyIncidentBoundaryActive = false
        stopCommittedBoundaryActive = false
        settings = SettingsRepository(this)
        bootIncidentStore = BootIncidentStore(this)
        auditLog = AuditLogRepository(this)
        stateChangeSoundPlayer = StateChangeSoundPlayer(this)
        rootShell = RootShell(onDiagnostic = auditLog::appendRoot)
        val startingSnapshot = ServiceRuntime.snapshot.value.copy(
            micAccess = MicAccessState.UNKNOWN,
            transitioning = false,
            controlReadback = false,
            acousticCalibrationValid = false,
            controllerProbe = "等待自动探测",
            serviceRunning = true,
            batteryOptimizationExempt = isBatteryOptimizationExempt(),
            serverAddresses = emptyList(),
            autoBlockAtEpochMs = null,
            leaseExactAlarmArmed = null,
            leaseRootWatchdogArmed = null,
            lastError = bootIncidentStore.message()?.let {
                "正在执行启动屏蔽；先前安全边界：$it"
            } ?: "正在执行启动屏蔽",
        )
        try {
            createNotificationChannel()
            ServiceRuntime.publish(startingSnapshot)
            startForeground(NOTIFICATION_ID, notification(startingSnapshot))
        } catch (failure: Throwable) {
            // Losing the visible FGS boundary must not prevent the physical fail-safe attempt.
            startupFailsafeBlock("前台通知启动失败：${failure.javaClass.simpleName}")
            throw failure
        }
        startupFailsafeBlock("服务启动预屏蔽无法确认")
        registerNetworkCallback()
        registerMicMuteMonitor()
        ensureTetheringMonitor()
        lastObservedLanAddresses = currentLanAddressSet()
        startNetworkAddressMonitor()
        startPermissionMonitor()
        scope.launch { initializeUntilReady() }
    }

    private fun startupFailsafeBlock(failureMessage: String): Boolean {
        val blocked = runCatching {
            runBlocking {
                withTimeoutOrNull(STARTUP_BLOCK_TIMEOUT_MS) {
                    com.jack.micbridge.safety.DirectBootFailsafeBlocker.block(applicationContext)
                } == true
            }
        }.getOrDefault(false)
        if (!blocked) {
            runCatching { bootIncidentStore.record(failureMessage) }
            runCatching { bootIncidentStore.postFailureNotification() }
        }
        return blocked
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val maintenanceOperation = action == ACTION_STOP || action == ACTION_REMOVE_GUARD
        val maintenanceGenerationAtDispatch = synchronized(maintenanceBoundaryLock) {
            if (maintenanceOperation) {
                maintenanceBoundaryGeneration += 1L
                maintenanceOperationsInFlight += 1
                maintenanceBoundaryActive = true
            }
            maintenanceBoundaryGeneration
        }
        val calibrationOperation = action in CALIBRATION_ACTIONS
        val calibrationGenerationAtDispatch = synchronized(calibrationBoundaryLock) {
            if (calibrationOperation) {
                if (action == ACTION_CALIBRATION_OPEN) calibrationBoundaryGeneration += 1L
                calibrationOperationsInFlight += 1
                calibrationBoundaryActive = true
            }
            calibrationBoundaryGeneration
        }
        val safetyBoundaryOperation = action == ACTION_BLOCK || action == ACTION_INSTALL_GUARD
        var recoverSafetyBoundaryOnStart = false
        val safetyBoundaryGenerationAtDispatch = if (safetyBoundaryOperation) {
            activateSafetyIncidentBoundary()
        } else {
            synchronized(safetyIncidentBoundaryLock) {
                if (action == ACTION_START) {
                    recoverSafetyBoundaryOnStart = safetyIncidentBoundaryActive
                }
                safetyIncidentBoundaryGeneration
            }
        }
        val taskRemovalEventAtDispatch = taskRemovalCounter.get()
        if (
            action == ACTION_BLOCK ||
            action == ACTION_INSTALL_GUARD ||
            action == ACTION_CALIBRATION_OPEN ||
            action == ACTION_CALIBRATION_ROOT_ISOLATION ||
            action == ACTION_CALIBRATION_CONFIRM_AND_BLOCK ||
            action == ACTION_COMPLETE_CALIBRATION
        ) {
            // Establish the authority boundary synchronously with the notification action. A
            // queued old socket must not OPEN before this explicit BLOCK reaches the mutex.
            revokeAndCloseHttpServers()
        }
        if (calibrationOperation) {
            // Unlike a plain close, advance invalidates any pre-calibration refresh that is
            // already sleeping in its settle window.
            networkRefreshEvents.advance(::revokeAndCloseHttpServersLocked)
        }
        if (maintenanceOperation) {
            // Deny every OPEN and rebind immediately, but keep the service's monitors alive if
            // the requested BLOCK/remove operation fails. Only onDestroy owns shuttingDown.
            revokeAndCloseHttpServers()
        }
        if (action == ACTION_START) {
            if (initialized && !shuttingDown) {
                // scheduleNetworkRefresh revokes the live listener generation synchronously.
                scheduleNetworkRefresh(
                    recoverTaskRemovalEvent = taskRemovalEventAtDispatch,
                    recoverSafetyIncidentGeneration = safetyBoundaryGenerationAtDispatch
                        .takeIf { recoverSafetyBoundaryOnStart },
                )
            } else {
                scope.launch {
                    if (awaitInitialized() && !shuttingDown) {
                        // ACTION_START is also the explicit recovery/rebind action after a
                        // transient bind, address, or permission failure.
                        scheduleNetworkRefresh(
                            recoverTaskRemovalEvent = taskRemovalEventAtDispatch,
                            recoverSafetyIncidentGeneration = safetyBoundaryGenerationAtDispatch
                                .takeIf { recoverSafetyBoundaryOnStart },
                        )
                    }
                }
            }
        } else {
            scope.launch {
                try {
                if (!awaitInitialized()) {
                    val blocked = com.jack.micbridge.safety.DirectBootFailsafeBlocker.block(
                        applicationContext,
                    )
                    val remover = if (::safety.isInitialized) {
                        safety
                    } else {
                        AutoBlockSafety(this@BridgeForegroundService, settings, rootShell)
                    }
                    val appOpsSafeExit =
                        action != ACTION_REMOVE_GUARD || prepareAppOpsSafeExitIfNeeded()
                    val immutableExitReady =
                        action != ACTION_REMOVE_GUARD ||
                            (blocked && appOpsSafeExit && freezeMaintenanceBlockTarget())
                    val leaseCleared = blocked && appOpsSafeExit && immutableExitReady && runCatching {
                        remover.cancel()
                        true
                    }.getOrDefault(false)
                    when {
                        action == ACTION_REMOVE_GUARD && leaseCleared -> {
                            if (remover.removeRootBootGuard()) {
                                if (settings.hasPendingAppOpsState) {
                                    settings.originalAppOpsMode = null
                                    settings.clearCalibration()
                                }
                                commitTerminalStopBoundary()
                                stopSelf()
                            }
                            else publishPreInitializationError("Root 开机保护移除失败；HTTP 保持关闭")
                        }
                        action == ACTION_STOP && leaseCleared -> {
                            commitTerminalStopBoundary()
                            stopSelf()
                        }
                        action == ACTION_STOP || action == ACTION_REMOVE_GUARD ->
                            publishPreInitializationError("屏蔽或安全租约清理无法确认，已拒绝停止或移除保护")
                    }
                    return@launch
                }
                when (action) {
                    ACTION_BLOCK -> {
                        calibrationMutex.withLock {
                            calibrationSession.reset()
                            val blocked = coordinator.localBlock("notification-block")
                            queueStateChangeCue(blocked)
                            if (isSafeStoppedBoundary(blocked)) {
                                val bootIncidentCleared = bootIncidentStore.clear()
                                val maintenanceCleared = clearMaintenanceBoundaryIfUncontested(
                                    maintenanceGenerationAtDispatch,
                                )
                                val taskRemovalCleared =
                                    clearTaskRemovalBoundaryIfCurrent(taskRemovalEventAtDispatch)
                                val calibrationCleared = clearCalibrationBoundaryIfUncontested(
                                    calibrationGenerationAtDispatch,
                                )
                                val prerequisiteBoundariesCleared =
                                    bootIncidentCleared && maintenanceCleared &&
                                        taskRemovalCleared && calibrationCleared
                                val safetyBoundaryCleared = prerequisiteBoundariesCleared &&
                                    clearSafetyIncidentBoundaryIfCurrent(
                                        safetyBoundaryGenerationAtDispatch,
                                    )
                                if (safetyBoundaryCleared) {
                                    scheduleNetworkRefresh()
                                } else {
                                    publishCurrent(
                                        "已确认屏蔽，但检测到未清除或更新的安全边界；HTTP 保持关闭",
                                    )
                                }
                            }
                        }
                    }
                    ACTION_CALIBRATION_OPEN -> if (
                        shuttingDown || maintenanceBoundaryActive
                    ) {
                        coordinator.localBlock("calibration-refused-shutdown")
                    } else {
                        calibrationMutex.withLock {
                            // Starting a new round permanently revokes the older acoustic proof
                            // before any temporary OPEN. If this write fails, the outer safety
                            // containment keeps HTTP closed and the attempt never starts.
                            settings.clearCalibration()
                            val identity = settings.currentAcousticCalibrationIdentity()
                            if (!calibrationSession.beginOpenAttempt(identity)) {
                                coordinator.localBlock("calibration-identity-unavailable")
                                publishCurrent(
                                    "无法冻结校准环境；请确认目标 ChatGPT 包、录音权限、签名和当前用户",
                                )
                            } else {
                                val opened = coordinator.calibrationOpen()
                                val current = settings.currentAcousticCalibrationIdentity()
                                calibrationSession.observeOpen(current, opened)
                                if (current != identity) {
                                    calibrationSession.reset()
                                    coordinator.localBlock("calibration-identity-changed-open")
                                    publishCurrent("校准期间目标应用或系统环境已变化；本轮作废并已屏蔽")
                                }
                            }
                        }
                    }
                    ACTION_CALIBRATION_ROOT_ISOLATION -> if (
                        shuttingDown || maintenanceBoundaryActive
                    ) {
                        coordinator.localBlock("calibration-isolation-refused-shutdown")
                    } else {
                        val calibrationRootGate = SensorPrivacyRootController(rootShell)
                        calibrationMutex.withLock {
                            val before = settings.currentAcousticCalibrationIdentity()
                            if (!calibrationSession.matchesCurrentIdentity(before)) {
                                coordinator.localBlock("calibration-identity-mismatch-isolation")
                                publishCurrent("校准环境与第一步不一致；本轮作废并已屏蔽")
                            } else {
                                val isolated = coordinator.calibrationIsolateRootFailsafe(
                                    rootGate = calibrationRootGate,
                                    audioGate = AudioManagerMicController(audioManager),
                                    appOpsVeto = AppOpsReadOnlyOpenVeto(rootShell, settings),
                                    // Keep the fast audible cut inside the coordinator mutex so
                                    // the 250 ms drift monitor cannot mistake this intentional
                                    // split state for an external mutation and revoke the round.
                                    immediateRootBlock = {
                                        calibrationRootGate.blockCurrentUserImmediately()
                                    },
                                )
                                val after = settings.currentAcousticCalibrationIdentity()
                                val identityStable = calibrationSession.matchesCurrentIdentity(after)
                                val verified = identityStable && isolated.controlReadback &&
                                    isolated.observed == MicAccessState.BLOCKED &&
                                    isolated.errorCode == null
                                calibrationSession.observeRootFailsafeIsolation(after, verified)
                                if (!identityStable) {
                                    coordinator.localBlock("calibration-identity-changed-isolation")
                                    publishCurrent(
                                        "Root-only 测试期间目标应用或系统环境已变化；本轮作废并已屏蔽",
                                    )
                                } else if (!verified) {
                                    publishCurrent(
                                        isolated.errorMessage
                                            ?: "Root 隔离校准失败；已优先恢复全局屏蔽",
                                    )
                                }
                            }
                        }
                    }
                    ACTION_CALIBRATION_CONFIRM_AND_BLOCK -> if (
                        shuttingDown || maintenanceBoundaryActive
                    ) {
                        coordinator.localBlock("calibration-confirm-refused-shutdown")
                    } else {
                        calibrationMutex.withLock {
                            val before = settings.currentAcousticCalibrationIdentity()
                            if (!calibrationSession.matchesCurrentIdentity(before)) {
                                coordinator.localBlock("calibration-identity-mismatch-confirm")
                                publishCurrent("校准环境与前序步骤不一致；本轮作废并已屏蔽")
                            } else {
                                val confirmed = coordinator.confirmRootIsolationAndBlock(
                                    rootGate = SensorPrivacyRootController(rootShell),
                                    audioGate = AudioManagerMicController(audioManager),
                                    appOpsVeto = AppOpsReadOnlyOpenVeto(rootShell, settings),
                                )
                                val after = settings.currentAcousticCalibrationIdentity()
                                val identityStable = calibrationSession.matchesCurrentIdentity(after)
                                val verified = identityStable && confirmed.controlReadback &&
                                    confirmed.observed == MicAccessState.BLOCKED &&
                                    confirmed.errorCode == null
                                calibrationSession.observeIsolationConfirmationAndBlock(
                                    after,
                                    verified,
                                )
                                if (!identityStable) {
                                    coordinator.localBlock("calibration-identity-changed-confirm")
                                    publishCurrent(
                                        "确认期间目标应用或系统环境已变化；本轮作废并已屏蔽",
                                    )
                                } else if (!verified) {
                                    publishCurrent(
                                        confirmed.errorMessage
                                            ?: "Root-only 确认或最终完整屏蔽失败；本轮证据作废",
                                    )
                                }
                            }
                        }
                    }
                    ACTION_COMPLETE_CALIBRATION -> calibrationMutex.withLock {
                        completeCalibrationSafely(calibrationGenerationAtDispatch)
                    }
                    ACTION_REFRESH -> publishCurrent()
                    ACTION_INSTALL_GUARD -> {
                        val blocked = coordinator.localBlock("install-guard")
                        val installed =
                            isSafeStoppedBoundary(blocked) && safety.installOrRefreshRootBootGuard()
                        val safetyBoundaryCleared = installed &&
                            clearSafetyIncidentBoundaryIfCurrent(
                                safetyBoundaryGenerationAtDispatch,
                            )
                        when {
                            !installed -> publishCurrent(
                                "无法先确认全局屏蔽，或 Root 开机保护安装失败",
                            )
                            !safetyBoundaryCleared -> publishCurrent(
                                "Root 开机保护已安装，但检测到更新的安全故障；HTTP 保持关闭",
                            )
                            else -> {
                                publishCurrent()
                                scheduleNetworkRefresh()
                            }
                        }
                    }
                    ACTION_REMOVE_GUARD -> {
                        val blocked = coordinator.localBlock("remove-guard")
                        if (isSafeStoppedBoundary(blocked)) {
                            val safeExit = prepareAppOpsSafeExitIfNeeded()
                            val immutableExitReady = safeExit && freezeMaintenanceBlockTarget()
                            val removed = immutableExitReady && safety.removeRootBootGuard()
                            if (removed) {
                                if (settings.hasPendingAppOpsState) {
                                    settings.originalAppOpsMode = null
                                    settings.clearCalibration()
                                }
                                commitTerminalStopBoundary()
                                stopSelf()
                            } else {
                                publishCurrent(
                                    "旧 AppOps 元数据清理或 Root 开机保护移除失败；保持全局屏蔽且 HTTP 关闭",
                                )
                            }
                        } else {
                            publishCurrent("无法确认屏蔽，已拒绝移除 Root 开机保护")
                        }
                    }
                    ACTION_STOP -> {
                        revokeAndCloseHttpServers()
                        val blocked = coordinator.localBlock("service-stop")
                        if (isSafeStoppedBoundary(blocked)) {
                            commitTerminalStopBoundary()
                            stopSelf()
                        } else {
                            publishCurrent("无法确认屏蔽，已拒绝正常停止服务")
                        }
                    }
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (action in CALIBRATION_ACTIONS) {
                        calibrationMutex.withLock { calibrationSession.reset() }
                    }
                    if (!shuttingDown) {
                        containSafetyCoroutineFailure(
                            sourceId = "service-action-failure",
                            context = "本地动作处理异常（${failure.javaClass.simpleName}）",
                        )
                    }
                } finally {
                    if (maintenanceOperation) {
                        synchronized(maintenanceBoundaryLock) {
                            maintenanceOperationsInFlight =
                                (maintenanceOperationsInFlight - 1).coerceAtLeast(0)
                        }
                    }
                    if (calibrationOperation) {
                        synchronized(calibrationBoundaryLock) {
                            calibrationOperationsInFlight =
                                (calibrationOperationsInFlight - 1).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun initialize() {
        if (!networkMonitoringReady) registerNetworkCallback()
        if (!isNetworkAddressMonitorReady()) {
            startNetworkAddressMonitor()
        }
        if (!micMuteMonitoringReady) registerMicMuteMonitor()
        ensureTetheringMonitor()
        if (!isPermissionMonitorReady()) startPermissionMonitor()
        if (!::coordinator.isInitialized) {
            safety = AutoBlockSafety(this, settings, rootShell)
            check(safety.claimRootWorkspaceOwner()) {
                "Root 工作区已被另一个 Android 用户实例占用，或无法取得安全所有权"
            }
            controller = ControllerFactory.create(this, settings, rootShell)
            coordinator = MicCoordinator(
                controller = controller,
                ledger = RequestLedger(this),
                leaseSafety = safety,
                tokenGeneration = { settings.tokenGeneration },
                maxOpenSeconds = { settings.maxOpenSeconds },
                calibrationValid = { settings.isAcousticCalibrationValid() },
                persistentRemoteOpen = { true },
                openAllowed = {
                    !shuttingDown &&
                        !stopCommittedBoundaryActive &&
                        !maintenanceBoundaryActive &&
                        !taskRemovalBoundaryActive &&
                        !safetyIncidentBoundaryActive &&
                        micMuteMonitoringReady &&
                        isPermissionMonitorReady() &&
                        isReliabilityReady() &&
                        isAppUserForeground() &&
                        hasVisibleNotificationPermission() &&
                        isBatteryOptimizationExempt()
                },
                remoteOpenAllowed = { generation ->
                    !shuttingDown &&
                        !stopCommittedBoundaryActive &&
                        !maintenanceBoundaryActive &&
                        !taskRemovalBoundaryActive &&
                        !calibrationBoundaryActive &&
                        !safetyIncidentBoundaryActive &&
                        !remoteMutationFenceActive &&
                        networkMonitoringReady &&
                        isNetworkAddressMonitorReady() &&
                        micMuteMonitoringReady &&
                        isPermissionMonitorReady() &&
                        isReliabilityReady() &&
                        hasLocalNetworkPermission() &&
                        hasVisibleNotificationPermission() &&
                        isAppUserForeground() &&
                        isBatteryOptimizationExempt() &&
                        generation == activeRemoteGeneration
                },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                onSnapshot = ::onCoordinatorSnapshot,
                onOperation = auditLog::append,
            )
        }
        val startupBlock = coordinator.initialize()
        check(isSafeStartupBoundary(startupBlock)) {
            "启动 BLOCK 未获得新鲜可验证读回"
        }
        // Move the comparatively expensive immutable boot-guard deployment out of the user's
        // OPEN button path. AutoBlockSafety keeps an in-process proof for this exact target;
        // every OPEN still arms a fresh per-lease watcher and revalidates the live supervisor.
        if (settings.rootBootGuardInstalled) {
            check(safety.installOrRefreshRootBootGuard()) {
                "已配置的 Root 开机保护无法在服务启动时刷新"
            }
        }
        val probe = controller.probe()
        controllerProbeSummary = buildString {
            append("available=${probe.available}, readable=${probe.stateReadable}, locked=")
            append(probe.worksWhileLocked?.toString() ?: "NOT_RUN")
            probe.notes?.takeIf { it.isNotBlank() }?.let { append("；").append(it) }
        }
        check(probe.available && probe.stateReadable) {
            "控制器自动探测未通过：$controllerProbeSummary"
        }
        if (settings.reliableMode && !isReliabilityReady()) acquireWakeLock()
        check(
            networkMonitoringReady && isNetworkAddressMonitorReady() && micMuteMonitoringReady &&
                isPermissionMonitorReady() && isReliabilityReady()
        ) {
            "网络、麦克风漂移、权限变化监控或可靠模式未就绪"
        }
        check(bootIncidentStore.clear()) { "无法清除已解决的开机安全告警" }
        initialized = true
        wakeSafetyMonitors()
        if (
            !shuttingDown &&
            networkMonitoringReady &&
            isNetworkAddressMonitorReady() &&
            micMuteMonitoringReady &&
            isPermissionMonitorReady() &&
            isReliabilityReady() &&
            isAppUserForeground() &&
            isSafeStartupBoundary(startupBlock)
        ) {
            // Every listener publication, including the first one, goes through the same
            // generation-serialized settle path as network and lifecycle recovery.
            scheduleNetworkRefresh()
        } else {
            val error = if (!networkMonitoringReady) {
                "网络变化监控注册失败；保持屏蔽且 HTTP 未开放"
            } else if (!isNetworkAddressMonitorReady()) {
                "网络地址监督器未就绪；保持屏蔽且 HTTP 未开放"
            } else if (!isPermissionMonitorReady()) {
                "权限变化监控注册失败；保持屏蔽且 HTTP 未开放"
            } else if (!isReliabilityReady()) {
                "可靠模式 WakeLock 监督器未就绪；保持屏蔽且 HTTP 未开放"
            } else if (!isAppUserForeground()) {
                "MicBridge 所属 Android 用户不在前台；保持屏蔽且 HTTP 未开放"
            } else {
                "启动时无法确认已屏蔽；HTTP 未开放"
            }
            publishCurrent(error)
        }
    }

    private suspend fun initializeUntilReady() {
        while (!initialized && !shuttingDown) {
            try {
                initialize()
            } catch (cancelled: CancellationException) {
                // Service teardown owns the terminal fail-closed boundary. Never turn normal
                // coroutine cancellation into another initialization attempt or a late publish.
                throw cancelled
            } catch (error: Throwable) {
                if (shuttingDown) return
                revokeAndCloseHttpServers()
                initialized = false
                val blocked = runCatching {
                    // Device-protected settings contain only a fixed release controller target;
                    // legacy AppOps metadata is never consulted to authorize a write.
                    com.jack.micbridge.safety.DirectBootFailsafeBlocker.block(applicationContext)
                }.getOrDefault(false)
                if (!blocked) {
                    bootIncidentStore.record("服务初始化失败且备用 BLOCK 无法确认；麦克风状态未知")
                    bootIncidentStore.postFailureNotification()
                }
                val message = "初始化失败（${error.javaClass.simpleName}）；HTTP 未开放，5 秒后重试"
                val snapshot = ServiceRuntime.snapshot.value.copy(
                    micAccess = if (blocked) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
                    transitioning = false,
                    controlReadback = blocked,
                    controllerProbe = controllerProbeSummary,
                    serviceRunning = true,
                    serverAddresses = emptyList(),
                    autoBlockAtEpochMs = null,
                    leaseExactAlarmArmed = null,
                    leaseRootWatchdogArmed = null,
                    lastError = message,
                )
                if (!publishAndNotifyIfActive(snapshot) || shuttingDown) return
                delay(INIT_RETRY_MS)
            }
        }
    }

    private suspend fun awaitInitialized(): Boolean {
        repeat(100) {
            if (initialized) return true
            delay(50L)
        }
        return initialized
    }

    private fun onCoordinatorSnapshot(snapshot: BridgeSnapshot) {
        coordinatorSnapshotEpoch.incrementAndGet()
        // Wake monitors before notification Binder work. This closes the cold-start/idle race
        // where a short OPEN lease could otherwise begin during a long idle delay.
        wakeSafetyMonitors()
        val merged = snapshot.copy(
            serviceRunning = true,
            controllerProbe = controllerProbeSummary ?: snapshot.controllerProbe,
            batteryOptimizationExempt = isBatteryOptimizationExempt(),
            serverAddresses = boundAddresses,
        )
        try {
            if (publishAndNotifyIfActive(merged)) {
                scheduleInProcessAutoBlock(merged.autoBlockAtEpochMs)
            }
        } finally {
            // A broadcast can observe the old TRANSITIONING ServiceRuntime value while this
            // final callback is publishing. Recheck after publication; broadcasts arriving
            // later see the final value and take the ordinary immediate-drift path above.
            if (!merged.transitioning && !remoteRequestInFlight) {
                schedulePendingMicStateRecheck(merged)
            }
        }
    }

    private fun schedulePendingMicStateRecheck(expected: BridgeSnapshot) {
        if (shuttingDown) return
        val event = micTransitionEventCounter.get()
        while (true) {
            val claimed = micAsyncClaimedEvent.get()
            if (event <= claimed) return
            if (micAsyncClaimedEvent.compareAndSet(claimed, event)) break
        }
        val expectedSnapshotEpoch = coordinatorSnapshotEpoch.get()
        scope.launch {
            try {
                if (
                    !initialized || shuttingDown ||
                    coordinatorSnapshotEpoch.get() != expectedSnapshotEpoch
                ) return@launch
                val checked = coordinator.readSnapshot(true, boundAddresses)
                // Our own read emits exactly one coordinator snapshot. Any additional epoch
                // means a newer legitimate operation overtook this check; never compare it to
                // the stale expected state captured above.
                if (coordinatorSnapshotEpoch.get() != expectedSnapshotEpoch + 1L) return@launch
                val drifted =
                    !checked.controlReadback || checked.micAccess != expected.micAccess ||
                        checked.lastError != null
                if (drifted && !shuttingDown) {
                    scheduleNetworkRefresh(
                        blockIfOpen = expected.micAccess == MicAccessState.OPEN ||
                            expected.transitioning,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!shuttingDown) {
                    containSafetyCoroutineFailure(
                        sourceId = "audio-drift-recheck-failure",
                        context = "过渡期间麦克风漂移复核异常（${failure.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    private fun scheduleInProcessAutoBlock(deadlineEpochMs: Long?) {
        if (deadlineEpochMs == scheduledDeadline) return
        scheduledDeadline = deadlineEpochMs
        autoBlockJob?.cancel()
        autoBlockJob = null
        if (deadlineEpochMs == null) return
        autoBlockJob = scope.launch {
            while (System.currentTimeMillis() < deadlineEpochMs) {
                delay(
                    (deadlineEpochMs - System.currentTimeMillis())
                        .coerceIn(1L, COUNTDOWN_TICK_MS),
                )
                if (scheduledDeadline == deadlineEpochMs && initialized && !shuttingDown) {
                    try {
                        updateNotification(ServiceRuntime.snapshot.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        containSafetyCoroutineFailure(
                            sourceId = "countdown-publication-failure",
                            context = "倒计时通知更新失败（${failure.javaClass.simpleName}）",
                        )
                        return@launch
                    }
                }
            }
            if (initialized && !shuttingDown) {
                try {
                    coordinator.localBlock("in-process-timeout")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    containSafetyCoroutineFailure(
                        sourceId = "in-process-timeout-failure",
                        context = "进程内超时屏蔽异常（${failure.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    /**
     * Keeps a known transition-time microphone broadcast inside the HTTP mutation's commit
     * boundary. A success response is not released until a fresh composite read agrees with
     * the operation result. Throwing here is intentional: ApiRouter emits a parseable
     * ok=false envelope and LocalHttpServer closes/rebuilds the listener after writing it.
     */
    private suspend fun executeRemoteMutation(
        endpoint: String,
        requestId: String,
        generation: Long,
    ): OperationResult = remoteRequestMutex.withLock {
        remoteRequestInFlight = true
        try {
            val result = coordinator.execute(endpoint, requestId, generation)
            if (!result.ok) return@withLock result

            var checks = 0
            while (micTransitionEventCounter.get() > micRemoteVerifiedEvent.get()) {
                val event = micTransitionEventCounter.get()
                val checked = coordinator.readSnapshot(true, boundAddresses)
                micRemoteVerifiedEvent.updateAndGet { prior -> maxOf(prior, event) }
                micAsyncClaimedEvent.updateAndGet { prior -> maxOf(prior, event) }
                val agrees = checked.controlReadback && checked.lastError == null &&
                    checked.micAccess == result.micAccess
                if (!agrees) {
                    remoteMutationFenceActive = true
                    try {
                        coordinator.localBlock("audio-drift-before-http-response")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The already-armed lease remains the final backstop. The router still
                        // returns failure and tears down this listener generation.
                    }
                    error("麦克风在响应提交前发生漂移；已拒绝成功响应")
                }
                checks += 1
                if (
                    checks >= MAX_TRANSITION_EVENT_COMMIT_CHECKS &&
                    micTransitionEventCounter.get() > micRemoteVerifiedEvent.get()
                ) {
                    remoteMutationFenceActive = true
                    try {
                        coordinator.localBlock("audio-drift-event-storm")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Preserve the original failure and fail-closed listener teardown.
                    }
                    error("麦克风变更事件在响应提交前持续变化")
                }
            }
            queueStateChangeCue(result)
            result
        } catch (cancelled: CancellationException) {
            // No exceptional exit may leave a second accepted request able to OPEN before the
            // listener generation is torn down by the HTTP failure path.
            remoteMutationFenceActive = true
            activateSafetyIncidentBoundary()
            throw cancelled
        } catch (failure: Throwable) {
            remoteMutationFenceActive = true
            // The response-triggered HTTP failure callback can be superseded by another local
            // boundary before it runs. Persist a separate deny gate synchronously here so no
            // calibration or remote path can OPEN until an explicit fresh recovery BLOCK.
            activateSafetyIncidentBoundary()
            throw if (failure is Exception) {
                failure
            } else {
                IllegalStateException("远程控制发生不可恢复异常", failure)
            }
        } finally {
            remoteRequestInFlight = false
            if (!shuttingDown) {
                schedulePendingMicStateRecheck(ServiceRuntime.snapshot.value)
            }
        }
    }

    private fun scheduleNetworkRefresh(
        blockIfOpen: Boolean = false,
        recoverTaskRemovalEvent: Long? = null,
        recoverSafetyIncidentGeneration: Long? = null,
        recoveredError: String? = null,
    ) {
        // Revoke authority and tear down accepted clients synchronously with the callback.
        // Network settling is allowed only after the safety direction has been established.
        val event = networkRefreshEvents.advance(::revokeAndCloseHttpServersLocked)
        scope.launch {
            try {
                // Avoid queueing obsolete work behind a slow older event. The same check is
                // repeated inside the serial gate so a newer callback cannot overtake it.
                if (!networkRefreshEvents.isCurrent(event)) return@launch
                networkRefreshEvents.runIfCurrent(event) {
                    if (!initialized || shuttingDown) return@runIfCurrent
                    val reason = if (blockIfOpen) "network-lost" else "network-change"
                    val blocked = coordinator.localBlock(reason)
                    if (!networkRefreshEvents.isCurrent(event) || shuttingDown) {
                        return@runIfCurrent
                    }
                    if (!isSafeStoppedBoundary(blocked)) {
                        publishCurrent("网络变化后无法确认屏蔽；HTTP 未开放")
                        return@runIfCurrent
                    }
                    if (!bootIncidentStore.clear()) {
                        publishCurrent("已确认屏蔽，但无法清除启动安全告警；HTTP 未开放")
                        return@runIfCurrent
                    }
                    if (
                        recoverTaskRemovalEvent != null &&
                        !clearTaskRemovalBoundaryIfCurrent(recoverTaskRemovalEvent)
                    ) {
                        publishCurrent("检测到更新的任务移除边界；HTTP 保持关闭")
                        return@runIfCurrent
                    }
                    if (
                        recoverSafetyIncidentGeneration != null &&
                        !clearSafetyIncidentBoundaryIfCurrent(
                            recoverSafetyIncidentGeneration,
                        )
                    ) {
                        publishCurrent("检测到更新的安全故障边界；HTTP 保持关闭")
                        return@runIfCurrent
                    }
                    delay(NETWORK_SETTLE_MS)
                    if (!networkRefreshEvents.isCurrent(event) || shuttingDown) {
                        return@runIfCurrent
                    }
                    restartServer(event, recoveredError)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!shuttingDown && networkRefreshEvents.isCurrent(event)) {
                    containSafetyCoroutineFailure(
                        sourceId = "network-boundary-failure",
                        context = "网络安全边界异常（${failure.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    private suspend fun restartServer(
        expectedNetworkEvent: Long,
        recoveredError: String? = null,
    ) = networkMutex.withLock {
        if (
            shuttingDown || stopCommittedBoundaryActive ||
            maintenanceBoundaryActive || calibrationBoundaryActive ||
            safetyIncidentBoundaryActive ||
            taskRemovalBoundaryActive ||
            !networkMonitoringReady || !isNetworkAddressMonitorReady() ||
            !micMuteMonitoringReady || !isPermissionMonitorReady() ||
            !isReliabilityReady() ||
            !networkRefreshEvents.isCurrent(expectedNetworkEvent)
        ) return@withLock
        // Revoke the old generation before closing listeners. An already accepted worker can
        // finish parsing, but it can never regain authority after a later listener is bound.
        if (!networkRefreshEvents.commitIfCurrent(expectedNetworkEvent) {
                revokeAndCloseHttpServersLocked()
            }
        ) return@withLock
        val boundaryAtStart = serverBoundaryCounter.get()

        // A request that had already entered the coordinator may finish first. This block then
        // runs after it, so no old connection can leave the final state OPEN.
        val boundaryBlock = coordinator.localBlock("network-boundary")
        if (!isSafeStoppedBoundary(boundaryBlock)) {
            publishCurrent("网络边界变化后无法确认屏蔽；HTTP 未开放")
            return@withLock
        }
        if (
            shuttingDown || stopCommittedBoundaryActive ||
            maintenanceBoundaryActive || calibrationBoundaryActive ||
            safetyIncidentBoundaryActive ||
            taskRemovalBoundaryActive ||
            !networkRefreshEvents.isCurrent(expectedNetworkEvent)
        ) return@withLock

        val addresses = eligibleLanAddresses()
        lastObservedLanAddresses = addresses.mapNotNull { it.hostAddress }.toSet()
        if (!hasLocalNetworkPermission()) {
            publishCurrent("缺少本地网络权限；保持屏蔽且不监听端口")
            return@withLock
        }
        if (!hasVisibleNotificationPermission()) {
            publishCurrent("缺少通知权限，无法持续显示安全状态；保持屏蔽且不监听端口")
            return@withLock
        }
        if (!isAppUserForeground()) {
            publishCurrent("MicBridge 所属 Android 用户不在前台；保持屏蔽且不监听端口")
            return@withLock
        }
        if (addresses.isEmpty()) {
            publishCurrent(noEligibleAddressMessage())
            return@withLock
        }
        val generation = remoteGenerationCounter.incrementAndGet()
        val router = ApiRouter(
            tokenMatches = settings::tokenMatches,
            coordinator = coordinator,
            statusProvider = {
                coordinator.readSnapshot(true, boundAddresses).copy(
                    controllerProbe = controllerProbeSummary,
                    batteryOptimizationExempt = isBatteryOptimizationExempt(),
                )
            },
            remoteGeneration = generation,
            onStatus = auditLog::appendStatus,
            mutationExecutor = ::executeRemoteMutation,
        )
        val server = LocalHttpServer(settings.port, router) { error ->
            val failureEvent = networkRefreshEvents.advanceIf(
                condition = { generation == activeRemoteGeneration },
                action = ::revokeAndCloseHttpServersLocked,
            )
            if (failureEvent != null) {
                scope.launch { handleHttpFailure(error, failureEvent) }
            }
        }
        var serverPublished = false
        var bindFailed = false
        val commitAccepted = networkRefreshEvents.commitIfCurrent(expectedNetworkEvent) {
            if (
                shuttingDown || stopCommittedBoundaryActive ||
                maintenanceBoundaryActive || calibrationBoundaryActive ||
                safetyIncidentBoundaryActive ||
                taskRemovalBoundaryActive ||
                !networkMonitoringReady || !isNetworkAddressMonitorReady() ||
                !micMuteMonitoringReady ||
                !isPermissionMonitorReady() || !isReliabilityReady() ||
                !hasLocalNetworkPermission() || !hasVisibleNotificationPermission() ||
                !isAppUserForeground() ||
                boundaryAtStart != serverBoundaryCounter.get()
            ) return@commitIfCurrent

            allHttpServers += server
            val startedAddresses = server.start(addresses)
            if (
                startedAddresses.isEmpty() ||
                shuttingDown || stopCommittedBoundaryActive ||
                maintenanceBoundaryActive || calibrationBoundaryActive ||
                safetyIncidentBoundaryActive ||
                taskRemovalBoundaryActive ||
                boundaryAtStart != serverBoundaryCounter.get()
            ) {
                bindFailed = startedAddresses.isEmpty()
                server.close()
                allHttpServers -= server
                return@commitIfCurrent
            }
            boundAddresses = startedAddresses
            activeRemoteGeneration = generation
            wakeSafetyMonitors()
            serverPublished = true
        }
        if (!commitAccepted || !serverPublished) {
            server.close()
            allHttpServers -= server
            if (
                shuttingDown || stopCommittedBoundaryActive ||
                maintenanceBoundaryActive || calibrationBoundaryActive ||
                safetyIncidentBoundaryActive ||
                taskRemovalBoundaryActive ||
                !networkRefreshEvents.isCurrent(expectedNetworkEvent)
            ) return@withLock
            if (!bindFailed) return@withLock
            publishCurrent("HTTP 端口未能绑定；保持屏蔽且不监听")
            return@withLock
        }
        if (!networkRefreshEvents.isCurrent(expectedNetworkEvent)) return@withLock
        publishCurrent(
            recoveredError ?: if (!isBatteryOptimizationExempt()) {
                "未获电池优化豁免；Doze 可能暂停网络，远程 OPEN 已禁用"
            } else null,
        )
    }

    private suspend fun handleHttpFailure(error: String, failureEvent: Long) {
        val shouldRecover = try {
            networkMutex.withLock {
                if (shuttingDown || !networkRefreshEvents.isCurrent(failureEvent)) {
                    return@withLock false
                }
                val blocked = coordinator.localBlock("http-failure")
                if (shuttingDown || !networkRefreshEvents.isCurrent(failureEvent)) {
                    return@withLock false
                }
                val safe = isSafeStoppedBoundary(blocked)
                val message = if (safe) {
                    "$error；已屏蔽并关闭 HTTP"
                } else {
                    "$error；HTTP 已关闭，但屏蔽状态无法确认"
                }
                publishCurrent(message)
                safe
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (shuttingDown || !networkRefreshEvents.isCurrent(failureEvent)) {
                return
            }
            containSafetyCoroutineFailure(
                sourceId = "http-failure-exception",
                context = "$error；已受理请求的安全处理异常（${failure.javaClass.simpleName}）",
            )
        }
        if (shouldRecover) {
            // Do not publish directly from this recovery path. Advancing once more invalidates
            // any competing settle job, and the single LatestEventMutex owner performs the
            // eventual rebind.
            scheduleNetworkRefresh(
                recoveredError = "$error；已确认屏蔽并恢复 HTTP 监听",
            )
        }
    }

    /**
     * Last-resort containment for a child coroutine whose ordinary controller path threw. The
     * direct-boot path executes and freshly reads both immutable gates, but leaves any existing
     * lease armed. Only the normal coordinator boundary is considered recoverable for HTTP.
     */
    private suspend fun containSafetyCoroutineFailure(
        sourceId: String,
        context: String,
    ): Boolean {
        activateSafetyIncidentBoundary()
        runCatching { revokeAndCloseHttpServers() }
        val primary = if (initialized && !shuttingDown) {
            try {
                coordinator.localBlock(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val primarySafe = primary?.let(::isSafeStoppedBoundary) == true
        val fallbackBlocked = if (primarySafe) {
            true
        } else {
            try {
                com.jack.micbridge.safety.DirectBootFailsafeBlocker.block(applicationContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
        }
        val message = when {
            primarySafe -> "$context；已确认完整屏蔽，HTTP 保持关闭"
            fallbackBlocked ->
                "$context；备用路径已确认两个系统门均屏蔽，保护租约保留且 HTTP 保持关闭"
            else -> "$context；HTTP 已关闭且屏蔽状态无法确认"
        }
        if (!primarySafe) {
            runCatching { bootIncidentStore.record(message) }
            runCatching { bootIncidentStore.postFailureNotification() }
        }
        val previous = ServiceRuntime.snapshot.value
        val snapshot = previous.copy(
            micAccess = if (primarySafe || fallbackBlocked) {
                MicAccessState.BLOCKED
            } else {
                MicAccessState.UNKNOWN
            },
            transitioning = false,
            controlReadback = primarySafe || fallbackBlocked,
            acousticCalibrationValid = if (primarySafe || fallbackBlocked) {
                previous.acousticCalibrationValid
            } else {
                false
            },
            serviceRunning = true,
            serverAddresses = emptyList(),
            autoBlockAtEpochMs = if (primarySafe) null else previous.autoBlockAtEpochMs,
            leaseExactAlarmArmed = if (primarySafe) null else previous.leaseExactAlarmArmed,
            leaseRootWatchdogArmed = if (primarySafe) null else previous.leaseRootWatchdogArmed,
            lastError = message,
        )
        runCatching { publishAndNotifyIfActive(snapshot) }
        return primarySafe
    }

    private fun revokeAndCloseHttpServers() {
        // Closing authority is also a generation change. Merely running at the boundary would
        // let an older refresh finish its settle delay and bind a fresh listener afterwards.
        networkRefreshEvents.advance(::revokeAndCloseHttpServersLocked)
    }

    /**
     * Once stopSelf() is committed, no later start command on this same Service instance may
     * re-authorize HTTP. Android may delay onDestroy on the main looper; only a new onCreate
     * instance resets this gate.
     */
    private fun commitTerminalStopBoundary() {
        networkRefreshEvents.advance {
            stopCommittedBoundaryActive = true
            revokeAndCloseHttpServersLocked()
        }
    }

    /** Must run inside [LatestEventMutex]'s synchronous boundary lock. */
    private fun revokeAndCloseHttpServersLocked() {
        serverBoundaryCounter.incrementAndGet()
        activeRemoteGeneration = NO_REMOTE_GENERATION
        remoteMutationFenceActive = false
        boundAddresses = emptyList()
        val servers = allHttpServers.toList()
        allHttpServers.clear()
        servers.forEach { it.close() }
        wakeSafetyMonitors()
    }

    private fun wakeSafetyMonitors() {
        permissionMonitorWakeups.trySend(Unit)
        networkAddressMonitorWakeups.trySend(Unit)
    }

    private fun clearMaintenanceBoundaryIfUncontested(expectedGeneration: Long): Boolean =
        synchronized(maintenanceBoundaryLock) {
            if (
                maintenanceOperationsInFlight == 0 &&
                maintenanceBoundaryGeneration == expectedGeneration &&
                !shuttingDown
            ) {
                maintenanceBoundaryActive = false
                true
            } else {
                false
            }
        }

    private fun clearCalibrationBoundaryIfUncontested(expectedGeneration: Long): Boolean =
        synchronized(calibrationBoundaryLock) {
            if (
                calibrationOperationsInFlight == 0 &&
                calibrationBoundaryGeneration == expectedGeneration &&
                !shuttingDown
            ) {
                calibrationBoundaryActive = false
                true
            } else {
                false
            }
        }

    /**
     * A successful COMPLETE call is itself counted as the sole in-flight calibration action.
     * Keeping that fact in the predicate prevents an older completion from reopening HTTP over
     * a newly queued calibration action.
     */
    private fun clearCalibrationBoundaryAfterSuccessfulCommit(
        expectedGeneration: Long,
    ): Boolean = synchronized(calibrationBoundaryLock) {
        if (
            calibrationOperationsInFlight == 1 &&
            calibrationBoundaryGeneration == expectedGeneration &&
            !shuttingDown
        ) {
            calibrationBoundaryActive = false
            true
        } else {
            false
        }
    }

    private fun activateSafetyIncidentBoundary(): Long {
        var generation = 0L
        // Serialize the deny publication with a listener's final commit check. If activation
        // wins, that listener cannot publish; if commit wins, every later request observes the
        // volatile deny gate while the HTTP failure path closes that generation.
        networkRefreshEvents.runAtBoundary {
            synchronized(safetyIncidentBoundaryLock) {
                safetyIncidentBoundaryGeneration += 1L
                safetyIncidentBoundaryActive = true
                generation = safetyIncidentBoundaryGeneration
            }
        }
        return generation
    }

    private fun clearSafetyIncidentBoundaryIfCurrent(expectedGeneration: Long): Boolean =
        synchronized(safetyIncidentBoundaryLock) {
            if (
                safetyIncidentBoundaryGeneration == expectedGeneration &&
                !shuttingDown
            ) {
                safetyIncidentBoundaryActive = false
                true
            } else {
                false
            }
        }

    private fun clearTaskRemovalBoundaryIfCurrent(expectedEvent: Long): Boolean =
        synchronized(taskRemovalLock) {
            if (taskRemovalCounter.get() == expectedEvent && !shuttingDown) {
                taskRemovalBoundaryActive = false
                true
            } else {
                false
            }
        }

    private fun publishPreInitializationError(message: String) {
        val snapshot = ServiceRuntime.snapshot.value.copy(
            micAccess = MicAccessState.UNKNOWN,
            transitioning = false,
            controlReadback = false,
            serviceRunning = true,
            serverAddresses = emptyList(),
            autoBlockAtEpochMs = null,
            leaseExactAlarmArmed = null,
            leaseRootWatchdogArmed = null,
            lastError = message,
        )
        publishAndNotifyIfActive(snapshot)
    }

    private suspend fun publishCurrent(overrideError: String? = null) {
        if (!initialized) return
        val current = coordinator.readSnapshot(true, boundAddresses).let {
            val enriched = it.copy(batteryOptimizationExempt = isBatteryOptimizationExempt())
                .copy(controllerProbe = controllerProbeSummary)
            if (overrideError == null) enriched else enriched.copy(lastError = overrideError)
        }
        publishAndNotifyIfActive(current)
    }

    private suspend fun completeCalibrationSafely(calibrationGeneration: Long) {
        // Finalization always establishes BLOCKED first. This makes the first later remote
        // toggle deterministically target OPEN and prevents checkbox acknowledgement from
        // turning a still-live calibration lease into an already-open production state.
        val blocked = coordinator.localBlock("calibration-finalize")
        val safeBoundary = isSafeStoppedBoundary(blocked)
        val testedIdentity = calibrationSession.identityForCommit(
            settings.currentAcousticCalibrationIdentity(),
            safeBoundary,
        )
        val sequenceObserved = testedIdentity != null
        val saved = testedIdentity != null && runCatching {
            settings.markAcousticCalibrationPassed(testedIdentity)
        }.getOrDefault(false)
        val savedAndCurrent = saved && runCatching {
            settings.isAcousticCalibrationValid()
        }.getOrDefault(false)
        if (savedAndCurrent) {
            calibrationSession.reset()
            val released = clearCalibrationBoundaryAfterSuccessfulCommit(calibrationGeneration)
            publishCurrent(
                if (released) null
                else "校准已保存；检测到后续校准动作，HTTP 保持关闭",
            )
            if (released) scheduleNetworkRefresh()
        } else {
            if (saved) {
                // Do not leave a proof that failed the immediate post-commit identity check.
                settings.clearCalibration()
            }
            publishCurrent(
                when {
                    !safeBoundary -> "校准提交失败：最终 BLOCKED 或安全租约清理无法确认"
                    !sequenceObserved ->
                        "校准提交失败：本次服务会话未记录 OPEN→Root-only+AppOps允许→确认并完整BLOCKED 序列"
                    saved -> "校准提交失败：保存后目标应用或系统身份已变化；旧证据已撤销"
                    else -> "校准提交失败：目标包、录音权限或校准记录无法确认"
                },
            )
        }
    }

    private suspend fun prepareAppOpsSafeExitIfNeeded(): Boolean {
        if (!settings.hasPendingAppOpsState) return true
        val appOps = AppOpsModeReader(rootShell)
        val originalPackage = settings.originalAppOpsPackage ?: return false
        val originalUser = settings.originalAppOpsUserId ?: return false
        val appOpsTarget = SafetyTarget(
            SettingsRepository.CONTROLLER_APP_OPS,
            originalPackage,
            originalUser,
        )
        val globalTarget = SafetyTarget(
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
            GLOBAL_MIC_TARGET,
            appOpsTarget.userId,
        )
        val sensor = SensorPrivacyRootController(rootShell)
        val globalBlock = sensor.block(globalTarget)
        if (!globalBlock.controlReadback || globalBlock.observed != MicAccessState.BLOCKED) {
            return false
        }
        // AppOps reports a value, not its last writer. A legacy ownership marker can therefore
        // never prove that a same-valued external policy was not applied later. Preserve the
        // fresh package value verbatim; only retire MicBridge's old metadata while the separate
        // global sensor gate is freshly verified BLOCKED.
        if (appOps.currentMode(originalPackage, originalUser) == null) return false
        val globalStillBlocked = sensor.readState(globalTarget) == MicAccessState.BLOCKED
        if (!globalStillBlocked) return false
        val cleared = runCatching {
            settings.originalAppOpsMode = null
            settings.controllerId = SettingsRepository.CONTROLLER_SENSOR_PRIVACY
            settings.clearCalibration()
            true
        }.getOrDefault(false)
        if (!cleared) return false
        maintenanceGlobalBlockTarget = globalTarget
        return true
    }

    /**
     * Freeze a target that remains usable after removeRootBootGuard deletes the workspace owner.
     * The immutable executor freshly closes and reads both release gates before this target may
     * be used by onDestroy; merely copying mutable settings is not sufficient proof.
     */
    private suspend fun freezeMaintenanceBlockTarget(): Boolean {
        val target = maintenanceGlobalBlockTarget ?: run {
            val selected = if (::controller.isInitialized) {
                controller
            } else {
                ControllerFactory.create(this, settings, rootShell)
            }
            selected.captureSafetyTarget()
        } ?: return false
        val verified = com.jack.micbridge.safety.FailsafeBlocker.block(
            applicationContext,
            target.controllerId,
            target.targetPackage,
            target.userId,
        )
        if (!verified) return false
        maintenanceGlobalBlockTarget = target
        return true
    }

    private fun hasLocalNetworkPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                this,
                PERMISSION_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasVisibleNotificationPermission(): Boolean {
        val runtimeGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!runtimeGranted) return false
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun isBatteryOptimizationExempt(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    private fun currentLanAddressSet(): Set<String> =
        eligibleLanAddresses().mapNotNull { it.hostAddress }.toSet()

    /**
     * API 31-35 can identify a shared Wi-Fi Network, but have no public downstream tethering
     * interface callback. API 36+ may additionally bind only the exact interface names reported
     * by TetheringManager. A familiar private address alone is never treated as hotspot identity.
     */
    private fun eligibleLanAddresses(): List<java.net.Inet4Address> {
        if (Build.VERSION.SDK_INT >= 36 && !tetheringMonitoringReady) {
            ensureTetheringMonitor()
        }
        return NetworkAddressProvider.merge(
            monitoredWifiAddresses(),
            if (tetheringMonitoringReady) {
            NetworkAddressProvider.privateIpv4OnInterfaces(
                tetheringChangeMonitor?.interfaceNames().orEmpty(),
            )
            } else {
                emptyList()
            },
        )
    }

    private fun monitoredWifiAddresses(): List<java.net.Inet4Address> {
        val links = synchronized(observedNetworkLock) {
            observedNetworks.values.mapNotNull { observation ->
                val capabilities = observation.capabilities ?: return@mapNotNull null
                val trusted = observation.blocked == false &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                observation.linkProperties?.takeIf { trusted }
            }
        }
        return NetworkAddressProvider.privateWifiIpv4(links)
    }

    private fun noEligibleAddressMessage(): String = when {
        Build.VERSION.SDK_INT < 36 ->
            "未找到可完整监控的同一 Wi-Fi 私网 IPv4；Android 自建热点需 Android 16/API 36，保持屏蔽且不监听端口"
        !tetheringMonitoringReady ->
            "未找到可信同一 Wi-Fi 地址，且热点接口监控注册失败；保持屏蔽且不监听端口"
        else -> "未找到可信同一 Wi-Fi 或已确认热点 IPv4；保持屏蔽且不监听端口"
    }

    private fun handlePotentialLanAddressChange() {
        val current = currentLanAddressSet()
        if (current == lastObservedLanAddresses) return
        lastObservedLanAddresses = current
        scheduleNetworkRefresh(
            blockIfOpen = ServiceRuntime.snapshot.value.micAccess == MicAccessState.OPEN,
        )
    }

    private fun handleNetworkIdentityEvent() {
        val snapshot = ServiceRuntime.snapshot.value
        // Network handles identify trust domains; two unrelated Wi-Fi networks may assign the
        // same 192.168.x.x address. Revoke the listener generation for every identity/link
        // callback even while BLOCKED, so an accepted socket can never cross that boundary.
        // Cellular/VPN churn may conservatively cause an extra BLOCK/rebind.
        scheduleNetworkRefresh(
            blockIfOpen = snapshot.micAccess == MicAccessState.OPEN || snapshot.transitioning,
        )
    }

    private fun startNetworkAddressMonitor(): Boolean = synchronized(lifecycleRegistrationLock) {
        if (shuttingDown) {
            networkAddressMonitoringReady = false
            return@synchronized false
        }
        networkAddressMonitorJob?.cancel()
        lateinit var monitor: Job
        monitor = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (!shuttingDown) {
                    try {
                        handlePotentialLanAddressChange()
                        networkAddressMonitoringReady = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        networkAddressMonitoringReady = false
                        revokeAndCloseHttpServers()
                        containSafetyCoroutineFailure(
                            sourceId = "network-address-monitor-failure",
                            context = "网络地址监督器异常（${failure.javaClass.simpleName}）",
                        )
                        delay(NETWORK_MONITOR_RETRY_MS)
                    }
                    val snapshot = ServiceRuntime.snapshot.value
                    val interval = when {
                        snapshot.micAccess == MicAccessState.OPEN || snapshot.transitioning ->
                            OPEN_ADDRESS_MONITOR_INTERVAL_MS
                        boundAddresses.isEmpty() -> MISSING_ADDRESS_MONITOR_INTERVAL_MS
                        else -> IDLE_ADDRESS_MONITOR_INTERVAL_MS
                    }
                    withTimeoutOrNull(interval) { networkAddressMonitorWakeups.receive() }
                }
            } finally {
                if (networkAddressMonitorJob === monitor) {
                    networkAddressMonitoringReady = false
                }
            }
        }
        networkAddressMonitorJob = monitor
        monitor.invokeOnCompletion { failure ->
            if (networkAddressMonitorJob === monitor) {
                networkAddressMonitoringReady = false
                if (!shuttingDown && failure != null) {
                    revokeAndCloseHttpServers()
                    scope.launch {
                        containSafetyCoroutineFailure(
                            sourceId = "network-address-monitor-stopped",
                            context = "网络地址监督器意外终止（${failure.javaClass.simpleName}）",
                        )
                    }
                }
            }
        }
        monitor.start()
        networkAddressMonitoringReady = monitor.isActive
        isNetworkAddressMonitorReady()
    }

    private fun isNetworkAddressMonitorReady(): Boolean =
        networkAddressMonitoringReady && networkAddressMonitorJob?.isActive == true

    private fun registerNetworkCallback(): Boolean = synchronized(lifecycleRegistrationLock) {
        if (shuttingDown) return@synchronized false
        val registered = runCatching {
            synchronized(observedNetworkLock) { observedNetworks.clear() }
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
            true
        }.getOrDefault(false)
        networkMonitoringReady = registered
        registered
    }

    private fun ensureTetheringMonitor(): Boolean = synchronized(lifecycleRegistrationLock) {
        if (shuttingDown || Build.VERSION.SDK_INT < 36) {
            tetheringMonitoringReady = false
            return@synchronized false
        }
        if (tetheringChangeMonitor != null) {
            tetheringMonitoringReady = true
            return@synchronized true
        }
        val monitor = TetheringChangeMonitor.start(this) {
            // A tethering transition can replace the trust domain without changing our local
            // address. Treat it as an authority boundary even while BLOCKED.
            handleNetworkIdentityEvent()
        }
        tetheringChangeMonitor = monitor
        tetheringMonitoringReady = monitor != null
        tetheringMonitoringReady
    }

    private fun registerMicMuteMonitor(): Boolean = synchronized(lifecycleRegistrationLock) {
        if (shuttingDown) return@synchronized false
        val registered = runCatching {
            ContextCompat.registerReceiver(
                this,
                micMuteChangedReceiver,
                IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)
        micMuteMonitoringReady = registered
        registered
    }

    private fun startPermissionMonitor(): Boolean = synchronized(lifecycleRegistrationLock) {
        if (shuttingDown) {
            permissionMonitoringReady = false
            return@synchronized false
        }
        runCatching {
            permissionMonitorJob?.cancel()
            permissionsUsable =
                hasLocalNetworkPermission() && hasVisibleNotificationPermission() &&
                    isAppUserForeground() && isReliabilityReady() &&
                    isBatteryOptimizationExempt()

            lateinit var monitor: Job
            monitor = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (!shuttingDown) {
                        try {
                            val recovered = !permissionMonitoringReady
                            runPermissionMonitorIteration()
                            permissionMonitoringReady = true
                            if (
                                recovered && permissionsUsable && initialized && !shuttingDown
                            ) {
                                scheduleNetworkRefresh()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            permissionMonitoringReady = false
                            revokeAndCloseHttpServers()
                            containSafetyCoroutineFailure(
                                sourceId = "permission-monitor-failure",
                                context = "权限/Root 监督器监控异常（${failure.javaClass.simpleName}）",
                            )
                            delay(PERMISSION_MONITOR_RETRY_MS)
                        }

                        val snapshot = ServiceRuntime.snapshot.value
                        val interval = when {
                            snapshot.micAccess == MicAccessState.OPEN || snapshot.transitioning ->
                                OPEN_PERMISSION_MONITOR_INTERVAL_MS
                            boundAddresses.isNotEmpty() -> ACTIVE_PERMISSION_MONITOR_INTERVAL_MS
                            else -> IDLE_PERMISSION_MONITOR_INTERVAL_MS
                        }
                        withTimeoutOrNull(interval) { permissionMonitorWakeups.receive() }
                    }
                } finally {
                    if (permissionMonitorJob === monitor) permissionMonitoringReady = false
                }
            }
            permissionMonitorJob = monitor
            monitor.invokeOnCompletion { failure ->
                if (permissionMonitorJob === monitor) {
                    permissionMonitoringReady = false
                    if (!shuttingDown && failure != null) {
                        revokeAndCloseHttpServers()
                        scope.launch {
                            containSafetyCoroutineFailure(
                                sourceId = "permission-monitor-stopped",
                                context = "权限监控意外终止（${failure.javaClass.simpleName}）",
                            )
                        }
                    }
                }
            }
            monitor.start()
            permissionMonitoringReady = monitor.isActive
            isPermissionMonitorReady()
        }.getOrDefault(false)
    }

    private suspend fun runPermissionMonitorIteration() {
        val usable =
            hasLocalNetworkPermission() &&
                hasVisibleNotificationPermission() &&
                isAppUserForeground() &&
                isReliabilityReady() &&
                isBatteryOptimizationExempt()
        val previous = synchronized(permissionStateLock) {
            val old = permissionsUsable
            permissionsUsable = usable
            old
        }
        val snapshotAtCheck = ServiceRuntime.snapshot.value
        val activeAuthorityOrLease =
            boundAddresses.isNotEmpty() || allHttpServers.isNotEmpty() ||
                snapshotAtCheck.micAccess == MicAccessState.OPEN ||
                snapshotAtCheck.transitioning ||
                snapshotAtCheck.autoBlockAtEpochMs != null ||
                snapshotAtCheck.leaseExactAlarmArmed != null ||
                snapshotAtCheck.leaseRootWatchdogArmed != null
        if (!usable && activeAuthorityOrLease) {
            // Android exposes no single listener covering runtime grants, a user-disabled
            // notification/channel, foreground-user changes, and battery exemption. Treat the
            // condition as a level, not merely a true->false edge: the monitor may have started
            // before the WakeLock and listener became ready.
            revokeAndCloseHttpServers()
            if (initialized) {
                val blocked = coordinator.localBlock("permission-revoked")
                publishCurrent(
                    if (isSafeStoppedBoundary(blocked)) {
                        "必要权限、通知可见性、前台用户或电池豁免已变化；已关闭 HTTP 并确认屏蔽"
                    } else {
                        "必要权限、通知可见性、前台用户或电池豁免已变化；HTTP 已关闭但屏蔽无法确认"
                    },
                )
            }
        } else if (!previous && usable && initialized && !shuttingDown) {
            scheduleNetworkRefresh()
        }
        if (
            usable && initialized && !shuttingDown &&
            ServiceRuntime.snapshot.value.micAccess == MicAccessState.OPEN
        ) {
            val guardHealthy = coordinator.activeRootGuardHealthy()
            if (!guardHealthy) {
                revokeAndCloseHttpServers()
                val blocked = coordinator.localBlock("root-guard-health-lost")
                publishCurrent(
                    if (isSafeStoppedBoundary(blocked)) {
                        "Root 租约监督器健康证明丢失；已撤销 HTTP 并重新屏蔽"
                    } else {
                        "Root 租约监督器健康证明丢失，且屏蔽无法确认；HTTP 已关闭"
                    },
                )
            }
        }
    }

    private fun isPermissionMonitorReady(): Boolean =
        permissionMonitoringReady && permissionMonitorJob?.isActive == true

    private fun isAppUserForeground(): Boolean = runCatching {
        getSystemService(UserManager::class.java).isUserForeground
    }.getOrDefault(false)

    private fun isReliabilityReady(): Boolean {
        if (!settings.reliableMode) return true
        return runCatching {
            wakeLock?.isHeld == true && wakeLockRenewJob?.isActive == true
        }.getOrDefault(false)
    }

    private fun acquireWakeLock() {
        synchronized(lifecycleRegistrationLock) {
            check(!shuttingDown) { "服务正在停止，拒绝创建 WakeLock" }
            if (isReliabilityReady()) return@synchronized
            wakeLockRenewJob?.cancel()
            wakeLockRenewJob = null
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MicBridge::ForegroundService")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            lateinit var monitor: Job
            monitor = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (!shuttingDown) {
                        delay(WAKE_LOCK_RENEW_MS)
                        val renewed = synchronized(lifecycleRegistrationLock) {
                            if (shuttingDown || wakeLockRenewJob !== monitor) {
                                false
                            } else {
                                val current = wakeLock ?: error("WakeLock disappeared")
                                if (current.isHeld) current.release()
                                current.acquire(WAKE_LOCK_TIMEOUT_MS)
                                true
                            }
                        }
                        if (!renewed) break
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    revokeAndCloseHttpServers()
                    containSafetyCoroutineFailure(
                        sourceId = "wakelock-renewal-failure",
                        context = "可靠模式 WakeLock 续期失败（${failure.javaClass.simpleName}）",
                    )
                }
            }
            wakeLockRenewJob = monitor
            monitor.start()
            check(isReliabilityReady()) { "WakeLock 或续期监督器未就绪" }
            permissionMonitorWakeups.trySend(Unit)
        }
    }

    private fun releaseWakeLock() {
        synchronized(lifecycleRegistrationLock) {
            wakeLockRenewJob?.cancel()
            wakeLockRenewJob = null
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MicBridge 状态",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "显示麦克风访问、失败保护与服务状态"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun notification(snapshot: BridgeSnapshot): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val blockIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_BLOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            snapshot.transitioning -> "MicBridge：状态切换中"
            snapshot.lastError != null && snapshot.controlReadback &&
                snapshot.micAccess == MicAccessState.BLOCKED ->
                "MicBridge：操作失败，已确认屏蔽"
            !snapshot.controlReadback || snapshot.micAccess == MicAccessState.UNKNOWN ->
                "MicBridge：状态无法确认"
            snapshot.micAccess == MicAccessState.BLOCKED && snapshot.acousticCalibrationValid ->
                "MicBridge：麦克风访问已屏蔽"
            snapshot.micAccess == MicAccessState.BLOCKED ->
                "MicBridge：控制层已屏蔽，声学未校准"
            snapshot.acousticCalibrationValid -> "MicBridge：麦克风访问已开放"
            else -> "MicBridge：临时开放，声学未校准"
        }
        val address = snapshot.serverAddresses.firstOrNull() ?: "HTTP 未监听"
        val detail = snapshot.lastError ?: when {
            snapshot.micAccess == MicAccessState.OPEN && snapshot.autoBlockAtEpochMs != null ->
                "$address · 最迟约 ${
                    ((snapshot.autoBlockAtEpochMs - System.currentTimeMillis() + 999L) / 1_000L)
                        .coerceAtLeast(0L)
                } 秒后自动屏蔽"
            snapshot.micAccess == MicAccessState.OPEN ->
                "$address · 持续开放；再次按 Action Button 屏蔽"
            else -> "$address · ${controllerName(snapshot.controllerId)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_micbridge)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(snapshot.lastError == null)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(
                if (snapshot.lastError != null || snapshot.micAccess == MicAccessState.UNKNOWN) {
                    NotificationCompat.PRIORITY_HIGH
                }
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setContentIntent(contentIntent)
            .addAction(0, "立即屏蔽", blockIntent)
            .addAction(0, "屏蔽后停止", stopIntent)
            .build()
    }

    private fun updateNotification(snapshot: BridgeSnapshot) {
        synchronized(publicationLock) {
            if (terminalPublication) return
            updateNotificationUnconditionally(snapshot)
        }
    }

    private fun publishAndNotifyIfActive(snapshot: BridgeSnapshot): Boolean =
        synchronized(publicationLock) {
            if (terminalPublication) return@synchronized false
            ServiceRuntime.publish(snapshot)
            updateNotificationUnconditionally(snapshot)
            true
        }

    private fun updateNotificationUnconditionally(snapshot: BridgeSnapshot) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(snapshot),
        )
    }

    private fun controllerName(id: String): String = when (id) {
        SettingsRepository.CONTROLLER_APP_OPS -> "Root AppOps"
        SettingsRepository.CONTROLLER_AUDIO_MANAGER -> "AudioManager"
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> "Root sensor_privacy"
        else -> id
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the launcher task is not a request to stop the foreground service. Still
        // treat it as a remote-authority boundary: close every accepted socket, establish a
        // freshly verified BLOCKED state, and only then publish a new listener generation.
        val event = synchronized(taskRemovalLock) {
            taskRemovalBoundaryActive = true
            taskRemovalCounter.incrementAndGet()
        }
        revokeAndCloseHttpServers()
        scope.launch {
            try {
            taskRemovalMutex.withLock {
                if (event != taskRemovalCounter.get()) return@withLock
                while (
                    !initialized && !shuttingDown &&
                    event == taskRemovalCounter.get()
                ) delay(50L)
                if (
                    !initialized || shuttingDown ||
                    event != taskRemovalCounter.get()
                ) return@withLock

                val blocked = coordinator.localBlock("task-removed")
                val resumeRemote = isSafeStoppedBoundary(blocked) && !shuttingDown &&
                    event == taskRemovalCounter.get()
                if (resumeRemote) {
                    // Keep taskRemovalBoundaryActive until the generation-serialized recovery
                    // has re-blocked and claimed this exact removal event.
                    scheduleNetworkRefresh(recoverTaskRemovalEvent = event)
                } else if (event == taskRemovalCounter.get()) {
                    publishCurrent("任务被移除后无法确认屏蔽；HTTP 保持关闭")
                }
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!shuttingDown && event == taskRemovalCounter.get()) {
                    containSafetyCoroutineFailure(
                        sourceId = "task-removal-failure",
                        context = "任务移除安全边界异常（${failure.javaClass.simpleName}）",
                    )
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Publish the deny bit without waiting for any possibly blocked system Binder call in a
        // registration critical section. Every OPEN admission checks this volatile flag.
        shuttingDown = true
        // Coordinator callbacks can outlive LocalHttpServer.close(): accepted workers are not
        // force-cancelled mid-mutation. The volatile terminal epoch stops future publication;
        // an already-entered notification Binder call is allowed to finish later, after the
        // physical boundary. The final state is published under publicationLock below.
        terminalPublication = true
        runCatching { revokeAndCloseHttpServers() }
        // Fence the persistent OPEN authorization under the same root flock before waiting for
        // any service mutex. This readback remains valid even if an older HTTP coroutine is
        // delayed and this service scope is cancelled: its late Root OPEN sees a replaced lease.
        runCatching {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_FALLBACK_TIMEOUT_MS) {
                    val emergencySafety = if (::safety.isInitialized) {
                        safety
                    } else {
                        AutoBlockSafety(this@BridgeForegroundService, settings, rootShell)
                    }
                    emergencySafety.emergencyFenceAndBlock()
                } == true
            }
        }
        val primaryBlockVerified = runCatching {
            runBlocking {
                withTimeoutOrNull(4_000L) {
                    networkMutex.withLock {
                        runCatching { revokeAndCloseHttpServers() }
                        val primary = if (initialized) {
                            val maintenanceTarget = maintenanceGlobalBlockTarget
                            if (maintenanceTarget != null) {
                                com.jack.micbridge.safety.FailsafeBlocker.block(
                                    applicationContext,
                                    maintenanceTarget.controllerId,
                                    maintenanceTarget.targetPackage,
                                    maintenanceTarget.userId,
                                )
                            } else {
                                val result = coordinator.localBlock("service-destroy")
                                isSafeStoppedBoundary(result)
                            }
                        } else {
                            false
                        }
                        primary
                    }
                } == true
            }
        }.getOrDefault(false)
        // The synchronous emergency sensor gate fences a late Root OPEN, but it does not prove
        // the configured redundant AudioManager gate. Only the complete primary readback may
        // publish a safe stopped boundary or retire the remaining lease protections.
        val shutdownBlockVerified = primaryBlockVerified
        if (shutdownBlockVerified) {
            // Lease cleanup is best-effort here and has a separate budget. Leaving a stale
            // lease is safe: the root supervisor and alarms can only issue another BLOCK.
            runCatching {
                runBlocking {
                    withTimeoutOrNull(SHUTDOWN_LEASE_CLEANUP_TIMEOUT_MS) {
                        val fallbackSafety = if (::safety.isInitialized) {
                            safety
                        } else {
                            AutoBlockSafety(this@BridgeForegroundService, settings, rootShell)
                        }
                        runCatching { fallbackSafety.cancel() }
                    }
                }
            }
        }
        val shutdownFailureMessage =
            "服务已停止，但屏蔽状态无法确认；请立即使用 Android 系统麦克风隐私开关"
        val incidentPersisted = if (shutdownBlockVerified) {
            true
        } else {
            runCatching { bootIncidentStore.record(shutdownFailureMessage) }.getOrDefault(false)
        }
        // Publish the truthful terminal state before waiting on any cleanup Binder. The
        // physical fence is already complete, terminalPublication rejects future callbacks,
        // and the persistent incident survives even if NotificationManager itself stalls.
        val terminalSnapshot = synchronized(publicationLock) {
            if (shutdownBlockVerified) {
                runCatching { ServiceRuntime.markStopped() }
            } else {
                runCatching {
                    ServiceRuntime.publish(
                        ServiceRuntime.snapshot.value.copy(
                            micAccess = MicAccessState.UNKNOWN,
                            transitioning = false,
                            controlReadback = false,
                            acousticCalibrationValid = false,
                            serviceRunning = false,
                            serverAddresses = emptyList(),
                            lastError = shutdownFailureMessage,
                            observedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            ServiceRuntime.snapshot.value
        }
        runCatching { updateNotificationUnconditionally(terminalSnapshot) }
        if (!shutdownBlockVerified) {
            runCatching {
                postShutdownFailureNotification(
                    if (incidentPersisted) shutdownFailureMessage
                    else "$shutdownFailureMessage；持久告警写入失败",
                )
            }
        }
        // Now wait out any registration/renewal that began before shuttingDown, then clean up
        // exactly the final registered objects. Binder stalls here cannot delay the safety fence.
        synchronized(lifecycleRegistrationLock) {
            if (networkMonitoringReady) {
                runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
                networkMonitoringReady = false
            }
            if (micMuteMonitoringReady) {
                runCatching { unregisterReceiver(micMuteChangedReceiver) }
                micMuteMonitoringReady = false
            }
            runCatching { tetheringChangeMonitor?.close() }
            tetheringChangeMonitor = null
            tetheringMonitoringReady = false
            synchronized(observedNetworkLock) { observedNetworks.clear() }
            networkAddressMonitorJob?.cancel()
            networkAddressMonitorJob = null
            networkAddressMonitoringReady = false
            permissionMonitorJob?.cancel()
            permissionMonitorJob = null
            permissionMonitoringReady = false
            releaseWakeLock()
        }
        autoBlockJob?.cancel()
        stateChangeSoundJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun postShutdownFailureNotification(message: String) {
        if (!hasVisibleNotificationPermission()) return
        val contentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val failure = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_micbridge)
            .setContentTitle("MicBridge：状态无法确认")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            FAILURE_NOTIFICATION_ID,
            failure,
        )
    }

    private fun isSafeStartupBoundary(result: ControlResult): Boolean =
        result.controlReadback &&
            result.observed == MicAccessState.BLOCKED &&
            result.errorCode != "LEASE_CANCEL_FAILED"

    private fun isSafeStoppedBoundary(result: OperationResult): Boolean =
        result.controlReadback &&
            result.micAccess == MicAccessState.BLOCKED &&
            result.errorCode != "LEASE_CANCEL_FAILED" &&
            result.autoBlockAtEpochMs == null &&
            result.leaseExactAlarmArmed == null &&
            result.leaseRootWatchdogArmed == null

    private fun queueStateChangeCue(result: OperationResult) {
        val cue = stateChangeCueFor(result) ?: return
        stateChangeSoundJob?.cancel()
        stateChangeSoundJob = scope.launch {
            runCatching { stateChangeSoundPlayer.play(cue) }
                .onFailure { failure ->
                    if (failure !is CancellationException) {
                        Log.w(TAG, "Unable to play $cue state cue", failure)
                    }
                }
        }
    }

    private data class NetworkObservation(
        var capabilities: NetworkCapabilities? = null,
        var linkProperties: LinkProperties? = null,
        var blocked: Boolean? = null,
    )

    companion object {
        private const val TAG = "MicBridgeService"
        const val ACTION_START = "com.jack.micbridge.START"
        const val ACTION_BLOCK = "com.jack.micbridge.BLOCK"
        const val ACTION_CALIBRATION_OPEN = "com.jack.micbridge.CALIBRATION_OPEN"
        const val ACTION_CALIBRATION_ROOT_ISOLATION =
            "com.jack.micbridge.CALIBRATION_ROOT_ISOLATION"
        const val ACTION_CALIBRATION_CONFIRM_AND_BLOCK =
            "com.jack.micbridge.CALIBRATION_CONFIRM_AND_BLOCK"
        const val ACTION_COMPLETE_CALIBRATION = "com.jack.micbridge.COMPLETE_CALIBRATION"
        const val ACTION_REFRESH = "com.jack.micbridge.REFRESH"
        const val ACTION_INSTALL_GUARD = "com.jack.micbridge.INSTALL_GUARD"
        const val ACTION_REMOVE_GUARD = "com.jack.micbridge.REMOVE_GUARD"
        const val ACTION_STOP = "com.jack.micbridge.STOP"
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        private const val CHANNEL_ID = "micbridge_status"
        private const val OPEN_PERMISSION_MONITOR_INTERVAL_MS = 250L
        private const val ACTIVE_PERMISSION_MONITOR_INTERVAL_MS = 2_000L
        private const val PERMISSION_MONITOR_RETRY_MS = 1_000L
        private const val SHUTDOWN_FALLBACK_TIMEOUT_MS = 4_000L
        private const val STARTUP_BLOCK_TIMEOUT_MS = 4_000L
        private const val SHUTDOWN_LEASE_CLEANUP_TIMEOUT_MS = 2_000L
        private const val IDLE_PERMISSION_MONITOR_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_ID = 8787
        private const val FAILURE_NOTIFICATION_ID = 8786
        private const val NETWORK_SETTLE_MS = 500L
        private const val NETWORK_MONITOR_RETRY_MS = 1_000L
        private const val COUNTDOWN_TICK_MS = 1_000L
        private const val MAX_TRANSITION_EVENT_COMMIT_CHECKS = 3
        private const val OPEN_ADDRESS_MONITOR_INTERVAL_MS = 250L
        private const val MISSING_ADDRESS_MONITOR_INTERVAL_MS = 2_000L
        private const val IDLE_ADDRESS_MONITOR_INTERVAL_MS = 30_000L
        private const val INIT_RETRY_MS = 5_000L
        private const val NO_REMOTE_GENERATION = -1L
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_MS = 45 * 60 * 1_000L
        private const val GLOBAL_MIC_TARGET = "android-global-microphone"
        private val CALIBRATION_ACTIONS = setOf(
            ACTION_CALIBRATION_OPEN,
            ACTION_CALIBRATION_ROOT_ISOLATION,
            ACTION_CALIBRATION_CONFIRM_AND_BLOCK,
            ACTION_COMPLETE_CALIBRATION,
        )

        fun start(context: Context, action: String = ACTION_START) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java).setAction(action),
            )
        }
    }
}
