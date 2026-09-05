package com.jack.micbridge.mic

import com.jack.micbridge.data.BeginRequestResult
import com.jack.micbridge.data.BridgeSnapshot
import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.IdempotencyStore
import com.jack.micbridge.data.LedgerEntry
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.RequestLedger
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.safety.ActiveSafetyLease
import com.jack.micbridge.safety.LeaseArmResult
import com.jack.micbridge.safety.LeaseSafety
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicCoordinatorTest {
    @Test
    fun `startup always blocks`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.OPEN)

        val result = fixture.coordinator.initialize()

        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(1, fixture.controller.blockCount)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `failed startup block preserves any existing lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.OPEN)
        fixture.controller.blockResultState = MicAccessState.OPEN

        val result = fixture.coordinator.initialize()

        assertFalse(result.controlReadback)
        assertEquals(MicAccessState.OPEN, result.observed)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `toggle changes current Android state not press parity`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.OPEN)
        fixture.coordinator.initialize()
        fixture.time = 1_000L

        val opened = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-0000000001",
        )
        fixture.time += 1_000L
        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-0000000002",
        )

        assertTrue(opened.ok)
        assertEquals(MicAccessState.OPEN, opened.micAccess)
        assertEquals(MicAccessState.BLOCKED, blocked.micAccess)
        assertEquals(1, fixture.controller.openCount)
        assertEquals(2, fixture.controller.blockCount)
        assertTrue(fixture.snapshots.any { it.transitioning })
        assertFalse(fixture.snapshots.last().transitioning)
    }

    @Test
    fun `unknown toggle forces block instead of guessing open`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.UNKNOWN)
        fixture.controller.blockResultState = MicAccessState.BLOCKED

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-unknown-01",
        )

        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(0, fixture.controller.openCount)
        assertEquals(1, fixture.controller.blockCount)
    }

    @Test
    fun `same request id never toggles twice and reports fresh state`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)

        val first = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-replay-0001",
        )
        fixture.controller.state = MicAccessState.BLOCKED // Simulate lease expiry before retry.
        val replay = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-replay-0001",
        )

        assertEquals(1, fixture.controller.openCount)
        assertTrue(first.ok)
        assertTrue(replay.replayed)
        assertEquals(MicAccessState.BLOCKED, replay.micAccess)
        assertEquals(MicAccessState.OPEN, replay.originalOutcome)
    }

    @Test
    fun `replay reconciliation publishes its fresh blocked state`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-replay-publish-01",
        )
        assertEquals(MicAccessState.OPEN, fixture.snapshots.last().micAccess)

        // Simulate an independent alarm/watchdog BLOCK before the HTTP response is retried.
        fixture.controller.state = MicAccessState.BLOCKED
        val replay = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-replay-publish-01",
        )

        assertTrue(replay.replayed)
        assertEquals(MicAccessState.BLOCKED, replay.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.snapshots.last().micAccess)
        assertNull(fixture.snapshots.last().autoBlockAtEpochMs)
    }

    @Test
    fun `explicit OPEN cannot replace or extend an already live lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.time = 1_000L
        val first = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-open-original-01",
        )
        fixture.time += 1_000L

        val repeated = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-open-assertion-02",
        )

        assertTrue(first.ok)
        assertTrue(repeated.ok)
        assertEquals(first.autoBlockAtEpochMs, repeated.autoBlockAtEpochMs)
        assertEquals(1, fixture.lease.armCount)
        assertEquals(1, fixture.controller.openCount)
    }

    @Test
    fun `persistent remote toggle stays open past timed limit until next toggle`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            persistentRemoteOpen = true,
        )

        val opened = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-persistent-open-01",
        )
        fixture.time += 120_000L
        val stillOpen = fixture.coordinator.readSnapshot(true, emptyList())
        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-persistent-block-02",
        )

        assertTrue(opened.ok)
        assertEquals(MicAccessState.OPEN, opened.micAccess)
        assertNull(opened.autoBlockAtEpochMs)
        assertEquals(false, opened.leaseExactAlarmArmed)
        assertEquals(1, fixture.lease.persistentArmCount)
        assertEquals(MicAccessState.OPEN, stillOpen.micAccess)
        assertNull(stillOpen.autoBlockAtEpochMs)
        assertTrue(blocked.ok)
        assertEquals(MicAccessState.BLOCKED, blocked.micAccess)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `status cannot report OPEN after the PID bound Root guard is unhealthy`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        assertTrue(
            fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_OPEN,
                "request-guard-status-01",
            ).ok,
        )
        fixture.lease.guardHealthy = false

        val snapshot = fixture.coordinator.readSnapshot(true, emptyList())

        assertEquals(MicAccessState.BLOCKED, snapshot.micAccess)
        assertFalse(snapshot.controlReadback && snapshot.lastError == null)
        assertTrue(fixture.controller.blockCount >= 1)
    }

    @Test
    fun `replay cannot report OPEN after the PID bound Root guard is unhealthy`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        val requestId = "request-guard-replay-01"
        assertTrue(fixture.coordinator.execute(MicCoordinator.ENDPOINT_OPEN, requestId).ok)
        fixture.lease.guardHealthy = false

        val replay = fixture.coordinator.execute(MicCoordinator.ENDPOINT_OPEN, requestId)

        assertTrue(replay.replayed)
        assertFalse(replay.ok)
        assertEquals(MicAccessState.BLOCKED, replay.micAccess)
    }

    @Test
    fun `already OPEN assertion fails closed when Root guard health is lost`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        assertTrue(
            fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_OPEN,
                "request-guard-original-01",
            ).ok,
        )
        fixture.time += 1_000L
        fixture.lease.guardHealthy = false

        val assertion = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-guard-assertion-02",
        )

        assertFalse(assertion.ok)
        assertEquals("OPEN_WITHOUT_VALID_LEASE", assertion.errorCode)
        assertEquals(MicAccessState.BLOCKED, assertion.micAccess)
    }

    /**
     * `verifyActiveGuard` is a Root shell round trip (~100–200 ms on device). One guarded OPEN
     * needs exactly one, taken inside the mutation lock right after `controller.open`; the
     * post-lock commit check reuses that proof instead of paying for a second one.
     */
    @Test
    fun `successful remote open verifies the Root guard exactly once`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true

        val opened = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-guard-once-01",
            remoteGeneration = 7L,
        )

        assertTrue(opened.ok)
        assertEquals(MicAccessState.OPEN, opened.micAccess)
        assertEquals(true, opened.leaseRootWatchdogArmed)
        assertEquals(1, fixture.lease.guardVerificationCount)
    }

    /** The single in-lock proof still fails the OPEN closed, with the same code and rollback. */
    @Test
    fun `unhealthy Root guard after open rolls back inside the mutation gate`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.controller.onOpen = { fixture.lease.guardHealthy = false }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-guard-unhealthy-01",
            remoteGeneration = 7L,
        )

        assertFalse(result.ok)
        assertEquals("LEASE_GUARD_UNHEALTHY", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(1, fixture.lease.guardVerificationCount)
    }

    @Test
    fun `concurrent same id is serialized`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        val first = async {
            fixture.coordinator.execute(MicCoordinator.ENDPOINT_TOGGLE, "request-concurrent-1")
        }
        val second = async {
            fixture.coordinator.execute(MicCoordinator.ENDPOINT_TOGGLE, "request-concurrent-1")
        }

        assertTrue(first.await().ok)
        assertTrue(second.await().replayed)
        assertEquals(1, fixture.controller.openCount)
    }

    @Test
    fun `open is refused when lease guard cannot arm`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.lease.armSucceeds = false

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-no-lease-01",
        )

        assertFalse(result.ok)
        assertEquals("LEASE_GUARD_NOT_ARMED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `arm rejection before durable write does not create a phantom lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.lease.armSucceeds = false
        fixture.lease.persistLeaseOnFailedArm = false
        fixture.lease.failCancelWhenNoLease = true

        val rejected = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-pre-durable-arm-failure",
        )

        assertFalse(rejected.ok)
        assertEquals("LEASE_GUARD_NOT_ARMED", rejected.errorCode)
        assertEquals(MicAccessState.BLOCKED, rejected.micAccess)
        assertEquals(0, fixture.lease.cancelCount)

        // A later explicit BLOCK remains a usable recovery path; it must not attempt to
        // cancel a lease that never existed.
        fixture.time += 1_000L
        val recovered = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_BLOCK,
            "request-block-after-pre-durable-failure",
        )
        assertTrue(recovered.ok)
        assertEquals(MicAccessState.BLOCKED, recovered.micAccess)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `Root sensor lease cannot remain OPEN without its independent watchdog`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "root_sensor_privacy",
        )
        fixture.lease.rootWatchdogArmed = false

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-root-watchdog-missing",
        )

        assertFalse(result.ok)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals("LEASE_INVALID_BEFORE_OPEN", result.errorCode)
        assertEquals(0, fixture.controller.openCount)
        assertEquals(1, fixture.controller.blockCount)
    }

    @Test
    fun `lease that expires during arming is refused before controller open`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.lease.onArm = { fixture.time = 30_000L }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-expired-before-open",
        )

        assertFalse(result.ok)
        assertEquals("LEASE_INVALID_BEFORE_OPEN", result.errorCode)
        assertEquals(0, fixture.controller.openCount)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
    }

    @Test
    fun `open completing after its authorization deadline is rolled back and never succeeds`() =
        runTest {
            val fixture = Fixture(initialState = MicAccessState.BLOCKED)
            fixture.lease.openValidDurationMs = 20_000L
            fixture.controller.onOpen = { fixture.time = 20_000L }

            val result = fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_OPEN,
                "request-late-open-deadline",
            )

            assertFalse(result.ok)
            assertEquals("LEASE_EXPIRED_DURING_OPEN", result.errorCode)
            assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
            assertTrue(fixture.controller.blockCount >= 1)
        }

    @Test
    fun `delivered expiry cannot block before an in-flight open mutation and be undone later`() =
        runTest {
            val fixture = Fixture(initialState = MicAccessState.BLOCKED)
            fixture.lease.openValidDurationMs = 20_000L
            val openReachedMutation = CompletableDeferred<Unit>()
            val allowOpenMutation = CompletableDeferred<Unit>()
            val expiryStarted = CompletableDeferred<Unit>()
            fixture.controller.beforeOpenMutation = {
                openReachedMutation.complete(Unit)
                allowOpenMutation.await()
            }
            fixture.controller.onOpen = { fixture.time = 20_000L }

            val request = async {
                fixture.coordinator.execute(
                    MicCoordinator.ENDPOINT_OPEN,
                    "request-serialized-late-open",
                )
            }
            openReachedMutation.await()
            val expiry = async {
                expiryStarted.complete(Unit)
                fixture.lease.withMutationLock {
                    fixture.controller.block(fixture.controller.defaultTarget)
                }
            }
            expiryStarted.await()
            assertEquals(0, fixture.controller.blockCount)

            allowOpenMutation.complete(Unit)
            val result = request.await()
            expiry.await()

            assertFalse(result.ok)
            assertEquals("LEASE_EXPIRED_DURING_OPEN", result.errorCode)
            assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        }

    @Test
    fun `failed open and failed safety block keep the armed lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.controller.openResultState = MicAccessState.UNKNOWN
        fixture.controller.blockResultState = MicAccessState.OPEN

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-open-fail-01",
        )

        assertFalse(result.ok)
        assertEquals(MicAccessState.OPEN, result.micAccess)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `open command error cannot leave a reported OPEN state`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.controller.openErrorCode = "COMMAND_FAILED"

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-open-error-state",
        )

        assertFalse(result.ok)
        assertEquals("COMMAND_FAILED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.controller.blockCount)
    }

    @Test
    fun `explicit open from unknown first establishes blocked and refuses open`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.UNKNOWN)

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-open-unknown",
        )

        assertFalse(result.ok)
        assertEquals("OPEN_PRECONDITION_UNKNOWN", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `network gate refuses a remote open while still allowing a safety block`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.remoteOpenAllowed = false

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-network-gate",
        )

        assertFalse(result.ok)
        assertEquals("REMOTE_OPEN_DISABLED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `lifecycle gate refuses calibration open and remains blocked`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.openAllowed = false

        val result = fixture.coordinator.calibrationOpen()

        assertFalse(result.ok)
        assertEquals("OPEN_LIFECYCLE_DISABLED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `root failsafe calibration proves sensor block while AudioManager remains open`() =
        runTest {
            val fixture = Fixture(
                initialState = MicAccessState.BLOCKED,
                calibrated = false,
                controllerId = "audio_manager",
            )
            fixture.lease.rootWatchdogArmed = true
            val opened = fixture.coordinator.calibrationOpen()
            assertEquals(MicAccessState.OPEN, opened.micAccess)

            val rootGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy")
            val audioGate = FakeController(MicAccessState.OPEN, "audio_manager")
            var immediateBlockCalled = false
            val isolated = fixture.coordinator.calibrationIsolateRootFailsafe(
                rootGate = rootGate,
                audioGate = audioGate,
                appOpsVeto = ReadOnlyOpenVeto { MicAccessState.OPEN },
                immediateRootBlock = {
                    immediateBlockCalled = true
                    true
                },
            )

            assertTrue(immediateBlockCalled)
            assertTrue(isolated.controlReadback)
            assertEquals(MicAccessState.BLOCKED, rootGate.state)
            assertEquals(MicAccessState.OPEN, audioGate.state)
            assertEquals(MicAccessState.UNKNOWN, fixture.snapshots.last().micAccess)
            assertFalse(fixture.snapshots.last().controlReadback)
            assertEquals(opened.autoBlockAtEpochMs, fixture.snapshots.last().autoBlockAtEpochMs)
            assertEquals(0, fixture.lease.cancelCount)

            val confirmed = fixture.coordinator.confirmRootIsolationAndBlock(
                rootGate,
                audioGate,
                ReadOnlyOpenVeto { MicAccessState.OPEN },
            )
            assertTrue(confirmed.controlReadback)
            assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
            assertEquals(1, fixture.lease.cancelCount)
        }

    @Test
    fun `failed immediate root block restores full block`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            calibrated = false,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.coordinator.calibrationOpen()

        val isolated = fixture.coordinator.calibrationIsolateRootFailsafe(
            rootGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy"),
            audioGate = FakeController(MicAccessState.OPEN, "audio_manager"),
            appOpsVeto = ReadOnlyOpenVeto { MicAccessState.OPEN },
            immediateRootBlock = { false },
        )

        assertFalse(isolated.controlReadback)
        assertEquals("CALIBRATION_IMMEDIATE_ROOT_BLOCK_FAILED", isolated.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `coupled root and AudioManager controls cannot pass isolated calibration`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            calibrated = false,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.coordinator.calibrationOpen()
        val coupledGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy")

        val isolated = fixture.coordinator.calibrationIsolateRootFailsafe(
            rootGate = coupledGate,
            audioGate = coupledGate,
            appOpsVeto = ReadOnlyOpenVeto { MicAccessState.OPEN },
        )

        assertFalse(isolated.controlReadback)
        assertEquals("CALIBRATION_ROOT_NOT_ISOLATED", isolated.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `AppOps denial cannot masquerade as successful root acoustic isolation`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            calibrated = false,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.coordinator.calibrationOpen()
        val rootGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy")
        val audioGate = FakeController(MicAccessState.OPEN, "audio_manager")

        val isolated = fixture.coordinator.calibrationIsolateRootFailsafe(
            rootGate,
            audioGate,
            ReadOnlyOpenVeto { MicAccessState.BLOCKED },
        )

        assertFalse(isolated.controlReadback)
        assertEquals("CALIBRATION_APPOPS_NOT_OPEN", isolated.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `confirmation rechecks AppOps and invalidates a changed split state`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            calibrated = false,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.coordinator.calibrationOpen()
        val rootGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy")
        val audioGate = FakeController(MicAccessState.OPEN, "audio_manager")
        fixture.coordinator.calibrationIsolateRootFailsafe(
            rootGate,
            audioGate,
            ReadOnlyOpenVeto { MicAccessState.OPEN },
        )

        val confirmed = fixture.coordinator.confirmRootIsolationAndBlock(
            rootGate,
            audioGate,
            ReadOnlyOpenVeto { MicAccessState.BLOCKED },
        )

        assertFalse(confirmed.controlReadback)
        assertEquals("CALIBRATION_ISOLATION_NOT_CURRENT", confirmed.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `root isolation publication failure synchronously restores full block`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            calibrated = false,
            controllerId = "audio_manager",
            failSnapshotWhen = {
                it.lastError.orEmpty().startsWith("隔离校准模式")
            },
        )
        fixture.lease.rootWatchdogArmed = true
        fixture.coordinator.calibrationOpen()
        val rootGate = FakeController(MicAccessState.OPEN, "root_sensor_privacy")
        val audioGate = FakeController(MicAccessState.OPEN, "audio_manager")

        val isolated = fixture.coordinator.calibrationIsolateRootFailsafe(
            rootGate,
            audioGate,
            ReadOnlyOpenVeto { MicAccessState.OPEN },
        )

        assertFalse(isolated.controlReadback)
        assertEquals("CALIBRATION_PUBLICATION_FAILED", isolated.errorCode)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `failed requested block preserves active lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.coordinator.execute(MicCoordinator.ENDPOINT_TOGGLE, "request-open-before-block")
        fixture.time += 1_000L
        fixture.controller.blockResultState = MicAccessState.OPEN

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-block-failure",
        )

        assertFalse(result.ok)
        assertEquals(MicAccessState.OPEN, result.micAccess)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `fresh status reconciles external auto block and clears lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.coordinator.execute(MicCoordinator.ENDPOINT_TOGGLE, "request-external-expiry")
        fixture.controller.state = MicAccessState.BLOCKED

        val snapshot = fixture.coordinator.readSnapshot(true, emptyList())

        assertEquals(MicAccessState.BLOCKED, snapshot.micAccess)
        assertNull(snapshot.autoBlockAtEpochMs)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `status synchronously blocks external open without a valid lease and reports incident`() =
        runTest {
            val fixture = Fixture(initialState = MicAccessState.OPEN)

            val snapshot = fixture.coordinator.readSnapshot(true, emptyList())

            assertEquals(MicAccessState.BLOCKED, snapshot.micAccess)
            assertTrue(snapshot.controlReadback)
            assertTrue(snapshot.lastError?.contains("没有有效安全租约") == true)
            assertEquals(1, fixture.controller.blockCount)
            assertEquals(0, fixture.lease.cancelCount)
        }

    @Test
    fun `status blocks open when monotonic lease deadline has expired`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        val opened = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-expired-lease-01",
        )
        assertTrue(opened.ok)
        fixture.time = 30_001L

        val snapshot = fixture.coordinator.readSnapshot(true, emptyList())

        assertEquals(MicAccessState.BLOCKED, snapshot.micAccess)
        assertTrue(snapshot.lastError?.contains("租约已到期") == true)
        assertNull(snapshot.autoBlockAtEpochMs)
        assertEquals(1, fixture.controller.blockCount)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `revoked HTTP generation during open fails and ends blocked`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.controller.onOpen = { fixture.remoteOpenAllowed = false }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-generation-revoked",
            remoteGeneration = 42L,
        )

        assertFalse(result.ok)
        assertEquals("REMOTE_GENERATION_REVOKED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.controller.openCount)
        assertEquals(2, fixture.controller.blockCount)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `HTTP generation revoked while arming never reaches controller open`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.lease.onArm = { fixture.remoteOpenAllowed = false }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-generation-revoked-arm",
            remoteGeneration = 42L,
        )

        assertFalse(result.ok)
        assertEquals("REMOTE_GENERATION_REVOKED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(0, fixture.controller.openCount)
    }

    @Test
    fun `startup blocks the exact target restored from persistent lease`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.OPEN)
        val persistedTarget = SafetyTarget(
            controllerId = "fake",
            targetPackage = "com.openai.chatgpt.persisted",
            userId = 10,
        )
        fixture.lease.activeLease = ActiveSafetyLease(
            requestId = "request-persisted-target",
            target = persistedTarget,
            deadlineEpochMs = 30_000L,
            deadlineElapsedRealtimeMs = 30_000L,
            exactAlarmArmed = true,
            rootWatchdogArmed = false,
        )

        val result = fixture.coordinator.initialize()

        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(listOf(persistedTarget), fixture.controller.blockTargets)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `remote open is refused before acoustic calibration`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED, calibrated = false)

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-uncalibrated",
        )

        assertFalse(result.ok)
        assertEquals("ACOUSTIC_CALIBRATION_REQUIRED", result.errorCode)
        assertEquals(0, fixture.controller.openCount)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(MicAccessState.BLOCKED, fixture.snapshots.last().micAccess)
        assertTrue(
            fixture.snapshots.last().lastError.orEmpty().contains("声学校准"),
        )
    }

    @Test
    fun `calibration revoked while opening rolls back before returning failure`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.controller.onOpen = { fixture.calibratedState = false }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-calibration-race",
        )

        assertFalse(result.ok)
        assertEquals("ACOUSTIC_CALIBRATION_REVOKED", result.errorCode)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.controller.openCount)
        assertTrue(fixture.controller.blockCount >= 1)
        assertEquals(1, fixture.lease.cancelCount)
        assertEquals(MicAccessState.BLOCKED, fixture.snapshots.last().micAccess)
    }

    @Test
    fun `different id immediately after open still performs safety block`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.time = 10_000L
        fixture.coordinator.execute(MicCoordinator.ENDPOINT_TOGGLE, "request-rapid-toggle-01")
        fixture.time += 100L

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-rapid-toggle-02",
        )

        assertTrue(result.ok)
        assertEquals(MicAccessState.BLOCKED, result.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.controller.openCount)
        assertEquals(1, fixture.controller.blockCount)
        assertEquals(1, fixture.lease.cancelCount)
    }

    @Test
    fun `explicit block immediately after toggle is never delayed`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.time = 10_000L
        assertTrue(
            fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_TOGGLE,
                "request-immediate-open-01",
            ).ok,
        )
        fixture.time += 100L

        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_BLOCK,
            "request-safety-block-02",
        )

        assertTrue(blocked.ok)
        assertEquals(MicAccessState.BLOCKED, blocked.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertEquals(1, fixture.controller.openCount)
        assertEquals(1, fixture.controller.blockCount)
    }

    /**
     * The locked revoke script already blocks the effective gate for every user and verifies the
     * readback before it replaces the lease marker, so it is the block that the lease protocol
     * depends on. Running it before `controller.block` keeps that ordering and lets the
     * cross-gate controller observe an already-BLOCKED sensor gate, so one BLOCK toggle performs
     * the expensive block-all-users round trip once rather than twice.
     */
    @Test
    fun `root lease block toggle revokes under the flock before the controller block`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        assertTrue(
            fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_TOGGLE,
                "request-root-revoke-open-01",
            ).ok,
        )
        fixture.time += 1_000L
        fixture.events.clear()

        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-root-revoke-block-02",
        )

        assertEquals(listOf("revoke", "block", "cancel"), fixture.events)
        assertEquals(1, fixture.lease.revokeCalls)
        assertEquals(listOf(true), fixture.lease.cancelRootLeaseAlreadyRevoked)
        assertTrue(blocked.ok)
        assertEquals(MicAccessState.BLOCKED, blocked.micAccess)
        assertEquals(MicAccessState.BLOCKED, fixture.controller.state)
        assertNull(blocked.leaseRootWatchdogArmed)
    }

    /** A failed revoke only loses the optimisation: `cancel` still runs the script itself. */
    @Test
    fun `failed early revoke falls back to the unchanged cancel path`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        assertTrue(
            fixture.coordinator.execute(
                MicCoordinator.ENDPOINT_TOGGLE,
                "request-root-revoke-fail-open-01",
            ).ok,
        )
        fixture.time += 1_000L
        fixture.events.clear()
        fixture.lease.revokeSucceeds = false

        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-root-revoke-fail-block-02",
        )

        assertEquals(listOf("revoke", "block", "cancel"), fixture.events)
        assertEquals(1, fixture.lease.revokeCalls)
        assertEquals(listOf(false), fixture.lease.cancelRootLeaseAlreadyRevoked)
        assertTrue(blocked.ok)
        assertEquals(MicAccessState.BLOCKED, blocked.micAccess)
        assertNull(blocked.leaseRootWatchdogArmed)
    }

    /**
     * A verified revoke never retires the lease on its own. If the controller block afterwards
     * cannot be read back, the durable lease, the alarms and the in-memory lease fields all
     * survive, exactly as they do for any other failed block.
     */
    @Test
    fun `revoked root lease survives a controller block that fails readback`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.BLOCKED,
            controllerId = "audio_manager",
        )
        fixture.lease.rootWatchdogArmed = true
        val opened = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-root-revoke-keep-open-01",
        )
        assertTrue(opened.ok)
        fixture.time += 1_000L
        fixture.events.clear()
        fixture.controller.blockResultState = MicAccessState.OPEN

        val blocked = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_TOGGLE,
            "request-root-revoke-keep-block-02",
        )

        assertEquals(listOf("revoke", "block"), fixture.events)
        assertEquals(1, fixture.lease.revokeCalls)
        assertEquals(0, fixture.lease.cancelCount)
        assertFalse(blocked.ok)
        assertEquals(MicAccessState.OPEN, blocked.micAccess)
        assertEquals(true, blocked.leaseRootWatchdogArmed)
        assertEquals(true, blocked.leaseExactAlarmArmed)
        // A BLOCK operation never reports an auto-block deadline, so the durable lease itself is
        // the observable proof that the guard survived the failed block.
        assertEquals(
            "request-root-revoke-keep-open-01",
            fixture.lease.activeLease?.requestId,
        )
    }

    @Test
    fun `block without an active lease never runs the root revoke script`() = runTest {
        val fixture = Fixture(
            initialState = MicAccessState.OPEN,
            controllerId = "audio_manager",
        )

        val result = fixture.coordinator.initialize()

        assertEquals(MicAccessState.BLOCKED, result.observed)
        assertEquals(listOf("block"), fixture.events)
        assertEquals(0, fixture.lease.revokeCalls)
        assertEquals(0, fixture.lease.cancelCount)
    }

    @Test
    fun `remote latency covers lease setup and the complete serialized operation`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.BLOCKED)
        fixture.operationNanos = 1_000_000L
        fixture.lease.onArm = { fixture.operationNanos += 7_000_000L }
        fixture.controller.onOpen = { fixture.operationNanos += 5_000_000L }

        val result = fixture.coordinator.execute(
            MicCoordinator.ENDPOINT_OPEN,
            "request-full-latency-01",
        )

        assertEquals(12L, result.latencyMs)
    }

    @Test
    fun `startup resolves interrupted ledger entry by blocking`() = runTest {
        val fixture = Fixture(initialState = MicAccessState.OPEN)
        fixture.ledger.begin("request-crashed-01", MicCoordinator.ENDPOINT_TOGGLE, 1, 0)

        fixture.coordinator.initialize()

        val entry = fixture.ledger.entries.getValue("request-crashed-01")
        assertEquals(RequestLedger.STATUS_COMPLETE, entry.status)
        assertEquals(MicAccessState.BLOCKED, entry.outcome)
        assertEquals(false, entry.ok)
    }

    private class Fixture(
        initialState: MicAccessState,
        calibrated: Boolean = true,
        controllerId: String = "fake",
        persistentRemoteOpen: Boolean = false,
        failSnapshotWhen: (BridgeSnapshot) -> Boolean = { false },
    ) {
        var time = 0L
        var operationNanos = 0L
        var remoteOpenAllowed = true
        var openAllowed = true
        var calibratedState = calibrated
        /** Ordered record of the controller and lease side effects of one operation. */
        val events = mutableListOf<String>()
        val controller = FakeController(initialState, controllerId, events)
        val ledger = MemoryLedger()
        val lease = FakeLease({ time }, events)
        val snapshots = mutableListOf<BridgeSnapshot>()
        val coordinator = MicCoordinator(
            controller = controller,
            ledger = ledger,
            leaseSafety = lease,
            tokenGeneration = { 1 },
            maxOpenSeconds = { 30 },
            calibrationValid = { calibratedState },
            persistentRemoteOpen = { persistentRemoteOpen },
            openAllowed = { openAllowed },
            remoteOpenAllowed = { _: Long -> remoteOpenAllowed },
            nowEpochMs = { time },
            elapsedRealtimeMs = { time },
            nanoTime = { operationNanos },
            onSnapshot = { snapshot ->
                if (failSnapshotWhen(snapshot)) error("snapshot failure")
                snapshots += snapshot
            },
        )
    }

    private class FakeController(
        initialState: MicAccessState,
        override val id: String = "fake",
        private val events: MutableList<String> = mutableListOf(),
    ) : MicController {
        override val displayName = "Fake"
        var state = initialState
        var blockResultState = MicAccessState.BLOCKED
        var openResultState = MicAccessState.OPEN
        var openErrorCode: String? = null
        var blockCount = 0
        var openCount = 0
        var beforeOpenMutation: suspend () -> Unit = {}
        var onOpen: suspend () -> Unit = {}
        val defaultTarget = SafetyTarget(id, "com.openai.chatgpt", 0)
        val blockTargets = mutableListOf<SafetyTarget?>()
        val openTargets = mutableListOf<SafetyTarget>()

        override suspend fun probe() = ProbeResult(true, true)
        override suspend fun captureSafetyTarget() = defaultTarget

        override suspend fun block(target: SafetyTarget?): ControlResult {
            blockCount++
            events += "block"
            blockTargets += target
            state = blockResultState
            return result(MicAccessState.BLOCKED, state)
        }

        override suspend fun open(
            target: SafetyTarget,
            authorization: OpenAuthorization,
        ): ControlResult {
            openCount++
            events += "open"
            beforeOpenMutation()
            openTargets += target
            state = openResultState
            onOpen()
            return result(MicAccessState.OPEN, state).copy(errorCode = openErrorCode)
        }

        override suspend fun readState(target: SafetyTarget?): MicAccessState = state

        private fun result(requested: MicAccessState, observed: MicAccessState) = ControlResult(
            requested = requested,
            observed = observed,
            controlReadback = requested == observed,
            durationMs = 1,
            errorCode = if (requested == observed) null else "FAILED",
        )
    }

    private class FakeLease(
        private val now: () -> Long,
        private val events: MutableList<String> = mutableListOf(),
    ) : LeaseSafety {
        var armSucceeds = true
        var persistLeaseOnFailedArm = true
        var failCancelWhenNoLease = false
        var armCount = 0
        var persistentArmCount = 0
        var rootWatchdogArmed = false
        var guardHealthy = true
        var guardVerificationCount = 0
        var cancelCount = 0
        var revokeSucceeds = true
        var revokeCalls = 0
        val cancelRootLeaseAlreadyRevoked = mutableListOf<Boolean>()
        var activeLease: ActiveSafetyLease? = null
        var onArm: () -> Unit = {}
        var openValidDurationMs: Long? = null
        private val mutationMutex = Mutex()

        override suspend fun arm(
            requestId: String,
            durationSeconds: Int,
            target: SafetyTarget,
        ): LeaseArmResult {
            armCount++
            val deadline = now() + (openValidDurationMs ?: durationSeconds * 1_000L)
            onArm()
            if (armSucceeds || persistLeaseOnFailedArm) {
                activeLease = ActiveSafetyLease(
                    requestId = requestId,
                    target = target,
                    deadlineEpochMs = deadline,
                    deadlineElapsedRealtimeMs = deadline,
                    exactAlarmArmed = armSucceeds,
                    rootWatchdogArmed = rootWatchdogArmed,
                )
            }
            return LeaseArmResult(
                armed = armSucceeds,
                deadlineEpochMs = deadline,
                deadlineElapsedRealtimeMs = deadline,
                exactAlarmArmed = armSucceeds,
                rootWatchdogArmed = rootWatchdogArmed,
                error = if (armSucceeds) null else "not armed",
            )
        }

        override suspend fun armPersistent(
            requestId: String,
            target: SafetyTarget,
        ): LeaseArmResult {
            persistentArmCount++
            val deadline = 0L
            activeLease = ActiveSafetyLease(
                requestId = requestId,
                target = target,
                deadlineEpochMs = deadline,
                deadlineElapsedRealtimeMs = deadline,
                exactAlarmArmed = false,
                rootWatchdogArmed = rootWatchdogArmed,
                persistent = true,
            )
            return LeaseArmResult(
                armed = armSucceeds,
                deadlineEpochMs = deadline,
                deadlineElapsedRealtimeMs = deadline,
                exactAlarmArmed = false,
                rootWatchdogArmed = rootWatchdogArmed,
                error = if (armSucceeds) null else "not armed",
                persistent = true,
            )
        }

        override suspend fun revokeRootLeaseAfterVerifiedBlock(): Boolean {
            revokeCalls++
            events += "revoke"
            // Mirrors AutoBlockSafety: the script alone never clears the durable lease or the
            // alarms, so a failure here must leave the fake's state completely untouched.
            return revokeSucceeds && activeLease != null
        }

        override suspend fun cancel() = cancel(rootLeaseAlreadyRevoked = false)

        override suspend fun cancel(rootLeaseAlreadyRevoked: Boolean) {
            cancelCount++
            cancelRootLeaseAlreadyRevoked += rootLeaseAlreadyRevoked
            events += "cancel"
            if (failCancelWhenNoLease && activeLease == null) {
                error("No durable lease exists")
            }
            activeLease = null
        }

        override suspend fun loadActiveLease(): ActiveSafetyLease? = activeLease

        override suspend fun verifyActiveGuard(requestId: String): Boolean {
            guardVerificationCount++
            return guardHealthy && activeLease?.requestId == requestId
        }

        override suspend fun <T> withMutationLock(block: suspend () -> T): T =
            mutationMutex.withLock { block() }
    }

    private class MemoryLedger : IdempotencyStore {
        val entries = linkedMapOf<String, LedgerEntry>()

        override fun begin(
            requestId: String,
            endpoint: String,
            tokenGeneration: Int,
            nowEpochMs: Long,
        ): BeginRequestResult {
            val existing = entries[requestId]
            if (existing != null) {
                return if (existing.endpoint == endpoint && existing.tokenGeneration == tokenGeneration) {
                    BeginRequestResult.Existing(existing)
                } else BeginRequestResult.Conflict("conflict")
            }
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

        override fun recoverInProgress(): List<LedgerEntry> = entries.values.filter {
            it.status == RequestLedger.STATUS_IN_PROGRESS
        }
    }
}
