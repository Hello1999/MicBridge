package com.jack.micbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

data class FoundButton(val address: String, val name: String, val rssi: Int)

/** Scans only for devices advertising the MicBridge button service. */
@SuppressLint("MissingPermission")
class ButtonScanner(context: Context) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)
        ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    private var callback: ScanCallback? = null

    /** Returns false when Bluetooth is off or unavailable. */
    fun start(onFound: (FoundButton) -> Unit): Boolean {
        val le = scanner ?: return false
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "MicBridge 按钮"
                onFound(FoundButton(result.device.address, name, result.rssi))
            }
        }
        callback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ButtonProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return runCatching { le.startScan(listOf(filter), settings, cb) }.isSuccess
    }

    fun stop() {
        callback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        callback = null
    }
}
