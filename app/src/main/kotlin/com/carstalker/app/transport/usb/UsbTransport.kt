package com.carstalker.app.transport.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.carstalker.core.transport.Transport
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** USB serial transport for FTDI, CH340, CP210x, PL2303 and CDC-ACM ELM327 cables. */
class UsbTransport(
    private val context: Context,
    private val device: UsbDevice,
    private val baudRate: Int = 38_400,
) : Transport {

    override val displayName: String = device.productName ?: device.deviceName

    private var port: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(UsbManager::class.java)
        if (!manager.hasPermission(device)) error("USB permission not granted")
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: error("No USB serial driver for this device")
        val opened = manager.openDevice(device) ?: error("Could not open USB device")
        connection = opened
        val serialPort = driver.ports.first()
        serialPort.open(opened)
        serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching {
            serialPort.dtr = true
            serialPort.rts = true
        }
        port = serialPort
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val port = port ?: error("Not connected")
        port.write(data, 2_000)
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val port = port ?: return@withContext ByteArray(0)
        val buffer = ByteArray(1024)
        val read = runCatching { port.read(buffer, timeoutMs.toInt().coerceAtLeast(1)) }.getOrDefault(0)
        if (read > 0) buffer.copyOf(read) else ByteArray(0)
    }

    override suspend fun discardPending() = withContext(Dispatchers.IO) {
        val port = port ?: return@withContext
        val buffer = ByteArray(256)
        repeat(64) {
            // A 1 ms timeout probes without blocking; 0 would wait forever on a bulk endpoint.
            val read = runCatching { port.read(buffer, 1) }.getOrDefault(0)
            if (read <= 0) return@withContext
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
    }

    companion object {
        fun availableDevices(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(UsbManager::class.java)
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            return drivers.map { it.device }
        }

        fun hasPermission(context: Context, device: UsbDevice): Boolean =
            context.getSystemService(UsbManager::class.java).hasPermission(device)

        fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
            val manager = context.getSystemService(UsbManager::class.java)
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: android.content.Intent) {
                    if (intent.action != action) return
                    runCatching { receiverContext.unregisterReceiver(this) }
                    onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(action),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                android.content.Intent(action).setPackage(context.packageName),
                flags,
            )
            manager.requestPermission(device, pending)
        }
    }
}
