package com.carstalker.core.obd

/** Decodes the supported-PID bitmasks returned by requests 0100, 0120, 0140, ... */
object PidSupport {

    private val ranges = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    /** Base PIDs whose support must be probed to enumerate everything the ECU reports. */
    val probeBases: List<Int> = ranges

    /**
     * @param base the request PID (0x00, 0x20, ...)
     * @param data four payload bytes after the `41 <base>` echo
     */
    fun decode(base: Int, data: IntArray): Set<Int> {
        if (data.size < 4) return emptySet()
        val result = mutableSetOf<Int>()
        for (bit in 0 until 32) {
            val byte = data[bit / 8]
            val set = (byte shr (7 - bit % 8)) and 1 == 1
            if (set) result += base + bit + 1
        }
        return result
    }

    /**
     * Advertised PIDs that the app has no definition for.
     *
     * The bitmaps also set the "next range" markers (0x20, 0x40, ...), which are requests rather
     * than parameters, so [probeBases] are dropped along with anything outside the Mode 01 range.
     * [known] is every id the app can already show, including user-defined PIDs.
     */
    fun unlisted(supported: Set<Int>, known: Set<Int>): List<Int> =
        supported.filter { it in 0x01..0xFF && it !in probeBases && it !in known }.sorted()
}
