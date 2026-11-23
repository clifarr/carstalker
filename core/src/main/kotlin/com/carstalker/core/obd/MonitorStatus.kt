package com.carstalker.core.obd

/** Emissions readiness information decoded from mode 01 PID 01. */
data class MonitorStatus(
    val milOn: Boolean,
    val monitors: List<Monitor>,
) {
    data class Monitor(val name: String, val supported: Boolean, val complete: Boolean)
}

object MonitorStatusParser {

    private data class Spec(
        val name: String,
        val availabilityBit: Int,
        val statusByte: Int, // 0 = C, 1 = D
        val completeBit: Int,
    )

    private val specs = listOf(
        Spec("Misfire", 0, 0, 1),
        Spec("Fuel system", 1, 0, 3),
        Spec("Components", 2, 0, 5),
        Spec("Catalyst", 3, 0, 7),
        Spec("Heated catalyst", 4, 1, 1),
        Spec("EVAP system", 5, 1, 3),
        Spec("Secondary air", 6, 1, 5),
        Spec("A/C refrigerant", 7, 1, 7),
    )

    fun parse(data: IntArray): MonitorStatus? {
        if (data.size < 4) return null
        val a = data[0]
        val b = data[1]
        val statusBytes = intArrayOf(data[2], data[3])

        fun bit(value: Int, index: Int) = (value shr index) and 1 == 1

        val monitors = specs.map { spec ->
            val supported = bit(b, spec.availabilityBit)
            MonitorStatus.Monitor(
                name = spec.name,
                supported = supported,
                complete = supported && bit(statusBytes[spec.statusByte], spec.completeBit),
            )
        }

        return MonitorStatus(
            milOn = bit(a, 7),
            monitors = monitors,
        )
    }
}
