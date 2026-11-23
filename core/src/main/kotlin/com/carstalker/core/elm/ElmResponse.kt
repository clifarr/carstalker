package com.carstalker.core.elm

import com.carstalker.core.util.Hex

/**
 * One response payload, optionally attributed to the ECU that sent it.
 *
 * [source] is the CAN identifier when the adapter is configured with headers on
 * (for example `0x7E8` for an 11-bit engine response or `0x18DAF110` for 29-bit).
 * It is null for headerless protocols and for adapters that hide headers.
 */
data class EcuMessage(
    val source: Int?,
    val payload: IntArray,
) {
    fun payloadEquals(other: IntArray): Boolean = payload.contentEquals(other)
}

/**
 * Parsed ELM327 response.
 *
 * [messages] contains one payload per ECU response, with any 11/29-bit CAN header and the
 * ISO-TP length byte removed. Multi-frame replies emitted as `0:`, `1:`, ... are reassembled
 * per source address so two ECUs answering at once are never merged.
 */
data class ElmResponse(
    val raw: String,
    val messages: List<EcuMessage>,
    val status: Status,
) {
    enum class Status { DATA, OK, NO_DATA, ERROR, EMPTY }

    val isData: Boolean get() = status == Status.DATA

    /** Payloads without their source address, for callers that do not need ECU attribution. */
    val payloads: List<IntArray> get() = messages.map { it.payload }

    companion object {
        val EMPTY = ElmResponse("", emptyList(), Status.EMPTY)
    }
}

/**
 * Parses the raw text returned by an ELM327 up to (and including) the `>` prompt.
 *
 * Handles: echo, `OK`, `SEARCHING...`, `BUS INIT...`, `NO DATA`, `?`, `CAN ERROR`,
 * spaces on/off, headers on/off, and ELM's `n:` multi-frame format.
 */
object ElmResponseParser {

    private val NOISE_PREFIXES = listOf(
        "SEARCHING", "BUS INIT", "WAITING", "STOPPED", "ELM327", "ELM328", "ELM329", "AT",
    )

    private val FRAME = Regex("^(\\d+)\\s*:\\s*(.*)$")

    fun parse(raw: String): ElmResponse {
        val lines = raw
            .replace(">", "\n")
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return ElmResponse.EMPTY

        var sawOk = false
        var sawNoData = false
        var sawError = false
        val messages = mutableListOf<EcuMessage>()
        val frames = LinkedHashMap<Int?, MutableMap<Int, IntArray>>()
        val compact = StringBuilder()

        fun flushSource(source: Int?) {
            val sourceFrames = frames[source] ?: return
            if (sourceFrames.isEmpty()) return
            sourceFrames.toSortedMap().values
                .reduceOrNull { acc, next -> acc + next }
                ?.takeIf { it.isNotEmpty() }
                ?.let { messages += EcuMessage(source, it) }
            frames.remove(source)
        }

        fun flushFrames() {
            frames.keys.toList().forEach(::flushSource)
        }

        fun flushCompact() {
            if (compact.isEmpty()) return
            Hex.bytesFromCompact(compact.toString())?.let { messages += EcuMessage(null, it) }
            compact.clear()
        }

        for (line in lines) {
            val upper = line.uppercase()

            when {
                upper == "OK" -> { sawOk = true; continue }
                upper.startsWith("NO DATA") -> { sawNoData = true; continue }
                upper == "?" || upper.startsWith("ERROR") || upper.startsWith("BUS ERROR") ||
                    upper.startsWith("CAN ERROR") || upper.startsWith("BUFFER FULL") ||
                    upper.startsWith("DATA ERROR") || upper.startsWith("FB ERROR") ||
                    upper.startsWith("<RX ERROR") || upper.contains("UNABLE TO CONNECT") -> {
                    sawError = true
                    continue
                }
                NOISE_PREFIXES.any { upper.startsWith(it) } -> continue
            }

            val frame = FRAME.matchEntire(line)
            if (frame != null) {
                flushCompact()
                val index = frame.groupValues[1].toInt()
                parseHexLine(frame.groupValues[2])?.let { parsed ->
                    frames.getOrPut(parsed.source) { sortedMapOf() }[index] = parsed.payload
                }
                continue
            }

            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            when {
                tokens.size > 1 -> {
                    flushCompact()
                    flushFrames()
                    parseHexLine(line)?.takeIf { it.payload.isNotEmpty() }
                        ?.let { messages += EcuMessage(it.source, it.payload) }
                }
                Hex.isHexToken(tokens[0]) && tokens[0].length > 2 && tokens[0].length % 2 == 0 -> {
                    // ATS0-style compact output: accumulate until the next line.
                    flushFrames()
                    compact.append(tokens[0])
                }
                else -> {
                    flushCompact()
                    flushFrames()
                    parseHexLine(line)?.takeIf { it.payload.isNotEmpty() }
                        ?.let { messages += EcuMessage(it.source, it.payload) }
                }
            }
        }
        flushCompact()
        flushFrames()

        val status = when {
            messages.isNotEmpty() -> ElmResponse.Status.DATA
            sawNoData -> ElmResponse.Status.NO_DATA
            sawError -> ElmResponse.Status.ERROR
            sawOk -> ElmResponse.Status.OK
            else -> ElmResponse.Status.EMPTY
        }
        return ElmResponse(raw = raw, messages = messages, status = status)
    }

    internal data class ParsedLine(val source: Int?, val payload: IntArray)

    /**
     * Converts one line of hex tokens to a source address and payload, removing an optional
     * 11/29-bit CAN header and length byte.
     */
    internal fun parseHexLine(line: String): ParsedLine? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.any { !Hex.isHexToken(it) }) return null

        val values = tokens.map { Hex.hexValue(it) }.toIntArray()

        // 11-bit header (e.g. "7E8 04 41 0C 1A F8") or 29-bit header (e.g. "18DAF110 04 ...").
        if (values.size >= 3) {
            val headerDigits = tokens[0].length
            val looksLike11 = headerDigits == 3 && values[0] in 0x7E0..0x7EF
            val looksLike29 = headerDigits == 8 && values[0] in 0x18DA0000..0x18DBFFFF
            if (looksLike11 || looksLike29) {
                val length = values[1]
                val payload = if (length in 0..8 && values.size >= length + 2) {
                    values.copyOfRange(2, 2 + length)
                } else {
                    values.copyOfRange(2, values.size)
                }
                return ParsedLine(values[0], payload)
            }
        }
        return ParsedLine(null, values)
    }
}
