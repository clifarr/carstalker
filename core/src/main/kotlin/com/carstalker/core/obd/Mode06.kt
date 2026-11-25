package com.carstalker.core.obd

import com.carstalker.core.util.Hex

/** Whether on-board monitor data was actually requested and read for this scan. */
enum class MonitorDataState(val label: String) {
    NOT_REQUESTED("Not checked"),
    IN_PROGRESS("Reading…"),
    AVAILABLE("Read"),
    UNSUPPORTED("Not supported"),
    FAILED_TO_READ("Could not read"),
    STALE("Outdated"),
}

/**
 * One Mode 06 on-board monitor test result.
 *
 * Mode 06 returns, per monitor and test, the measured value plus the minimum and maximum
 * limits the ECU used. That makes it possible to show margin-to-failure instead of just
 * pass/fail. Values are already scaled to [unit].
 */
data class Mode06Result(
    val mid: Int,
    val midName: String,
    val tid: Int,
    val tidName: String,
    val uas: Int,
    val unit: String,
    val value: Double,
    val min: Double?,
    val max: Double?,
    val passed: Boolean?,
    val source: Int?,
    /** False when the reported unit family contradicts the monitor/test identity. */
    val mappingTrusted: Boolean = true,
    /** Original response bytes, kept for audit and export. */
    val raw: String = "",
) {
    val label: String
        get() = if (mappingTrusted) {
            "${midName.ifBlank { "Monitor ${Hex.toHex(mid)}" }} · ${tidName.ifBlank { "Test ${Hex.toHex(tid)}" }}"
        } else {
            "Unidentified on-board monitor test (MID ${Hex.toHex(mid)} / TID ${Hex.toHex(tid)})"
        }

    /** How far the measured value is from the nearest limit, as a percentage of the limit span. */
    val marginPercent: Double?
        get() {
            val lo = min ?: return null
            val hi = max ?: return null
            if (hi <= lo) return null
            val distance = when {
                value < lo -> value - lo
                value > hi -> value - hi
                else -> minOf(value - lo, hi - value)
            }
            return distance / (hi - lo) * 100.0
        }
}

data class Mode06Report(
    val results: List<Mode06Result> = emptyList(),
    val supportedMids: Set<Int> = emptySet(),
    val readAt: Long = 0L,
    val loading: Boolean = false,
    /** A report with parsed results is treated as read unless the caller says otherwise. */
    val state: MonitorDataState = if (results.isEmpty()) MonitorDataState.NOT_REQUESTED else MonitorDataState.AVAILABLE,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = results.isEmpty()

    /** Only results whose monitor identity and unit are trustworthy. */
    val trustedResults: List<Mode06Result> get() = results.filter { it.mappingTrusted }

    val failures: List<Mode06Result> get() = trustedResults.filter { it.passed == false }
    val passes: List<Mode06Result> get() = trustedResults.filter { it.passed == true }
    val inconclusive: List<Mode06Result> get() = results.filter { it.passed == null }

    /** Counts are only meaningful once the read completed. */
    val countsAvailable: Boolean get() = state == MonitorDataState.AVAILABLE
}

/**
 * Parser for SAE J1979 Mode 06 (CAN) responses.
 *
 * A response is `46 <MID> <TID> <UAS> <value hi> <value lo> <min hi> <min lo> <max hi> <max lo>`
 * repeated in 9-byte blocks. The UAS byte selects the scaling and unit; unknown UAS values are
 * kept as raw counts rather than discarded.
 */
object Mode06Parser {

    private data class Uas(
        val signed: Boolean = false,
        val scale: Double = 1.0,
        val offset: Double = 0.0,
        val unit: String = "count",
    )

