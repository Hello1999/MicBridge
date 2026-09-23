package com.jack.micbridge.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot

@Composable
fun ConnectionScreen(
    snapshot: BridgeSnapshot, token: String, portInput: String, onPortChange: (String) -> Unit,
    onSavePort: () -> Unit, onCopy: (String, String, Boolean) -> Unit, onRotate: () -> Unit,
    onSettings: () -> Unit, remotePermissionGranted: Boolean, onRequestPermission: () -> Unit,
) {
    var tokenVisible by remember(token) { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val validPort = portInput.toIntOrNull()?.let { it in 1024..65535 } == true
    BridgePage {
        PageIntro("用 iPhone 遥控", "可选配置。两台手机连接同一个可信 Wi-Fi，再设置快捷指令。")
        if (!remotePermissionGranted) {
            Notice("允许局域网连接", "iPhone 遥控需要允许本地网络访问。在这台设备上直接操作麦克风不需要此权限。", action = "允许连接", onAction = onRequestPermission)
        } else if (snapshot.serverAddresses.isEmpty()) {
            Notice("尚未准备好连接", if (snapshot.serviceRunning) "还没有可用地址。请检查 Wi-Fi 和使用前准备。" else "先在麦克风页开启保护，再回到这里获取地址。", action = "检查设置", onAction = onSettings)
        } else if (!snapshot.acousticCalibrationValid) {
            Notice("地址已就绪，还需设备验证", "完成屏蔽与恢复测试后，快捷指令才能恢复麦克风。", action = "前往设置", onAction = onSettings)
        }
        SettingsGroup("1. 设置请求地址") {
            if (snapshot.serverAddresses.isEmpty()) {
                DetailText("请求地址", "准备完成后显示")
            } else {
                snapshot.serverAddresses.forEachIndexed { index, address ->
                    if (index > 0) SettingDivider()
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val url = "http://$address/v1/mic/toggle"
                        SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium) }
                        SecondaryAction("复制地址") { onCopy("MicBridge 请求地址", url, false) }
                    }
                }
            }
            Text("在快捷指令中添加“获取 URL 内容”，粘贴地址，将方法设为 POST。", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup("2. 添加访问密钥") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    token, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("访问密钥") },
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { tokenVisible = !tokenVisible }) { Text(if (tokenVisible) "隐藏" else "显示") } },
                    shape = MaterialTheme.shapes.small,
                )
                SecondaryAction("复制密钥") { onCopy("MicBridge 访问密钥", token, true) }
                Text("添加请求头 X-MicBridge-Token，将复制的密钥粘贴为值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup("3. 为每次操作生成编号") {
            DetailText("请求头 X-Request-Id", "每次运行生成一个新编号：格式化当前日期为 yyyyMMddHHmmssSSS，再用连字符连接一个 9 位随机数。把编号保存为变量，作为这个请求头的值。")
            SettingDivider()
            DetailText("运行一次，切换一次", "仅发送一次 POST。返回的 ok 和 verified 都为 true，且 request_id 与本次编号相同，才算成功；mic_access 会显示 open 或 blocked。")
        }
        Text("不要用新编号自动重试请求，以免再次反向切换。首次配置后，请同时核对麦克风页的实际状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup("高级连接设置") {
            SettingRow(if (showAdvanced) "收起连接设置" else "修改端口或更换密钥", if (showAdvanced) null else "通常无需更改", onClick = { showAdvanced = !showAdvanced })
            if (showAdvanced) {
                SettingDivider()
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        portInput, onPortChange, label = { Text("服务端口") }, enabled = !snapshot.serviceRunning,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        isError = !validPort, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                        supportingText = { Text(if (snapshot.serviceRunning) "停止服务后可修改" else "有效范围 1024–65535") },
                    )
                    SecondaryAction("保存端口", !snapshot.serviceRunning && validPort, onSavePort)
                    TextButton(onRotate, enabled = !snapshot.serviceRunning) { Text("更换访问密钥") }
                    Text("更换密钥后，需要同步更新快捷指令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            if (Build.VERSION.SDK_INT >= 36) "请妥善保管访问密钥，仅在可信 Wi-Fi 或受支持的 Android 私人热点中使用。"
            else "请妥善保管访问密钥。本系统版本需使用同一可信 Wi-Fi，不支持这台 Android 创建的热点。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
