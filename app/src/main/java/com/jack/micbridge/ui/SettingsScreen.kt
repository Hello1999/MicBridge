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
    onCalibrate: () -> Unit, onDiagnostics: () -> Unit, onAdvanced: () -> Unit,
) {
    BridgePage {
        PageIntro("设置", "完成设备准备，让麦克风控制在后台保持可用。")
        SettingsGroup("使用前准备") {
            SettingRow("状态通知", if (runtimePermissionsGranted) "已允许" else "待设置：在通知栏显示麦克风状态", value = if (runtimePermissionsGranted) null else "去设置", onClick = onPermissions)
            SettingDivider()
            SettingRow("测试结束自动屏蔽", if (exactAlarmGranted) "已允许" else "待设置：允许“闹钟和提醒”权限", value = if (exactAlarmGranted) null else "去设置", onClick = onExactAlarm)
            SettingDivider()
            SettingRow("允许持续后台运行", if (snapshot.batteryOptimizationExempt) "已确认" else "待设置：将 MicBridge 设为不受电池优化限制", value = if (snapshot.batteryOptimizationExempt) null else "去设置", onClick = onBattery)
        }
        SettingsGroup("后台运行") {
            SettingRow("麦克风保护服务", if (snapshot.serviceRunning) "运行中" else "已停止", BridgeSymbol.POWER)
            SecondaryAction(if (snapshot.serviceRunning) "屏蔽并停止服务" else "开启麦克风屏蔽", onClick = if (snapshot.serviceRunning) onStop else onStart)
            Spacer(Modifier.height(12.dp)); SettingDivider()
            ToggleSetting("开机自动启动", "设备启动后自动开启麦克风保护", values.autoStart, onCheckedChange = onAutoStart)
            SettingDivider()
            ToggleSetting("保持后台运行", if (snapshot.serviceRunning) "停止服务后可修改" else "减少休眠中断，可能增加耗电", values.reliableMode, !snapshot.serviceRunning, onReliable)
            SettingDivider()
            SettingRow("应用系统设置", "管理自启动、电池和其他系统权限", onClick = onAppSettings)
        }
        SettingsGroup("验证与帮助") {
            SettingRow("设备验证", if (snapshot.acousticCalibrationValid) "已通过屏蔽与恢复测试" else "首次使用前测试实际收音效果", BridgeSymbol.SHIELD, onClick = onCalibrate)
            SettingDivider()
            SettingRow("问题与操作记录", "查看失败原因，重新检查当前状态", BridgeSymbol.INFO, onClick = onDiagnostics)
            SettingDivider()
            SettingRow("高级维护", "控制方案、开机保护和测试时限", BridgeSymbol.CONTROL, onClick = onAdvanced)
        }
    }
}

@Composable
fun AdvancedScreen(
    snapshot: BridgeSnapshot, values: SettingsValues, secondsInput: String,
    onController: (String) -> Unit, onSecondsChange: (String) -> Unit, onSaveSeconds: () -> Unit,
    onMaintenance: () -> Unit, onInstallGuard: () -> Unit, onRemoveGuard: () -> Unit,
) {
    val controllerEditable = !snapshot.serviceRunning && !values.guardInstalled && !values.pendingLegacyState
    val validSeconds = secondsInput.toIntOrNull()?.let { it in SettingsRepository.MIN_OPEN_SECONDS..SettingsRepository.MAX_OPEN_SECONDS } == true
    BridgePage {
        PageIntro("高级维护", "通常无需更改。仅在设备不兼容或排查问题时使用。")
        if (!controllerEditable) Notice("控制方案暂不可更改", "更换前请先停止服务；若已安装开机保护，请先移除保护。", action = "返回设置", onAction = onMaintenance)
        SettingsGroup("系统麦克风控制方案") {
            listOf(
                Triple(SettingsRepository.CONTROLLER_AUDIO_MANAGER, "标准方案", "系统静音与 Root 保护协同控制，建议使用"),
                Triple(SettingsRepository.CONTROLLER_SENSOR_PRIVACY, "兼容方案", "直接使用系统麦克风隐私开关，标准方案不适用时选择"),
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
        SettingsGroup("Root 开机保护") {
            SettingRow(if (values.guardInstalled) "已安装开机保护" else "未安装开机保护", "在应用启动前尝试屏蔽麦克风，安装后需实际重启验证。", BridgeSymbol.SHIELD)
            SecondaryAction(if (values.guardInstalled) "重新安装开机保护" else "安装开机保护", snapshot.serviceRunning && values.controllerId in setOf(SettingsRepository.CONTROLLER_AUDIO_MANAGER, SettingsRepository.CONTROLLER_SENSOR_PRIVACY), onInstallGuard)
            Spacer(Modifier.height(8.dp))
            TextButton(onRemoveGuard, enabled = values.guardInstalled || values.pendingLegacyState, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除保护并停止服务") }
            Spacer(Modifier.height(6.dp))
        }
        SettingsGroup("测试开放时限") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(secondsInput, onSecondsChange, label = { Text("最长开放秒数") }, enabled = !snapshot.serviceRunning, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validSeconds, shape = MaterialTheme.shapes.small, supportingText = { Text(if (snapshot.serviceRunning) "停止服务后可修改" else "5–30 秒") })
                SecondaryAction("保存时限", !snapshot.serviceRunning && validSeconds, onSaveSeconds)
                Text("仅用于设备验证。日常恢复后会保持开放，直到再次屏蔽；服务异常时会尝试自动屏蔽。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("两种方案均需要 Root，影响这台设备上的麦克风使用。系统对通话等特殊场景可能另有处理，需在实际设备上验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.pendingLegacyState) Notice("需要清理旧版配置", "请使用“移除保护并停止服务”完成迁移。已有的应用麦克风权限会保留。", error = true)
    }
}
