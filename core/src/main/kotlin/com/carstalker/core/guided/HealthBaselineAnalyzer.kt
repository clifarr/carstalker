package com.carstalker.core.guided

import kotlin.math.abs

/**
 * Longitudinal baseline comparison.
 *
 * A difference is only reported when the vehicle profile and the captured conditions match the
 * stored baseline closely enough. The first run documents a baseline; it does not claim a trend.
 */
object HealthBaselineAnalyzer {

    private const val RPM_TOLERANCE = 100.0
    private const val RPM_TOLERANCE_FRACTION = 0.10
    private const val COOLANT_TOLERANCE = 10.0
    private const val LOAD_TOLERANCE = 10.0
    private const val AMBIENT_TOLERANCE = 5.0
    private const val MAF_CHANGE = 0.15
    private const val MAP_CHANGE = 0.10
    private const val TRIM_CHANGE = 10.0
    private const val VOLTAGE_CHANGE = 1.0
    private const val FUEL_CHANGE = 0.20

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.COOLANT),
            minHz = 0.5,
        )
        val current = BaselineExtractor.fromCaptures(
            captures = input.stages,
            profile = input.capabilities.profile,
            vehicleKey = input.vehicleKey,
            createdAt = input.generatedAt,
        ) ?: return AnalysisSupport.unable(
            input,
            quality,
            "No warm-idle baseline data was captured. The test needs a warm-idle stage with RPM and coolant.",
            nextSteps = listOf("Repeat the warm-idle stage and confirm RPM/coolant are reported."),
        )

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(snapshot: BaselineSnapshot, prefix: String) {
            snapshot.metrics["warm_idle.rpm"]?.let {
                observations += GuidedObservation("$prefix warm-idle RPM", AnalysisSupport.valueWithUnit(it, "rpm", 0))
            }
            snapshot.metrics["warm_idle.coolant"]?.let {
                observations += GuidedObservation("$prefix warm-idle coolant", AnalysisSupport.valueWithUnit(it, "°C", 1))
            }
            snapshot.metrics["warm_idle.load"]?.let {
                observations += GuidedObservation("$prefix warm-idle load", AnalysisSupport.valueWithUnit(it, "%", 1))
            }
            snapshot.metrics["warm_idle.maf"]?.let {
                observations += GuidedObservation("$prefix warm-idle MAF", AnalysisSupport.valueWithUnit(it, "g/s", 1))
            }
            snapshot.metrics["warm_idle.map"]?.let {
                observations += GuidedObservation("$prefix warm-idle MAP", AnalysisSupport.valueWithUnit(it, "kPa", 1))
            }
            snapshot.metrics["warm_idle.trim_b1"]?.let {
                observations += GuidedObservation("$prefix warm-idle combined trim B1", "${AnalysisSupport.formatSigned(it)} %")
            }
            snapshot.metrics["warm_idle.voltage"]?.let {
                observations += GuidedObservation("$prefix warm-idle voltage", AnalysisSupport.valueWithUnit(it, "V", 2))
            }
        }

        observe(current, "Current")
        val baseline = input.baseline
        if (baseline == null) {
            warnings += "No saved baseline exists for this vehicle. This capture can be saved as the baseline."
            nextSteps += "Save this result as the baseline, then repeat under the same conditions later."
            return AnalysisSupport.buildResult(
                input = input,
                status = DiagnosticStatus.INCONCLUSIVE,
                confidence = ResultConfidence.LOW,
                summary = "No baseline is stored yet. This run documents the current values; save it and repeat later to compare.",
                observations = observations,
                stages = stageSummaries(input),
                quality = quality,
                warnings = warnings,
                nextSteps = nextSteps,
                limitations = listOf(
                    "A baseline is only comparable when the vehicle, engine state, RPM, load and ambient conditions match.",
                    "A trend is not a diagnosis.",
                ),
            )
        }

        observe(baseline, "Baseline")
        val baselineDate = if (baseline.createdAt > 0) {
            java.time.Instant.ofEpochMilli(baseline.createdAt).toString()
        } else {
            "unknown"
        }
        observations += GuidedObservation("Baseline saved", baselineDate)

        val mismatchReasons = mutableListOf<String>()
        if (baseline.vehicleKey.isNotBlank() && input.vehicleKey.isNotBlank() && baseline.vehicleKey != input.vehicleKey) {
            mismatchReasons += "The baseline belongs to a different vehicle identifier."
        }
        if (baseline.profile.fuelType != input.capabilities.profile.fuelType ||
            baseline.profile.aspiration != input.capabilities.profile.aspiration ||
            baseline.profile.bankCount != input.capabilities.profile.bankCount
        ) {
            mismatchReasons += "The engine profile differs from the baseline (${baseline.profile.label()} vs ${input.capabilities.profile.label()})."
        }

        compareCondition("RPM", current.conditions["rpm"], baseline.conditions["rpm"], RPM_TOLERANCE)?.let { mismatchReasons += it }
        compareCondition("coolant", current.conditions["coolant"], baseline.conditions["coolant"], COOLANT_TOLERANCE)?.let { mismatchReasons += it }
        compareCondition("load", current.conditions["load"], baseline.conditions["load"], LOAD_TOLERANCE)?.let { mismatchReasons += it }
        compareCondition("intake air", current.conditions["iat"], baseline.conditions["iat"], AMBIENT_TOLERANCE)?.let { mismatchReasons += it }
        compareCondition("ambient", current.conditions["ambient"], baseline.conditions["ambient"], AMBIENT_TOLERANCE)?.let { mismatchReasons += it }

        if (mismatchReasons.isNotEmpty()) {
            warnings += "Conditions did not match the baseline: ${mismatchReasons.joinToString("; ")}."
            nextSteps += "Repeat the capture under the same engine and ambient conditions as the baseline."
            return AnalysisSupport.buildResult(
                input = input,
                status = DiagnosticStatus.INCONCLUSIVE,
                confidence = ResultConfidence.LOW,
                summary = "The capture conditions do not match the saved baseline, so a trend would be misleading.",
                observations = observations,
                stages = stageSummaries(input),
                quality = quality,
                warnings = warnings,
                nextSteps = nextSteps,
                limitations = listOf(
                    "Baselines must match engine state, RPM, load, ambient and capture quality.",
                    "The comparison uses configurable example thresholds, not a manufacturer specification.",
                ),
            )
        }

        val compared = compareMetrics(current, baseline)
        compared.forEach { comparison ->
            observations += GuidedObservation(
                "${comparison.label} vs baseline",
                "${comparison.currentText} (baseline ${comparison.baselineText})",
            )
        }

        fun addFinding(
            id: String,
            title: String,
            explanation: String,
            supporting: List<String>,
            alternatives: List<String>,
            nextCheck: String,
        ) {
            findings += GuidedFinding(
                id = id,
                title = title,
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = explanation,
                supporting = supporting,
                alternatives = alternatives,
                nextCheck = nextCheck,
            )
        }

        compared.filter { it.id == "maf" && abs(it.relativeChange) > MAF_CHANGE && abs(it.absoluteChange) > 1.0 }.forEach { comparison ->
            addFinding(
                id = "baseline_airflow_shift",
                title = "Airflow differs from the baseline",
                explanation = "MAF differs from the saved same-condition baseline beyond the configured threshold. A dirty sensor, intake change, leak or sensor drift can explain it; it is not proof of a fault.",
                supporting = listOf("${comparison.label}: ${comparison.currentText} vs baseline ${comparison.baselineText}"),
                alternatives = listOf("MAF drift/contamination", "Intake leak/restriction", "Filter change", "Sensor/wiring", "Condition mismatch"),
                nextCheck = "Inspect the intake and MAF, then repeat under matched conditions.",
            )
        }
        compared.filter { it.id == "map" && abs(it.relativeChange) > MAP_CHANGE && abs(it.absoluteChange) > 5.0 }.forEach { comparison ->
            addFinding(
                id = "baseline_map_shift",
                title = "Manifold pressure differs from the baseline",
                explanation = "MAP differs from the saved baseline beyond the configured threshold. A sensor change, intake restriction, throttle adaptation or condition mismatch can explain it.",
                supporting = listOf("${comparison.label}: ${comparison.currentText} vs baseline ${comparison.baselineText}"),
                alternatives = listOf("MAP sensor", "Intake restriction", "Throttle adaptation", "Condition mismatch"),
                nextCheck = "Check MAP/BARO plausibility and repeat under matched conditions.",
            )
        }
        compared.filter { it.id == "trim" && abs(it.absoluteChange) > TRIM_CHANGE }.forEach { comparison ->
            addFinding(
                id = "baseline_trim_shift",
                title = "Fuel trim differs from the baseline",
                explanation = "Combined fuel trim differs from the saved baseline by more than the configured threshold. A mixture, airflow or fuel-delivery change can explain it.",
                supporting = listOf("${comparison.label}: ${comparison.currentText} vs baseline ${comparison.baselineText}"),
                alternatives = listOf("Vacuum/intake leak", "Airflow measurement", "Fuel delivery", "O2 sensor"),
                nextCheck = "Run the lean or rich mixture guide if a mixture code is present.",
            )
        }
        compared.filter { it.id == "voltage" && abs(it.absoluteChange) > VOLTAGE_CHANGE }.forEach { comparison ->
            addFinding(
                id = "baseline_voltage_shift",
                title = "Voltage differs from the baseline",
                explanation = "Voltage differs from the saved baseline by more than the configured threshold. Battery, charging or connection changes can explain it.",
                supporting = listOf("${comparison.label}: ${comparison.currentText} vs baseline ${comparison.baselineText}"),
                alternatives = listOf("Battery state", "Charging system", "Connection resistance", "Measurement offset"),
                nextCheck = "Run the charging guide and compare at the battery posts.",
            )
        }
        compared.filter { it.id == "fuel" && abs(it.relativeChange) > FUEL_CHANGE && abs(it.absoluteChange) > 0.2 }.forEach { comparison ->
            addFinding(
                id = "baseline_fuel_shift",
                title = "Fuel flow differs from the baseline",
                explanation = "Fuel flow differs from the saved baseline beyond the configured threshold. Load, enrichment, airflow or a fuel-delivery change can explain it.",
                supporting = listOf("${comparison.label}: ${comparison.currentText} vs baseline ${comparison.baselineText}"),
                alternatives = listOf("Load/condition change", "Fuel delivery", "Airflow measurement", "Estimate error"),
                nextCheck = "Repeat under matched conditions and compare with a measured fuel-flow reference.",
            )
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            else -> DiagnosticStatus.NO_ANOMALY_DETECTED
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED ->
                "The matched-condition metrics are within the configured baseline band. This is a trend check, not a guarantee."
            DiagnosticStatus.SUSPICIOUS ->
                "One or more metrics differ from the saved baseline. The result lists the plausible causes and the next check."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The baseline comparison was inconclusive."
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = if (status == DiagnosticStatus.NO_ANOMALY_DETECTED) ResultConfidence.MEDIUM else ResultConfidence.LOW,
            summary = summary,
            observations = observations,
            findings = findings,
            stages = stageSummaries(input),
            quality = quality,
            warnings = warnings,
            nextSteps = nextSteps,
            limitations = listOf(
                "Baselines must match vehicle, engine state, RPM, load, ambient and capture quality.",
                "The comparison uses configurable example thresholds, not a manufacturer specification.",
                "A trend is not a diagnosis; components can drift without setting a code.",
            ),
        )
    }

    private data class MetricComparison(
        val id: String,
        val label: String,
        val currentText: String,
        val baselineText: String,
        val relativeChange: Double,
        val absoluteChange: Double,
    )

    private fun compareMetrics(current: BaselineSnapshot, baseline: BaselineSnapshot): List<MetricComparison> {
        val specs = listOf(
            Triple("maf", "MAF", "g/s"),
            Triple("map", "MAP", "kPa"),
            Triple("trim", "Combined trim B1", "%"),
            Triple("voltage", "Voltage", "V"),
            Triple("fuel", "Fuel rate", "L/h"),
        )
        val comparisons = mutableListOf<MetricComparison>()
        for ((id, label, unit) in specs) {
            for (prefix in listOf(BaselineExtractor.WARM_IDLE, BaselineExtractor.STEADY_RPM)) {
                val key = when (id) {
                    "trim" -> "$prefix.trim_b1"
                    "fuel" -> "$prefix.fuel_rate"
                    else -> "$prefix.$id"
                }
                val currentValue = current.metrics[key] ?: continue
                val baselineValue = baseline.metrics[key] ?: continue
                val absolute = currentValue - baselineValue
                val relative = if (baselineValue != 0.0) absolute / abs(baselineValue) else 0.0
                val stageLabel = if (prefix == BaselineExtractor.WARM_IDLE) "idle" else "elevated RPM"
                val decimals = when (id) {
                    "voltage" -> 2
                    "trim" -> 1
                    else -> 1
                }
                fun formatValue(value: Double): String = if (id == "trim") {
                    AnalysisSupport.formatSigned(value, decimals)
                } else {
                    AnalysisSupport.format(value, decimals)
                }
                comparisons += MetricComparison(
                    id = id,
                    label = "$label ($stageLabel)",
                    currentText = "${formatValue(currentValue)} $unit".trim(),
                    baselineText = "${formatValue(baselineValue)} $unit".trim(),
                    relativeChange = relative,
                    absoluteChange = absolute,
                )
            }
        }
        return comparisons
    }

    private fun compareCondition(
        label: String,
        current: Double?,
        baseline: Double?,
        tolerance: Double,
    ): String? {
        if (current == null || baseline == null) return null
        val allowed = if (label == "RPM") {
            maxOf(tolerance, abs(baseline) * RPM_TOLERANCE_FRACTION)
        } else {
            tolerance
        }
        return if (abs(current - baseline) > allowed) {
            "$label ${AnalysisSupport.format(current, 1)} vs baseline ${AnalysisSupport.format(baseline, 1)}"
        } else {
            null
        }
    }

    private fun stageSummaries(input: AnalysisInput): List<StageSummary> =
        input.stages.map {
            AnalysisSupport.stageSummary(it, valid = it.series(Signals.RPM).isNotEmpty(), keyPid = Signals.RPM)
        }
}
