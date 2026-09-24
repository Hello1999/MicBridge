package com.jack.micbridge.mic

import android.content.Context
import android.os.UserManager
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget
import java.util.concurrent.TimeUnit

/** System-wide OPEN is only valid for this app's current foreground Android user. */
class ForegroundUserMicController(
    private val primary: MicController,
    private val targetUserIsForeground: (Int) -> Boolean,
) : MicController {
    override val id: String = primary.id
    override val displayName: String = primary.displayName

    override suspend fun probe(): ProbeResult = primary.probe()
    override suspend fun captureSafetyTarget(): SafetyTarget? = primary.captureSafetyTarget()
    override suspend fun block(target: SafetyTarget?): ControlResult = primary.block(target)

    override suspend fun open(
        target: SafetyTarget,
        authorization: OpenAuthorization,
    ): ControlResult {
        val started = System.nanoTime()
        if (!isCurrentUser(target)) return refuseAndBlock(target, started)
        val opened = primary.open(target, authorization)
        if (!opened.controlReadback || opened.observed != MicAccessState.OPEN) return opened
        if (!isCurrentUser(target)) return refuseAndBlock(target, started)
        return opened
    }

    override suspend fun readState(target: SafetyTarget?): MicAccessState {
        val observed = primary.readState(target)
        if (observed == MicAccessState.BLOCKED) return observed
        val fixedTarget = target ?: primary.captureSafetyTarget() ?: return MicAccessState.UNKNOWN
        return if (isCurrentUser(fixedTarget)) observed else MicAccessState.UNKNOWN
    }

    private fun isCurrentUser(target: SafetyTarget): Boolean =
        target.userId >= 0 && runCatching { targetUserIsForeground(target.userId) }.getOrDefault(false)

    private suspend fun refuseAndBlock(target: SafetyTarget, started: Long): ControlResult {
        val blocked = primary.block(target)
        val verified = blocked.controlReadback && blocked.observed == MicAccessState.BLOCKED
        return ControlResult(
            requested = MicAccessState.OPEN,
            observed = if (verified) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
            controlReadback = false,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            errorCode = "FOREGROUND_USER_CHANGED",
            errorMessage = listOfNotNull(
                "当前 Android 用户无法确认或已切换；已拒绝开放并优先屏蔽",
                blocked.errorMessage,
            ).joinToString("；"),
        )
    }

    companion object {
        fun inProcessForegroundUserCheck(context: Context): (Int) -> Boolean = { userId ->
            userId == android.os.Process.myUid() / 100_000 &&
                runCatching {
                    context.getSystemService(UserManager::class.java).isUserForeground
                }.getOrDefault(false)
        }
    }
}
