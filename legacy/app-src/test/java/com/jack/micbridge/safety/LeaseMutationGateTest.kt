package com.jack.micbridge.safety

import com.jack.micbridge.data.SafetyTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaseMutationGateTest {
    private val oldTarget = SafetyTarget("root_sensor_privacy", "android-global-microphone", 0)

    @Test
    fun `stale alarm cannot block a replacement lease`() = runTest {
        val current = lease("new-request-generation", oldTarget)
        var blockCalls = 0

        val handled = handleCurrentLeaseExpiry(
            expectedRequestId = "old-request-generation",
            expectedTarget = oldTarget,
            loadLease = { current },
            block = {
                blockCalls++
                true
            },
            gate = LeaseMutationGate(),
        )

        assertEquals(LeaseExpiryResult.STALE, handled)
        assertEquals(0, blockCalls)
    }

    @Test
    fun `delivered old alarm and rearm are serialized through the block side effect`() = runTest {
        val gate = LeaseMutationGate()
        val newTarget = oldTarget.copy(userId = 10)
        var current = lease("old-request-generation", oldTarget)
        val blockEntered = CompletableDeferred<Unit>()
        val finishBlock = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val expiry = launch {
            handleCurrentLeaseExpiry(
                expectedRequestId = current.requestId,
                expectedTarget = oldTarget,
                loadLease = { current },
                block = {
                    blockEntered.complete(Unit)
                    finishBlock.await()
                    events += "old-block"
                    true
                },
                gate = gate,
            )
        }
        blockEntered.await()
        val rearmAndOpen = launch {
            gate.withLock {
                current = lease("new-request-generation", newTarget)
                events += "new-arm"
            }
            events += "new-open"
        }
        runCurrent()
        assertFalse(rearmAndOpen.isCompleted)

        finishBlock.complete(Unit)
        joinAll(expiry, rearmAndOpen)

        assertTrue(expiry.isCompleted)
        assertEquals(listOf("old-block", "new-arm", "new-open"), events)
    }

    private fun lease(requestId: String, target: SafetyTarget) = ActiveSafetyLease(
        requestId = requestId,
        target = target,
        deadlineEpochMs = 30_000L,
        deadlineElapsedRealtimeMs = 30_000L,
        exactAlarmArmed = true,
        rootWatchdogArmed = false,
    )
}
