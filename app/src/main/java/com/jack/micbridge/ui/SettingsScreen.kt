package com.jack.micbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.SettingsRepository

data class SettingsValues(
    val autoStart: Boolean, val reliableMode: Boolean, val guardInstalled: Boolean,
    val pendingLegacyState: Boolean, val controllerId: String,
)

@Composable
fun SettingsScreen(
    snapshot: BridgeSnapshot, values: SettingsValues, runtimePermissionsGranted: Boolean, exactAlarmGranted: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onAutoStart: (Boolean) -> Unit, onReliable: (Boolean) -> Unit,
    onPermissions: () -> Unit, onExactAlarm: () -> Unit, onBattery: () -> Unit, onAppSettings: () -> Unit,
    onInstallGuard: () -> Unit, onRemoveGuard: () -> Unit, onCalibrate: () -> Unit, onDiagnostics: () -> Unit, onAdvanced: () -> Unit,
) {
    BridgePage {
        PageIntro("设置", "配置后台运行，管理设备校准与保护。")
        SettingsGroup("后台运行") {
            SettingRow("桥接服务", if (snapshot.serviceRunning) "运行中；停止前会先确认屏蔽" else "已停止", BridgeSymbol.POWER)
            SecondaryAction(if (snapshot.serviceRunning) "屏蔽后停止服务" else "启动服务", onClick = if (snapshot.serviceRunning) onStop else onStart)
            Spacer(Modifier.height(12.dp)); SettingDivider()
            ToggleSetting("开机自动启动", "设备启动后恢复桥接服务", values.autoStart, onCheckedChange = onAutoStart)
            SettingDivider()
            ToggleSetting("保持后台可靠运行", if (snapshot.serviceRunning) "需先停止服务，再修改此项" else "保持 CPU 唤醒，提高后台运行可靠性", values.reliableMode, !snapshot.serviceRunning, onReliable)
        }
        SettingsGroup("运行权限") {
            SettingRow("通知与运行权限", if (runtimePermissionsGranted) "已允许" else "尚未完整授予", onClick = onPermissions)
            SettingDivider()
            SettingRow("精确安全闹钟", if (exactAlarmGranted) "已允许" else "需允许，用于临时开放到期屏蔽", onClick = onExactAlarm)
            SettingDivider()
            SettingRow("电池优化豁免", if (snapshot.batteryOptimizationExempt) "已确认" else "尚未确认，远程开放不可用", onClick = onBattery)
            SettingDivider()
            SettingRow("系统后台设置", "在厂商设置中允许后台运行；ChatGPT 需启用后台对话", onClick = onAppSettings)
        }
        SettingsGroup("Root 开机保护") {
            SettingRow(if (values.guardInstalled) "保护脚本已部署" else "尚未部署保护脚本", "实际重启后的屏蔽效果需真机确认", BridgeSymbol.SHIELD)
            SecondaryAction(if (values.guardInstalled) "刷新开机保护" else "安装开机保护", snapshot.serviceRunning && values.controllerId in setOf(SettingsRepository.CONTROLLER_AUDIO_MANAGER, SettingsRepository.CONTROLLER_SENSOR_PRIVACY), onInstallGuard)
            Spacer(Modifier.height(8.dp))
            TextButton(onRemoveGuard, enabled = values.guardInstalled || values.pendingLegacyState, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("安全移除保护并停止") }
            Spacer(Modifier.height(6.dp))
        }
        SettingsGroup("校准与维护") {
            SettingRow("声学校准", if (snapshot.acousticCalibrationValid) "当前环境有效" else "验证实际屏蔽与恢复收音", BridgeSymbol.MIC, onClick = onCalibrate)
            SettingDivider()
            SettingRow("诊断与记录", "控制器读回、错误与最近操作", BridgeSymbol.INFO, onClick = onDiagnostics)
            SettingDivider()
            SettingRow("高级控制", "控制方案、目标应用、临时开放时限", BridgeSymbol.CONTROL, onClick = onAdvanced)
        }
    }
}

@Composable
fun AdvancedScreen(
    snapshot: BridgeSnapshot, values: SettingsValues, packageInput: String, secondsInput: String,
    onController: (String) -> Unit, onPackageChange: (String) -> Unit, onSavePackage: () -> Unit,
    onSecondsChange: (String) -> Unit, onSaveSeconds: () -> Unit, onMaintenance: () -> Unit,
) {
    val controllerEditable = !snapshot.serviceRunning && !values.guardInstalled && !values.pendingLegacyState
    val validPackage = PackagePattern.matches(packageInput)
    val validSeconds = secondsInput.toIntOrNull()?.let { it in SettingsRepository.MIN_OPEN_SECONDS..SettingsRepository.MAX_OPEN_SECONDS } == true
    BridgePage {
        if (!controllerEditable) Notice("控制设置已锁定", "更换控制方案或目标应用前，需完整屏蔽、移除开机保护并完成安全退出。", action = "前往安全维护", onAction = onMaintenance)
        Notice("影响系统麦克风访问", "系统级麦克风控制也会影响电话、相机和其他应用。紧急呼叫行为不在保证范围内。")
        SettingsGroup("控制方案") {
            listOf(
                Triple(SettingsRepository.CONTROLLER_AUDIO_MANAGER, "标准方案", "首选 · AudioManager 主控，需要 Root"),
                Triple(SettingsRepository.CONTROLLER_SENSOR_PRIVACY, "Root 方案", "标准方案真机测试失败后，再手动选择"),
            ).forEachIndexed { index, (id, title, description) ->
                if (index > 0) SettingDivider()
                Row(Modifier.fillMaxWidth().selectable(values.controllerId == id, enabled = controllerEditable, role = Role.RadioButton, onClick = { onController(id) }).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(values.controllerId == id, null, enabled = controllerEditable)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SettingsGroup("目标应用") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(packageInput, onPackageChange, label = { Text("ChatGPT Android 包名") }, enabled = controllerEditable, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), isError = !validPackage, shape = MaterialTheme.shapes.small, supportingText = { Text(if (validPackage) "保存后需要重新校准" else "请输入完整的应用包名") })
                SecondaryAction("保存目标应用", controllerEditable && validPackage, onSavePackage)
            }
        }
        SettingsGroup("校准临时开放时限") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(secondsInput, onSecondsChange, label = { Text("最长开放秒数") }, enabled = !snapshot.serviceRunning, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validSeconds, shape = MaterialTheme.shapes.small, supportingText = { Text(if (snapshot.serviceRunning) "需先停止服务，再修改时限" else "5–30 秒") })
                SecondaryAction("保存时限", !snapshot.serviceRunning && validSeconds, onSaveSeconds)
                Text("仅用于声学校准和诊断开放。iPhone 快捷指令采用持续开放，再次按下才会屏蔽。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Notice("控制与验证机制", "两个方案都包含系统门控与 ChatGPT AppOps 的只读检查，运行时不会自动更换方案。开放还需满足精确闹钟、Root 监督器等服务端条件。")
        if (values.pendingLegacyState) Notice("检测到旧版遗留配置", "安全维护会先确认全局屏蔽，保留当前 AppOps 策略并清理旧元数据，不会自动放宽外部权限。", error = true)
    }
}

val PackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
