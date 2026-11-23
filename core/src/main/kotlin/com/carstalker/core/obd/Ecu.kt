package com.carstalker.core.obd

/**
 * Generic ECU addressing helpers for OBD-II over CAN.
 *
 * These names are deliberately generic: they come from the OBD-II addressing ranges, not from
 * a manufacturer database. Enhanced module names belong in vehicle packs.
 */
object Ecu {

    const val ENGINE_11BIT = 0x7E8
    const val ENGINE_29BIT = 0x18DAF110

    fun isEngine(source: Int?): Boolean = when (source) {
        null -> true
        ENGINE_11BIT, ENGINE_29BIT -> true
        else -> false
    }

    fun name(source: Int?): String {
        if (source == null) return "Engine"
        return when (source) {
            0x7E8 -> "Engine"
            in 0x7E9..0x7EF -> "ECU 0x${source.toString(16).uppercase()}"
            in 0x18DA0000..0x18DBFFFF -> when (source) {
                0x18DAF110 -> "Engine"
                else -> "ECU 0x${source.toString(16).uppercase()}"
            }
            else -> "ECU 0x${source.toString(16).uppercase()}"
        }
    }

    /** Stable key for grouping and UI selection. */
    fun key(source: Int?): String = source?.toString(16) ?: "engine"
}
