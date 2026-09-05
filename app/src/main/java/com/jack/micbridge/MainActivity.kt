package com.jack.micbridge

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jack.micbridge.data.AuditLogRepository
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.service.ServiceRuntime
import com.jack.micbridge.ui.AdvancedActions
import com.jack.micbridge.ui.AdvancedSettings
import com.jack.micbridge.ui.CalibrationActions
import com.jack.micbridge.ui.CalibrationChecks
import com.jack.micbridge.ui.CalibrationWizard
import com.jack.micbridge.ui.DiagnosticsCard
import com.jack.micbridge.ui.ReadinessActions
import com.jack.micbridge.ui.ReadinessChecklist
import com.jack.micbridge.ui.Section
import com.jack.micbridge.ui.ShortcutConfigCard
import com.jack.micbridge.ui.StatusHero
import com.jack.micbridge.ui.readinessItems
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MicBridgeScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ACTION_START is a trust-boundary reset: it revokes the live HTTP listener generation
        // and BLOCKs before rebinding. Sending it on every resume meant that merely opening the
        // app to look at the status muted the microphone. It is now sent only when the service
        // is not running (cold launch) or when the user returns from a system settings page this
        // app opened, because permissions, battery exemption or exact-alarm grants may have
        // changed there. Any other foreground return only asks for a fresh status publication.
        val trustBoundary = SystemSettingsNavigator.consumePendingTrustBoundary()
        val running = ServiceRuntime.snapshot.value.serviceRunning
        when {
            !running || trustBoundary -> BridgeForegroundService.start(this)
            else -> BridgeForegroundService.start(this, BridgeForegroundService.ACTION_REFRESH)
        }
    }
}

/** Tracks whether the user left for a system settings page that can change an admission gate. */
internal object SystemSettingsNavigator {
    @Volatile
    private var pendingTrustBoundary = false

    fun consumePendingTrustBoundary(): Boolean {
        val pending = pendingTrustBoundary
        pendingTrustBoundary = false
        return pending
    }

