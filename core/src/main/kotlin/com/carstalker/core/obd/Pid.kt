package com.carstalker.core.obd

import com.carstalker.core.util.Hex

/** A live-data parameter definition. [formula] receives the data bytes (A, B, C, D...) of the response. */
data class Pid(
    val id: Int,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val category: Category,
    val decimals: Int = 0,
    /** Higher weights are picked first when the dashboard fills itself. */
    val weight: Int = 0,
    val isCustom: Boolean = false,
    val formula: (IntArray) -> Double,
) {
    enum class Category(val label: String) {
        ENGINE("Engine"),
        DRIVETRAIN("Drivetrain"),
        FUEL("Fuel"),
        TEMPERATURE("Temperature"),
        ELECTRICAL("Electrical"),
        EMISSIONS("Emissions"),
        OTHER("Other"),
    }

    val hex: String get() = Hex.toHex(id)

    fun decode(data: IntArray): Double? {
        if (data.size < requiredBytes) return null
        return formula(data).takeIf { it.isFinite() }
    }

    /** How many data bytes the formula needs; used to reject truncated frames. */
    var requiredBytes: Int = 1
        internal set

    companion object {
        fun of(
            id: Int,
            name: String,
            shortName: String,
            unit: String,
            min: Double,
            max: Double,
            category: Category,
            decimals: Int = 0,
            requiredBytes: Int = 1,
            weight: Int = 0,
            formula: (IntArray) -> Double,
        ): Pid = Pid(id, name, shortName, unit, min, max, category, decimals, weight, false, formula)
            .also { it.requiredBytes = requiredBytes }
    }
}

/** Data-byte accessors; return 0 when a byte is missing so short frames decode defensively. */
internal fun IntArray.a(): Int = getOrElse(0) { 0 }
internal fun IntArray.b(): Int = getOrElse(1) { 0 }
internal fun IntArray.c(): Int = getOrElse(2) { 0 }
internal fun IntArray.d(): Int = getOrElse(3) { 0 }
