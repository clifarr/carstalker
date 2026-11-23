package com.carstalker.app.transport.classic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.carstalker.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM/SPP) transport for ELM327 dongles.
 *
 * Most cheap OBD-II dongles are BR/EDR with the Serial Port Profile rather than BLE,
 * so they never show up in a BLE scan. This talks to them over the standard SPP UUID.
 */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val secure: Boolean = true,
) : Transport {

    override val displayName: String = device.name ?: device.address

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: error("Bluetooth is not available")
        if (!adapter.isEnabled) error("Bluetooth is turned off")

        val remote = adapter.getRemoteDevice(device.address)
        val socket = openSocket(remote)
        this@ClassicTransport.socket = socket
        input = socket.inputStream
        output = socket.outputStream
        // Give the dongle a moment to settle after RFCOMM setup.
        Thread.sleep(120)
    }

    private fun openSocket(remote: BluetoothDevice): BluetoothSocket {
        val attempts = if (secure) {
            listOf(true, false)
        } else {
            listOf(false, true)
        }
        var lastError: Throwable? = null
        for (useSecure in attempts) {
            val candidate = try {
                remote.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            try {
                runCatching { candidate.connect() }.getOrElse { throw it }
                return candidate
            } catch (t: Throwable) {
                lastError = t
                runCatching { candidate.close() }
            }
        }
        throw lastError ?: IllegalStateException("Could not open the Bluetooth serial socket")
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val output = output ?: error("Not connected")
        output.write(data)
        output.flush()
    }

    /**
     * RFCOMM sockets have no read timeout, so poll [InputStream.available] until the deadline.
     * This keeps the ELM handshake from hanging on a silent adapter.
     */
    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val input = input ?: return@withContext ByteArray(0)
        val buffer = ByteArray(1024)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val available = runCatching { input.available() }.getOrDefault(0)
            if (available > 0) {
                val read = runCatching { input.read(buffer, 0, minOf(available, buffer.size)) }.getOrDefault(0)
                return@withContext if (read > 0) buffer.copyOf(read) else ByteArray(0)
            }
            if (!isOpen()) return@withContext ByteArray(0)
            Thread.sleep(4)
        }
        ByteArray(0)
    }

    private fun isOpen(): Boolean = runCatching { socket?.isConnected == true }.getOrDefault(false)

    override suspend fun discardPending() = withContext(Dispatchers.IO) {
        val input = input ?: return@withContext
        val buffer = ByteArray(256)
        // Drain whatever the kernel already has; never block waiting for more.
        repeat(64) {
            val available = runCatching { input.available() }.getOrDefault(0)
            if (available <= 0) return@repeat
            val read = runCatching { input.read(buffer, 0, minOf(available, buffer.size)) }.getOrDefault(-1)
            if (read <= 0) return@withContext
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** True when the device advertises the Serial Port Profile. */
        fun isSerialDevice(device: BluetoothDevice): Boolean = runCatching {
            device.uuids?.any { it.uuid == SPP_UUID } == true
        }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        fun bondedDevices(context: Context): List<BluetoothDevice> {
            val manager = context.getSystemService(BluetoothManager::class.java)
            return runCatching { manager.adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()
        }
    }
}
