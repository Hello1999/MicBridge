package com.jack.micbridge

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.AuditLogRepository
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.service.ServiceRuntime
import kotlinx.coroutines.delay
import java.time.Instant

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
        // A cold launch and a return from system network/permission settings are both trust
        // boundaries. ACTION_START synchronously revokes the old listener generation and
        // follows with BLOCK-before-rebind when the service is already initialized.
        BridgeForegroundService.start(this)
    }
}

@Composable
private fun MicBridgeScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val auditLog = remember { AuditLogRepository(context) }
    val snapshot by ServiceRuntime.snapshot.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var tokenVisible by remember { mutableStateOf(false) }
    var packageInput by remember(refreshKey) { mutableStateOf(settings.targetPackage) }
    var portInput by remember(refreshKey) { mutableStateOf(settings.port.toString()) }
    var secondsInput by remember(refreshKey) { mutableStateOf(settings.maxOpenSeconds.toString()) }
    var calibrationBlockedConfirmed by remember(
        refreshKey,
        snapshot.serviceRunning,
        settings.controllerId,
        settings.targetPackage,
    ) { mutableStateOf(false) }
    var calibrationOpenConfirmed by remember(
        refreshKey,
        snapshot.serviceRunning,
        settings.controllerId,
        settings.targetPackage,
    ) { mutableStateOf(false) }
    var calibrationRootFailsafeConfirmed by remember(
        refreshKey,
        snapshot.serviceRunning,
        settings.controllerId,
        settings.targetPackage,
    ) { mutableStateOf(false) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        transientMessage = if (results.values.all { it }) {
            BridgeForegroundService.start(context)
            "权限已授予；正在启动服务"
        } else {
            "权限未完整授予；服务会保持屏蔽且不开放 HTTP"
        }
    }

    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            delay(4_000L)
            transientMessage = null
        }
    }

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
                "Android 是唯一状态源。应用不录音，也没有 RECORD_AUDIO 权限。",
                style = MaterialTheme.typography.bodyMedium,
            )
            StatusCard(snapshot)
            transientMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }

            Section("服务与安全") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val missing = requiredRuntimePermissions(context)
                        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                        else BridgeForegroundService.start(context)
                    }) { Text("启动服务") }
                    OutlinedButton(
                        onClick = { BridgeForegroundService.start(context, BridgeForegroundService.ACTION_BLOCK) },
                        enabled = snapshot.serviceRunning,
                    ) { Text("立即屏蔽") }
                    OutlinedButton(
                        onClick = { BridgeForegroundService.start(context, BridgeForegroundService.ACTION_STOP) },
                        enabled = snapshot.serviceRunning,
                    ) { Text("屏蔽后停止") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可靠模式（前台服务持有 Partial WakeLock）", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.reliableMode,
                        enabled = !snapshot.serviceRunning,
                        onCheckedChange = {
                            runCatching { settings.reliableMode = it }
                                .onSuccess { refreshKey++ }
                                .onFailure { transientMessage = "可靠模式保存失败" }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开机自动启动服务", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.autoStart,
                        onCheckedChange = {
                            runCatching { settings.autoStart = it }
                                .onSuccess { refreshKey++ }
                                .onFailure { transientMessage = "自动启动设置保存失败" }
                        },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            BridgeForegroundService.start(context, BridgeForegroundService.ACTION_INSTALL_GUARD)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = snapshot.serviceRunning &&
                            settings.controllerId in setOf(
                                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
                                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                            ),
                    ) { Text("安装/刷新 Root 开机保护") }
                    OutlinedButton(
                        onClick = {
                            BridgeForegroundService.start(context, BridgeForegroundService.ACTION_REMOVE_GUARD)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = settings.rootBootGuardInstalled || settings.hasPendingAppOpsState,
                    ) { Text("全局屏蔽、清理旧元数据、移除并停止") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { openExactAlarmSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("精确闹钟设置")
                    }
                    OutlinedButton(
                        onClick = { openBatteryOptimizationSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("电池优化设置")
                    }
                    OutlinedButton(
                        onClick = { openAppDetailsSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("应用后台设置")
                    }
                }
                Text(
                    "电池优化豁免：${if (snapshot.batteryOptimizationExempt) "已确认" else "未确认（远程 OPEN 禁用）"}。" +
                        "还需在厂商电池/后台管理中设为“不受限制”，并在 ChatGPT 设置中启用“后台对话”；这些设置仍必须锁屏真测。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Root 开机脚本：${if (settings.rootBootGuardInstalled) "已部署（重启读回仍需真机验证）" else "未部署/不支持"}。两个发布控制器每次开放都必须成功启动 Root watcher 并取得、读回有超时的 kernel wake lock；AudioManager 选项的 Root fail-safe 映射为全局 sensor_privacy。系统双精确安全闹钟仍是允许开放的必要条件。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("控制器") {
                Text("服务运行、Root 开机脚本仍在或存在旧 AppOps 元数据时不可更换控制器/目标包；使用上方的安全维护按钮完成原子化退出。")
                Text(
                    "系统级麦克风门会同时影响电话、相机、录音和其他应用；本工具不保证覆盖紧急呼叫行为。先在非紧急场景真机验证。",
                    color = ComposeColor(0xFFB3261E),
                    fontWeight = FontWeight.Bold,
                )
                if (settings.hasPendingAppOpsState) {
                    Text(
                        "检测到开发版遗留 AppOps 元数据。由于系统不提供可靠的最后写入者证明，安全维护只会在全局麦克风已确认屏蔽后保留当前 AppOps 值并清理元数据；不会自动放宽外部策略。",
                        color = ComposeColor(0xFFB3261E),
                    )
                }
                ControllerChoice(
                    selected = settings.controllerId,
                    enabled = !snapshot.serviceRunning &&
                        !settings.rootBootGuardInstalled &&
                        !settings.hasPendingAppOpsState,
                    onSelected = {
                        runCatching {
                            settings.controllerId = it
                            settings.clearCalibration()
                        }.onSuccess {
                            refreshKey++
                        }.onFailure {
                            transientMessage = "控制器设置保存失败；保持屏蔽"
                        }
                    },
                )
                OutlinedTextField(
                    value = packageInput,
                    onValueChange = { packageInput = it },
                    label = { Text("ChatGPT Android 包名") },
                    supportingText = { Text("默认 com.openai.chatgpt；以 adb/已安装包为准") },
                    enabled = !snapshot.serviceRunning &&
                        !settings.rootBootGuardInstalled &&
                        !settings.hasPendingAppOpsState,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        if (PACKAGE_PATTERN.matches(packageInput)) {
                            runCatching {
                                settings.targetPackage = packageInput
                                settings.originalAppOpsMode = null
                                settings.clearCalibration()
                            }.onSuccess {
                                transientMessage = "目标包已保存；请重新校准"
                                refreshKey++
                            }.onFailure {
                                transientMessage = "目标包保存失败；服务将拒绝开放"
                            }
                        } else transientMessage = "包名格式无效"
                    },
                    enabled = !snapshot.serviceRunning &&
                        !settings.rootBootGuardInstalled &&
                        !settings.hasPendingAppOpsState,
                ) { Text("保存目标包") }
            }

            Section("校准临时开放时限") {
                OutlinedTextField(
                    value = secondsInput,
                    onValueChange = { secondsInput = it.filter(Char::isDigit) },
                    label = { Text("校准最长开放秒数（5–30）") },
                    enabled = !snapshot.serviceRunning,
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    val value = secondsInput.toIntOrNull()
                    if (value != null && value in
                        SettingsRepository.MIN_OPEN_SECONDS..SettingsRepository.MAX_OPEN_SECONDS
                    ) {
                        runCatching { settings.maxOpenSeconds = value }
                            .onSuccess {
                                transientMessage = "开放上限已保存"
                                refreshKey++
                            }
                            .onFailure { transientMessage = "开放上限保存失败" }
                    } else transientMessage = "请输入 5–30"
                }, enabled = !snapshot.serviceRunning) { Text("保存时限") }
                Text(
                    "仅用于声学校准。iPhone/Action Button 的 HTTP toggle 采用持续开放：再次按下时才切回 BLOCKED。服务重启或安全边界异常仍会故障安全屏蔽。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("声学校准（真机必做）") {
                Text(
                    "先启动 ChatGPT Voice/Live 并临时开放；再执行 Root 隔离屏蔽。隔离步骤会保持 AudioManager=OPEN，只允许 Root sensor_privacy 阻断收音，因此必须确认此时 ChatGPT 确实听不到声音。随后立即执行完整屏蔽。固件、控制器或 ChatGPT 版本变化会自动使校准失效。",
                )
                OutlinedButton(
                    onClick = {
                        calibrationRootFailsafeConfirmed = false
                        calibrationBlockedConfirmed = false
                        calibrationOpenConfirmed = false
                        BridgeForegroundService.start(
                            context,
                            BridgeForegroundService.ACTION_CALIBRATION_OPEN,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = snapshot.serviceRunning,
                ) { Text("1. 临时开放用于测试") }
                OutlinedButton(
                    onClick = {
                        calibrationRootFailsafeConfirmed = false
                        BridgeForegroundService.start(
                            context,
                            BridgeForegroundService.ACTION_CALIBRATION_ROOT_ISOLATION,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = snapshot.serviceRunning &&
                        snapshot.controlReadback &&
                        snapshot.micAccess == MicAccessState.OPEN &&
                        snapshot.leaseRootWatchdogArmed == true,
                ) { Text("2. 仅用 Root 屏蔽（隔离校验）") }
                CheckRow(
                    "隔离步骤中 AudioManager 保持开放，但 ChatGPT 确实听不到测试短语",
                    calibrationRootFailsafeConfirmed,
                ) { calibrationRootFailsafeConfirmed = it }
                OutlinedButton(
                    onClick = {
                        BridgeForegroundService.start(
                            context,
                            BridgeForegroundService.ACTION_CALIBRATION_CONFIRM_AND_BLOCK,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = snapshot.serviceRunning &&
                        snapshot.micAccess == MicAccessState.UNKNOWN &&
                        snapshot.autoBlockAtEpochMs != null &&
                        snapshot.lastError.orEmpty().contains("隔离校准模式") &&
                        calibrationRootFailsafeConfirmed,
                ) { Text("3. 确认无收音并立即完整屏蔽") }
                CheckRow(
                    "屏蔽后 ChatGPT 在锁屏/熄屏下确实听不到测试短语",
                    calibrationBlockedConfirmed,
                ) { calibrationBlockedConfirmed = it }
                CheckRow(
                    "开放后无需重建会话即可恢复，且已连续验证至少 20 次",
                    calibrationOpenConfirmed,
                ) { calibrationOpenConfirmed = it }
                Button(
                    onClick = {
                        BridgeForegroundService.start(
                            context,
                            BridgeForegroundService.ACTION_COMPLETE_CALIBRATION,
                        )
                        calibrationBlockedConfirmed = false
                        calibrationOpenConfirmed = false
                        calibrationRootFailsafeConfirmed = false
                        transientMessage = "正在最终确认 BLOCKED 并提交本次校准"
                    },
                    enabled = snapshot.serviceRunning &&
                        snapshot.controlReadback &&
                        snapshot.micAccess == MicAccessState.BLOCKED &&
                        snapshot.autoBlockAtEpochMs == null &&
                        calibrationRootFailsafeConfirmed &&
                        calibrationBlockedConfirmed &&
                        calibrationOpenConfirmed,
                ) { Text("记录校准通过") }
                Text(
                    "当前声学校准：${if (settings.isAcousticCalibrationValid()) "有效" else "未完成或已失效"}",
                    fontWeight = FontWeight.Bold,
                )
            }

            Section("局域网 API") {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    enabled = !snapshot.serviceRunning,
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        val value = portInput.toIntOrNull()
                        if (value != null && value in 1024..65535) {
                            runCatching { settings.port = value }
                                .onSuccess {
                                    transientMessage = "端口已保存"
                                    refreshKey++
                                }
                                .onFailure { transientMessage = "端口保存失败" }
                        } else transientMessage = "端口必须为 1024–65535"
                    },
                    enabled = !snapshot.serviceRunning,
                ) { Text("保存端口") }
                val url = snapshot.serverAddresses.firstOrNull()?.let {
                    "http://$it/v1/mic/toggle"
                } ?: "服务启动并连接私网后显示 URL"
                Text("Toggle URL", fontWeight = FontWeight.Bold)
                Text(url)
                OutlinedButton(
                    onClick = { copyText(context, "MicBridge Toggle URL", url) },
                    enabled = snapshot.serverAddresses.isNotEmpty(),
                ) { Text("复制 URL") }

                Text("令牌", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = settings.token,
                    onValueChange = {},
                    readOnly = true,
                    visualTransformation = if (tokenVisible) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "遮罩" else "显示")
                    }
                    OutlinedButton(onClick = { copyText(context, "MicBridge Token", settings.token, sensitive = true) }) {
                        Text("复制令牌")
                    }
                    OutlinedButton(onClick = {
                        runCatching { settings.rotateToken() }
                            .onSuccess {
                                tokenVisible = false
                                transientMessage = "令牌已更换，旧令牌立即失效"
                                refreshKey++
                            }
                            .onFailure {
                                transientMessage = "令牌更换失败；旧令牌仍有效"
                            }
                    }, enabled = !snapshot.serviceRunning) { Text("重新生成") }
                }
                Text(
                    if (Build.VERSION.SDK_INT >= 36) {
                        "HTTP 明文令牌只适合专用 Android 私人热点或可信 Wi-Fi。热点仅在系统 TetheringManager 回调已成功注册并明确报告下游接口时绑定；否则保持屏蔽且不监听。"
                    } else {
                        "Android 15/API 35 及以下只支持两台手机加入同一个可信 Wi-Fi；不支持由本机 Android 创建热点。服务仅绑定已被系统网络回调完整跟踪的 Wi-Fi 地址。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("诊断") {
                Text("控制读回：${snapshot.controlReadback}")
                Text("声学校准：${snapshot.acousticCalibrationValid}")
                Text("控制器：${snapshot.controllerId}")
                Text("控制器自动探测：${snapshot.controllerProbe ?: "尚未完成"}")
                Text("精确安全闹钟：${snapshot.leaseExactAlarmArmed ?: "无活动租约"}")
                Text("Root 租约看门狗：${snapshot.leaseRootWatchdogArmed ?: "无活动租约"}")
                Text("电池优化豁免：${snapshot.batteryOptimizationExempt}")
                Text("地址：${snapshot.serverAddresses.joinToString().ifBlank { "无" }}")
                Text("最近耗时：${snapshot.lastLatencyMs?.let { "$it ms" } ?: "无"}")
                Text("最近错误：${snapshot.lastError ?: "无"}")
                OutlinedButton(
                    onClick = {
                        BridgeForegroundService.start(context, BridgeForegroundService.ACTION_START)
                        refreshKey++
                    },
                    enabled = snapshot.serviceRunning,
                ) { Text("先屏蔽并安全重扫网络/状态") }
                val recentAudit = remember(refreshKey, snapshot.observedAtEpochMs) {
                    auditLog.recent(20)
                }
                Text("最近审计记录（不含令牌/音频）", fontWeight = FontWeight.Bold)
                if (recentAudit.isEmpty()) {
                    Text("暂无", style = MaterialTheme.typography.bodySmall)
                } else {
                    recentAudit.forEach { entry ->
                        Text(
                            "${Instant.ofEpochMilli(entry.epochMs)} · ${entry.source} · " +
                                "${entry.result} · verified=${entry.verified} · " +
                                "controller=${entry.controller} · ${entry.latencyMs} ms · " +
                                "id=${entry.requestIdHash ?: "-"} · ${entry.errorCode ?: "OK"}" +
                                (entry.diagnostic?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusCard(snapshot: BridgeSnapshot) {
    var nowEpochMs by remember(snapshot.autoBlockAtEpochMs) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(snapshot.autoBlockAtEpochMs) {
        val deadline = snapshot.autoBlockAtEpochMs ?: return@LaunchedEffect
        while (nowEpochMs < deadline) {
            delay(1_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val (title, color) = when {
        snapshot.transitioning -> "状态切换中" to ComposeColor(0xFFC74600)
        !snapshot.controlReadback || snapshot.micAccess == MicAccessState.UNKNOWN ->
            "状态无法确认" to ComposeColor(0xFFB3261E)
        snapshot.micAccess == MicAccessState.BLOCKED && snapshot.acousticCalibrationValid ->
            "麦克风访问已屏蔽" to ComposeColor(0xFF147D45)
        snapshot.micAccess == MicAccessState.BLOCKED ->
            "控制层读回已屏蔽（声学未校准）" to ComposeColor(0xFFC74600)
        snapshot.acousticCalibrationValid ->
            "麦克风访问已开放" to ComposeColor(0xFFC74600)
        else -> "临时开放（声学未校准）" to ComposeColor(0xFFB3261E)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("服务：${if (snapshot.serviceRunning) "运行中" else "已停止"}")
                Text(
                    "验证：${if (snapshot.controlReadback && snapshot.acousticCalibrationValid && snapshot.lastError == null) "控制读回 + 声学校准有效" else "未满足完整验证"}",
                )
                snapshot.lastError?.let { message ->
                    Text("告警：$message", color = ComposeColor(0xFFB3261E))
                }
                snapshot.autoBlockAtEpochMs?.let { deadline ->
                    Text("自动屏蔽截止：${Instant.ofEpochMilli(deadline)}")
                    Text(
                        "剩余：${((deadline - nowEpochMs + 999L) / 1_000L).coerceAtLeast(0L)} 秒",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ControllerChoice(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    val choices = listOf(
        SettingsRepository.CONTROLLER_AUDIO_MANAGER to
            "AudioManager + Root sensor_privacy gate + ChatGPT AppOps 只读 veto（第一候选）",
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY to
            "Root sensor_privacy + AudioManager gate + ChatGPT AppOps 只读 veto（第二候选）",
    )
    choices.forEach { (id, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected == id,
                onClick = { onSelected(id) },
                enabled = enabled,
            )
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.weight(1f))
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

private fun openExactAlarmSettings(context: Context) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    if (alarmManager.canScheduleExactAlarms()) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure { openAppDetailsSettings(context) }
}

private fun openAppDetailsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

private fun copyText(context: Context, label: String, value: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