    // Unit and scaling IDs from SAE J1979 Appendix D, restated as data. These are public
    // identifiers and scale factors, not copied implementation code.
    private val uasTable: Map<Int, Uas> = buildMap {
        fun add(id: Int, scale: Double, unit: String, offset: Double = 0.0, signed: Boolean = false) {
            put(id, Uas(signed, scale, offset, unit))
        }
        add(0x01, 1.0, "count")
        add(0x02, 0.1, "count")
        add(0x03, 0.01, "count")
        add(0x04, 0.001, "count")
        add(0x05, 0.0000305, "count")
        add(0x06, 0.000305, "count")
        add(0x07, 0.25, "rpm")
        add(0x08, 0.01, "km/h")
        add(0x09, 1.0, "km/h")
        add(0x0A, 0.122, "mV")
        add(0x0B, 0.001, "V")
        add(0x0C, 0.01, "V")
        add(0x0D, 0.00390625, "mA")
        add(0x0E, 0.001, "A")
        add(0x0F, 0.01, "A")
        add(0x10, 1.0, "ms")
        add(0x11, 100.0, "ms")
        add(0x12, 1.0, "s")
        add(0x13, 1.0, "mΩ")
        add(0x14, 1.0, "Ω")
        add(0x15, 1.0, "kΩ")
        add(0x16, 0.1, "°C", offset = -40.0)
        add(0x17, 0.01, "kPa")
        add(0x18, 0.0117, "kPa")
        add(0x19, 0.079, "kPa")
        add(0x1A, 1.0, "kPa")
        add(0x1B, 10.0, "kPa")
        add(0x1C, 0.01, "°")
        add(0x1D, 0.5, "°")
        add(0x1E, 0.0000305, "ratio")
        add(0x1F, 0.05, "ratio")
        add(0x20, 0.00390625, "ratio")
        add(0x21, 1.0, "mHz")
        add(0x22, 1.0, "Hz")
        add(0x23, 1.0, "kHz")
        add(0x24, 1.0, "count")
        add(0x25, 1.0, "km")
        add(0x26, 0.1, "mV/ms")
        add(0x27, 0.01, "g/s")
        add(0x28, 1.0, "g/s")
        add(0x29, 0.25, "Pa/s")
        add(0x2A, 0.001, "kg/h")
        add(0x2B, 1.0, "count")
        add(0x2C, 0.01, "g")
        add(0x2D, 0.01, "mg")
        add(0x2E, 1.0, "boolean")
        add(0x2F, 0.01, "%")
        add(0x30, 0.001526, "%")
        add(0x31, 0.001, "L")
        add(0x32, 0.0000305, "in")
        add(0x33, 0.00024414, "ratio")
        add(0x34, 1.0, "min")
        add(0x35, 10.0, "ms")
        add(0x36, 0.01, "g")
        add(0x37, 0.1, "g")
        add(0x38, 1.0, "g")
        add(0x39, 0.01, "%", offset = -327.68)
        add(0x3A, 0.001, "g")
        add(0x3B, 0.0001, "g")
        add(0x3C, 0.1, "µs")
        add(0x3D, 0.01, "mA")
        add(0x3E, 0.00006103516, "mm²")
        add(0x3F, 0.01, "L")
        add(0x40, 1.0, "ppm")
        add(0x41, 0.01, "µA")
        add(0x81, 1.0, "count", signed = true)
        add(0x82, 0.1, "count", signed = true)
        add(0x83, 0.01, "count", signed = true)
        add(0x84, 0.001, "count", signed = true)
        add(0x85, 0.0000305, "count", signed = true)
        add(0x86, 0.000305, "count", signed = true)
        add(0x87, 1.0, "ppm", signed = true)
        add(0x8A, 0.122, "mV", signed = true)
        add(0x8B, 0.001, "V", signed = true)
        add(0x8C, 0.01, "V", signed = true)
        add(0x8D, 0.00390625, "mA", signed = true)
        add(0x8E, 0.001, "A", signed = true)
        add(0x90, 1.0, "ms", signed = true)
        add(0x96, 0.1, "°C", signed = true)
        add(0x99, 0.1, "kPa", signed = true)
        add(0x9C, 0.01, "°", signed = true)
        add(0x9D, 0.5, "°", signed = true)
        add(0xA8, 1.0, "g/s", signed = true)
        add(0xA9, 0.25, "Pa/s", signed = true)
        add(0xAD, 0.01, "mg", signed = true)
        add(0xAE, 0.1, "mg", signed = true)
        add(0xAF, 0.01, "%", signed = true)
        add(0xB0, 0.003052, "%", signed = true)
        add(0xB1, 2.0, "mV/s", signed = true)
        add(0xFC, 0.01, "kPa", signed = true)
        add(0xFD, 0.001, "kPa", signed = true)
        add(0xFE, 0.25, "Pa", signed = true)
    }

