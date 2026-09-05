package com.jack.micbridge.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.SettingsRepository

internal class AdvancedActions(
    val installGuard: () -> Unit,
    val removeGuardAndStop: () -> Unit,
    val openExactAlarmSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val openAppDetails: () -> Unit,
)

/** One-time configuration. Collapsed by default so daily use never scrolls past it. */
@Composable
internal fun AdvancedSettings(
    snapshot: BridgeSnapshot,
    settings: SettingsRepository,
    refreshKey: Int,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit,
    actions: AdvancedActions,
) {
    val running = snapshot.serviceRunning
    val locked = running || settings.rootBootGuardInstalled || settings.hasPendingAppOpsState
    var packageInput by remember(refreshKey) { mutableStateOf(settings.targetPackage) }
    var portInput by remember(refreshKey) { mutableStateOf(settings.port.toString()) }
    var secondsInput by remember(refreshKey) { mutableStateOf(settings.maxOpenSeconds.toString()) }

    CollapsibleSection(
        title = "高级设置",
        subtitle = "控制器、目标包、端口、令牌、后台策略；更换控制器/目标包需先停止服务",
        key = "advanced",
    ) {
        Text("控制器", fontWeight = FontWeight.Bold)
        Text(
            "系统级麦克风门会同时影响电话、相机、录音和其他应用；本工具不保证覆盖紧急呼叫行为。",
            color = Tone.Bad,
            style = MaterialTheme.typography.bodySmall,
        )
        if (settings.hasPendingAppOpsState) {
            Text(
                "检测到开发版遗留 AppOps 元数据；使用下方“全局屏蔽并移除保护”完成安全清理后才能更换控制器。",
                color = Tone.Bad,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        listOf(
            SettingsRepository.CONTROLLER_AUDIO_MANAGER to
                "AudioManager 主控 + Root sensor_privacy 门 + ChatGPT AppOps 只读否决（默认）",
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY to
                "Root sensor_privacy 主控 + AudioManager 门 + ChatGPT AppOps 只读否决（备选）",
        ).forEach { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.controllerId == id,
                    enabled = !locked,
                    onClick = {
                        runCatching {
                            settings.controllerId = id
                            settings.clearCalibration()
                        }.onSuccess {
                            onMessage("控制器已更换；需要重新校准")
                            onChanged()
                        }.onFailure { onMessage("控制器设置保存失败；保持屏蔽") }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (locked) {
            Text(
                "服务运行、Root 开机保护仍在或存在旧元数据时不可更换控制器/目标包。",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Muted,
            )
        }

        OutlinedTextField(
            value = packageInput,
            onValueChange = { packageInput = it },
            label = { Text("ChatGPT Android 包名") },
            supportingText = { Text("默认 com.openai.chatgpt；以已安装包为准") },
            enabled = !locked,
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
                        onMessage("目标包已保存；需要重新校准")
                        onChanged()
                    }.onFailure { onMessage("目标包保存失败；服务将拒绝开放") }
                } else onMessage("包名格式无效")
            },
            enabled = !locked,
        ) { Text("保存目标包") }

        Text("局域网端口", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = portInput,
                onValueChange = { portInput = it.filter(Char::isDigit) },
                label = { Text("端口") },
                enabled = !running,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val value = portInput.toIntOrNull()
                    if (value != null && value in 1024..65535) {
                        runCatching { settings.port = value }
                            .onSuccess { onMessage("端口已保存"); onChanged() }
                            .onFailure { onMessage("端口保存失败") }
                    } else onMessage("端口必须为 1024–65535")
                },
                enabled = !running,
            ) { Text("保存") }
        }

        Text("校准临时开放时限", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = secondsInput,
                onValueChange = { secondsInput = it.filter(Char::isDigit) },
                label = { Text("秒（5–30）") },
                enabled = !running,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val value = secondsInput.toIntOrNull()
                    if (value != null && value in
                        SettingsRepository.MIN_OPEN_SECONDS..SettingsRepository.MAX_OPEN_SECONDS
                    ) {
                        runCatching { settings.maxOpenSeconds = value }
                            .onSuccess { onMessage("开放上限已保存"); onChanged() }
                            .onFailure { onMessage("开放上限保存失败") }
                    } else onMessage("请输入 5–30")
                },
                enabled = !running,
            ) { Text("保存") }
        }
        Text(
            "只约束声学校准与诊断 /open。Action Button 的 toggle 持续开放，再次按下才静音。",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )

        Text("令牌", fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = {
                runCatching { settings.rotateToken() }
                    .onSuccess { onMessage("令牌已更换，旧令牌立即失效；请更新快捷指令"); onChanged() }
                    .onFailure { onMessage("令牌更换失败；旧令牌仍有效") }
            },
            enabled = !running,
        ) { Text("重新生成令牌（需先停止服务）") }

        Text("后台与开机", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("可靠模式")
                Text(
                    "前台服务持有 Partial WakeLock，1 小时超时、45 分钟续取；增加耗电，关闭需重启服务并重新验证锁屏",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Muted,
                )
            }
            Switch(
                checked = settings.reliableMode,
                enabled = !running,
                onCheckedChange = {
                    runCatching { settings.reliableMode = it }
                        .onSuccess { onChanged() }
                        .onFailure { onMessage("可靠模式保存失败") }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开机自动启动服务", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.autoStart,
                onCheckedChange = {
                    runCatching { settings.autoStart = it }
                        .onSuccess { onChanged() }
                        .onFailure { onMessage("自动启动设置保存失败") }
                },
            )
        }
        Text(
            "Root 开机脚本：${if (settings.rootBootGuardInstalled) "已部署" else "未部署"}。" +
                "两个发布控制器每次开放都会启动 Root watcher；AudioManager 选项的 Root fail-safe 映射为全局 sensor_privacy。",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
        OutlinedButton(
            onClick = actions.installGuard,
            modifier = Modifier.fillMaxWidth(),
            enabled = running,
        ) { Text("安装/刷新 Root 开机保护") }
        OutlinedButton(
            onClick = actions.removeGuardAndStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = settings.rootBootGuardInstalled || settings.hasPendingAppOpsState,
        ) { Text("全局屏蔽、清理旧元数据、移除保护并停止") }

        Text("系统设置入口", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.openExactAlarmSettings) { Text("精确闹钟") }
            OutlinedButton(onClick = actions.openBatterySettings) { Text("电池优化") }
            OutlinedButton(onClick = actions.openAppDetails) { Text("应用详情") }
        }
        Text(
            if (Build.VERSION.SDK_INT >= 36) {
                "热点仅在系统 TetheringManager 回调成功注册并报告下游接口时绑定；否则保持屏蔽且不监听。"
            } else {
                "Android 15/API 35 及以下只支持两台手机加入同一可信 Wi‑Fi，不支持本机热点。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
    }
}

internal val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
