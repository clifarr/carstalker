package com.carstalker.core

import com.carstalker.core.elm.Elm327Client
import com.carstalker.core.elm.ObdClient
import com.carstalker.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ReplyTransport(private val replies: Map<String, String>) : Transport {
    private val pending = ArrayDeque<ByteArray>()

    override val displayName: String = "reply"

    override suspend fun connect() = Unit

    override suspend fun write(data: ByteArray) {
        val command = String(data, Charsets.US_ASCII).trim()
        val reply = replies[command] ?: "NO DATA\r\r>"
        pending.addLast(reply.toByteArray(Charsets.US_ASCII))
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        if (pending.isEmpty()) ByteArray(0) else pending.removeFirst()

    override suspend fun discardPending() = pending.clear()

    override suspend fun close() = Unit
}

class Mode06ScanTest {

    @Test
    fun `scans dtcs from every responding ecu`() {
        val transport = ReplyTransport(
            mapOf(
                "03" to "7E8 07 43 01 71 03 01 00 00\r7E9 03 43 00 00\r\r>",
                "07" to "7E8 04 47 01 71 00\r\r>",
                "0A" to "7E8 03 4A 00 00\r\r>",
            ),
        )
        val modules = runBlocking { ObdClient(Elm327Client(transport)).readDtcScan() }

        assertEquals(2, modules.size)
        assertEquals("Engine", modules[0].name)
        assertEquals(listOf("P0171", "P0301"), modules[0].stored.map { it.code })
        assertEquals(listOf("P0171"), modules[0].pending.map { it.code })
        assertTrue(modules[0].permanent.isEmpty())
        assertEquals("ECU 0x7E9", modules[1].name)
        assertTrue(modules[1].isEmpty)
    }

    @Test
    fun `reads a freeze frame with its dtc and values`() {
        val transport = ReplyTransport(
            mapOf(
                "020200" to "42 02 00 03 01\r\r>",
                "020C00" to "42 0C 00 1A F8\r\r>",
                "020500" to "42 05 00 7B\r\r>",
                "020201" to "NO DATA\r\r>",
            ),
        )
        val frames = runBlocking {
            ObdClient(Elm327Client(transport)).readFreezeFrames(listOf(0x7E8), maxFrames = 2)
        }

        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals("P0301", frame.dtc)
        assertEquals(1726.0, frame.values[0x0C]!!, 0.001)
        assertEquals(83.0, frame.values[0x05]!!, 0.001)
    }

    @Test
    fun `scans supported mids and returns pass and fail results`() {
        val transport = ReplyTransport(
            mapOf(
                "0600" to "46 00 80 00 00 01\r\r>",
                "0620" to "46 20 80 00 80 81\r\r>",
                "0640" to "46 40 00 00 00 01\r\r>",
                "0660" to "46 60 00 00 00 01\r\r>",
                "0680" to "46 80 00 00 00 01\r\r>",
                "06A0" to "46 A0 80 00 00 00\r\r>",
                "06C0" to "46 C0 00 00 00 00\r\r>",
                "0601" to "46 01 01 0A 0B B0 0B B0 0B B0 01 05 10 00 48 00 00 00 64 01 0C 24 00 00 00 00 00 05\r\r>",
                "06A1" to "46 A1 0C 24 00 0A 00 00 00 05 A1 0B 24 00 02 00 00 00 64\r\r>",
            ),
        )
        val report = runBlocking { ObdClient(Elm327Client(transport)).readMode06() }

        assertEquals(5, report.results.size)
        assertEquals(1, report.failures.size)
        assertEquals(4, report.passes.size)
        assertTrue(report.supportedMids.contains(0xA1))
    }
}
