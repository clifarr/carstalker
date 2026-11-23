package com.carstalker.core.obd

enum class UnitSystem { METRIC, IMPERIAL }

data class DisplayValue(val value: Double, val unit: String)

/** Converts canonical metric OBD values to the selected display units. */
object Units {

    fun display(pid: Pid, value: Double, system: UnitSystem): DisplayValue =
        display(pid.id, value, pid.unit, system, pid.isCustom)

    fun display(pidId: Int, value: Double, metricUnit: String, system: UnitSystem, isCustom: Boolean = false): DisplayValue {
        if (system == UnitSystem.METRIC || isCustom) return DisplayValue(value, metricUnit)
        return when (pidId) {
            0x0D -> DisplayValue(value * 0.621371, "mph")
            0x05, 0x0F, 0x46, 0x5C, 0x3C, 0x3D -> DisplayValue(value * 9.0 / 5.0 + 32.0, "°F")
            0x0A, 0x0B, 0x22, 0x23, 0x33 -> DisplayValue(value * 0.145038, "psi")
            0x21, 0x31, 0xA6 -> DisplayValue(value * 0.621371, "mi")
            0x10 -> DisplayValue(value * 0.132277, "lb/min")
            0x5E -> DisplayValue(value * 0.264172, "gal/h")
            0x63 -> DisplayValue(value * 0.737562, "lb·ft")
            else -> DisplayValue(value, metricUnit)
        }
    }
}
