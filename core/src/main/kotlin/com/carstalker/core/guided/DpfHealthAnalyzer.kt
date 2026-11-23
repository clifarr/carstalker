package com.carstalker.core.guided

import kotlin.math.abs

/**
 * Passive DPF health interpreter.
 *
 * Uses the standard J1979 DPF pressure/temperature PIDs (0x7A/0x7B/0x7C) when the vehicle
 * advertises them. It checks sensor zero, signal liveness and pressure response to exhaust flow;
 * it never forces a regeneration, guesses soot load or applies a universal pressure limit.
 */
object DpfHealthAnalyzer {

    private const val ZERO_OFFSET_KPA = 2.0
    private const val STATIC_RANGE_KPA = 0.5
    private const val RESPONSE_KPA = 0.5
    private const val HIGH_RELATIVE_FACTOR = 3.0
    private const val HIGH_RELATIVE_KPA = 5.0
    private const val REGEN_TEMPERATURE_C = 500.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val pressurePid: Int?,
        val pressure: SeriesStats,
        val temperature: SeriesStats,
        val rpm: SeriesStats,
        val maf: SeriesStats,
        val map: SeriesStats,
        val load: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.DPF_PRESSURE_B1),
            minHz = 0.4,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val keyOn = stages.firstOrNull { it.stage.stageId == "key_on_engine_off" }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "steady_rpm" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.pressure.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} DPF pressure",
                    "${AnalysisSupport.format(stage.pressure.median, 2)} kPa " +
                        "(range ${AnalysisSupport.format(stage.pressure.min, 2)}–${AnalysisSupport.format(stage.pressure.max, 2)})",
                )
            }
            if (stage.temperature.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} DPF temperature",
                    "${AnalysisSupport.format(stage.temperature.median, 1)} °C " +
                        "(range ${AnalysisSupport.format(stage.temperature.min, 1)}–${AnalysisSupport.format(stage.temperature.max, 1)})",
                )
            }
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
        }

        observe(keyOn)
        observe(idle)
        observe(elevated)

        val pressureStages = stages.filter { it.pressure.count >= MIN_SAMPLES }
        if (pressureStages.isEmpty()) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No DPF pressure samples were captured. The vehicle may not support PID 0x7A/0x7B.",
                nextSteps = listOf("Check whether the vehicle advertises DPF pressure PID 0x7A or 0x7B."),
            )
        }

        val allPressure = pressureStages.map { it.pressure }
        val overallRange = allPressure.maxOf { it.max } - allPressure.minOf { it.min }
        val static = overallRange < STATIC_RANGE_KPA
        if (static) {
            findings += GuidedFinding(
                id = "dpf_pressure_static",
                title = "DPF pressure signal is nearly static",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "Differential pressure barely changed between key-on, idle and a higher-flow stage. A blocked sensor port, wiring fault, default value or a capture that did not change exhaust flow can produce this.",
                supporting = allPressure.map {
                    "${it.count} samples, range ${AnalysisSupport.format(it.range, 2)} kPa"
                },
                alternatives = listOf(
                    "Blocked DPF pressure port or hose",
                    "Sensor/wiring fault",
                    "ECU default value",
                    "Capture did not change exhaust flow",
                ),
                nextCheck = "Inspect the DPF pressure hoses/ports and compare the sensor with a known-good reference.",
            )
            nextSteps += "Inspect the DPF pressure hoses and sensor before condemning the filter."
        }

        val keyOnPressure = keyOn?.pressure?.takeIf { it.count >= MIN_SAMPLES }
        if (keyOnPressure != null && abs(keyOnPressure.median) > ZERO_OFFSET_KPA) {
            findings += GuidedFinding(
                id = "dpf_sensor_offset",
                title = "DPF pressure is not near zero with the engine off",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "With no exhaust flow, differential pressure should be close to zero. A larger offset points to the sensor, its plumbing or its learned zero before any filter-restriction conclusion.",
                supporting = listOf("Key-on median ${AnalysisSupport.format(keyOnPressure.median, 2)} kPa"),
                alternatives = listOf(
                    "Sensor offset/calibration",
                    "Blocked or leaking pressure hose",
                    "Water/condensation in the hose",
                    "Wiring/reference fault",
                ),
                nextCheck = "Check the pressure hoses for blockage/leaks and verify the sensor zero with the engine off.",
            )
            nextSteps += "Verify the DPF pressure sensor zero and hoses before interpreting restriction."
        }

        val idlePressure = idle?.pressure?.takeIf { it.count >= MIN_SAMPLES }
        val elevatedPressure = elevated?.pressure?.takeIf { it.count >= MIN_SAMPLES }
        if (idlePressure != null && elevatedPressure != null) {
            val rpmRatio = elevated.rpm.median / idle.rpm.median.coerceAtLeast(1.0)
            val rise = elevatedPressure.median - idlePressure.median
            if (rpmRatio > 1.5 && rise < RESPONSE_KPA) {
                findings += GuidedFinding(
                    id = "dpf_no_pressure_response",
                    title = "DPF pressure did not rise with exhaust flow",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "Differential pressure normally rises as exhaust flow increases. It did not move meaningfully between idle and the higher-RPM stage, so either the flow did not change enough or the pressure signal is not responding.",
                    supporting = listOf(
                        "Idle pressure ${AnalysisSupport.format(idlePressure.median, 2)} kPa",
                        "Elevated pressure ${AnalysisSupport.format(elevatedPressure.median, 2)} kPa",
                        "RPM ratio ${AnalysisSupport.format(rpmRatio, 2)}×",
                    ),
                    alternatives = listOf(
                        "Sensor/plumbing not responding",
                        "Insufficient exhaust-flow change",
                        "ECU strategy/regeneration",
                        "Pressure signal filtered or defaulted",
                    ),
                    nextCheck = "Repeat with a confirmed higher exhaust flow or inspect the pressure sensor/hoses.",
                )
            } else if (idlePressure.median > 0 && elevatedPressure.median > idlePressure.median * HIGH_RELATIVE_FACTOR &&
                elevatedPressure.median > HIGH_RELATIVE_KPA
            ) {
                findings += GuidedFinding(
                    id = "dpf_pressure_high_relative",
                    title = "DPF pressure rises sharply with flow",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "Differential pressure increased much more than the idle value as flow rose. That can indicate restriction, ash load, sensor/plumbing error or simply a large flow change; it is not a universal pressure limit.",
                    supporting = listOf(
                        "Idle pressure ${AnalysisSupport.format(idlePressure.median, 2)} kPa",
                        "Elevated pressure ${AnalysisSupport.format(elevatedPressure.median, 2)} kPa",
                    ),
                    alternatives = listOf(
                        "DPF restriction/ash load",
                        "Sensor/plumbing error",
                        "Large exhaust-flow change",
                        "Regeneration conditions",
                    ),
                    nextCheck = "Compare with the manufacturer's DPF pressure specification and check the pressure hoses/sensor.",
                )
                nextSteps += "Compare the relative pressure rise with the manufacturer's DPF data."
            }
        } else {
            warnings += "Both warm-idle and steady-RPM stages are needed to assess DPF pressure response."
        }

        val temperatures = stages.mapNotNull { it.temperature.takeIf { stats -> stats.available } }
        if (temperatures.isEmpty()) {
            warnings += "DPF temperature (PID 0x7C) was not reported; regeneration temperature cannot be observed."
        } else {
            val implausible = temperatures.firstOrNull { it.median < -40.0 || it.median > 1_000.0 }
            if (implausible != null) {
                findings += GuidedFinding(
                    id = "dpf_temperature_implausible",
                    title = "DPF temperature is implausible",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "The DPF temperature is outside the physically plausible range. Check the sensor, wiring and scaling before interpreting regeneration or restriction.",
                    supporting = listOf("Median ${AnalysisSupport.format(implausible.median, 1)} °C"),
                    alternatives = listOf("Temperature sensor/wiring", "Scaling/unit mismatch", "ECU default value"),
                    nextCheck = "Inspect the DPF temperature sensor and verify the PID scaling.",
                )
            }
            val regen = temperatures.maxByOrNull { it.max }
            if (regen != null && regen.max > REGEN_TEMPERATURE_C) {
                observations += GuidedObservation(
                    "DPF temperature peak",
                    "${AnalysisSupport.format(regen.max, 1)} °C — a regeneration may have been in progress.",
                )
            }
        }

        val dpfCodes = input.activeCodes.filter {
            it.startsWith("P2002") || it.startsWith("P2463") || it.startsWith("P2452")
        }
        if (dpfCodes.isNotEmpty()) {
            warnings += "DPF-related code(s) present: ${dpfCodes.joinToString()}. The live data supports the next check; it does not prove the filter or sensor is the failed part."
        }
        warnings += "Generic OBD does not provide soot load, ash load or regeneration status on every diesel."
        warnings += "No universal DPF differential-pressure limit is assumed; use the manufacturer's data."

        val responds = idlePressure != null && elevatedPressure != null &&
            elevatedPressure.median > idlePressure.median + RESPONSE_KPA
        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            responds -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED ->
                "DPF pressure was responsive and the engine-off zero was plausible in this capture. Soot load and regeneration status still need manufacturer data."
            DiagnosticStatus.SUSPICIOUS ->
                "DPF data shows a plausibility flag. The result points to the next sensor/filter check without proving a failed part."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The DPF capture did not contain enough pressure response for a conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    if (analysis.pressure.available) put("dpf_pressure_kpa", analysis.pressure.median)
                    if (analysis.temperature.available) put("dpf_temperature_c", analysis.temperature.median)
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                status == DiagnosticStatus.NO_ANOMALY_DETECTED && temperatures.isNotEmpty() -> ResultConfidence.MEDIUM
                findings.isNotEmpty() -> ResultConfidence.LOW
                else -> ResultConfidence.LOW
            },
            summary = summary,
            observations = observations,
            findings = findings,
            stages = stageSummaries,
            quality = quality,
            warnings = warnings,
            nextSteps = nextSteps,
            limitations = listOf(
                "Standard DPF pressure/temperature PIDs are not supported by every diesel.",
                "Generic OBD does not expose soot load, ash load or regeneration status on every vehicle.",
                "Relative pressure changes are used; no universal kPa limit is assumed.",
                "A high post-regeneration value can be ash, restriction, sensor/plumbing error or unfavourable conditions.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val pressurePid = when {
            stage.series(Signals.DPF_PRESSURE_B1).isNotEmpty() -> Signals.DPF_PRESSURE_B1
            stage.series(Signals.DPF_PRESSURE_B2).isNotEmpty() -> Signals.DPF_PRESSURE_B2
            else -> null
        }
        val pressure = pressurePid?.let { AnalysisSupport.statsOrNull(stage, it) }
            ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            pressurePid = pressurePid,
            pressure = pressure,
            temperature = AnalysisSupport.statsOrNull(stage, Signals.DPF_TEMPERATURE) ?: AnalysisSupport.stats(emptyList()),
            rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList()),
            maf = AnalysisSupport.statsOrNull(stage, Signals.MAF) ?: AnalysisSupport.stats(emptyList()),
            map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList()),
            load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList()),
            valid = pressure.count >= MIN_SAMPLES && stage.series(Signals.RPM).size >= MIN_SAMPLES,
        )
    }
}
