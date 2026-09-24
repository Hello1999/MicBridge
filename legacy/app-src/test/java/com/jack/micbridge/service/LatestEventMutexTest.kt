package com.jack.micbridge.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestEventMutexTest {
    @Test
    fun `event made stale before lock never runs its side effect`() = runTest {
        val gate = LatestEventMutex()
        val stale = gate.advance()
        val current = gate.advance()
        var staleRan = false
        var currentRan = false

        val staleAccepted = gate.runIfCurrent(stale) { staleRan = true }
        val currentAccepted = gate.runIfCurrent(current) { currentRan = true }

        assertFalse(staleAccepted)
        assertFalse(staleRan)
        assertTrue(currentAccepted)
        assertTrue(currentRan)
    }

    @Test
    fun `new event invalidates running event before it may publish or rebind`() = runTest {
        val gate = LatestEventMutex()
        val first = gate.advance()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()

        val oldWork = async {
            gate.runIfCurrent(first) {
                effects += "first-block"
                firstEntered.complete(Unit)
                releaseFirst.await()
                gate.commitIfCurrent(first) { effects += "first-rebind" }
            }
        }
        firstEntered.await()
        val second = gate.advance()
        val newWork = async {
            gate.runIfCurrent(second) {
                effects += "second-block"
                if (gate.isCurrent(second)) effects += "second-rebind"
            }
        }
        releaseFirst.complete(Unit)

        assertTrue(oldWork.await())
        assertTrue(newWork.await())
        assertEquals(
            listOf("first-block", "second-block", "second-rebind"),
            effects,
        )
    }

    @Test
    fun `final commit from an obsolete event is rejected after synchronous revoke`() {
        val gate = LatestEventMutex()
        val first = gate.advance()
        val effects = mutableListOf<String>()
        val second = gate.advance { effects += "second-revoke" }

        val oldCommitted = gate.commitIfCurrent(first) { effects += "first-rebind" }
        val newCommitted = gate.commitIfCurrent(second) { effects += "second-rebind" }

        assertFalse(oldCommitted)
        assertTrue(newCommitted)
        assertEquals(listOf("second-revoke", "second-rebind"), effects)
    }

    @Test
    fun `only current server failure advances generation and revokes`() {
        val gate = LatestEventMutex()
        val current = gate.advance()
        var activeServer = 12L
        var revokeCount = 0

        val staleFailure = gate.advanceIf(
            condition = { activeServer == 11L },
            action = { revokeCount++ },
        )
        assertNull(staleFailure)
        assertTrue(gate.isCurrent(current))
        assertEquals(0, revokeCount)

        val currentFailure = gate.advanceIf(
            condition = { activeServer == 12L },
            action = {
                activeServer = -1L
                revokeCount++
            },
        )
        assertEquals(current + 1L, currentFailure)
        assertFalse(gate.isCurrent(current))
        assertEquals(-1L, activeServer)
        assertEquals(1, revokeCount)
    }
}
