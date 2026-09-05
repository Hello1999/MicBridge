package com.jack.micbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState
import kotlinx.coroutines.delay

/** The one line the user needs: what the microphone is doing and whether the Shortcut will work. */
internal data class HeroModel(
    val title: String,
    val color: Color,
    val shortcutLine: String,
    val shortcutOk: Boolean,
    val detail: String?,
)

internal fun heroModel(snapshot: BridgeSnapshot): HeroModel {
    val (title, color) = when {
        snapshot.transitioning -> "切换中…" to Tone.Warn
        snapshot.calibrationIsolationActive -> "校准中：仅 Root 屏蔽" to Tone.Warn
        !snapshot.controlReadback || snapshot.micAccess == MicAccessState.UNKNOWN ->
            "状态无法确认" to Tone.Bad
        snapshot.micAccess == MicAccessState.BLOCKED -> "已静音" to Tone.Good
        else -> "已开放（ChatGPT 可收音）" to Tone.Warn
    }
    val shortcut = shortcutAvailability(snapshot)
    return HeroModel(
        title = title,
        color = color,
        shortcutLine = shortcut.first,
        shortcutOk = shortcut.second,
        detail = snapshot.lastError,
    )
}

/**
 * Explains, before the user presses the Action Button, why a toggle would currently be refused.
 * The order mirrors the service's admission checks so the first failing gate is the one shown.
 */
internal fun shortcutAvailability(snapshot: BridgeSnapshot): Pair<String, Boolean> = when {
    !snapshot.serviceRunning -> "快捷指令会失败：服务未运行" to false
    snapshot.serverAddresses.isEmpty() ->
        "快捷指令会失败：未监听局域网（等待可信 Wi‑Fi 或热点）" to false
    snapshot.calibrationInvalidReason != null ->
        "快捷指令会失败（3 次振动）：${snapshot.calibrationInvalidReason}，需要重新校准" to false
    !snapshot.batteryOptimizationExempt -> "快捷指令会失败：未获电池优化豁免，远程开放已禁用" to false
    snapshot.readiness.exactAlarmPermitted == false ->
        "校准与诊断开放会失败：缺少精确闹钟权限" to false
    else -> "快捷指令可用：http://${snapshot.serverAddresses.first()}/v1/mic/toggle" to true
}

@Composable
internal fun StatusHero(snapshot: BridgeSnapshot) {
    var nowEpochMs by remember(snapshot.autoBlockAtEpochMs) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(snapshot.autoBlockAtEpochMs) {
        val deadline = snapshot.autoBlockAtEpochMs ?: return@LaunchedEffect
        while (nowEpochMs < deadline) {
            delay(1_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val model = heroModel(snapshot)
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(model.color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    model.title,
                    color = model.color,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    model.shortcutLine,
                    color = if (model.shortcutOk) Tone.Good else Tone.Bad,
                    fontWeight = FontWeight.Medium,
                )
                if (snapshot.micAccess == MicAccessState.OPEN && snapshot.autoBlockAtEpochMs == null &&
                    !snapshot.transitioning
                ) {
                    Text("持续开放：再次按 Action Button 或点“立即静音”才会静音", style = MaterialTheme.typography.bodySmall)
                }
                snapshot.autoBlockAtEpochMs?.let { deadline ->
                    Text(
                        "校准临时开放，剩余 ${((deadline - nowEpochMs + 999L) / 1_000L).coerceAtLeast(0L)} 秒后自动静音",
                        fontWeight = FontWeight.Bold,
                    )
                }
                model.detail?.let { Text("提示：$it", color = Tone.Bad, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
