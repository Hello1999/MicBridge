package com.jack.micbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.AuditEntry
import com.jack.micbridge.data.BridgeSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AuditTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun DiagnosticsScreen(snapshot: BridgeSnapshot, records: List<AuditEntry>?, onRefresh: () -> Unit) {
    BridgePage {
        PageIntro("诊断与记录", "查看控制读回和最近操作，定位服务问题。")
        SettingsGroup("当前状态") {
            DetailText("控制读回", if (snapshot.controlReadback) "已确认" else "未确认")
            SettingDivider(); DetailText("声学校准", if (snapshot.acousticCalibrationValid) "有效" else "未完成或已失效")
            SettingDivider(); DetailText("控制器", snapshot.controllerId)
            SettingDivider(); DetailText("自动探测", snapshot.controllerProbe ?: "尚未完成")
            SettingDivider(); DetailText("安全闹钟 / Root 监督器", "${snapshot.leaseExactAlarmArmed.asReadiness()} / ${snapshot.leaseRootWatchdogArmed.asReadiness()}")
            SettingDivider(); DetailText("最近耗时", snapshot.lastLatencyMs?.let { "$it ms" } ?: "暂无操作")
            SettingDivider(); DetailText("服务地址", snapshot.serverAddresses.joinToString("\n").ifEmpty { "无可用监听地址" })
        }
        SettingsGroup("最近错误") {
            SelectionContainer { DetailText("完整诊断信息", snapshot.lastError ?: "无") }
        }
        SecondaryAction("先屏蔽并重新检查", snapshot.serviceRunning, onRefresh)
        SettingsGroup("最近操作") {
            if (records == null) DetailText("审计日志", "读取中…")
            else if (records.isEmpty()) DetailText("暂无记录", "操作发生后会显示在这里，不包含令牌或音频。")
            else records.forEachIndexed { index, entry ->
                if (index > 0) SettingDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entry.result}  ·  ${entry.latencyMs} ms", style = MaterialTheme.typography.titleSmall)
                    Text("${AuditTime.format(Instant.ofEpochMilli(entry.epochMs))}  ${entry.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("验证：${if (entry.verified) "通过" else "未通过"}；控制器：${entry.controller}", style = MaterialTheme.typography.bodySmall)
                    entry.errorCode?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    entry.diagnostic?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    entry.requestIdHash?.let { Text("请求摘要 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun Boolean?.asReadiness() = when (this) { true -> "已布防"; false -> "未布防"; null -> "无活动租约" }
