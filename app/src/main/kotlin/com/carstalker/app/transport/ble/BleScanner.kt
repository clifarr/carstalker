package com.carstalker.app.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice,
)

/**
 * BLE scanner that surfaces named devices, sorted by signal strength.
 *
 * Scanning only happens when the user asks for it and stops automatically after
 * [SCAN_DURATION_MS]; nothing here ever opens a connection.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val seen = mutableMapOf<String, DiscoveredDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stop() }
    private var active = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
            val name = runCatching { device.name }.getOrNull() ?: advertisedName
            val services = result.scanRecord?.serviceUuids?.map { it.uuid }
            if (!BleAdapterNames.isCompatibleBle(name ?: "", services)) return
            val displayName = name?.takeIf { it.isNotBlank() } ?: "OBD adapter"
            seen[device.address] = DiscoveredDevice(displayName, device.address, result.rssi, device)
            publish()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
    }

    fun start() {
        if (active) return
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            _scanning.value = false
            return
        }
        seen.clear()
        publish()
        active = true
        _scanning.value = true
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, SCAN_DURATION_MS)
        runCatching {
            scanner.startScan(
                emptyList(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        }.onFailure {
            active = false
            _scanning.value = false
            handler.removeCallbacks(stopRunnable)
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        if (!active) {
            _scanning.value = false
            return
        }
        active = false
        _scanning.value = false
        val manager = context.getSystemService(BluetoothManager::class.java)
        runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun publish() {
        _devices.value = seen.values.sortedByDescending { it.rssi }
    }

    companion object {
        private const val SCAN_DURATION_MS = 15_000L
    }
}
