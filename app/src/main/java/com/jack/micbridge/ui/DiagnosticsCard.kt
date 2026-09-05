package com.jack.micbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.AuditEntry
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class LatencySummary(val samples: Int, val p50Ms: Long, val maxMs: Long)

/** Server-side latency of recent successful toggles; never a substitute for end-to-end timing. */
internal fun toggleLatencySummary(entries: List<AuditEntry>): LatencySummary? {
    val samples = entries
        .filter { it.verified && it.source in TOGGLE_SOURCES && it.errorCode == null }
        .map { it.latencyMs }
        .sorted()
    if (samples.isEmpty()) return null
    return LatencySummary(
        samples = samples.size,
        p50Ms = samples[(samples.size - 1) / 2],
        maxMs = samples.last(),
    )
}

private val TOGGLE_SOURCES = setOf("/v1/mic/toggle", "local-ui")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
internal fun DiagnosticsCard(
    snapshot: BridgeSnapshot,
    recentAudit: List<AuditEntry>,
    onRescan: () -> Unit,
) {
    CollapsibleSection(
        title = "诊断",
        subtitle = "控制器探测、租约保护、延迟与最近审计记录",
        key = "diagnostics",
    ) {
        KeyValue("控制器", snapshot.controllerId)
        KeyValue("自动探测", snapshot.controllerProbe ?: "尚未完成")
        KeyValue("控制读回", if (snapshot.controlReadback) "已确认" else "未确认")
        KeyValue("声学校准", snapshot.calibrationInvalidReason ?: "有效")
        KeyValue("精确安全闹钟", snapshot.leaseExactAlarmArmed?.toString() ?: "无活动租约")
        KeyValue("Root 租约看门狗", snapshot.leaseRootWatchdogArmed?.toString() ?: "无活动租约")
        KeyValue("监听地址", snapshot.serverAddresses.joinToString().ifBlank { "无" })
        KeyValue("最近操作耗时", snapshot.lastLatencyMs?.let { "$it ms" } ?: "无")
        KeyValue("最近 Root 往返", snapshot.rootRoundTripsLastOperation?.toString() ?: "无")
        toggleLatencySummary(recentAudit)?.let {
            KeyValue("toggle 服务端延迟", "p50 ${it.p50Ms} ms · 最大 ${it.maxMs} ms（${it.samples} 次成功样本）")
        }
        KeyValue("最近错误", snapshot.lastError ?: "无")
        OutlinedButton(onClick = onRescan, enabled = snapshot.serviceRunning) {
            Text("先屏蔽并安全重扫网络/状态")
        }
        Text("最近审计记录（不含令牌/音频）", fontWeight = FontWeight.Bold)
        if (recentAudit.isEmpty()) {
            Text("暂无", style = MaterialTheme.typography.bodySmall)
        } else {
            recentAudit.forEach { AuditRow(it) }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    val color = when {
        entry.errorCode != null -> Tone.Bad
        entry.verified -> Tone.Good
        else -> Tone.Muted
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${TIME_FORMAT.format(Instant.ofEpochMilli(entry.epochMs).atZone(ZoneId.systemDefault()))} · " +
                    "${entry.source} · ${stateLabel(entry.result)}",
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                "${if (entry.verified) "已验证" else "未验证"} · ${entry.latencyMs} ms · ${entry.controller}" +
                    (entry.requestIdHash?.let { " · id=$it" } ?: "") +
                    (entry.errorCode?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            entry.diagnostic?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
        }
    }
}

private fun stateLabel(state: MicAccessState): String = when (state) {
    MicAccessState.BLOCKED -> "已静音"
    MicAccessState.OPEN -> "已开放"
    MicAccessState.UNKNOWN -> "未知"
}
