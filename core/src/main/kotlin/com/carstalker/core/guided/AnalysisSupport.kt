package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

data class SeriesStats(
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double,
    val stdDev: Double,
    val p10: Double,
    val p90: Double,
    val first: Double,
    val last: Double,
    val durationMs: Long,
    val effectiveHz: Double,
    val gaps: Int,
) {
    val range: Double get() = max - min
    val available: Boolean get() = count > 0
}

/** Shared statistics, signal lookup and formatting for guided analyses. */
object AnalysisSupport {

    fun stats(points: List<CapturePoint>, gapMs: Long = 2_000): SeriesStats {
        if (points.isEmpty()) {
            return SeriesStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0)
        }
        val sorted = points.sortedBy { it.timestampMs }
        val values = sorted.map { it.value }.sorted()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val duration = (sorted.last().timestampMs - sorted.first().timestampMs).coerceAtLeast(0)
        var gaps = 0
        for (index in 1 until sorted.size) {
            if (sorted[index].timestampMs - sorted[index - 1].timestampMs > gapMs) gaps++
        }
        return SeriesStats(
            count = sorted.size,
            min = values.first(),
            max = values.last(),
            mean = mean,
            median = median(values),
            stdDev = sqrt(variance),
            p10 = percentile(values, 0.10),
            p90 = percentile(values, 0.90),
            first = sorted.first().value,
            last = sorted.last().value,
            durationMs = duration,
            effectiveHz = if (duration > 0) (sorted.size - 1) * 1000.0 / duration else 0.0,
            gaps = gaps,
        )
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * fraction.coerceIn(0.0, 1.0)).toInt()
        return sorted[index]
    }

    /**
     * Combines STFT and LTFT by nearest timestamp so the median is of `STFT + LTFT`, not of two
     * independently medianed channels sampled at different instants.
     */
    fun combinedTrim(
        stft: List<CapturePoint>,
        ltft: List<CapturePoint>,
        maxGapMs: Long = 1_500,
    ): List<CapturePoint> {
        if (stft.isEmpty() || ltft.isEmpty()) return emptyList()
        val longTerm = ltft.sortedBy { it.timestampMs }
        val combined = mutableListOf<CapturePoint>()
        for (point in stft.sortedBy { it.timestampMs }) {
            val nearest = nearest(longTerm, point.timestampMs) ?: continue
            if (abs(nearest.timestampMs - point.timestampMs) <= maxGapMs) {
                combined += CapturePoint(point.pid, point.timestampMs, point.value + nearest.value)
            }
        }
        return combined
    }

    fun nearest(points: List<CapturePoint>, timestampMs: Long): CapturePoint? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val point = points[mid]
            when {
                point.timestampMs < timestampMs -> low = mid + 1
                point.timestampMs > timestampMs -> high = mid - 1
                else -> return point
            }
        }
        val lower = points.getOrNull(high)
        val upper = points.getOrNull(low)
        return when {
            lower == null -> upper
            upper == null -> lower
            timestampMs - lower.timestampMs <= upper.timestampMs - timestampMs -> lower
            else -> upper
        }
    }

    /** Returns the preferred voltage series: control-module voltage first, adapter voltage second. */
    fun voltageSeries(stage: StageCapture): List<CapturePoint> =
        stage.series(Signals.CONTROL_MODULE_VOLTAGE).ifEmpty { stage.series(Signals.ADAPTER_VOLTAGE) }

    fun usesAdapterVoltage(stage: StageCapture): Boolean =
        stage.series(Signals.CONTROL_MODULE_VOLTAGE).isEmpty() && stage.series(Signals.ADAPTER_VOLTAGE).isNotEmpty()

    fun closedLoopFraction(stage: StageCapture): Double? {
        val statuses = stage.series(Signals.FUEL_SYSTEM_STATUS)
        if (statuses.isEmpty()) return null
        val closed = statuses.count { it.value.toInt() == 2 || it.value.toInt() == 16 }
        return closed.toDouble() / statuses.size
    }

    fun fuelSystemLabel(value: Double): String = when (value.toInt()) {
        1 -> "Open loop — engine cold"
        2 -> "Closed loop"
        4 -> "Open loop — load/deceleration"
        8 -> "Open loop — system fault"
        16 -> "Closed loop — fault present"
        else -> "Unknown state (${value.toInt()})"
    }

    fun stageSummary(
        stage: StageCapture,
        valid: Boolean,
        keyPid: Int = Signals.RPM,
        metrics: Map<String, Double> = emptyMap(),
        notes: List<String> = emptyList(),
    ): StageSummary = StageSummary(
        stageId = stage.stageId,
        title = stage.title,
        valid = valid,
        completed = stage.completed,
        sampleCount = stage.series(keyPid).size,
        durationSeconds = stage.durationMs / 1000.0,
        effectiveHz = stage.averageHz(keyPid),
        gaps = stats(stage.series(keyPid)).gaps,
        metrics = metrics,
        notes = notes + stage.notes,
    )

    fun missingRequiredSignals(input: AnalysisInput): List<String> = input.definition.requiredSignals
        .filterNot { signal ->
            input.stages.any { stage -> signal.candidates.any { stage.series(it).isNotEmpty() } }
        }
        .map { it.label }

    fun quality(
        input: AnalysisInput,
        keyPids: List<Int>,
        minHz: Double = 0.8,
    ): CaptureQualitySummary {
        val missing = missingRequiredSignals(input)
        val notes = mutableListOf<String>()
        if (missing.isNotEmpty()) notes += "Missing required signal(s): ${missing.joinToString()}."
        if (input.stages.isEmpty()) notes += "No stages were captured."
        var adequate = true
        for (stage in input.stages) {
            for (pid in keyPids) {
                val points = stage.series(pid)
                if (points.isEmpty()) continue
                val hz = stage.averageHz(pid)
                if (hz < minHz) {
                    adequate = false
                    notes += "${Signals.label(pid)} in ${stage.title} sampled at ${format(hz)} Hz."
                }
                val gaps = stats(points).gaps
                if (gaps > 0) notes += "${stage.title}: $gaps gap(s) in ${Signals.label(pid)}."
            }
        }
        val interruptions = input.stages.sumOf { it.interruptions }
        if (interruptions > 0) notes += "$interruptions stage interruption(s); mixed samples were discarded."
        if (input.adapterVoltageUsed) notes += "Adapter voltage was used instead of control-module voltage."
        return CaptureQualitySummary(
            requiredSignalsPresent = missing.isEmpty(),
            samplingAdequate = adequate,
            missingSignals = missing,
            notes = notes.distinct(),
            interruptions = interruptions,
        )
    }

    fun baseLimitations(input: AnalysisInput): List<String> =
        (input.definition.limitations + listOf(
            "Rule thresholds are configurable examples and are not universal vehicle specifications.",
            "A single OBD capture cannot prove a component failed.",
        )).distinct()

    fun baseWarnings(input: AnalysisInput): List<String> = buildList {
        if (input.prerequisites.flashingMil) add("A flashing MIL is a stop condition for load and rev tests.")
        if (!input.prerequisites.safeLocationConfirmed) add("A safe, stationary location was not confirmed.")
    }.distinct()

    fun format(value: Double, decimals: Int = 1): String =
        if (decimals <= 0) Math.round(value).toString()
        else String.format(Locale.US, "%.${decimals}f", value)

    fun formatSigned(value: Double, decimals: Int = 1): String {
        val text = format(value, decimals)
        return if (value > 0) "+$text" else text
    }

    fun valueWithUnit(value: Double, unit: String, decimals: Int = 1): String =
        if (unit.isBlank()) format(value, decimals) else "${format(value, decimals)} $unit"

    fun statsOrNull(stage: StageCapture?, pid: Int, minSamples: Int = 3): SeriesStats? =
        stage?.series(pid)?.takeIf { it.size >= minSamples }?.let { stats(it) }

    fun combinedTrim(
        stage: StageCapture,
        stftPid: Int,
        ltftPid: Int,
        minSamples: Int = 8,
    ): SeriesStats? {
        val combined = combinedTrim(stage.series(stftPid), stage.series(ltftPid))
        return combined.takeIf { it.size >= minSamples }?.let { stats(it) }
    }

    fun buildResult(
        input: AnalysisInput,
        status: DiagnosticStatus,
        confidence: ResultConfidence,
        summary: String,
        observations: List<GuidedObservation> = emptyList(),
        findings: List<GuidedFinding> = emptyList(),
        stages: List<StageSummary> = emptyList(),
        quality: CaptureQualitySummary,
        warnings: List<String> = emptyList(),
        nextSteps: List<String> = emptyList(),
        limitations: List<String> = emptyList(),
    ): GuidedResult = GuidedResult(
        diagnosticId = input.definition.id,
        definitionVersion = input.definition.version,
        title = input.definition.title,
        status = status,
        confidence = confidence,
        summary = summary,
        observations = observations,
        findings = findings,
        stages = stages,
        quality = quality,
        limitations = (baseLimitations(input) + limitations).distinct(),
        warnings = (baseWarnings(input) + warnings).distinct(),
        nextSteps = nextSteps.distinct(),
        generatedAt = input.generatedAt,
        profile = input.capabilities.profile,
        stagesSkipped = input.skippedStages,
    )

    fun unable(
        input: AnalysisInput,
        quality: CaptureQualitySummary,
        message: String,
        nextSteps: List<String> = emptyList(),
    ): GuidedResult = buildResult(
        input = input,
        status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            else -> DiagnosticStatus.UNABLE_TO_RUN
        },
        confidence = ResultConfidence.LOW,
        summary = input.safetyStopReason ?: message,
        quality = quality.copy(requiredSignalsPresent = false),
        warnings = quality.notes,
        nextSteps = nextSteps.ifEmpty { listOf("Repeat the capture when the required signals are available.") },
    )
}
