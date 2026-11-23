package com.carstalker.app.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.carstalker.core.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Generic BLE transport for ELM327 adapters.
 *
 * Prefers the ubiquitous FFE0/FFE1 serial service, but falls back to the first writable
 * characteristic and the first notifiable one, which covers most clone adapters.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport {

    override val displayName: String = device.name ?: device.address

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeContinuation: CompletableDeferred<Unit>? = null
    private var mtu = 23

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("BLE connection failed"))
                ready.completeExceptionally(IllegalStateException("Adapter disconnected"))
                incoming.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IllegalStateException("Service discovery failed"))
                return
            }
            val services = gatt.services
            val characteristics = services.flatMap { it.characteristics }
            val known = services.firstOrNull { it.uuid == UUID_FFE0 }?.getCharacteristic(UUID_FFE1)
            val notifier: BluetoothGattCharacteristic? = known
                ?: characteristics.firstOrNull { it.uuid == UUID_FFE1 }
                ?: characteristics.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                }
            val writer: BluetoothGattCharacteristic? = known
                ?: characteristics.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }

            if (writer == null || notifier == null) {
                ready.completeExceptionally(IllegalStateException("Adapter has no serial characteristic"))
                return
            }
            writeCharacteristic = writer
            notifyCharacteristic = notifier
            gatt.setCharacteristicNotification(notifier, true)

            val descriptor = notifier.getDescriptor(UUID_CCCD)
            if (descriptor != null) {
                writeDescriptor(gatt, descriptor)
            } else {
                ready.complete(Unit)
            }
            runCatching { gatt.requestMtu(517) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleTransport.mtu = mtu
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { incoming.trySend(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.trySend(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val continuation = writeContinuation
            writeContinuation = null
            if (status == BluetoothGatt.GATT_SUCCESS) continuation?.complete(Unit)
            else continuation?.completeExceptionally(IllegalStateException("BLE write failed ($status)"))
        }
    }

    override suspend fun connect() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: error("Bluetooth is not available")
        if (!adapter.isEnabled) error("Bluetooth is turned off")
        gatt = adapter.getRemoteDevice(device.address).connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
        )
        withTimeout(20_000) { connected.await() }
        withTimeout(20_000) { ready.await() }
    }

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            val gatt = gatt ?: error("Not connected")
            val characteristic = writeCharacteristic ?: error("No write characteristic")
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            for (chunk in data.toList().chunked(chunkSize)) {
                val bytes = chunk.toByteArray()
                val continuation = CompletableDeferred<Unit>()
                writeContinuation = continuation
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                if (!started) {
                    writeContinuation = null
                    error("Could not start BLE write")
                }
                withTimeout(3_000) { continuation.await() }
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)

    override suspend fun discardPending() {
        while (incoming.tryReceive().isSuccess) Unit
    }

    override suspend fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        incoming.close()
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (t: Throwable) {
            Log.w("BleTransport", "Could not enable notifications", t)
            ready.complete(Unit)
        }
    }

    companion object {
        val UUID_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val UUID_FFE1: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
