package com.jack.micbridge.server

import com.jack.micbridge.data.BeginRequestResult
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.IdempotencyStore
import com.jack.micbridge.data.LedgerEntry
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.RequestLedger
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.mic.MicController
import com.jack.micbridge.mic.MicCoordinator
import com.jack.micbridge.safety.LeaseArmResult
import com.jack.micbridge.safety.LeaseSafety
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRouterTest {
    @Test
    fun `health exposes no state and needs no token`() = runTest {
        val fixture = Fixture()

        val response = fixture.router.route(request("GET", "/healthz"))

        assertEquals(200, response.status)
        assertEquals("{\"ok\":true}", response.body)
        assertEquals(0, fixture.statusReads)
        assertFalse(response.failClosedOnWriteFailure)
        assertFalse(response.failClosedAfterResponse)
    }

    @Test
    fun `wrong token cannot read status`() = runTest {
        val fixture = Fixture()

        val response = fixture.router.route(
            request("GET", "/v1/status", mapOf(ApiRouter.TOKEN_HEADER to "wrong")),
        )

        assertEquals(401, response.status)
        assertFalse(response.body.contains("blocked"))
        assertEquals(0, fixture.statusReads)
        assertFalse(response.failClosedOnWriteFailure)
        assertFalse(response.failClosedAfterResponse)
    }

    @Test
    fun `mutation requires safe request id`() = runTest {
        val fixture = Fixture()

        val response = fixture.router.route(
            request(
                "POST",
                MicCoordinator.ENDPOINT_TOGGLE,
                mapOf(
                    ApiRouter.TOKEN_HEADER to "secret",
                    ApiRouter.REQUEST_ID_HEADER to "short",
                ),
            ),
        )

        assertEquals(400, response.status)
        assertEquals(0, fixture.controller.openCount)
        assertFalse(response.failClosedOnWriteFailure)
        assertFalse(response.failClosedAfterResponse)
    }

    @Test
    fun `valid toggle echoes id and uses parseable 200 envelope`() = runTest {
        val fixture = Fixture()
        val requestId = "request-router-000001"

        val response = fixture.router.route(
            request(
                "POST",
                MicCoordinator.ENDPOINT_TOGGLE,
                mapOf(
                    ApiRouter.TOKEN_HEADER to "secret",
                    ApiRouter.REQUEST_ID_HEADER to requestId,
                ),
            ),
        )

        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"ok\":true"))
        assertTrue(response.body.contains("\"mic_access\":\"open\""))
        assertTrue(response.body.contains("\"request_id\":\"$requestId\""))
        assertEquals(1, fixture.controller.openCount)
        assertTrue(response.failClosedOnWriteFailure)
        assertFalse(response.failClosedAfterResponse)
    }

    @Test
    fun `authenticated business failure remains 200 and echoes id`() = runTest {
        val fixture = Fixture(calibrated = false)
        val requestId = "request-router-failure-01"

        val response = fixture.router.route(
            request(
                "POST",
                MicCoordinator.ENDPOINT_TOGGLE,
                mapOf(
                    ApiRouter.TOKEN_HEADER to "secret",
                    ApiRouter.REQUEST_ID_HEADER to requestId,
                ),
            ),
        )

        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"ok\":false"))
        assertTrue(response.body.contains("\"request_id\":\"$requestId\""))
        assertTrue(response.body.contains("ACOUSTIC_CALIBRATION_REQUIRED"))
        assertEquals(0, fixture.controller.openCount)
        assertTrue(response.failClosedOnWriteFailure)
        assertFalse(response.failClosedAfterResponse)
    }

    @Test
    fun `admitted mutation exception returns parseable failure and requests fail closed`() = runTest {
        val fixture = Fixture()
        fixture.controller.readFailure = IllegalStateException("boom")
        val requestId = "request-router-crash-001"

        val response = fixture.router.route(
            request(
                "POST",
                MicCoordinator.ENDPOINT_TOGGLE,
                mapOf(
                    ApiRouter.TOKEN_HEADER to "secret",
                    ApiRouter.REQUEST_ID_HEADER to requestId,
                ),
            ),
        )

        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"ok\":false"))
        assertTrue(response.body.contains("\"mic_access\":\"unknown\""))
        assertTrue(response.body.contains("\"verified\":false"))
        assertTrue(response.body.contains("\"request_id\":\"$requestId\""))
        assertTrue(response.body.contains("INTERNAL_ERROR"))
        assertTrue(response.failClosedOnWriteFailure)
        assertTrue(response.failClosedAfterResponse)
    }

    @Test
    fun `status incident cannot be reported verified even with blocked readback`() = runTest {
        val fixture = Fixture(
            statusSnapshot = BridgeSnapshot(
                micAccess = MicAccessState.BLOCKED,
                controlReadback = true,
                acousticCalibrationValid = true,
                lastError = "unsafe OPEN was reconciled",
            ),
        )

        val response = fixture.router.route(
            request(
                "GET",
                ApiRouter.STATUS_PATH,
                mapOf(ApiRouter.TOKEN_HEADER to "secret"),
            ),
        )

        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"ok\":false"))
        assertTrue(response.body.contains("\"verified\":false"))
    }

    @Test
    fun `unknown v1 path is authenticated then returns 404`() = runTest {
        val fixture = Fixture()

        val unauthenticated = fixture.router.route(request("GET", "/v1/not-real"))
        val authenticated = fixture.router.route(
            request("GET", "/v1/not-real", mapOf(ApiRouter.TOKEN_HEADER to "secret")),
        )

        assertEquals(401, unauthenticated.status)
        assertEquals(404, authenticated.status)
        assertTrue(authenticated.body.contains("NOT_FOUND"))
    }

    @Test
    fun `known endpoint with wrong method returns 405 without mutation`() = runTest {
        val fixture = Fixture()

        val response = fixture.router.route(
            request(
                "GET",
                MicCoordinator.ENDPOINT_TOGGLE,
                mapOf(ApiRouter.TOKEN_HEADER to "secret"),
            ),
        )

        assertEquals(405, response.status)
        assertTrue(response.body.contains("METHOD_NOT_ALLOWED"))
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `reserved lease request ids are rejected before mutation`() = runTest {
        listOf(
            "cancel-client-request-0001",
            "expired-client-request-001",
            "removed-client-request-001",
        ).forEach { requestId ->
            val fixture = Fixture()

            val response = fixture.router.route(
                request(
                    "POST",
                    MicCoordinator.ENDPOINT_TOGGLE,
                    mapOf(
                        ApiRouter.TOKEN_HEADER to "secret",
                        ApiRouter.REQUEST_ID_HEADER to requestId,
                    ),
                ),
            )

            assertEquals(requestId, 400, response.status)
            assertTrue(requestId, response.body.contains("INVALID_REQUEST_ID"))
            assertEquals(requestId, 0, fixture.controller.openCount)
            assertFalse(requestId, response.failClosedOnWriteFailure)
            assertFalse(requestId, response.failClosedAfterResponse)
        }
    }

    private fun request(method: String, target: String, headers: Map<String, String> = emptyMap()) =
        HttpRequest(method, target, "HTTP/1.1", headers)

    private class Fixture(
        calibrated: Boolean = true,
        private val statusSnapshot: BridgeSnapshot = BridgeSnapshot(
            micAccess = MicAccessState.BLOCKED,
            controlReadback = true,
            acousticCalibrationValid = true,
        ),
    ) {
        val controller = FakeController()
        private val ledger = MemoryLedger()
        var statusReads = 0
        private val coordinator = MicCoordinator(
            controller,
            ledger,
            object : LeaseSafety {
                override suspend fun arm(
                    requestId: String,
                    durationSeconds: Int,
                    target: SafetyTarget,
                ) = LeaseArmResult(
                    armed = true,
                    deadlineEpochMs = 30_000,
                    deadlineElapsedRealtimeMs = 30_000,
                    exactAlarmArmed = true,
                    rootWatchdogArmed = false,
                )

                override suspend fun cancel() = Unit
            },
            tokenGeneration = { 1 },
            maxOpenSeconds = { 30 },
            calibrationValid = { calibrated },
            nowEpochMs = { 1_000 },
        )
        val router = ApiRouter(
            tokenMatches = { it == "secret" },
            coordinator = coordinator,
            statusProvider = {
                statusReads++
                statusSnapshot
            },
        )
    }

    private class FakeController : MicController {
        override val id = "fake"
        override val displayName = "Fake"
        var state = MicAccessState.BLOCKED
        var openCount = 0
        var readFailure: RuntimeException? = null
        private val target = SafetyTarget(id, "com.openai.chatgpt", 0)

        override suspend fun probe() = ProbeResult(true, true)
        override suspend fun captureSafetyTarget() = target
        override suspend fun readState(target: SafetyTarget?): MicAccessState {
            readFailure?.let { throw it }
            return state
        }
        override suspend fun block(target: SafetyTarget?) = result(MicAccessState.BLOCKED).also {
            state = MicAccessState.BLOCKED
        }
        override suspend fun open(
            target: SafetyTarget,
            authorization: com.jack.micbridge.mic.OpenAuthorization,
        ) = result(MicAccessState.OPEN).also {
            state = MicAccessState.OPEN
            openCount++
        }

        private fun result(target: MicAccessState) = ControlResult(
            target,
            target,
            true,
            1,
        )
    }

    private class MemoryLedger : IdempotencyStore {
        private val entries = mutableMapOf<String, LedgerEntry>()

        override fun begin(
            requestId: String,
            endpoint: String,
            tokenGeneration: Int,
            nowEpochMs: Long,
        ): BeginRequestResult {
            entries[requestId]?.let { return BeginRequestResult.Existing(it) }
            entries[requestId] = LedgerEntry(
                requestId,
                endpoint,
                tokenGeneration,
                RequestLedger.STATUS_IN_PROGRESS,
                null,
                null,
                null,
                null,
                nowEpochMs,
            )
            return BeginRequestResult.New
        }

        override fun complete(
            requestId: String,
            outcome: MicAccessState,
            ok: Boolean,
            errorCode: String?,
            errorMessage: String?,
            nowEpochMs: Long,
        ) {
            entries[requestId] = entries.getValue(requestId).copy(
                status = RequestLedger.STATUS_COMPLETE,
                outcome = outcome,
                ok = ok,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        }

        override fun recoverInProgress(): List<LedgerEntry> = emptyList()
    }
}
