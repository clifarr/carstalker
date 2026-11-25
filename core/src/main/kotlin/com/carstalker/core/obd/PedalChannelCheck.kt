package com.carstalker.core.obd

import com.carstalker.core.diagnose.CaptureData

/** One throttle or pedal channel measured during a pedal press. */
data class PedalChannelReading(
    val pidId: Int,
    val name: String,
    val shortName: String,
    val min: Double,
    val max: Double,
    /** Typical value while the pedal was released; the 0 % end of the interpolation. */
    val releasedValue: Double = min,
    /** Highest value while the pedal was pressed; the 100 % end of the interpolation. */
    val pressedValue: Double = max,
) {
    val range: Double get() = (max - min).coerceAtLeast(0.0)
    val moved: Boolean get() = range >= PedalChannelCheck.MOVEMENT_THRESHOLD_PERCENT
    val calibrationSpan: Double get() = (pressedValue - releasedValue).coerceAtLeast(0.0)

    /**
     * True when the channel moves but does not already span 0–100 %. Those are the cars
     * where a released pedal reads 10 % and a pressed pedal reads 90 %.
     */
    val needsInterpolation: Boolean get() = moved &&
        calibrationSpan >= PedalChannelCheck.MOVEMENT_THRESHOLD_PERCENT &&
        (releasedValue > 0.5 || pressedValue < 99.5)
}

/** Result of a short pedal-press capture. */
data class PedalChannelResult(
    val readings: List<PedalChannelReading>,
    /** Pedal channels that followed the pedal, most useful first. */
    val accelerator: List<PedalChannelReading>,
    /** Throttle-plate channels that moved, largest range first. */
    val throttlePlate: List<PedalChannelReading>,
    val recommended: List<Int>,
    val summary: String,
)

/**
 * Finds which throttle/pedal channels actually follow the pedal on this car.
 *
 * There is no reliable free database of per-model channel choices, and generic PIDs are
 * interpreted differently by different ECUs: a Honda may report throttle-body position on
 * `0x11`/`0x45` while the pedal is only on `0x49`/`0x4A`. A short live capture during a pedal
 * press is more trustworthy than a model guess, and it works for cars that do not report a VIN.
 */
object PedalChannelCheck {
    val ACCELERATOR_PIDS = listOf(0x49, 0x4A)
    val THROTTLE_PLATE_PIDS = listOf(0x11, 0x45, 0x47, 0x4C)
    val CANDIDATE_PIDS: List<Int> = (ACCELERATOR_PIDS + THROTTLE_PLATE_PIDS).distinct()

    /** A pedal press should move a working channel far more than this. */
    const val MOVEMENT_THRESHOLD_PERCENT = 8.0
    const val DURATION_MS = 10_000L

    /** The first fifth of the capture is the release phase: 2 s of a 10 s check. */
    const val RELEASE_FRACTION = 0.2

    /**
     * Dashboard list after adding [pidId]. A [replacePid] that is already on the dashboard is
     * swapped out, so recommending a pedal channel does not grow the list past [max].
     * Returns null when the list is full and nothing can be replaced.
     */
    fun applyToDashboard(base: List<Int>, pidId: Int, replacePid: Int?, max: Int): List<Int>? = when {
        pidId in base -> base
        replacePid != null && replacePid in base -> base.map { if (it == replacePid) pidId else it }
        base.size < max -> base + pidId
        else -> null
    }

    /** First throttle-plate channel on the dashboard that did not follow the pedal. */
    fun staticThrottlePlateOn(base: List<Int>, result: PedalChannelResult?): Int? {
        if (result == null) return null
        return base.firstOrNull { id ->
            id in THROTTLE_PLATE_PIDS && result.readings.none { it.pidId == id && it.moved }
        }
    }

    fun analyze(capture: CaptureData): PedalChannelResult {
        val releaseCutoff = capture.startedAt +
            (capture.durationMs * RELEASE_FRACTION).toLong()
        val readings = CANDIDATE_PIDS.mapNotNull { id ->
            val points = capture.series(id)
            if (points.isEmpty()) return@mapNotNull null
            val pid = PidCatalog.find(id) ?: return@mapNotNull null
            val released = points.filter { it.timestampMs <= releaseCutoff }
            PedalChannelReading(
                pidId = id,
                name = pid.name,
                shortName = pid.shortName,
                min = points.minOf { it.value },
                max = points.maxOf { it.value },
                releasedValue = if (released.isEmpty()) {
                    points.minOf { it.value }
                } else {
                    median(released.map { it.value })
                },
                pressedValue = points.maxOf { it.value },
            )
        }
        val accelerator = readings
            .filter { it.pidId in ACCELERATOR_PIDS && it.moved }
            .sortedWith(compareBy({ ACCELERATOR_PIDS.indexOf(it.pidId) }, { -it.range }))
        val throttlePlate = readings
            .filter { it.pidId in THROTTLE_PLATE_PIDS && it.moved }
            .sortedByDescending { it.range }
        val recommended = when {
            accelerator.isNotEmpty() -> accelerator.map { it.pidId }
            throttlePlate.isNotEmpty() -> listOf(throttlePlate.first().pidId)
            else -> emptyList()
        }
        val acceleratorNames = accelerator.joinToString { it.shortName }
        val throttleNames = throttlePlate.joinToString { it.shortName }
        val summary = when {
            accelerator.isNotEmpty() && throttlePlate.isEmpty() ->
                "The pedal is reported on $acceleratorNames. The throttle-plate channels did not follow the pedal."
            accelerator.isNotEmpty() ->
                "The pedal is reported on $acceleratorNames. $throttleNames also moved."
            throttlePlate.isNotEmpty() ->
                "The throttle plate is reported on $throttleNames, but no pedal channel moved. Use that channel for this car."
            else ->
                "No channel moved enough. Keep the engine running, release the pedal for two seconds, then press it fully and hold."
        }
        return PedalChannelResult(readings, accelerator, throttlePlate, recommended, summary)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
