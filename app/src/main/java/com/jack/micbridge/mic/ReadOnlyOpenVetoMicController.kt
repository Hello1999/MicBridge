package com.jack.micbridge.mic

import android.content.Context
import android.os.UserManager
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.data.SettingsRepository
import java.util.concurrent.TimeUnit

/** A read-only condition that may veto OPEN but can never mutate its underlying subsystem. */
fun interface ReadOnlyOpenVeto {
    suspend fun readState(target: SafetyTarget): MicAccessState
}

/**
 * Adds a strict read-only OPEN veto around a controller.
 *
 * BLOCK is always delegated to [primary]; in particular, this wrapper never turns AppOps into
 * an implicit write fallback. A veto is checked both before and after the primary mutation, and
 * a changed/unknown post-read is rolled back through the selected controller.
 */
class ReadOnlyOpenVetoMicController(
    private val primary: MicController,
    private val vetoName: String,
    private val veto: ReadOnlyOpenVeto,
) : MicController {
    override val id: String = primary.id
    override val displayName: String = "${primary.displayName} + $vetoName 只读否决"

    override suspend fun probe(): ProbeResult {
        val primaryProbe = primary.probe()
        val target = primary.captureSafetyTarget()
        val vetoState = target?.let { veto.readState(it) } ?: MicAccessState.UNKNOWN
        return ProbeResult(
            available = primaryProbe.available && vetoState != MicAccessState.UNKNOWN,
            stateReadable = primaryProbe.stateReadable && vetoState != MicAccessState.UNKNOWN,
            worksWhileLocked = primaryProbe.worksWhileLocked,
            notes = listOfNotNull(
                primaryProbe.notes,
                when (vetoState) {
                    MicAccessState.OPEN -> "$vetoName 未发现显式拒绝"
                    MicAccessState.BLOCKED -> "$vetoName 检测到显式拒绝"
                    MicAccessState.UNKNOWN -> "$vetoName 无法读取"
                },
            ).joinToString("；"),
        )
    }

    override suspend fun captureSafetyTarget(): SafetyTarget? = primary.captureSafetyTarget()

    override suspend fun block(target: SafetyTarget?): ControlResult = primary.block(target)

    override suspend fun open(
        target: SafetyTarget,
        authorization: OpenAuthorization,
    ): ControlResult {
        val started = System.nanoTime()
        val before = veto.readState(target)
        if (before != MicAccessState.OPEN) {
            return vetoFailureAfterBlocking(before, target, started)
        }

        val primaryResult = primary.open(target, authorization)
        if (!primaryResult.controlReadback || primaryResult.observed != MicAccessState.OPEN) {
            return primaryResult.copy(durationMs = elapsedMs(started))
        }

        val after = veto.readState(target)
        if (after == MicAccessState.OPEN) {
            return primaryResult.copy(durationMs = elapsedMs(started))
        }

        val rollback = primary.block(target)
        val safeObserved = if (
            rollback.controlReadback && rollback.observed == MicAccessState.BLOCKED
        ) MicAccessState.BLOCKED else MicAccessState.UNKNOWN
        return ControlResult(
            requested = MicAccessState.OPEN,
            observed = safeObserved,
            controlReadback = false,
            durationMs = elapsedMs(started),
            errorCode = if (after == MicAccessState.BLOCKED) {
                "APPOPS_EXPLICIT_VETO"
            } else {
                "APPOPS_VETO_UNKNOWN"
            },
            errorMessage = if (after == MicAccessState.BLOCKED) {
                "ChatGPT RECORD_AUDIO AppOps 出现显式拒绝；已回滚所选控制器 OPEN"
            } else {
                "ChatGPT RECORD_AUDIO AppOps 读回无法确认；已回滚所选控制器 OPEN"
            },
        )
    }

    override suspend fun readState(target: SafetyTarget?): MicAccessState {
        val primaryState = primary.readState(target)
        if (primaryState == MicAccessState.BLOCKED) return MicAccessState.BLOCKED
        val fixedTarget = target ?: primary.captureSafetyTarget() ?: return MicAccessState.UNKNOWN
        return when (val vetoState = veto.readState(fixedTarget)) {
            // AppOps can deny one package, but it cannot prove that the two system-wide writable
            // gates are BLOCKED. Expose the disagreement instead of overstating system state.
            MicAccessState.BLOCKED -> MicAccessState.UNKNOWN
            MicAccessState.OPEN -> primaryState
            MicAccessState.UNKNOWN -> MicAccessState.UNKNOWN
        }
    }

    private suspend fun vetoFailureAfterBlocking(
        state: MicAccessState,
        target: SafetyTarget,
        startedNanos: Long,
    ): ControlResult {
        val rollback = primary.block(target)
        val blocked = rollback.controlReadback && rollback.observed == MicAccessState.BLOCKED
        val reason = if (state == MicAccessState.BLOCKED) {
            "ChatGPT RECORD_AUDIO AppOps 已显式拒绝；未执行 OPEN"
        } else {
            "ChatGPT RECORD_AUDIO AppOps 读回无法确认；未执行 OPEN"
        }
        return ControlResult(
            requested = MicAccessState.OPEN,
            observed = if (blocked) MicAccessState.BLOCKED else MicAccessState.UNKNOWN,
            controlReadback = false,
            durationMs = elapsedMs(startedNanos),
            errorCode = if (state == MicAccessState.BLOCKED) {
                "APPOPS_EXPLICIT_VETO"
            } else {
                "APPOPS_VETO_UNKNOWN"
            },
            errorMessage = listOfNotNull(reason, rollback.errorMessage).joinToString("；"),
        )
    }

    private fun elapsedMs(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
}

/** Strict read-only AppOps veto for the package selected in settings. */
class AppOpsReadOnlyOpenVeto internal constructor(
    private val shell: RootShell,
    private val targetPackageProvider: () -> String,
    private val targetUserIsForeground: (Int) -> Boolean,
) : ReadOnlyOpenVeto {
    constructor(
        shell: RootShell,
        settings: SettingsRepository,
        targetUserIsForeground: (Int) -> Boolean,
    ) : this(shell, { settings.targetPackage }, targetUserIsForeground)

    override suspend fun readState(target: SafetyTarget): MicAccessState {
        if (target.userId < 0) return MicAccessState.UNKNOWN
        // The frozen target user must still be the foreground user. This app can only build
        // targets for its own Android user, so "our user is foreground and equals the target
        // user" is the same decision the former `am get-current-user` probe made, without its
        // ~40-60 ms shell round trip. A target belonging to any other user is not provable in
        // process and therefore fails closed as UNKNOWN, which still vetoes OPEN.
        if (!targetUserIsForeground(target.userId)) return MicAccessState.UNKNOWN
        val packageName = runCatching {
            RootShell.requirePackageName(targetPackageProvider())
        }.getOrNull() ?: return MicAccessState.UNKNOWN
        val result = shell.execute(
            "exec cmd appops get --user ${target.userId} " +
                "${RootShell.quote(packageName)} RECORD_AUDIO",
            timeoutMs = VETO_READ_TIMEOUT_MS,
        )
        if (!result.succeeded) return MicAccessState.UNKNOWN
        return modesToVetoState(AppOpsModeReader.parseModes(result.stdout))
    }

    companion object {
        private const val VETO_READ_TIMEOUT_MS = 1_000L
        private const val PER_USER_RANGE = 100_000
        private val EXPLICIT_VETO_MODES = setOf(
            AppOpsModeReader.MODE_IGNORE,
            AppOpsModeReader.MODE_DENY,
            AppOpsModeReader.MODE_ERRORED,
        )
        private val NON_VETO_MODES = setOf(
            AppOpsModeReader.MODE_ALLOW,
            AppOpsModeReader.MODE_DEFAULT,
            AppOpsModeReader.MODE_FOREGROUND,
        )

        /**
         * In-process equivalent of the removed `am get-current-user` guard: the target user must
         * be this app's own user, and this app's user must currently be the foreground user.
         * Any read failure resolves to `false`, i.e. an UNKNOWN veto read.
         */
        fun inProcessForegroundUserCheck(context: Context): (Int) -> Boolean = { userId ->
            userId == android.os.Process.myUid() / PER_USER_RANGE &&
                runCatching {
                    context.getSystemService(UserManager::class.java).isUserForeground
                }.getOrDefault(false)
        }

        fun modesToVetoState(
            modes: AppOpsModeReader.Companion.ParsedModes?,
        ): MicAccessState {
            // A non-default UID mode overrides package scope. `foreground` is conditional on the
            // target UID's live process state, but it is not an explicit AppOps denial. This layer
            // only detects hard vetoes; it does not claim that ChatGPT can currently record.
            return when (modes?.effectiveMode) {
                in EXPLICIT_VETO_MODES -> MicAccessState.BLOCKED
                in NON_VETO_MODES -> MicAccessState.OPEN
                else -> MicAccessState.UNKNOWN
            }
        }
    }
}
