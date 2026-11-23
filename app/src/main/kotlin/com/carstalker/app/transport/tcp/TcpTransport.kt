package com.carstalker.app.transport.tcp

import com.carstalker.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** TCP transport for Wi-Fi ELM327 adapters (usually port 35000). */
class TcpTransport(
    private val host: String,
    private val port: Int,
) : Transport {

    override val displayName: String = "$host:$port"

    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // Wi-Fi adapters can be slow to answer the first command after association.
        socket.connect(InetSocketAddress(host, port), 10_000)
        socket.soTimeout = 250
        this@TcpTransport.socket = socket
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val socket = socket ?: return@withContext ByteArray(0)
        socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        val buffer = ByteArray(1024)
        try {
            val read = socket.getInputStream().read(buffer)
            if (read > 0) buffer.copyOf(read) else ByteArray(0)
        } catch (_: SocketTimeoutException) {
            ByteArray(0)
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val socket = socket ?: error("Not connected")
        socket.getOutputStream().write(data)
        socket.getOutputStream().flush()
    }

    override suspend fun discardPending() = withContext(Dispatchers.IO) {
        val socket = socket ?: return@withContext
        socket.soTimeout = 1
        val buffer = ByteArray(256)
        repeat(64) {
            val read = runCatching { socket.getInputStream().read(buffer) }.getOrDefault(-1)
            if (read <= 0) return@withContext
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }
}
