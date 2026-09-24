package com.jack.micbridge.ble

import java.util.UUID

/**
 * GATT contract shared with firmware/esp32c3_ptt. Keep the UUIDs and payload in sync.
 *
 * State characteristic (read + notify), payload `[state, seq]`:
 * - state: 0 = released, 1 = pressed
 * - seq: wrapping 0..255 counter, incremented on every notification
 * While pressed the firmware repeats the packet every [ButtonProtocol.HEARTBEAT_MS] so the
 * phone can close the mic if the button dies mid-press without waiting for BLE supervision.
 */
object ButtonProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8f1d0001-6b5c-4c3e-9a2e-5f3b7d9c0a11")
    val STATE_UUID: UUID = UUID.fromString("8f1d0002-6b5c-4c3e-9a2e-5f3b7d9c0a11")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEARTBEAT_MS = 400L
    const val PRESS_TIMEOUT_MS = 1_500L

    data class Packet(val pressed: Boolean, val seq: Int)

    fun parse(value: ByteArray?): Packet? {
        if (value == null || value.isEmpty()) return null
        val state = value[0].toInt() and 0xff
        if (state > 1) return null
        val seq = if (value.size > 1) value[1].toInt() and 0xff else -1
        return Packet(pressed = state == 1, seq = seq)
    }
}
