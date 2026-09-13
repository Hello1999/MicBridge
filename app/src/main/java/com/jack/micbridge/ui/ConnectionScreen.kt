package com.jack.micbridge.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot

@Composable
fun ConnectionScreen(
    snapshot: BridgeSnapshot, token: String, portInput: String, onPortChange: (String) -> Unit,
    onSavePort: () -> Unit, onCopy: (String, String, Boolean) -> Unit, onRotate: () -> Unit,
    onSettings: () -> Unit,
) {
    var tokenVisible by remember(token) { mutableStateOf(false) }
    val validPort = portInput.toIntOrNull()?.let { it in 1024..65535 } == true
    BridgePage {
        PageIntro("连接 iPhone", "将两台手机接入同一个可信 Wi-Fi，再配置快捷指令。")
        if (snapshot.serverAddresses.isEmpty()) {
            Notice("等待局域网服务", "服务完成初始化并连接可监控的私网后，请求地址会显示在这里。", action = "查看服务设置", onAction = onSettings)
        }
        SettingsGroup("请求地址") {
            if (snapshot.serverAddresses.isEmpty()) {
                DetailText("当前地址", "尚不可用")
            } else {
                snapshot.serverAddresses.forEachIndexed { index, address ->
                    if (index > 0) SettingDivider()
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("POST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        val url = "http://$address/v1/mic/toggle"
                        SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
                        TextButton(onClick = { onCopy("MicBridge Toggle URL", url, false) }) {
                            BridgeIcon(BridgeSymbol.COPY, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("复制请求地址")
                        }
                    }
                }
            }
        }
        SettingsGroup("访问令牌") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    token, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bearer token") },
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { tokenVisible = !tokenVisible }) { Text(if (tokenVisible) "隐藏" else "显示") } },
                    shape = MaterialTheme.shapes.small,
                )
                SecondaryAction("复制令牌") { onCopy("MicBridge Token", token, true) }
                Text("令牌用于验证快捷指令请求，请勿分享。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Notice("一次按下，一次请求", "每次运行仅发送一次 POST，并检查返回的请求 ID、成功状态、验证状态和麦克风状态。本地监听正常不代表 iPhone 已连通。")
        SettingsGroup("连接设置") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    portInput, onPortChange, label = { Text("服务端口") }, enabled = !snapshot.serviceRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = !validPort, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                    supportingText = { Text(if (snapshot.serviceRunning) "需先屏蔽并停止服务，再修改端口" else "有效范围 1024–65535") },
                )
                SecondaryAction("保存端口", !snapshot.serviceRunning && validPort, onSavePort)
                TextButton(onRotate, enabled = !snapshot.serviceRunning) { Text("重新生成令牌") }
                Text("更换令牌会使旧快捷指令立即失效；需先停止服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            if (Build.VERSION.SDK_INT >= 36) "使用可信 Wi-Fi，或系统能完整监控的 Android 私人热点。明文 HTTP 不适合不可信网络。"
            else "本系统版本支持两台手机接入同一可信 Wi-Fi，不支持由这台 Android 创建热点。明文 HTTP 不适合不可信网络。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
