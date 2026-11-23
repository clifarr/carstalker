package com.carstalker.core.guided

import kotlin.math.abs

/**
 * MAF plausibility interpreter.
 *
 * Generic OBD does not provide the engine's expected airflow map, so this test checks whether the
 * MAF signal is alive and scales sensibly with RPM, MAP and load. It deliberately avoids universal
 * grams-per-second thresholds.
 */
object MafPlausibilityAnalyzer {

    private const val STATIC_AMPLITUDE_GPS = 0.5
    private const val MIN_SCALING_RATIO = 0.75
    private const val MAX_SCALING_RATIO = 1.35
    private const val MAP_DISAGREEMENT = 0.30
    private const val TRIM_CONTEXT = 15.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val maf: SeriesStats,
        val rpm: SeriesStats,
        val map: SeriesStats,
        val load: SeriesStats,
        val iat: SeriesStats,
        val throttle: SeriesStats,
        val combinedTrim: SeriesStats?,
        val valid: Boolean,
    ) {
        val mafPerRpm: Double?
            get() = if (rpm.median > 100 && maf.available) maf.median / rpm.median else null
        val mafPerRpmMap: Double?
            get() = if (rpm.median > 100 && map.median > 5 && maf.available) {
                maf.median / (rpm.median * map.median)
            } else {
                null
            }
    }

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.MAF),
            minHz = 0.8,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "steady_rpm" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.maf.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAF",
                    "${AnalysisSupport.format(stage.maf.median, 1)} g/s " +
                        "(range ${AnalysisSupport.format(stage.maf.min, 1)}–${AnalysisSupport.format(stage.maf.max, 1)})",
                )
            }
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
            if (stage.map.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAP",
                    AnalysisSupport.valueWithUnit(stage.map.median, "kPa", 1),
                )
            }
            if (stage.load.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} load",
                    AnalysisSupport.valueWithUnit(stage.load.median, "%", 1),
                )
            }
            stage.combinedTrim?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(it.median)} %",
                )
            }
        }

        observe(idle)
        observe(elevated)

        if (stages.isEmpty() || stages.all { !it.maf.available }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No MAF samples were captured. Confirm the car reports PID 0x10, then repeat the test.",
                nextSteps = listOf("Confirm PID 0x10 is supported, then repeat the warm-idle and steady-RPM stages."),
            )
        }

        val bothStages = idle?.maf?.available == true && elevated?.maf?.available == true
        val allMaf = stages.mapNotNull { it.maf.takeIf { stats -> stats.available } }
        val overallMafRange = if (allMaf.isEmpty()) 0.0 else {
            allMaf.maxOf { it.max } - allMaf.minOf { it.min }
        }
        val static = allMaf.isNotEmpty() && overallMafRange < STATIC_AMPLITUDE_GPS
        if (static) {
            findings += GuidedFinding(
                id = "maf_static",
                title = "MAF signal is nearly static",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The MAF reading barely changed between idle and a higher airflow condition. A disconnected sensor, a wiring fault, a default value or an engine that did not change airflow can produce this.",
                supporting = allMaf.map {
                    "${it.count} samples, range ${AnalysisSupport.format(it.range, 2)} g/s"
                },
                against = listOf("The engine may not have reached a meaningfully different airflow"),
                alternatives = listOf(
                    "MAF sensor or connector",
                    "Wiring/reference fault",
                    "ECU default/limp value",
                    "Intake obstruction",
                    "Capture condition did not change airflow",
                ),
                nextCheck = "Check the MAF connector and signal with the key on and during a gentle throttle change; compare with a known-good sensor if needed.",
            )
            nextSteps += "Inspect the MAF connector and signal before replacing the sensor."
        }

        if (bothStages) {
            val rpmRatio = elevated.rpm.median / idle.rpm.median.coerceAtLeast(1.0)
            val mafRatio = elevated.maf.median / idle.maf.median.coerceAtLeast(0.1)
            if (rpmRatio > 1.5) {
                val expectedLow = rpmRatio * MIN_SCALING_RATIO
                val expectedHigh = rpmRatio * MAX_SCALING_RATIO
                if (mafRatio < expectedLow || mafRatio > expectedHigh) {
                    val direction = if (mafRatio < expectedLow) "under-reports" else "over-reports"
                    findings += GuidedFinding(
                        id = "maf_scaling",
                        title = "MAF does not scale with engine speed",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "Airflow normally rises broadly with RPM at a similar throttle/load. The MAF ratio was outside the configured band, so the signal $direction the airflow change. This is a plausibility flag, not a grams-per-second specification.",
                        supporting = listOf(
                            "RPM ratio ${AnalysisSupport.format(rpmRatio, 2)}×",
                            "MAF ratio ${AnalysisSupport.format(mafRatio, 2)}×",
                            "Idle MAF ${AnalysisSupport.format(idle.maf.median, 1)} g/s; elevated ${AnalysisSupport.format(elevated.maf.median, 1)} g/s",
                        ),
                        alternatives = listOf(
                            "MAF sensor contamination/fault",
                            "Intake leak or restriction",
                            "Throttle/load difference between stages",
                            "Exhaust restriction",
                            "Wiring or scaling issue",
                        ),
                        nextCheck = "Compare MAF with MAP/load at the same RPM, inspect the intake, and check the sensor signal with a scope or known-good part.",
                    )
                    nextSteps += "Compare MAF with MAP/load at matched RPM and inspect the intake for leaks or restrictions."
                }
            }

            val idleMapRatio = idle.mafPerRpmMap
            val elevatedMapRatio = elevated.mafPerRpmMap
            if (idleMapRatio != null && elevatedMapRatio != null && idleMapRatio > 0 && elevatedMapRatio > 0) {
                val change = abs(elevatedMapRatio - idleMapRatio) / idleMapRatio
                if (change > MAP_DISAGREEMENT) {
                    findings += GuidedFinding(
                        id = "maf_map_disagreement",
                        title = "MAF and MAP do not tell the same airflow story",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "The MAF-to-RPM-and-MAP ratio changed materially between stages. A real airflow change, a sensor bias or a capture-condition difference can explain it; it is not proof of a MAF failure.",
                        supporting = listOf(
                            "Idle MAF/(RPM·MAP) ${AnalysisSupport.format(idleMapRatio * 1000, 2)}",
                            "Elevated MAF/(RPM·MAP) ${AnalysisSupport.format(elevatedMapRatio * 1000, 2)}",
                            "Change ${AnalysisSupport.format(change * 100, 0)} %",
                        ),
                        alternatives = listOf("MAF bias", "MAP bias", "Throttle/load difference", "Intake leak/restriction"),
                        nextCheck = "Repeat at matched RPM/load, then compare MAF and MAP against a known-good reference.",
                    )
                }
            }
        }

        val trim = stages.mapNotNull { it.combinedTrim }.firstOrNull { abs(it.median) > TRIM_CONTEXT }
        if (trim != null) {
            val direction = if (trim.median > 0) "positive (lean correction)" else "negative (rich correction)"
            findings += GuidedFinding(
                id = "maf_trim_context",
                title = "Fuel trim is outside the normal band",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The fuel trim is $direction. A MAF bias can cause this, but so can fuel pressure, injectors, leaks and O2 sensors. The trim alone does not identify the airflow meter.",
                supporting = listOf("Combined trim median ${AnalysisSupport.formatSigned(trim.median)} %"),
                alternatives = listOf("MAF bias", "Intake/exhaust leak", "Fuel delivery", "O2 sensor"),
                nextCheck = "Run the lean or rich mixture guide to see whether the trim pattern changes with airflow.",
            )
        }

        if (!bothStages) {
            warnings += "Both a warm-idle and a steady-RPM stage are needed to assess MAF scaling."
        }
        if (input.capabilities.profile.fuelType == FuelType.UNKNOWN) {
            warnings += "Engine type is not set; the airflow interpretation is generic."
        }
        if (input.capabilities.profile.diesel) {
            warnings += "Diesel airflow behaviour differs; the test only checks consistency, not a petrol expected value."
        }

        val plausible = findings.none { it.id == "maf_static" || it.id == "maf_scaling" } && bothStages
        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            plausible -> DiagnosticStatus.NO_ANOMALY_DETECTED
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "The MAF signal was alive and scaled broadly with RPM and airflow in this capture."
            DiagnosticStatus.SUSPICIOUS -> "The MAF signal has a plausibility flag. The result lists the competing explanations."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture did not contain enough airflow data for a conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.MAF,
                metrics = buildMap {
                    put("maf_gps", analysis.maf.median)
                    put("rpm", analysis.rpm.median)
                    analysis.combinedTrim?.let { put("combined_trim_b1", it.median) }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                plausible -> ResultConfidence.MEDIUM
                findings.any { it.id == "maf_static" } -> ResultConfidence.MEDIUM
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
                "The test does not know the engine displacement, volumetric efficiency or manufacturer airflow map.",
                "It checks consistency and response, not a universal grams-per-second specification.",
                "A dirty MAF, intake leak, exhaust restriction or wiring fault can all shift the signal.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val maf = AnalysisSupport.statsOrNull(stage, Signals.MAF) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            maf = maf,
            rpm = rpm,
            map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList()),
            load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList()),
            iat = AnalysisSupport.statsOrNull(stage, Signals.IAT) ?: AnalysisSupport.stats(emptyList()),
            throttle = AnalysisSupport.statsOrNull(stage, Signals.THROTTLE) ?: AnalysisSupport.stats(emptyList()),
            combinedTrim = AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1),
            valid = maf.count >= MIN_SAMPLES && rpm.count >= MIN_SAMPLES,
        )
    }
}
