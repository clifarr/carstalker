package com.carstalker.core

import com.carstalker.core.elm.Elm327Client
import com.carstalker.core.elm.ElmResponse
import com.carstalker.core.transport.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transport stub that replays canned replies and records what was written. */
private class FakeTransport(private val reply: (String) -> String?) : Transport {

    private val pending = ArrayDeque<ByteArray>()
    val written = mutableListOf<String>()

    override val displayName: String = "fake"

    override suspend fun connect() = Unit

    fun preload(text: String) {
        pending.addLast(text.toByteArray(Charsets.US_ASCII))
    }

    override suspend fun write(data: ByteArray) {
        val command = String(data, Charsets.US_ASCII).trim()
        written += command
        reply(command)?.let { pending.addLast(it.toByteArray(Charsets.US_ASCII)) }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        if (pending.isEmpty()) ByteArray(0) else pending.removeFirst()

    override suspend fun discardPending() {
        pending.clear()
    }

    override suspend fun close() = Unit
}

class Elm327ClientTest {

    @Test
    fun `stale bytes from a previous exchange are discarded`() {
        val transport = FakeTransport { command ->
            if (command == "010C") "41 0C 1A F8\r\r>" else null
        }
        // Leftover speed reply sitting in the buffer, as happens after a timeout.
        transport.preload("41 0D 32\r\r>")
        val client = Elm327Client(transport)

        val response = runBlockingCommand(client, "010C")

        assertEquals(ElmResponse.Status.DATA, response.status)
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.payloads.single())
    }

    @Test
    fun `a reply that arrives after a timeout does not shift every later response`() {
        val transport = FakeTransport { command ->
            when (command) {
                "010C" -> "41 0C 1A F8\r\r>"
                else -> null
            }
        }
        val client = Elm327Client(transport)

        // First command gets no answer in time.
        val timedOut = runBlockingCommand(client, "0105", timeoutMs = 60)
        assertEquals(ElmResponse.Status.EMPTY, timedOut.status)

        // The ECU answers late; that stale frame must not be returned for the next request.
        transport.preload("41 05 7B\r\r>")
        val response = runBlockingCommand(client, "010C")

        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.payloads.single())
    }

    @Test
    fun `initialise pins the detected protocol and configures CAN`() {
        val transport = FakeTransport { command ->
            when {
                command == "ATDPN" -> "A6\r\r>"
                command == "ATI" -> "ELM327 v1.5\r\r>"
                command == "ATRV" -> "13.9V\r\r>"
                else -> "OK\r\r>"
            }
        }
        val info = kotlinx.coroutines.runBlocking { Elm327Client(transport).initialize() }

        assertEquals("ELM327 v1.5", info.identifier)
        assertEquals("ISO 15765-4 CAN (11-bit, 500k) (auto)", info.protocolName)
        assertTrue("protocol not pinned", "ATSP6" in transport.written)
        assertTrue("CAN auto formatting missing", "ATCAF1" in transport.written)
        assertTrue("CAN flow control missing", "ATCFC1" in transport.written)
        assertTrue("400 ms response window missing", "ATST 64" in transport.written)
        assertFalse("ATAR is for 29-bit only", "ATAR" in transport.written)
    }

    @Test
    fun `29-bit CAN enables auto receive address and no CAN setup goes to an ISO bus`() {
        val can = FakeTransport { command ->
            when {
                command == "ATDPN" -> "A7\r\r>"
                command == "0100" -> "41 00 BE 1F A8 13\r\r>"
                else -> "OK\r\r>"
            }
        }
        kotlinx.coroutines.runBlocking { Elm327Client(can).initialize() }
        assertTrue("ATAR missing for 29-bit CAN", "ATAR" in can.written)
        assertTrue("CAN formatting missing", "ATCAF1" in can.written)

        val iso = FakeTransport { command ->
            when {
                command == "ATDPN" -> "A3\r\r>"
                command == "0100" -> "41 00 BE 1F A8 13\r\r>"
                else -> "OK\r\r>"
            }
        }
        kotlinx.coroutines.runBlocking { Elm327Client(iso).initialize() }
        assertFalse("CAN setup sent to an ISO bus", "ATCAF1" in iso.written)
        assertTrue("400 ms window missing", "ATST 64" in iso.written)
    }

    @Test
    fun `falls back to automatic protocol when the pinned bus stops answering`() {
        var pinned = false
        val transport = FakeTransport { command ->
            when {
                command == "ATDPN" -> "A6\r\r>"
                command.startsWith("ATSP6") -> { pinned = true; "OK\r\r>" }
                // The forced protocol goes silent, as some clones do.
                command == "0100" && pinned -> "NO DATA\r\r>"
                command == "ATSP0" -> { pinned = false; "OK\r\r>" }
                command == "0100" -> "41 00 BE 1F A8 13\r\r>"
                else -> "OK\r\r>"
            }
        }
        kotlinx.coroutines.runBlocking { Elm327Client(transport).initialize() }

        assertTrue("should have tried to pin", "ATSP6" in transport.written)
        assertTrue("should have fallen back", "ATSP0" in transport.written)
    }

    private fun runBlockingCommand(
        client: Elm327Client,
        command: String,
        timeoutMs: Long = 500,
    ): ElmResponse = kotlinx.coroutines.runBlocking { client.command(command, timeoutMs) }
}
