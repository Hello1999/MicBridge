package com.jack.micbridge.server

import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.OperationResult
import com.jack.micbridge.mic.MicCoordinator
import java.time.Instant

class ApiRouter(
    private val tokenMatches: (String?) -> Boolean,
    private val coordinator: MicCoordinator,
    private val statusProvider: suspend () -> BridgeSnapshot,
    private val remoteGeneration: Long = 0L,
    private val onStatus: (BridgeSnapshot) -> Unit = {},
    private val mutationExecutor: (suspend (String, String, Long) -> OperationResult)? = null,
) : HttpRequestRouter {
    override suspend fun route(request: HttpRequest): HttpResponse {
        if (request.target == HEALTH_PATH) {
            if (request.method != "GET") return methodNotAllowed()
            return HttpResponse(200, Json.objectOf("ok" to true))
        }

        if (!request.target.startsWith("/v1/")) return error(404, "NOT_FOUND", "未知路径")
        if (!tokenMatches(request.headers[TOKEN_HEADER])) {
            return error(401, "UNAUTHORIZED", "令牌缺失或无效")
        }

        if (request.target == STATUS_PATH) {
            if (request.method != "GET") return methodNotAllowed()
            val snapshot = statusProvider()
            onStatus(snapshot)
            return HttpResponse(200, statusJson(snapshot))
        }

        if (request.target !in MUTATION_PATHS) return error(404, "NOT_FOUND", "未知路径")
        if (request.method != "POST") return methodNotAllowed()
        val requestId = request.headers[REQUEST_ID_HEADER]
        if (
            requestId == null || !REQUEST_ID_PATTERN.matches(requestId) ||
            RESERVED_REQUEST_ID_PREFIXES.any(requestId::startsWith)
        ) {
            return error(
                400,
                "INVALID_REQUEST_ID",
                "X-Request-Id 必须为 16–128 位安全标识且不能使用保留前缀",
            )
        }

        // Valid authenticated application failures deliberately remain HTTP 200 so Apple
        // Shortcuts can inspect ok=false and emit the required three-pulse error feedback.
        return try {
            HttpResponse(
                status = 200,
                body = operationJson(
                    mutationExecutor?.invoke(request.target, requestId, remoteGeneration)
                        ?: coordinator.execute(request.target, requestId, remoteGeneration),
                ),
                failClosedOnWriteFailure = true,
            )
        } catch (error: Exception) {
            // Admission, authentication and request-id validation have completed. The
            // coordinator may have crossed a writable boundary before throwing, so this is the
            // one router failure class that must synchronously drive the service fail closed.
            HttpResponse(
                // Keep admitted mutation failures in the normal JSON envelope. Some Shortcuts
                // versions abort on HTTP 5xx before inspecting the body, which would suppress
                // the required three-pulse UNKNOWN feedback.
                status = 200,
                body = Json.objectOf(
                    "ok" to false,
                    "mic_access" to "unknown",
                    "verified" to false,
                    "request_id" to requestId,
                    "haptic_pulses" to UNKNOWN_PULSES,
                    "error" to Json.error(
                        "INTERNAL_ERROR",
                        "修改请求发生内部错误；服务将安全屏蔽并重建监听",
                    ),
                    "server_time" to Instant.now().toString(),
                ),
                failClosedOnWriteFailure = true,
                failClosedAfterResponse = true,
            )
        }
    }

    private fun statusJson(snapshot: BridgeSnapshot): String {
        val verified = !snapshot.transitioning &&
            snapshot.micAccess != com.jack.micbridge.data.MicAccessState.UNKNOWN &&
            snapshot.controlReadback &&
            snapshot.acousticCalibrationValid &&
            snapshot.lastError == null
        val micAccess = snapshot.micAccess.wireValue
        return Json.objectOf(
        "ok" to verified,
        "mic_access" to micAccess,
        "transitioning" to snapshot.transitioning,
        "verified" to verified,
        // The status envelope reports ok == verified, so both inputs of the shared rule are the
        // same boolean here.
        "haptic_pulses" to hapticPulses(ok = verified, verified = verified, micAccess = micAccess),
        "command_succeeded" to null,
        "control_readback" to snapshot.controlReadback,
        "acoustic_calibration_valid" to snapshot.acousticCalibrationValid,
        "controller" to snapshot.controllerId,
        "controller_probe" to snapshot.controllerProbe,
        "service" to if (snapshot.serviceRunning) "running" else "stopped",
        "battery_optimization_exempt" to snapshot.batteryOptimizationExempt,
        "addresses" to snapshot.serverAddresses,
        "auto_block_at" to Json.instant(snapshot.autoBlockAtEpochMs),
        "lease_exact_alarm_armed" to snapshot.leaseExactAlarmArmed,
        "lease_root_watchdog_armed" to snapshot.leaseRootWatchdogArmed,
        "observed_at" to Json.instant(snapshot.observedAtEpochMs),
        "server_time" to Instant.now().toString(),
        "error" to Json.error(
            if (verified) null else "STATE_UNVERIFIED",
            snapshot.lastError ?: when {
                !snapshot.controlReadback -> "当前麦克风控制状态无法确认"
                !snapshot.acousticCalibrationValid -> "当前固件、控制器与 ChatGPT 版本尚未通过声学校准"
                else -> "当前状态未满足完整验证条件"
            },
        ),
    )
    }

    private fun operationJson(result: OperationResult): String {
        val verified = result.micAccess != com.jack.micbridge.data.MicAccessState.UNKNOWN &&
            result.controlReadback && result.acousticCalibrationValid
        val ok = result.ok && verified
        val boundaryErrorCode = result.errorCode ?: if (!verified) "STATE_UNVERIFIED" else null
        val boundaryErrorMessage = result.errorMessage ?: if (!verified) {
            "当前控制状态或声学校准无法确认"
        } else null
        val micAccess = result.micAccess.wireValue
        return Json.objectOf(
        "ok" to ok,
        "mic_access" to micAccess,
        "previous" to result.previous.wireValue,
        "verified" to verified,
        "command_succeeded" to result.commandSucceeded,
        "control_readback" to result.controlReadback,
        "acoustic_calibration_valid" to result.acousticCalibrationValid,
        "controller" to result.controllerId,
        "request_id" to result.requestId,
        "haptic_pulses" to hapticPulses(ok = ok, verified = verified, micAccess = micAccess),
        "auto_block_at" to Json.instant(result.autoBlockAtEpochMs),
        "lease_exact_alarm_armed" to result.leaseExactAlarmArmed,
        "lease_root_watchdog_armed" to result.leaseRootWatchdogArmed,
        "latency_ms" to result.latencyMs,
        "replayed" to result.replayed,
        "original_outcome" to result.originalOutcome?.wireValue,
        "server_time" to Instant.now().toString(),
        "error" to Json.error(boundaryErrorCode, boundaryErrorMessage),
    )
    }

    // Server-side pre-computation of the pulse count the Apple Shortcut would otherwise derive
    // from ok/verified/mic_access. It only folds those checks into one integer; the client still
    // has to confirm the echoed request_id itself before it may trust this number.
    private fun hapticPulses(ok: Boolean, verified: Boolean, micAccess: String): Int = when {
        !ok || !verified -> UNKNOWN_PULSES
        micAccess == OPEN_WIRE_VALUE -> OPEN_PULSES
        micAccess == BLOCKED_WIRE_VALUE -> BLOCKED_PULSES
        else -> UNKNOWN_PULSES
    }

    private fun methodNotAllowed() = error(405, "METHOD_NOT_ALLOWED", "方法不支持")

    private fun error(status: Int, code: String, message: String) = HttpResponse(
        status,
        Json.objectOf(
            "ok" to false,
            "mic_access" to "unknown",
            "verified" to false,
            "haptic_pulses" to UNKNOWN_PULSES,
            "error" to Json.error(code, message),
            "server_time" to Instant.now().toString(),
        ),
    )

    companion object {
        const val HEALTH_PATH = "/healthz"
        const val STATUS_PATH = "/v1/status"
        const val TOKEN_HEADER = "x-micbridge-token"
        const val REQUEST_ID_HEADER = "x-request-id"
        val MUTATION_PATHS = setOf(
            MicCoordinator.ENDPOINT_TOGGLE,
            MicCoordinator.ENDPOINT_OPEN,
            MicCoordinator.ENDPOINT_BLOCK,
        )
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{16,128}")
        val RESERVED_REQUEST_ID_PREFIXES = listOf("cancel-", "expired-", "removed-")
        const val OPEN_PULSES = 1
        const val BLOCKED_PULSES = 2
        const val UNKNOWN_PULSES = 3
        private val OPEN_WIRE_VALUE = com.jack.micbridge.data.MicAccessState.OPEN.wireValue
        private val BLOCKED_WIRE_VALUE = com.jack.micbridge.data.MicAccessState.BLOCKED.wireValue
    }
}
