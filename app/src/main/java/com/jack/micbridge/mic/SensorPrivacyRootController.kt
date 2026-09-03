package com.jack.micbridge.mic

import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget
import java.util.concurrent.TimeUnit

class SensorPrivacyRootController(
    private val shell: RootShell,
) : MicController {
    override val id: String = "root_sensor_privacy"
    override val displayName: String = "Root sensor_privacy（系统全局）"

    override suspend fun probe(): ProbeResult {
        if (!shell.hasRoot()) return ProbeResult(false, false, notes = "Root 未授权")
        val state = readState()
        return ProbeResult(
            available = state != MicAccessState.UNKNOWN,
            stateReadable = state != MicAccessState.UNKNOWN,
            notes = "锁屏时可能被系统认证策略拒绝；仅在真机反复校准后使用",
        )
    }

    override suspend fun captureSafetyTarget(): SafetyTarget? {
        val userId = currentUserId() ?: return null
        return SafetyTarget(id, "android-global-microphone", userId)
    }

    override suspend fun block(target: SafetyTarget?): ControlResult {
        val fixedTarget = target ?: captureSafetyTarget()
            ?: return failed(true, System.nanoTime(), "CURRENT_USER_UNKNOWN", "无法读取当前 Android 用户")
        if (!isValidTarget(fixedTarget)) {
            return failed(true, System.nanoTime(), "CONTROL_CONTEXT_MISMATCH", "安全租约目标与控制器不一致")
        }
        return setPrivacy(enabled = true, userId = fixedTarget.userId)
    }

    override suspend fun open(
        target: SafetyTarget,
        authorization: OpenAuthorization,
    ): ControlResult {
        if (!isValidTarget(target) || currentUserId() != target.userId) {
            return failed(false, System.nanoTime(), "CONTROL_CONTEXT_MISMATCH", "安全租约用户与当前用户不一致")
        }
        return setPrivacy(
            enabled = false,
            userId = target.userId,
            authorization = authorization,
        )
    }

    override suspend fun readState(target: SafetyTarget?): MicAccessState {
        val fixedTarget = target ?: captureSafetyTarget() ?: return MicAccessState.UNKNOWN
        if (!isValidTarget(fixedTarget)) return MicAccessState.UNKNOWN
        return readStateForUser(fixedTarget.userId)
    }

    suspend fun readStateForUser(userId: Int): MicAccessState {
        val result = shell.execute("dumpsys sensor_privacy")
        if (!result.succeeded) return MicAccessState.UNKNOWN
        return parseMicrophoneState(result.stdout, userId)
    }

    private suspend fun setPrivacy(
        enabled: Boolean,
        userId: Int,
        authorization: OpenAuthorization? = null,
    ): ControlResult {
        val started = System.nanoTime()
        val rawCommand = if (enabled) {
            """
                set -e
                ${SensorPrivacyRootProtocol.prerequisites()}
                USER_ID=${RootShell.quote(userId.toString())}
                BLOCK_VERIFIED=0
                LAST=unverified
                ${SensorPrivacyRootProtocol.blockAllUsersAttempt()}
                [ "${'$'}BLOCK_VERIFIED" = 1 ]
            """.trimIndent()
        } else {
            "cmd sensor_privacy disable $userId microphone"
        }
        val command = if (enabled) {
            rawCommand
        } else {
            RootOpenAuthorizationGuard.command(rawCommand, authorization ?: return failed(
                false,
                started,
                "OPEN_AUTHORIZATION_MISSING",
                "缺少 Root OPEN 安全授权",
            ))
        }
        val result = shell.execute(command)
        val observed = readStateForUser(userId)
        val requested = if (enabled) MicAccessState.BLOCKED else MicAccessState.OPEN
        val verified = result.succeeded && observed == requested
        return ControlResult(
            requested = requested,
            observed = observed,
            controlReadback = verified,
            durationMs = elapsedMs(started),
            errorCode = when {
                result.timedOut -> "ROOT_TIMEOUT"
                !result.succeeded -> "SENSOR_PRIVACY_COMMAND_FAILED"
                !verified -> "STATE_UNVERIFIED"
                else -> null
            },
            errorMessage = when {
                result.timedOut -> "Root 命令超时"
                !result.succeeded -> "sensor_privacy Root 命令失败（输出已隐藏）"
                !verified -> "命令可能被锁屏策略拒绝，读回未确认"
                else -> null
            },
        )
    }

    private suspend fun currentUserId(): Int? {
        val result = shell.execute("am get-current-user")
        val userId = result.stdout.trim().toIntOrNull()
        return userId?.takeIf { result.succeeded && it >= 0 }
    }

    private fun isValidTarget(target: SafetyTarget): Boolean =
        target.controllerId == id &&
            target.targetPackage == "android-global-microphone" &&
            target.userId >= 0

    private fun failed(
        enabled: Boolean,
        started: Long,
        code: String,
        message: String,
    ) = ControlResult(
        requested = if (enabled) MicAccessState.BLOCKED else MicAccessState.OPEN,
        observed = MicAccessState.UNKNOWN,
        controlReadback = false,
        durationMs = elapsedMs(started),
        errorCode = code,
        errorMessage = message,
    )

    companion object {
        fun parseMicrophoneState(output: String, userId: Int? = null): MicAccessState {
            val userPattern = Regex("^user_id\\s*=\\s*(\\d+)${'$'}", RegexOption.IGNORE_CASE)
            val sensorPattern = Regex("^sensor\\s*=\\s*(\\d+)${'$'}", RegexOption.IGNORE_CASE)
            val statePattern = Regex("^state_type\\s*=\\s*(\\d+)${'$'}", RegexOption.IGNORE_CASE)
            val legacyNestedStatePattern =
                Regex("^is_enabled\\s*=\\s*(true|false)${'$'}", RegexOption.IGNORE_CASE)
            var braceDepth = 0
            var currentUser: Int? = null
            var currentUserDepth = -1
            var currentSensor: Int? = null
            var currentSensorDepth = -1
            var sawNestedUsers = false
            var sawBlocked = false
            var sawDisabled = false
            var sawUnknownRelevantState = false
            var globalEnabled: Boolean? = null
            var globalStateConflict = false
            output.lineSequence().forEach { raw ->
                val line = raw.trim()
                val leadingClosures = line.takeWhile { it == '}' }.length
                repeat(leadingClosures) {
                    val nextDepth = (braceDepth - 1).coerceAtLeast(0)
                    braceDepth = nextDepth
                    if (currentSensorDepth > braceDepth) {
                        currentSensor = null
                        currentSensorDepth = -1
                    }
                    if (currentUserDepth > braceDepth) {
                        currentUser = null
                        currentUserDepth = -1
                    }
                }
                userPattern.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    sawNestedUsers = true
                    currentUser = it
                    currentUserDepth = braceDepth
                    currentSensor = null
                    currentSensorDepth = -1
                    return@forEach
                }
                sensorPattern.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    currentSensor = it
                    currentSensorDepth = braceDepth
                    return@forEach
                }
                statePattern.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.let { state ->
                    if (currentSensor == MICROPHONE_SENSOR && (userId == null || currentUser == userId)) {
                        when (state) {
                            STATE_ENABLED -> sawBlocked = true
                            STATE_DISABLED -> sawDisabled = true
                            else -> sawUnknownRelevantState = true
                        }
                    }
                    return@forEach
                }
                legacyNestedStatePattern.matchEntire(line)?.groupValues?.get(1)?.let { state ->
                    val enabled = state.equals("true", ignoreCase = true)
                    when {
                        currentSensor == MICROPHONE_SENSOR &&
                            (userId == null || currentUser == userId) -> {
                            if (enabled) sawBlocked = true else sawDisabled = true
                        }
                        currentSensor == null && currentUser != null &&
                            (userId == null || currentUser == userId) -> {
                            // Deprecated per-user all-sensor state is a veto when enabled, but
                            // false does not prove that the microphone-specific toggles are off.
                            if (enabled) sawBlocked = true
                        }
                        currentSensor == null && currentUser == null -> {
                            if (globalEnabled != null && globalEnabled != enabled) {
                                globalStateConflict = true
                            }
                            globalEnabled = enabled
                        }
                    }
                }
                val openingCount = line.count { it == '{' }
                val trailingClosures = line.drop(leadingClosures).count { it == '}' }
                braceDepth = (braceDepth + openingCount - trailingClosures).coerceAtLeast(0)
            }
            if (globalStateConflict) return MicAccessState.UNKNOWN
            if (globalEnabled == true || sawBlocked) return MicAccessState.BLOCKED
            if (sawUnknownRelevantState) return MicAccessState.UNKNOWN
            if (sawDisabled) return MicAccessState.OPEN
            if (sawNestedUsers) return MicAccessState.UNKNOWN

            // A user-scoped control decision must never be inferred from an OEM one-line field
            // that omits its user. Besides crossing profiles, substring matching such as
            // `sensor=1` also matches `sensor=10` and can turn unrelated metadata into a false
            // OPEN/BLOCKED readback. Unknown OEM formats therefore fail closed here.
            if (userId != null) return MicAccessState.UNKNOWN

            // Compatibility is intentionally limited to an entire, unambiguous microphone
            // state line when no user-scoped decision was requested (principally diagnostics).
            // Do not loosen this into token/substring matching: mixed metadata on a line cannot
            // prove which sensor a boolean belongs to.
            val legacyStates = output.lineSequence().mapNotNull { raw ->
                LEGACY_MICROPHONE_STATE.matchEntire(raw.trim())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.lowercase()
            }.toList()
            if (legacyStates.any { it == "true" || it == "blocked" }) {
                return MicAccessState.BLOCKED
            }
            if (legacyStates.isNotEmpty() && legacyStates.all {
                    it == "false" || it == "unblocked"
                }
            ) return MicAccessState.OPEN
            return MicAccessState.UNKNOWN
        }

        private const val MICROPHONE_SENSOR = 1
        private const val STATE_ENABLED = 1
        private const val STATE_DISABLED = 2
        private val LEGACY_MICROPHONE_STATE = Regex(
            "(?i)^(?:sensor\\s*[:=]\\s*1\\b\\s+(?:microphone\\s+)?|microphone\\s+)" +
                "(?:enabled|state)\\s*[:=]\\s*(true|false|blocked|unblocked)$",
        )

        private fun elapsedMs(startedNanos: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    }
}
