package com.carstalker.core.elm

import com.carstalker.core.transport.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Adapter details gathered during the ELM327 handshake. */
data class AdapterInfo(
    val identifier: String,
    val protocolName: String,
    val voltage: Double?,
    val description: String = "",
    val protocolCode: String = "",
    /** True when the adapter reports a firmware version that never existed (a common clone). */
    val isCloneSuspect: Boolean = false,
) {
}

/**
 * Serialized ELM327 command interface. Every command is a request/response exchange ending with
 * the `>` prompt; all access goes through a mutex so polling and one-off requests never interleave.
 */
class Elm327Client(private val transport: Transport) {

    private val mutex = Mutex()

    suspend fun initialize(): AdapterInfo = mutex.withLock {
        // A reset can take a moment on clones; give it room and let it settle.
        execute("ATZ", 6_000)
        delay(800)
        execute("ATE0", 2_000)   // echo off
        execute("ATL0", 2_000)   // linefeeds off
        execute("ATH0", 2_000)   // headers off
        execute("ATS1", 2_000)   // spaces on
        execute("ATSP0", 4_000)  // automatic protocol
        execute("ATAT1", 2_000)  // adaptive timing; ATAT2 tightens too aggressively for clones
        execute("ATAL", 2_000)   // allow long (>7 byte) responses

        // The adapter only picks a protocol once it has seen traffic, so ask for supported
        // PIDs first; without this ATDPN can still report "A0" and we would tune for the wrong bus.
        execute("0100", 6_000)

        val protocolCode = execute("ATDPN", 4_000).raw
            .replace(">", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()

        val protocol = ElmProtocols.describe(protocolCode)

        // Pin the protocol the adapter just found. Without this every reconnection pays for a
        // fresh search, and switching ECU addresses can restart it mid-poll.
        protocol.number?.let { number ->
            execute("ATSP$number", 2_000)
            if (protocol.isCan) {
                execute("ATCAF1", 2_000) // CAN auto formatting
                execute("ATCFC1", 2_000) // CAN flow control
                if (protocol.isExtendedCan) execute("ATAR", 2_000) // auto receive address
                // Headers on lets us attribute responses to the ECU that sent them. The parser
                // understands 11- and 29-bit CAN headers; legacy buses stay headerless.
                execute("ATH1", 2_000)
            }
        }

        // ATST hh sets the ECU response window to hh x 4 ms, so 0x64 = 400 ms.
        // Bluetooth Classic alone costs 100-200 ms per exchange on many clones, so a shorter
        // window makes every read fail intermittently.
        execute("ATST 64", 2_000)

        val identifier = firstLine(execute("ATI", 2_000).raw) ?: "ELM327"
        val description = firstLine(execute("AT@1", 2_000).raw).orEmpty()
        val protocolDescription = firstLine(execute("ATDP", 2_000).raw).orEmpty()
        val voltage = execute("ATRV", 2_000).raw.let { parseVoltage(it) }
        val cloneSuspect = CLONE_VERSION.containsMatchIn(identifier) || CLONE_VERSION.containsMatchIn(description)

        AdapterInfo(
            identifier = identifier,
            protocolName = protocolDescription.ifBlank { protocol.label },
            voltage = voltage,
            description = description,
            protocolCode = protocolCode,
            isCloneSuspect = cloneSuspect,
        )
    }

    private fun firstLine(raw: String): String? =
        raw.lines()
            .map { it.replace(">", "").trim() }
            .firstOrNull {
                it.isNotEmpty() &&
                    !it.equals("OK", ignoreCase = true) &&
                    it != "?" &&
                    !it.startsWith("ERROR", ignoreCase = true) &&
                    !it.startsWith("NO DATA", ignoreCase = true)
            }
            ?.takeIf { it.isNotEmpty() }

    suspend fun command(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResponse =
        mutex.withLock { execute(text, timeoutMs) }

    suspend fun queryPid(mode: Int, pid: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResponse =
        command(com.carstalker.core.util.Hex.toHex(mode) + com.carstalker.core.util.Hex.toHex(pid), timeoutMs)

    suspend fun close() = transport.close()

    private suspend fun execute(text: String, timeoutMs: Long): ElmResponse {
        // Any bytes already buffered belong to a previous exchange. Consuming them here is what
        // keeps responses aligned with requests; otherwise every read lags one command behind.
        transport.discardPending()
        transport.write((text + "\r").toByteArray(Charsets.US_ASCII))
        val raw = readUntilPrompt(timeoutMs)
        if (DEBUG) {
            trace("> $text  < ${raw.replace("\r", "|").replace("\n", "|").take(120)}")
        }
        return ElmResponseParser.parse(raw)
    }

    private suspend fun readUntilPrompt(timeoutMs: Long): String {
        val builder = StringBuilder()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val chunk = transport.read(remainingMs.coerceAtMost(100))
            if (chunk.isEmpty()) continue
            builder.append(String(chunk, Charsets.US_ASCII))
            if (builder.contains('>')) break
        }
        return builder.toString()
    }

    private fun parseVoltage(raw: String): Double? =
        Regex("(\\d{1,2}\\.\\d{1,2})").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L

        /** Raw command/response tracing, enabled for debug builds by the app layer. */
        var DEBUG: Boolean = false

        /** Sink for [DEBUG] output; the Android layer points this at logcat. */
        var trace: (String) -> Unit = {}

        /** ELM327 v1.5 never existed; clones commonly report it. */
        private val CLONE_VERSION = Regex("(?i)ELM327\\s*v?1\\.5")
    }
}
