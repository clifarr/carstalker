package com.carstalker.app.transport.classic

import com.carstalker.app.transport.ble.BleAdapterNames

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ClassicDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val device: BluetoothDevice,
)

/**
 * Enumerates Bluetooth Classic devices: bonded devices first, then a discovery scan.
 * Discovery never connects; the user always chooses.
 */
@SuppressLint("MissingPermission")
class ClassicScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<ClassicDevice>>(emptyList())
    val devices: StateFlow<List<ClassicDevice>> = _devices

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering

    private val found = linkedMapOf<String, ClassicDevice>()
    private var receiver: BroadcastReceiver? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.parcelableDevice() ?: return
                    add(device, bonded = false)
                }
                android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> stop()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.parcelableDevice() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    add(device, bonded = state == BluetoothDevice.BOND_BONDED)
                }
            }
        }
    }

    fun refreshBonded() {
        found.clear()
        ClassicTransport.bondedDevices(context).forEach { add(it, bonded = true) }
        publish()
    }

    fun startDiscovery() {
        refreshBonded()
        register()
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        runCatching {
            adapter.cancelDiscovery()
            adapter.startDiscovery()
            _discovering.value = true
        }
    }

    fun stop() {
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        runCatching { manager.adapter?.cancelDiscovery() }
        _discovering.value = false
        unregister()
    }

    private fun add(device: BluetoothDevice, bonded: Boolean) {
        val name = runCatching { device.name }.getOrNull() ?: return
        if (name.isBlank()) return
        // Only serial-profile devices whose names aren't clearly something else, or known adapter names.
        val hasSerial = ClassicTransport.isSerialDevice(device)
        if (!BleAdapterNames.looksLikeAdapter(name) && !hasSerial) return
        if (!BleAdapterNames.looksLikeAdapter(name) && BleAdapterNames.isExcluded(name)) return
        val existing = found[device.address]
        found[device.address] = ClassicDevice(
            name = name,
            address = device.address,
            bonded = bonded || existing?.bonded == true,
            device = device,
        )
        publish()
    }

    private fun publish() {
        _devices.value = found.values.sortedWith(
            compareByDescending<ClassicDevice> { BleAdapterNames.looksLikeAdapter(it.name) }
                .thenByDescending { it.bonded }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun register() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        val newReceiver = broadcastReceiver
        ContextCompat.registerReceiver(context, newReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = newReceiver
    }

    private fun unregister() {
        val current = receiver ?: return
        runCatching { context.unregisterReceiver(current) }
        receiver = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
