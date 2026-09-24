package com.jack.micbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Keeps one GATT connection to the saved ESP32 button and reports its state packets.
 * The first attempt connects directly (fast); after that it reconnects with autoConnect so
 * the phone waits for the button in the background at low power.
 */
@SuppressLint("MissingPermission")
class ButtonLink(
    private val context: Context,
    private val address: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(ready: Boolean)
        fun onPacket(packet: ButtonProtocol.Packet)
        fun onProblem(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private var hasConnectedOnce = false
    private var retryDelayMs = 1_000L

    fun start() {
        connect(direct = true)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    private fun connect(direct: Boolean) {
        if (closed) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onProblem("蓝牙未开启")
            scheduleReconnect()
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            listener.onProblem("按钮地址无效，请重新搜索")
            return
        }
        gatt = device.connectGatt(context, !direct, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (closed) return
        handler.postDelayed({ connect(direct = !hasConnectedOnce) }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    hasConnectedOnce = true
                    retryDelayMs = 1_000L
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    listener.onReady(false)
                    g.close()
                    if (gatt === g) gatt = null
                    // A clean autoConnect drop re-arms immediately; errors back off.
                    if (status == BluetoothGatt.GATT_SUCCESS && hasConnectedOnce) {
                        connect(direct = false)
                    } else {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val characteristic = g.getService(ButtonProtocol.SERVICE_UUID)
                    ?.getCharacteristic(ButtonProtocol.STATE_UUID)
                if (characteristic == null) {
                    listener.onProblem("该设备不是 MicBridge 按钮固件")
                    g.disconnect()
                    return@post
                }
                g.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(ButtonProtocol.CCCD_UUID)
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (cccd == null) {
                    listener.onReady(true)
                    g.readCharacteristic(characteristic)
                } else if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, value)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onProblem("订阅按钮通知失败（$status）")
                    g.disconnect()
                    return@post
                }
                listener.onReady(true)
                // Pick up the current state in case the button is already held.
                g.readCharacteristic(descriptor.characteristic)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) deliver(value)
        }

        @Deprecated("Pre-33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) deliver(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = deliver(value)

        @Deprecated("Pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) deliver(characteristic.value)
        }
    }

    // Packets go straight to the listener off the binder thread: this is the latency path.
    private fun deliver(value: ByteArray?) {
        val packet = ButtonProtocol.parse(value) ?: return
        if (!closed) listener.onPacket(packet)
    }
}