    private val midNames: Map<Int, String> = buildMap {
        put(0x01, "O2 sensor B1S1")
        put(0x02, "O2 sensor B1S2")
        put(0x03, "O2 sensor B1S3")
        put(0x04, "O2 sensor B1S4")
        put(0x05, "O2 sensor B2S1")
        put(0x06, "O2 sensor B2S2")
        put(0x07, "O2 sensor B2S3")
        put(0x08, "O2 sensor B2S4")
        put(0x09, "O2 sensor B3S1")
        put(0x0A, "O2 sensor B3S2")
        put(0x0B, "O2 sensor B3S3")
        put(0x0C, "O2 sensor B3S4")
        put(0x0D, "O2 sensor B4S1")
        put(0x0E, "O2 sensor B4S2")
        put(0x0F, "O2 sensor B4S3")
        put(0x10, "O2 sensor B4S4")
        put(0x21, "Catalyst B1")
        put(0x22, "Catalyst B2")
        put(0x23, "Catalyst B3")
        put(0x24, "Catalyst B4")
        put(0x31, "EGR B1")
        put(0x32, "EGR B2")
        put(0x33, "EGR B3")
        put(0x34, "EGR B4")
        put(0x35, "VVT B1")
        put(0x36, "VVT B2")
        put(0x37, "VVT B3")
        put(0x38, "VVT B4")
        put(0x39, "EVAP 0.150 in")
        put(0x3A, "EVAP 0.090 in")
        put(0x3B, "EVAP 0.040 in")
        put(0x3C, "EVAP 0.020 in")
        put(0x3D, "Purge flow")
        put(0x41, "O2 heater B1S1")
        put(0x42, "O2 heater B1S2")
        put(0x43, "O2 heater B1S3")
        put(0x44, "O2 heater B1S4")
        put(0x45, "O2 heater B2S1")
        put(0x46, "O2 heater B2S2")
        put(0x47, "O2 heater B2S3")
        put(0x48, "O2 heater B2S4")
        put(0x49, "O2 heater B3S1")
        put(0x4A, "O2 heater B3S2")
        put(0x4B, "O2 heater B3S3")
        put(0x4C, "O2 heater B3S4")
        put(0x4D, "O2 heater B4S1")
        put(0x4E, "O2 heater B4S2")
        put(0x4F, "O2 heater B4S3")
        put(0x50, "O2 heater B4S4")
        put(0x61, "Heated catalyst B1")
        put(0x62, "Heated catalyst B2")
        put(0x63, "Heated catalyst B3")
        put(0x64, "Heated catalyst B4")
        put(0x71, "Secondary air 1")
        put(0x72, "Secondary air 2")
        put(0x73, "Secondary air 3")
        put(0x74, "Secondary air 4")
        put(0x81, "Fuel system B1")
        put(0x82, "Fuel system B2")
        put(0x83, "Fuel system B3")
        put(0x84, "Fuel system B4")
        put(0x85, "Boost pressure B1")
        put(0x86, "Boost pressure B2")
        put(0x90, "NOx absorber B1")
        put(0x91, "NOx absorber B2")
        put(0x98, "NOx catalyst B1")
        put(0x99, "NOx catalyst B2")
        put(0xA1, "Misfire general")
        put(0xA2, "Misfire cylinder 1")
        put(0xA3, "Misfire cylinder 2")
        put(0xA4, "Misfire cylinder 3")
        put(0xA5, "Misfire cylinder 4")
        put(0xA6, "Misfire cylinder 5")
        put(0xA7, "Misfire cylinder 6")
        put(0xA8, "Misfire cylinder 7")
        put(0xA9, "Misfire cylinder 8")
        put(0xAA, "Misfire cylinder 9")
        put(0xAB, "Misfire cylinder 10")
        put(0xAC, "Misfire cylinder 11")
        put(0xAD, "Misfire cylinder 12")
        put(0xB0, "PM filter B1")
        put(0xB1, "PM filter B2")
    }

