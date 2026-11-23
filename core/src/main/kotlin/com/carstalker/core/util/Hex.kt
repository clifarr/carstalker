package com.carstalker.core.util

/** Hex helpers shared by the ELM327 parser and OBD decoders. */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun isHexToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= 8 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun hexValue(token: String): Int = token.toInt(16)

    /** Splits a compact hex string such as "410C1AF8" into byte values. */
    fun bytesFromCompact(text: String): IntArray? {
        if (text.length < 2 || text.length % 2 != 0 || !isHexToken(text)) return null
        return IntArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    fun toHex(value: Int, digits: Int = 2): String {
        val sb = StringBuilder()
        for (shift in (digits - 1) * 4 downTo 0 step 4) {
            sb.append(DIGITS[(value shr shift) and 0xF])
        }
        return sb.toString()
    }

}
