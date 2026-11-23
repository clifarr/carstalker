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

    /** Format bytes that begin a three-byte ISO/J1850 header. */
    private val ISO_HEADER_FORMATS = setOf(0x48, 0x68, 0x41, 0x61, 0x49)

    /** Drops the ISO-TP length byte at [start] and returns exactly that many payload bytes. */
    private fun stripLengthByte(values: IntArray, start: Int): IntArray {
        if (start >= values.size) return IntArray(0)
        val first = values[start]
        // A high nibble above zero means an ISO-TP first frame; its header must be kept so the
        // multi-frame reassembler can read the declared length.
        if (first > 0x0F) return values.copyOfRange(start, values.size)

        val from = start + 1
        return if (first in 0..8 && values.size >= from + first) {
            values.copyOfRange(from, from + first)
        } else {
            values.copyOfRange(from, values.size)
        }
    }

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
        val pendingIsotp = LinkedHashMap<Int?, IsotpBuffer>()
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

        // ISO-TP frames are handled per line so a first frame is not published as data.
        fun isotp(source: Int?, payload: IntArray, hadHeader: Boolean): EcuMessage? =
            when (val type = Isotp.type(payload)) {
                // Without a header the adapter has already removed ISO-TP framing, and the
                // payload starts directly with the OBD response byte.
                Isotp.Type.SINGLE -> EcuMessage(
                    source,
                    if (hadHeader) Isotp.single(payload) else payload,
                )
                Isotp.Type.FIRST -> {
                    pendingIsotp[source] = Isotp.first(payload)
                    null
                }
                Isotp.Type.CONSECUTIVE -> {
                    val buffer = pendingIsotp[source] ?: return null
                    buffer.append(payload)
                    if (buffer.isComplete) {
                        pendingIsotp.remove(source)
                        EcuMessage(source, buffer.toIntArray())
                    } else {
                        null
                    }
                }
                // Some adapters strip ISO-TP framing even with headers on, so anything that
                // is not a multi-frame marker is treated as a complete payload.
                Isotp.Type.OTHER -> EcuMessage(source, payload)
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
                    parseHexLine(line)?.let { parsed ->
                        isotp(parsed.source, parsed.payload, parsed.hadHeader)?.let { messages += it }
                    }
                }
                Hex.isHexToken(tokens[0]) && tokens[0].length > 2 && tokens[0].length % 2 == 0 -> {
                    // ATS0-style compact output: accumulate until the next line.
                    flushFrames()
                    compact.append(tokens[0])
                }
                else -> {
                    flushCompact()
                    flushFrames()
                    parseHexLine(line)?.let { parsed ->
                        isotp(parsed.source, parsed.payload, parsed.hadHeader)?.let { messages += it }
                    }
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

    internal data class ParsedLine(val source: Int?, val payload: IntArray, val hadHeader: Boolean = false)

    /**
     * Converts one line of hex tokens to a source address and payload, removing an optional
     * 11/29-bit CAN header and length byte.
     */
    internal fun parseHexLine(line: String): ParsedLine? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.any { !Hex.isHexToken(it) }) return null

        val values = tokens.map { Hex.hexValue(it) }.toIntArray()

        // 11-bit header, e.g. "7E8 04 41 0C 1A F8" (one token) or "7E8 04 ...".
        if (values.size >= 3 && tokens[0].length == 3 && values[0] in 0x7E0..0x7EF) {
            return ParsedLine(values[0], stripLengthByte(values, 1), hadHeader = true)
        }

        // 29-bit header written as a single token, e.g. "18DAF110 04 41 0C 0C 84".
        if (values.size >= 3 && tokens[0].length == 8 && values[0] in 0x18DA0000..0x18DBFFFF) {
            return ParsedLine(values[0], stripLengthByte(values, 1), hadHeader = true)
        }

        // Three-byte header used by ISO 9141-2, KWP and J1850, e.g. "48 6B 10 41 0C 1A F8".
        // These buses have no ISO-TP length byte, so the payload starts right after the header.
        if (values.size >= 5 && values[0] in ISO_HEADER_FORMATS && values[3] in 0x40..0x4A) {
            val header = (values[0] shl 16) or (values[1] shl 8) or values[2]
            return ParsedLine(header, values.copyOfRange(3, values.size), hadHeader = true)
        }

        // 29-bit header split into four bytes, e.g. "18 DA F1 10 04 41 0C 0C 84".
        // Many adapters print it this way, and without this every payload starts with 0x18.
        if (values.size >= 5 && values[0] == 0x18 && values[2] in 0xF0..0xFF && values[3] in 0x00..0xFF) {
            val header = (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
            if (header in 0x18DA0000..0x18DBFFFF) {
                return ParsedLine(header, stripLengthByte(values, 4), hadHeader = true)
            }
        }

        return ParsedLine(null, values)
    }
}

/**
 * ISO 15765-2 framing, which CAN ECUs use on top of the raw bus.
 *
 * A single frame carries its own length in the first nibble. A multi-frame answer starts with a
 * first frame (length in 12 bits) and continues with consecutive frames, each carrying a
 * sequence number. Without this, a VIN response would be published starting at its length byte
 * and be rejected by the mode 09 decoder.
 */
internal object Isotp {

    enum class Type { SINGLE, FIRST, CONSECUTIVE, OTHER }

    fun type(payload: IntArray): Type {
        if (payload.isEmpty()) return Type.OTHER
        return when (payload[0] shr 4) {
            0x0 -> Type.SINGLE
            0x1 -> Type.FIRST
            0x2 -> Type.CONSECUTIVE
            else -> Type.OTHER
        }
    }

    /** Payload of a single frame, without the length nibble. */
    fun single(payload: IntArray): IntArray {
        val length = payload[0] and 0x0F
        val from = 1
        return if (length in 1..7 && payload.size >= from + length) {
            payload.copyOfRange(from, from + length)
        } else {
            payload.copyOfRange(from, payload.size)
        }
    }

    /** Starts a multi-frame buffer from a first frame. */
    fun first(payload: IntArray): IsotpBuffer {
        val length = ((payload[0] and 0x0F) shl 8) or payload.getOrElse(1) { 0 }
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else IntArray(0)
        return IsotpBuffer(length, data)
    }
}

/** Accumulates consecutive frames until the declared ISO-TP length is reached. */
internal class IsotpBuffer(private val expectedLength: Int, initial: IntArray) {

    private val data = ArrayList<Int>(expectedLength.coerceAtLeast(initial.size))

    init {
        initial.take(expectedLength).forEach(data::add)
    }

    val isComplete: Boolean get() = data.size >= expectedLength

    fun append(payload: IntArray) {
        if (payload.size < 2) return
        // Byte 0 carries the sequence number; the rest is data.
        for (index in 1 until payload.size) {
            if (data.size >= expectedLength) return
            data += payload[index]
        }
    }

    fun toIntArray(): IntArray = data.toIntArray()
}
