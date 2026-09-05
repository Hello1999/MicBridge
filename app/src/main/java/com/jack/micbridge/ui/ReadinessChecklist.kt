package com.jack.micbridge.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.SettingsRepository

internal data class ReadinessItem(
    val label: String,
    /** null = unknown (service not initialized yet), true = pass, false = fail. */
    val ok: Boolean?,
    val hint: String? = null,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/** One line per admission gate. Every gate here is a reason a remote OPEN can be refused. */
internal fun readinessItems(
    snapshot: BridgeSnapshot,
    settings: SettingsRepository,
    actions: ReadinessActions,
): List<ReadinessItem> {
    val running = snapshot.serviceRunning
    val r = snapshot.readiness
    return buildList {
        add(
            ReadinessItem(
                "前台服务运行中",
                running,
                hint = if (running) null else "未运行时不监听任何端口",
                actionLabel = if (running) null else "启动服务",
                action = if (running) null else actions.startService,
            ),
        )
        add(
            ReadinessItem(
                "Root 已授权",
                if (running) r.rootAvailable else null,
                hint = "两个发布控制器都需要 Root；在 Root 管理器中允许 MicBridge",
            ),
        )
        add(
            ReadinessItem(
                "通知权限与状态通道可见",
                if (running) r.notificationPermitted else null,
                hint = "常驻通知是安全状态的可见边界；被关闭时拒绝开放",
                actionLabel = "应用设置",
                action = actions.openAppDetails,
            ),
        )
        add(
            ReadinessItem(
                "电池优化豁免",
                if (running) snapshot.batteryOptimizationExempt else null,
                hint = "未豁免时 Doze 可能暂停网络，远程开放被禁用；厂商后台管理也需设为不受限制",
                actionLabel = "电池设置",
                action = actions.openBatterySettings,
            ),
        )
        add(
            ReadinessItem(
                "精确闹钟权限",
                r.exactAlarmPermitted,
                hint = "校准/诊断开放依赖双精确安全闹钟",
                actionLabel = "闹钟设置",
                action = actions.openExactAlarmSettings,
            ),
        )
        if (Build.VERSION.SDK_INT >= 37) {
            add(
                ReadinessItem(
                    "本地网络权限",
                    r.localNetworkPermitted,
                    actionLabel = "申请权限",
                    action = actions.requestPermissions,
                ),
            )
        }
        if (settings.reliableMode) {
            add(
                ReadinessItem(
                    "可靠模式 WakeLock 已持有",
                    if (running) r.reliableModeReady else null,
                    hint = "服务启动后自动取得；未就绪时不开放 HTTP",
                ),
            )
        }
        add(
            ReadinessItem(
                "MicBridge 所属用户在前台",
                if (running) r.userForeground else null,
                hint = "工作资料或副用户不在前台时拒绝开放",
            ),
        )
        add(
            ReadinessItem(
                "已绑定局域网监听地址",
                if (running) snapshot.serverAddresses.isNotEmpty() else null,
                hint = if (Build.VERSION.SDK_INT >= 36) {
                    "需要可信 Wi‑Fi，或本机热点且系统 TetheringManager 回调已注册"
                } else {
                    "本系统版本只支持两台设备加入同一可信 Wi‑Fi"
                },
                actionLabel = if (running) "重扫网络" else null,
                action = if (running) actions.rescanNetwork else null,
            ),
        )
        add(
            ReadinessItem(
                "Root 开机保护已部署",
                settings.rootBootGuardInstalled,
                hint = "重启期间保持屏蔽；未部署时每次开放仍会先部署，首次开放更慢",
                actionLabel = if (!settings.rootBootGuardInstalled && running) "安装" else null,
                action = if (!settings.rootBootGuardInstalled && running) actions.installGuard else null,
            ),
        )
        add(
            ReadinessItem(
                "声学校准有效",
                snapshot.calibrationInvalidReason == null,
                hint = snapshot.calibrationInvalidReason?.let { "$it；在下方“声学校准”重新完成 4 步" },
            ),
        )
    }
}

internal class ReadinessActions(
    val startService: () -> Unit,
    val openAppDetails: () -> Unit,
    val openBatterySettings: () -> Unit,
    val openExactAlarmSettings: () -> Unit,
    val requestPermissions: () -> Unit,
    val rescanNetwork: () -> Unit,
    val installGuard: () -> Unit,
)

@Composable
internal fun ReadinessChecklist(items: List<ReadinessItem>) {
    val failing = items.count { it.ok == false }
    Section(
        title = "就绪检查",
        subtitle = if (failing == 0) "全部通过" else "$failing 项未通过；任一项未通过都会让快捷指令收到失败",
    ) {
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val (glyph, color) = when (item.ok) {
                        true -> "✓" to Tone.Good
                        false -> "✗" to Tone.Bad
                        null -> "…" to Tone.Muted
                    }
                    Text(glyph, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(item.label, modifier = Modifier.weight(1f))
                    if (item.action != null && item.actionLabel != null && item.ok != true) {
                        TextButton(onClick = item.action) { Text(item.actionLabel) }
                    }
                }
                if (item.ok != true && item.hint != null) {
                    Text(
                        item.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Muted,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
        }
    }
}
