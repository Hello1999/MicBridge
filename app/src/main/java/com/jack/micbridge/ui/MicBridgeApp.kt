package com.jack.micbridge.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.jack.micbridge.data.*
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.service.ServiceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BridgeRoute(val title: String, val symbol: BridgeSymbol) {
    CONTROL("控制", BridgeSymbol.CONTROL), CONNECT("连接", BridgeSymbol.LINK), SETTINGS("设置", BridgeSymbol.SETTINGS),
    CALIBRATION("声学校准", BridgeSymbol.MIC), DIAGNOSTICS("诊断与记录", BridgeSymbol.INFO), ADVANCED("高级控制", BridgeSymbol.CONTROL);
    val isMain get() = this in MainRoutes
    companion object { val MainRoutes = listOf(CONTROL, CONNECT, SETTINGS) }
}

private enum class Confirmation { REMOVE_GUARD, ROTATE_TOKEN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicBridgeApp(resumeGeneration: Int) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val audit = remember { AuditLogRepository(context) }
    val snapshot by ServiceRuntime.snapshot.collectAsState()
    val calibration by ServiceRuntime.calibration.collectAsState()
    var route by rememberSaveable { mutableStateOf(BridgeRoute.CONTROL) }
    var settingsRevision by remember { mutableIntStateOf(0) }
    // Drafts are independent of preference refreshes. Saving one setting must not
    // discard another field's unsubmitted input. Human calibration evidence is separate.
    var packageInput by rememberSaveable { mutableStateOf(settings.targetPackage) }
    var portInput by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var secondsInput by rememberSaveable { mutableStateOf(settings.maxOpenSeconds.toString()) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var pendingDestination by remember { mutableStateOf<BridgeRoute?>(null) }
    var exitObservation by remember { mutableStateOf<Long?>(null) }
    var dispatchHold by remember { mutableStateOf(false) }
    var dispatchSequence by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pageState = rememberSaveableStateHolder()

    fun notify(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, withDismissAction = true)
        }
    }
    fun command(action: String, message: String) {
        runCatching { BridgeForegroundService.start(context, action) }
            .onSuccess { notify(message) }
            .onFailure { notify("操作未能发出，请检查应用运行权限") }
    }
    fun save(success: String, mutation: () -> Unit) {
        runCatching(mutation).onSuccess { settingsRevision++; notify(success) }
            .onFailure { settingsRevision++; notify("保存失败，设置尚未完整生效") }
    }
    fun editableController() = !ServiceRuntime.snapshot.value.serviceRunning && !settings.rootBootGuardInstalled && !settings.hasPendingAppOpsState
    fun openSystemSettings(action: String, forPackage: Boolean = false) {
        runCatching {
            context.startActivity(Intent(action).apply { if (forPackage) data = "package:${context.packageName}".toUri() })
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
                .onFailure { notify("请从系统设置进入 MicBridge 的应用设置") }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) command(BridgeForegroundService.ACTION_START, "权限已授予，正在启动并确认屏蔽")
        else notify("权限未完整授予，远程服务不会开放")
        settingsRevision++
    }
    fun start() {
        val missing = requiredRuntimePermissions(context)
        if (missing.isEmpty()) command(BridgeForegroundService.ACTION_START, "正在启动并确认屏蔽")
        else permissionLauncher.launch(missing.toTypedArray())
    }
    fun block() = command(BridgeForegroundService.ACTION_BLOCK, "正在执行完整屏蔽，请等待状态读回")
    fun navigate(destination: BridgeRoute) {
        if (route == BridgeRoute.CALIBRATION && destination != route &&
            (calibration.stage != CalibrationStage.IDLE || calibration.working || dispatchHold || snapshot.autoBlockAtEpochMs != null)
        ) {
            pendingDestination = destination
            exitObservation = snapshot.observedAtEpochMs
            block()
        } else route = destination
    }
    fun back() = navigate(if (route.isMain) BridgeRoute.CONTROL else BridgeRoute.SETTINGS)

    LaunchedEffect(dispatchSequence) {
        if (dispatchHold) { delay(900); dispatchHold = false }
    }
    LaunchedEffect(snapshot, calibration, pendingDestination) {
        val destination = pendingDestination ?: return@LaunchedEffect
        // Wait for fresh BLOCKED readback and for the service to consume its round.
        if (!calibration.working && calibration.stage == CalibrationStage.IDLE &&
            snapshot.controlReadback && !snapshot.transitioning && snapshot.micAccess == MicAccessState.BLOCKED &&
            snapshot.autoBlockAtEpochMs == null && snapshot.observedAtEpochMs != exitObservation
        ) { pendingDestination = null; route = destination; notify("已确认完整屏蔽，本轮校准已退出") }
    }
    LaunchedEffect(pendingDestination) {
        if (pendingDestination != null) {
            delay(15_000)
            pendingDestination = null
            notify("尚未确认完整屏蔽，已留在校准页，请重试")
        }
    }
    BackHandler(route != BridgeRoute.CONTROL) { back() }

    val values = remember(settingsRevision, snapshot, resumeGeneration) {
        SettingsValues(settings.autoStart, settings.reliableMode, settings.rootBootGuardInstalled, settings.hasPendingAppOpsState, settings.controllerId)
    }
    val token = remember(settingsRevision) { settings.token }
    val runtimeGranted = remember(settingsRevision, resumeGeneration) { requiredRuntimePermissions(context).isEmpty() }
    val exactAlarmGranted = remember(settingsRevision, resumeGeneration) { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    val records by produceState<List<AuditEntry>?>(null, route, settingsRevision, snapshot.observedAtEpochMs) {
        if (route == BridgeRoute.DIAGNOSTICS) value = withContext(Dispatchers.IO) { audit.recent(20) }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (route.isMain) "MicBridge" else route.title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (!route.isMain) IconButton(onClick = { back() }, modifier = Modifier.semantics { contentDescription = "返回" }) { BridgeIcon(BridgeSymbol.BACK) }
                },
                actions = {
                    if (route.isMain) Row(Modifier.padding(end = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BridgeIcon(BridgeSymbol.POWER, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (snapshot.serviceRunning) "运行中" else "已停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                BridgeRoute.MainRoutes.forEach { destination ->
                    NavigationBarItem(
                        selected = route == destination || !route.isMain && destination == BridgeRoute.SETTINGS,
                        onClick = { navigate(destination) },
                        icon = { BridgeIcon(destination.symbol, Modifier.size(22.dp)) },
                        label = { Text(destination.title, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            pageState.SaveableStateProvider(route.name) {
                when (route) {
                    BridgeRoute.CONTROL -> ControlScreen(snapshot, ::start, ::block, { navigate(BridgeRoute.CONNECT) }, { navigate(BridgeRoute.CALIBRATION) }, { navigate(BridgeRoute.DIAGNOSTICS) })
                    BridgeRoute.CONNECT -> ConnectionScreen(
                        snapshot, token, portInput, { portInput = it.filter(Char::isDigit).take(5) },
                        onSavePort = {
                            val port = portInput.toIntOrNull()
                            if (!ServiceRuntime.snapshot.value.serviceRunning && port != null && port in 1024..65535) save("端口已保存") { settings.port = port }
                        },
                        onCopy = { label, value, sensitive ->
                            runCatching { copyText(context, label, value, sensitive) }.onSuccess { notify(if (sensitive) "令牌已复制" else "请求地址已复制") }.onFailure { notify("复制失败") }
                        },
                        onRotate = { confirmation = Confirmation.ROTATE_TOKEN }, onSettings = { navigate(BridgeRoute.SETTINGS) },
                    )
                    BridgeRoute.SETTINGS -> SettingsScreen(
                        snapshot, values, runtimeGranted, exactAlarmGranted,
                        onStart = ::start, onStop = { command(BridgeForegroundService.ACTION_STOP, "正在屏蔽，确认后将停止服务") },
                        onAutoStart = { save("开机启动设置已保存") { settings.autoStart = it } },
                        onReliable = { if (!ServiceRuntime.snapshot.value.serviceRunning) save("后台运行设置已保存") { settings.reliableMode = it } },
                        onPermissions = { if (runtimeGranted) openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) else permissionLauncher.launch(requiredRuntimePermissions(context).toTypedArray()) },
                        onExactAlarm = { if (exactAlarmGranted) notify("精确闹钟权限已允许") else openSystemSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) },
                        onBattery = { openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                        onAppSettings = { openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                        onInstallGuard = { command(BridgeForegroundService.ACTION_INSTALL_GUARD, "正在确认屏蔽并配置开机保护") },
                        onRemoveGuard = { confirmation = Confirmation.REMOVE_GUARD },
                        onCalibrate = { navigate(BridgeRoute.CALIBRATION) }, onDiagnostics = { navigate(BridgeRoute.DIAGNOSTICS) }, onAdvanced = { navigate(BridgeRoute.ADVANCED) },
                    )
                    BridgeRoute.ADVANCED -> AdvancedScreen(
                        snapshot, values, packageInput, secondsInput,
                        onController = { if (editableController()) save("控制方案已保存，请重新校准") { settings.controllerId = it; settings.clearCalibration() } },
                        onPackageChange = { packageInput = it },
                        onSavePackage = { if (editableController() && PackagePattern.matches(packageInput)) save("目标应用已保存，请重新校准") { settings.targetPackage = packageInput; settings.originalAppOpsMode = null; settings.clearCalibration() } },
                        onSecondsChange = { secondsInput = it.filter(Char::isDigit).take(2) },
                        onSaveSeconds = {
                            val seconds = secondsInput.toIntOrNull()
                            if (!ServiceRuntime.snapshot.value.serviceRunning && seconds != null && seconds in SettingsRepository.MIN_OPEN_SECONDS..SettingsRepository.MAX_OPEN_SECONDS) save("校准时限已保存") { settings.maxOpenSeconds = seconds }
                        }, onMaintenance = { navigate(BridgeRoute.SETTINGS) },
                    )
                    BridgeRoute.CALIBRATION -> CalibrationScreen(
                        snapshot, calibration, settings.targetPackage, dispatchHold, pendingDestination != null,
                        onCommand = { action ->
                            dispatchHold = true; dispatchSequence++
                            command(action, if (action == BridgeForegroundService.ACTION_COMPLETE_CALIBRATION) "正在最终确认屏蔽并提交校准" else "正在执行校准步骤，请等待实际读回")
                        }, onStart = ::start, onBlock = ::block,
                    )
                    BridgeRoute.DIAGNOSTICS -> DiagnosticsScreen(snapshot, records) { command(BridgeForegroundService.ACTION_START, "正在先屏蔽并重新检查网络与状态"); settingsRevision++ }
                }
            }
        }
    }

    confirmation?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            icon = { BridgeIcon(if (kind == Confirmation.REMOVE_GUARD) BridgeSymbol.SHIELD else BridgeSymbol.LINK) },
            title = { Text(if (kind == Confirmation.REMOVE_GUARD) "安全移除开机保护" else "更换访问令牌") },
            text = { Text(if (kind == Confirmation.REMOVE_GUARD) "将先完整屏蔽麦克风、清理旧元数据，再移除保护并停止服务。当前 AppOps 策略会保留。只有屏蔽得到确认后才会继续。" else "旧令牌将立即失效。更换后，需要同步更新 iPhone 快捷指令。") },
            confirmButton = {
                TextButton(onClick = {
                    if (kind == Confirmation.REMOVE_GUARD) command(BridgeForegroundService.ACTION_REMOVE_GUARD, "正在完整屏蔽并执行安全移除")
                    else if (!ServiceRuntime.snapshot.value.serviceRunning) save("令牌已更换，请更新快捷指令") { settings.rotateToken() }
                    confirmation = null
                }, enabled = kind != Confirmation.ROTATE_TOKEN || !snapshot.serviceRunning) { Text(if (kind == Confirmation.REMOVE_GUARD) "屏蔽并移除" else "更换令牌") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

private fun requiredRuntimePermissions(context: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, BridgeForegroundService.PERMISSION_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) add(BridgeForegroundService.PERMISSION_LOCAL_NETWORK)
}

private fun copyText(context: Context, label: String, value: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
