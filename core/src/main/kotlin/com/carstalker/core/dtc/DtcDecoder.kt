package com.carstalker.core.dtc

import com.carstalker.core.util.Hex

object DtcDecoder {

    /**
     * Decodes the payload of a mode 03/07/0A response.
     *
     * @param data bytes after the positive-response echo (e.g. `43`)
     */
    fun decode(data: IntArray): List<Dtc> {
        val result = LinkedHashSet<Dtc>()
        var i = 0
        while (i + 1 < data.size) {
            val high = data[i]
            val low = data[i + 1]
            i += 2
            if (high == 0 && low == 0) continue
            val prefix = when ((high shr 6) and 0x3) {
                0 -> 'P'
                1 -> 'C'
                2 -> 'B'
                else -> 'U'
            }
            val digits = Hex.toHex(high and 0x3F, 2) + Hex.toHex(low, 2)
            val code = "$prefix$digits"
            val category = when (prefix) {
                'P' -> Dtc.Category.POWERTRAIN
                'C' -> Dtc.Category.CHASSIS
                'B' -> Dtc.Category.BODY
                else -> Dtc.Category.NETWORK
            }
            result += Dtc(code, DtcCatalog.describe(code), category)
        }
        return result.toList()
    }
}
