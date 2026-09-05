package com.jack.micbridge.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot

/** Everything the iPhone Shortcut needs, in the order the setup guide asks for it. */
@Composable
internal fun ShortcutConfigCard(
    snapshot: BridgeSnapshot,
    token: String,
    port: Int,
    onMessage: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tokenVisible by remember { mutableStateOf(false) }
    val url = snapshot.serverAddresses.firstOrNull()?.let { "http://$it/v1/mic/toggle" }
    Section(
        title = "iPhone 快捷指令配置",
        subtitle = "快捷指令只发送一次 POST，请求头 X-MicBridge-Token 与 X-Request-Id；详见 docs/IPHONE_SHORTCUT_ZH.md",
    ) {
        Text("Toggle URL", fontWeight = FontWeight.Bold)
        Text(url ?: "服务启动并连接可信局域网后显示（端口 $port）")
        if (snapshot.serverAddresses.size > 1) {
            Text(
                "其他可用地址：${snapshot.serverAddresses.drop(1).joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Muted,
            )
        }
        Text("令牌", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = token,
            onValueChange = {},
            readOnly = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tokenVisible = !tokenVisible }) {
                Text(if (tokenVisible) "遮罩" else "显示")
            }
            OutlinedButton(
                onClick = {
                    copyText(context, "MicBridge Toggle URL", url ?: return@OutlinedButton)
                    onMessage("URL 已复制")
                },
                enabled = url != null,
            ) { Text("复制 URL") }
            OutlinedButton(onClick = {
                copyText(context, "MicBridge Token", token, sensitive = true)
                onMessage("令牌已复制（剪贴板标记为敏感）")
            }) { Text("复制令牌") }
        }
        Button(
            onClick = {
                copyText(
                    context,
                    "MicBridge Shortcut",
                    "URL: ${url ?: "-"}\nX-MicBridge-Token: $token",
                    sensitive = true,
                )
                onMessage("URL 与令牌已一起复制；粘贴到快捷指令后请清空剪贴板")
            },
            enabled = url != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("复制 URL + 令牌") }
        Text(
            "令牌是明文 HTTP 凭据，只在可信同一 Wi‑Fi 或本机热点使用；不要截图或上传快捷指令到公共链接。" +
                "修改 IP、端口或令牌后，先在 iPhone 解锁状态手动运行一次快捷指令以处理主机授权。",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
    }
}

internal fun copyText(context: Context, label: String, value: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