    private val tidNames: Map<Int, String> = mapOf(
        0x01 to "Rich→lean threshold voltage",
        0x02 to "Lean→rich threshold voltage",
        0x03 to "Low voltage for switch time",
        0x04 to "High voltage for switch time",
        0x05 to "Rich→lean switch time",
        0x06 to "Lean→rich switch time",
        0x07 to "Minimum sensor voltage",
        0x08 to "Maximum sensor voltage",
        0x09 to "Time between sensor transitions",
        0x0A to "Sensor period",
        0x0B to "EWMA misfire count (10 cycles)",
        0x0C to "Misfire count (current/last cycle)",
    )

    fun midName(mid: Int): String = midNames[mid] ?: "Monitor ${Hex.toHex(mid)}"
    fun tidName(tid: Int): String = tidNames[tid] ?: "Test ${Hex.toHex(tid)}"

    private enum class UnitFamily { VOLTAGE, TIME, COUNT, OTHER }

    /**
     * The unit family a monitor/test pair is expected to report. A mismatch means the decoder
     * cannot trust the descriptive label, so the result is kept as raw data and excluded from
     * pass/fail counts.
     */
    private fun expectedFamily(mid: Int, tid: Int): UnitFamily? = when (mid) {
        in 0x01..0x10, in 0x21..0x24 -> when (tid) {
            0x01, 0x02, 0x03, 0x04, 0x07, 0x08 -> UnitFamily.VOLTAGE
            0x05, 0x06, 0x09, 0x0A -> UnitFamily.TIME
            0x0B, 0x0C -> UnitFamily.COUNT
            else -> null
        }
        in 0xA1..0xAD -> when (tid) {
            0x0B, 0x0C -> UnitFamily.COUNT
            else -> null
        }
        else -> null
    }

    private fun familyOf(unit: String): UnitFamily = when (unit) {
        "mV", "V" -> UnitFamily.VOLTAGE
        "ms", "s" -> UnitFamily.TIME
        "count" -> UnitFamily.COUNT
        else -> UnitFamily.OTHER
    }

    fun parseSupportedMids(base: Int, data: IntArray): Set<Int> = PidSupport.decode(base, data)

    /**
     * @param payload response bytes beginning with `46`
     * @param requestedMid the MID sent in the request, used when the response repeats it
     */
    fun parse(payload: IntArray, requestedMid: Int, source: Int?): List<Mode06Result> {
        if (payload.size < 10 || payload[0] != ObdMode.ONBOARD_MONITOR + 0x40) return emptyList()
        val results = mutableListOf<Mode06Result>()
        var index = 1
        while (index + 8 < payload.size) {
            val blockStart = index
            val mid = payload[index]
            val tid = payload[index + 1]
            val uasId = payload[index + 2]
            val rawValue = (payload[index + 3] shl 8) or payload[index + 4]
            val rawMin = (payload[index + 5] shl 8) or payload[index + 6]
            val rawMax = (payload[index + 7] shl 8) or payload[index + 8]
            index += 9

            if (mid != requestedMid && mid != 0x00) continue
            val uas = uasTable[uasId]
            val value = scale(rawValue, uas)
            val min = scale(rawMin, uas)
            val max = scale(rawMax, uas)
            val notCompleted = rawValue == 0 && rawMin == 0 && rawMax == 0
            val expected = expectedFamily(mid, tid)
            val mappingTrusted = uas != null &&
                (expected == null || familyOf(uas.unit) == expected)
            val passed = when {
                uas == null || notCompleted || !mappingTrusted -> null
                uasId == 0x2E -> rawValue != 0
                else -> value in min..max
            }
            val rawText = payload.copyOfRange(blockStart, minOf(index, payload.size))
                .joinToString(" ") { String.format(java.util.Locale.US, "%02X", it and 0xFF) }
            results += Mode06Result(
                mid = mid,
                midName = midName(mid),
                tid = tid,
                tidName = tidName(tid),
                uas = uasId,
                unit = uas?.unit ?: "raw",
                value = value,
                min = min.takeUnless { notCompleted },
                max = max.takeUnless { notCompleted },
                passed = passed,
                source = source,
                mappingTrusted = mappingTrusted,
                raw = "46 $rawText",
            )
        }
        return results
    }

    private fun scale(raw: Int, uas: Uas?): Double {
        if (uas == null) return raw.toDouble()
        val signed = if (uas.signed && raw and 0x8000 != 0) raw - 0x10000 else raw
        return signed * uas.scale + uas.offset
    }
}
