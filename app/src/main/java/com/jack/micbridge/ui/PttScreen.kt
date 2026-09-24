package com.jack.micbridge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jack.micbridge.LinkState
import com.jack.micbridge.MuteMethod
import com.jack.micbridge.Prefs
import com.jack.micbridge.PttRuntime
import com.jack.micbridge.PttStatus
import com.jack.micbridge.ble.ButtonScanner
import com.jack.micbridge.ble.FoundButton
import com.jack.micbridge.mic.MicLevelMeter
import com.jack.micbridge.service.PttService

private val servicePermissions = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun PttScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val status by PttRuntime.status.collectAsState()

    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var deviceAddress by remember { mutableStateOf(prefs.deviceAddress) }
    var method by remember { mutableStateOf(prefs.method) }
    var showScan by remember { mutableStateOf(false) }
    var startAfterGrant by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val bluetoothOk = result[Manifest.permission.BLUETOOTH_CONNECT] != false &&
            context.granted(Manifest.permission.BLUETOOTH_CONNECT)
        if (!bluetoothOk) {
            Toast.makeText(context, "需要“附近设备”权限才能连接按钮", Toast.LENGTH_LONG).show()
        } else if (startAfterGrant) {
            PttService.start(context)
        } else {
            showScan = true
        }
        startAfterGrant = false
    }
    fun withPermissions(startService: Boolean, action: () -> Unit) {
        if (servicePermissions.all { context.granted(it) }) action()
        else {
            startAfterGrant = startService
            permissionLauncher.launch(servicePermissions)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text("MicBridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "按住按钮说话，松开静音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TalkDisk(status)

            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("按键说话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (status.running) "运行中：平时静音，按住说话。关闭即恢复麦克风。"
                            else "开启后手机麦克风默认静音，按住按钮才会收音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = status.running,
                        onCheckedChange = { on ->
                            if (on) withPermissions(startService = true) { PttService.start(context) }
                            else PttService.stop(context)
                        },
                    )
                }
            }

            Section {
                Text("按钮", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(deviceName ?: "未绑定", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            linkText(status, deviceAddress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (deviceAddress != null) {
                        TextButton(onClick = {
                            prefs.deviceAddress = null
                            prefs.deviceName = null
                            deviceAddress = null
                            deviceName = null
                            if (status.running) { PttService.stop(context); PttService.start(context) }
                        }) { Text("解除") }
                    }
                    OutlinedButton(onClick = { withPermissions(startService = false) { showScan = true } }) {
                        Text("搜索")
                    }
                }
            }

            Section {
                Text("静音方式", style = MaterialTheme.typography.titleMedium)
                MuteMethod.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !status.running) { method = m; prefs.method = m }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = method == m, onClick = null, enabled = !status.running, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        Column {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            Text(m.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (status.running) {
                    Text("关闭“按键说话”后才能更改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            MicTestSection(status)

            status.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }

            TextButton(onClick = {
                PttService.restoreMic(context, method) { ok ->
                    ContextCompat.getMainExecutor(context).execute {
                        Toast.makeText(context, if (ok) "麦克风已恢复" else "恢复失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }, enabled = !status.running) {
                Text("恢复麦克风（异常时使用）")
            }
        }
    }

    if (showScan) {
        ScanDialog(
            onPick = { found ->
                prefs.deviceAddress = found.address
                prefs.deviceName = found.name
                deviceAddress = found.address
                deviceName = found.name
                showScan = false
                if (status.running) { PttService.stop(context); PttService.start(context) }
            },
            onDismiss = { showScan = false },
        )
    }
}

private fun linkText(status: PttStatus, address: String?): String = when {
    address == null -> "点“搜索”找到 ESP32 按钮"
    !status.running -> address
    status.link == LinkState.CONNECTED -> "已连接" + (status.lastLatencyMs?.let { " · 上次切换 $it ms" } ?: "")
    else -> "正在连接…"
}

@Composable
private fun TalkDisk(status: PttStatus) {
    val talking = status.running && status.micOpen == true
    val (label, sub, color) = when {
        !status.running -> Triple("未启用", "打开“按键说话”开始", PttColors.idle)
        status.micOpen == true -> Triple("说话中", if (status.buttonPressed) "实体按钮按下" else "屏幕按钮按下", PttColors.talking)
        status.micOpen == false -> Triple("已静音", "按住实体按钮，或按住这里", PttColors.muted)
        else -> Triple("状态未知", "正在应用静音…", PttColors.idle)
    }
    val animated by animateColorAsState(color, label = "disk")
    val scale by animateFloatAsState(if (talking) 1.06f else 1f, label = "scale")
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .size(220.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(animated)
            .pointerInput(status.running) {
                if (!status.running) return@pointerInput
                detectTapGestures(onPress = {
                    PttRuntime.screenPressed.value = true
                    try { tryAwaitRelease() } finally { PttRuntime.screenPressed.value = false }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MicTestSection(status: PttStatus) {
    val context = LocalContext.current
    var level by remember { mutableFloatStateOf(0f) }
    var testing by remember { mutableStateOf(false) }
    val meter = remember { MicLevelMeter { level = it } }
    DisposableEffect(Unit) { onDispose { meter.stop() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) testing = meter.start()
    }

    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("收音测试", style = MaterialTheme.typography.titleMedium)
                Text(
                    "开启按键说话后对着手机说话：按住时电平跳动，松开应归零。归零失败就换另一种静音方式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = {
                if (testing) { meter.stop(); testing = false }
                else if (context.granted(Manifest.permission.RECORD_AUDIO)) testing = meter.start()
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (testing) "停止" else "测试") }
        }
        if (testing) {
            Text(
                when {
                    !status.running -> "当前：按键说话未开启，麦克风未受控制，电平跳动是正常的"
                    status.micOpen == true -> "当前：麦克风已恢复（说话中）"
                    status.micOpen == false -> "当前：已静音，此时电平应接近 0"
                    else -> "当前：静音状态未知"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status.running && status.micOpen == false) PttColors.muted else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(10.dp).clip(CircleShape),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ScanDialog(onPick: (FoundButton) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundButton>() }
    var bluetoothOff by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val scanner = ButtonScanner(context)
        val main = ContextCompat.getMainExecutor(context)
        bluetoothOff = !scanner.start { device ->
            main.execute {
                val i = found.indexOfFirst { it.address == device.address }
                if (i >= 0) found[i] = device else found.add(device)
            }
        }
        onDispose { scanner.stop() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("选择按钮") },
        text = {
            Column {
                when {
                    bluetoothOff -> Text("请先打开蓝牙。")
                    found.isEmpty() -> Text("正在搜索… 请给 ESP32 按钮上电并靠近手机。")
                }
                found.forEachIndexed { i, device ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(device) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
