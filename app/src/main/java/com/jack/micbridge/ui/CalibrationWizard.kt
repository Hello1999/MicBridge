package com.jack.micbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.MicAccessState

internal enum class CalibrationStep { OPEN, ISOLATE, CONFIRM_BLOCK, COMMIT }

internal class CalibrationChecks(
    val rootFailsafeConfirmed: Boolean,
    val blockedConfirmed: Boolean,
    val openConfirmed: Boolean,
)

internal class CalibrationActions(
    val open: () -> Unit,
    val isolate: () -> Unit,
    val confirmAndBlock: () -> Unit,
    val commit: () -> Unit,
    val setRootFailsafeConfirmed: (Boolean) -> Unit,
    val setBlockedConfirmed: (Boolean) -> Unit,
    val setOpenConfirmed: (Boolean) -> Unit,
)

/**
 * Derives which of the four calibration boundaries the service is currently at from published
 * state only. The step gating below is a UI convenience; the service re-validates every step
 * against its own in-memory session and fresh readbacks, so an out-of-order tap simply fails.
 */
internal fun currentCalibrationStep(snapshot: BridgeSnapshot, checks: CalibrationChecks): CalibrationStep =
    when {
        snapshot.calibrationIsolationActive -> CalibrationStep.CONFIRM_BLOCK
        snapshot.micAccess == MicAccessState.OPEN && snapshot.autoBlockAtEpochMs != null &&
            snapshot.leaseRootWatchdogArmed == true && snapshot.controlReadback -> CalibrationStep.ISOLATE
        snapshot.micAccess == MicAccessState.BLOCKED && snapshot.controlReadback &&
            snapshot.autoBlockAtEpochMs == null && checks.rootFailsafeConfirmed -> CalibrationStep.COMMIT
        else -> CalibrationStep.OPEN
    }

@Composable
internal fun CalibrationWizard(
    snapshot: BridgeSnapshot,
    calibrationValid: Boolean,
    invalidReason: String?,
    checks: CalibrationChecks,
    actions: CalibrationActions,
) {
    val step = currentCalibrationStep(snapshot, checks)
    val running = snapshot.serviceRunning
    CollapsibleSection(
        title = "声学校准",
        subtitle = if (calibrationValid) "当前有效；ChatGPT 或系统更新后需重做" else "未完成：${invalidReason ?: "未校准"}",
        key = "calibration",
        initiallyExpanded = !calibrationValid,
    ) {
        Text(
            "为什么需要：控制层读回不等于 ChatGPT 真的听不到。四步必须在同一次服务会话内按序完成，" +
                "任一步失败、越序或状态变化都会清零本轮证据。先在 ChatGPT 中开启 Voice/Live 会话再开始。",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
        StepRow(
            index = 1,
            title = "临时开放用于测试",
            description = "布防双闹钟与 Root watcher 后临时开放；对 ChatGPT 说一句测试短语，确认能听到。",
            state = stepState(step, CalibrationStep.OPEN),
        ) {
            OutlinedButton(onClick = actions.open, enabled = running && !snapshot.transitioning) {
                Text(if (step == CalibrationStep.OPEN) "开始：临时开放" else "重新开始")
            }
        }
        StepRow(
            index = 2,
            title = "仅用 Root 屏蔽（隔离校验）",
            description = "保持 AudioManager 开放，只用 Root sensor_privacy 阻断；此时 ChatGPT 必须听不到。",
            state = stepState(step, CalibrationStep.ISOLATE),
        ) {
            OutlinedButton(
                onClick = actions.isolate,
                enabled = running && step == CalibrationStep.ISOLATE,
            ) { Text("执行隔离屏蔽") }
        }
        StepRow(
            index = 3,
            title = "确认无收音并立即完整屏蔽",
            description = "在隔离状态存在时勾选并确认；应用重新读回后执行完整屏蔽并清理租约。",
            state = stepState(step, CalibrationStep.CONFIRM_BLOCK),
        ) {
            CheckRow(
                "隔离期间 ChatGPT 确实听不到测试短语",
                checks.rootFailsafeConfirmed,
                actions.setRootFailsafeConfirmed,
            )
            OutlinedButton(
                onClick = actions.confirmAndBlock,
                enabled = running && step == CalibrationStep.CONFIRM_BLOCK && checks.rootFailsafeConfirmed,
            ) { Text("确认并完整屏蔽") }
        }
        StepRow(
            index = 4,
            title = "记录校准通过",
            description = "完整屏蔽读回确认后，勾选两项人工观察并提交。",
            state = stepState(step, CalibrationStep.COMMIT),
        ) {
            CheckRow(
                "屏蔽后 ChatGPT 在锁屏/熄屏下确实听不到测试短语",
                checks.blockedConfirmed,
                actions.setBlockedConfirmed,
            )
            CheckRow(
                "开放后无需重建会话即可恢复，且已连续验证至少 20 次",
                checks.openConfirmed,
                actions.setOpenConfirmed,
            )
            Button(
                onClick = actions.commit,
                enabled = running && step == CalibrationStep.COMMIT &&
                    checks.blockedConfirmed && checks.openConfirmed,
            ) { Text("记录校准通过") }
        }
    }
}

private enum class StepState { DONE, CURRENT, PENDING }

private fun stepState(current: CalibrationStep, own: CalibrationStep): StepState = when {
    own.ordinal < current.ordinal -> StepState.DONE
    own == current -> StepState.CURRENT
    else -> StepState.PENDING
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    description: String,
    state: StepState,
    content: @Composable () -> Unit,
) {
    val color = when (state) {
        StepState.DONE -> Tone.Good
        StepState.CURRENT -> MaterialTheme.colorScheme.primary
        StepState.PENDING -> Tone.Muted
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state) {
                    StepState.DONE -> "✓"
                    StepState.CURRENT -> "▶"
                    StepState.PENDING -> "$index"
                },
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("$index. $title", color = color, fontWeight = FontWeight.Bold)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        if (state != StepState.PENDING) content()
    }
}
