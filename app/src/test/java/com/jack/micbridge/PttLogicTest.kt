package com.jack.micbridge

import com.jack.micbridge.ble.ButtonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttLogicTest {
    @Test
    fun parsesPressedAndReleasedPackets() {
        assertEquals(ButtonProtocol.Packet(true, 7), ButtonProtocol.parse(byteArrayOf(1, 7)))
        assertEquals(ButtonProtocol.Packet(false, 255), ButtonProtocol.parse(byteArrayOf(0, -1)))
        assertEquals(ButtonProtocol.Packet(true, -1), ButtonProtocol.parse(byteArrayOf(1)))
    }

    @Test
    fun rejectsMalformedPackets() {
        assertNull(ButtonProtocol.parse(null))
        assertNull(ButtonProtocol.parse(byteArrayOf()))
        assertNull(ButtonProtocol.parse(byteArrayOf(2, 0)))
    }

    @Test
    fun mutedByDefault() {
        assertFalse(PttPolicy.micShouldBeOpen(PttInputs(), nowMs = 10_000))
    }

    @Test
    fun heldButtonWithFreshHeartbeatOpensMic() {
        val inputs = PttInputs(linkReady = true, blePressed = true, lastPacketAtMs = 10_000)
        assertTrue(PttPolicy.micShouldBeOpen(inputs, nowMs = 10_000 + ButtonProtocol.PRESS_TIMEOUT_MS))
    }

    @Test
    fun staleHeartbeatMutes() {
        val inputs = PttInputs(linkReady = true, blePressed = true, lastPacketAtMs = 10_000)
        assertFalse(PttPolicy.micShouldBeOpen(inputs, nowMs = 10_001 + ButtonProtocol.PRESS_TIMEOUT_MS))
    }

    @Test
    fun disconnectedButtonMutesEvenIfLastPacketWasPressed() {
        val inputs = PttInputs(linkReady = false, blePressed = true, lastPacketAtMs = 10_000)
        assertFalse(PttPolicy.micShouldBeOpen(inputs, nowMs = 10_000))
    }

    @Test
    fun screenButtonOpensWithoutHardware() {
        assertTrue(PttPolicy.micShouldBeOpen(PttInputs(screenPressed = true), nowMs = 0))
    }
}