    fun openExactAlarmSettings(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return
        launch(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
        )
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (!launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        launch(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }

    private fun launch(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }
            .onSuccess { pendingTrustBoundary = true }
            .isSuccess
}

@Composable
private fun MicBridgeScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val auditLog = remember { AuditLogRepository(context) }
    val snapshot by ServiceRuntime.snapshot.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    // Human observations for the calibration round. They reset whenever the round cannot be
    // the same one: service restart, controller/target change, or an explicit new round.
    val roundKey = "$refreshKey|${snapshot.serviceRunning}|${settings.controllerId}|${settings.targetPackage}"
    var rootFailsafeConfirmed by remember(roundKey) { mutableStateOf(false) }
    var blockedConfirmed by remember(roundKey) { mutableStateOf(false) }
    var openConfirmed by remember(roundKey) { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        transientMessage = if (results.values.all { it }) {
            BridgeForegroundService.start(context)
            "权限已授予；正在启动服务"
        } else {
            "权限未完整授予；服务会保持静音且不开放 HTTP"
        }
    }
    val startService = {
        val missing = requiredRuntimePermissions(context)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else BridgeForegroundService.start(context)
    }
    val send: (String) -> Unit = { action -> BridgeForegroundService.start(context, action) }

    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            delay(4_000L)
            transientMessage = null
        }
    }

    val readiness = readinessItems(
        snapshot,
        settings,
        ReadinessActions(
            startService = startService,
            openAppDetails = { SystemSettingsNavigator.openAppDetailsSettings(context) },
            openBatterySettings = { SystemSettingsNavigator.openBatteryOptimizationSettings(context) },
            openExactAlarmSettings = { SystemSettingsNavigator.openExactAlarmSettings(context) },
            requestPermissions = {
                val missing = requiredRuntimePermissions(context)
                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            },
            rescanNetwork = { send(BridgeForegroundService.ACTION_START) },
            installGuard = { send(BridgeForegroundService.ACTION_INSTALL_GUARD) },
        ),
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MicBridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Android 是唯一状态源；应用不录音，也没有 RECORD_AUDIO 权限。",
                style = MaterialTheme.typography.bodySmall,
            )
            StatusHero(snapshot)
            transientMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }

            Section("操作") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { send(BridgeForegroundService.ACTION_TOGGLE) },
                        // Opening takes the remote admission path, which requires a live
                        // listener generation; without a bound address the test would only be
                        // refused as REMOTE_OPEN_DISABLED, so the button waits for the address.
                        enabled = snapshot.serviceRunning && !snapshot.transitioning &&
                            !snapshot.calibrationIsolationActive &&
                            (snapshot.micAccess == MicAccessState.OPEN ||
                                snapshot.serverAddresses.isNotEmpty()),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (snapshot.micAccess == MicAccessState.OPEN) "测试：静音" else "测试：开放") }
                    OutlinedButton(
                        onClick = { send(BridgeForegroundService.ACTION_BLOCK) },
                        enabled = snapshot.serviceRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("立即静音") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (snapshot.serviceRunning) {
                        OutlinedButton(
                            onClick = { send(BridgeForegroundService.ACTION_STOP) },
                            modifier = Modifier.weight(1f),
                        ) { Text("静音后停止服务") }
                    } else {
                        Button(onClick = startService, modifier = Modifier.weight(1f)) { Text("启动服务") }
                    }
                }
                Text(
                    "“测试”按钮走与 iPhone 完全相同的路径（账本、校准门、Root 租约、监听代次）；" +
                        "在这里失败的切换从快捷指令也会失败。打开本页不会再改变麦克风状态。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ReadinessChecklist(readiness)

            ShortcutConfigCard(
                snapshot = snapshot,
                token = settings.token,
                port = settings.port,
                onMessage = { transientMessage = it },
            )

            CalibrationWizard(
                snapshot = snapshot,
                calibrationValid = snapshot.calibrationInvalidReason == null &&
                    settings.isAcousticCalibrationValid(),
                invalidReason = snapshot.calibrationInvalidReason
                    ?: settings.acousticCalibrationInvalidReason(),
                checks = CalibrationChecks(rootFailsafeConfirmed, blockedConfirmed, openConfirmed),
                actions = CalibrationActions(
                    open = {
                        rootFailsafeConfirmed = false
                        blockedConfirmed = false
                        openConfirmed = false
                        send(BridgeForegroundService.ACTION_CALIBRATION_OPEN)
                    },
                    isolate = {
                        rootFailsafeConfirmed = false
                        send(BridgeForegroundService.ACTION_CALIBRATION_ROOT_ISOLATION)
                    },
                    confirmAndBlock = { send(BridgeForegroundService.ACTION_CALIBRATION_CONFIRM_AND_BLOCK) },
                    commit = {
                        send(BridgeForegroundService.ACTION_COMPLETE_CALIBRATION)
                        rootFailsafeConfirmed = false
                        blockedConfirmed = false
                        openConfirmed = false
                        transientMessage = "正在最终确认已静音并提交本次校准"
                    },
                    setRootFailsafeConfirmed = { rootFailsafeConfirmed = it },
                    setBlockedConfirmed = { blockedConfirmed = it },
                    setOpenConfirmed = { openConfirmed = it },
                ),
            )

            AdvancedSettings(
                snapshot = snapshot,
                settings = settings,
                refreshKey = refreshKey,
                onChanged = { refreshKey++ },
                onMessage = { transientMessage = it },
                actions = AdvancedActions(
                    installGuard = { send(BridgeForegroundService.ACTION_INSTALL_GUARD) },
                    removeGuardAndStop = { send(BridgeForegroundService.ACTION_REMOVE_GUARD) },
                    openExactAlarmSettings = { SystemSettingsNavigator.openExactAlarmSettings(context) },
                    openBatterySettings = { SystemSettingsNavigator.openBatteryOptimizationSettings(context) },
                    openAppDetails = { SystemSettingsNavigator.openAppDetailsSettings(context) },
                ),
            )

            val recentAudit = remember(refreshKey, snapshot.observedAtEpochMs) { auditLog.recent(20) }
            DiagnosticsCard(
                snapshot = snapshot,
                recentAudit = recentAudit,
                onRescan = {
                    send(BridgeForegroundService.ACTION_START)
                    refreshKey++
                },
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun requiredRuntimePermissions(context: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(
            context,
            BridgeForegroundService.PERMISSION_LOCAL_NETWORK,
        ) != PackageManager.PERMISSION_GRANTED
    ) add(BridgeForegroundService.PERMISSION_LOCAL_NETWORK)
}
