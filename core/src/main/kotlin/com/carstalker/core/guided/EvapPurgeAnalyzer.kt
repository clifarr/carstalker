package com.carstalker.core.guided

import com.carstalker.core.diagnose.SignalAnalysis
import kotlin.math.abs

/**
 * EVAP purge response interpreter.
 *
 * Generic OBD exposes the purge command, not valve position or actual flow. The test can only
 * correlate command with fuel-trim response; it cannot locate a leak or prove which valve failed.
 */
object EvapPurgeAnalyzer {

    private const val MIN_PURGE_PCT = 2.0
    private const val TRIM_RESPONSE_PCT = 3.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val purge: SeriesStats,
        val trim: SeriesStats?,
        val rpm: SeriesStats,
        val map: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.EVAP_PURGE),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        if (stages.isEmpty() || stages.all { !it.purge.available }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No commanded-purge channel was captured. Generic OBD may not expose EVAP purge on this vehicle.",
                nextSteps = listOf("Confirm PID 0x2E is supported; use a smoke/leak test for P0442/P0455 instead."),
            )
        }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.purge.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} purge command",
                    "${AnalysisSupport.format(stage.purge.median, 1)} % " +
                        "(range ${AnalysisSupport.format(stage.purge.min, 1)}–${AnalysisSupport.format(stage.purge.max, 1)})",
                )
            }
            stage.trim?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(it.median)} %",
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
        }

        stages.forEach { observe(it) }

        val purgeStats = stages.mapNotNull { it.purge.takeIf { stats -> stats.count >= MIN_SAMPLES } }
        val maxPurge = purgeStats.maxOfOrNull { it.max } ?: 0.0
        val purgeRange = purgeStats.maxOfOrNull { it.range } ?: 0.0

        if (maxPurge < MIN_PURGE_PCT) {
            findings += GuidedFinding(
                id = "purge_not_commanded",
                title = "EVAP purge was not commanded during this test",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The ECU kept the purge command near zero. Many vehicles only enable purge under specific coolant, speed, load and fuel-level conditions, so this capture did not exercise the system.",
                supporting = listOf("Maximum commanded purge ${AnalysisSupport.format(maxPurge, 1)} %"),
                alternatives = listOf("Enable conditions not met", "Purge disabled by a fault/strategy", "Commanded PID not updating"),
                nextCheck = "Repeat after a normal drive/refuel when purge is enabled, or use a bidirectional scan tool to command the purge valve.",
            )
            nextSteps += "Repeat when purge is enabled or use a bidirectional tool to command the valve."
        }

        val trimStages = stages.filter { it.trim != null }
        if (maxPurge >= MIN_PURGE_PCT && trimStages.isNotEmpty()) {
            val stage = trimStages.maxByOrNull { it.purge.max }!!
            val trimPoints = AnalysisSupport.combinedTrim(
                stage.stage.series(Signals.STFT_B1),
                stage.stage.series(Signals.LTFT_B1),
            )
            val correlation = SignalAnalysis.correlation(stage.stage.series(Signals.EVAP_PURGE), trimPoints)
            if (correlation != null) {
                observations += GuidedObservation(
                    "Purge/trim correlation",
                    AnalysisSupport.format(correlation, 2),
                )
                if (correlation > -0.2) {
                    findings += GuidedFinding(
                        id = "purge_no_trim_response",
                        title = "No clear trim response to purge command",
                        evidenceLevel = EvidenceLevel.WEAK,
                        confidence = ResultConfidence.LOW,
                        explanation = "Purge command changed but the combined trim did not show the expected enrichment response. A canister that is not saturated, a small command, a blocked line or a valve that is not flowing can all explain it.",
                        supporting = listOf("Purge/trim correlation ${AnalysisSupport.format(correlation, 2)}"),
                        alternatives = listOf(
                            "Canister not saturated",
                            "Small purge command",
                            "Blocked/leaking vapour line",
                            "Purge valve not flowing",
                            "Trim response masked by other corrections",
                        ),
                        nextCheck = "Repeat after a refuel, and inspect the purge valve/line with a bidirectional test or smoke machine.",
                    )
                    nextSteps += "Inspect the purge valve and line with a bidirectional test or smoke machine."
                } else {
                    findings += GuidedFinding(
                        id = "purge_trim_response",
                        title = "Trim responds to purge command",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "The combined trim moved in the expected enrichment direction as purge command changed. That supports a working flow path, but it does not locate a leak or prove the valve is healthy.",
                        supporting = listOf("Purge/trim correlation ${AnalysisSupport.format(correlation, 2)}"),
                        alternatives = listOf("Normal purge flow", "Coincidental trim correction", "Other vapour source"),
                        nextCheck = "Use a smoke or leak test if a leak code remains; do not replace parts based on this correlation alone.",
                    )
                }
            }

            if (trimStages.size >= 2) {
                val idle = trimStages.firstOrNull { it.stage.stageId == "warm_idle" }
                val elevated = trimStages.firstOrNull { it.stage.stageId == "steady_rpm" }
                if (idle?.trim != null && elevated?.trim != null) {
                    val trimChange = elevated.trim.median - idle.trim.median
                    val purgeChange = elevated.purge.median - idle.purge.median
                    if (purgeChange > MIN_PURGE_PCT && abs(trimChange) < TRIM_RESPONSE_PCT) {
                        findings += GuidedFinding(
                            id = "purge_stage_no_response",
                            title = "Higher purge command did not change the trim",
                            evidenceLevel = EvidenceLevel.WEAK,
                            confidence = ResultConfidence.LOW,
                            explanation = "The elevated-RPM stage had a higher purge command but a similar combined trim. This can be normal if the canister is not saturated or the ECU compensates quickly; it is not proof of a failed valve.",
                            supporting = listOf(
                                "Purge change ${AnalysisSupport.format(purgeChange, 1)} percentage points",
                                "Trim change ${AnalysisSupport.formatSigned(trimChange)} percentage points",
                            ),
                            alternatives = listOf("Canister not saturated", "Fast ECU compensation", "Blocked line", "Valve flow issue"),
                            nextCheck = "Repeat after a refuel and inspect the purge system if the code persists.",
                        )
                    }
                }
            }
        } else if (purgeRange >= MIN_PURGE_PCT) {
            warnings += "Purge command changed but no usable fuel-trim channel was captured; response could not be assessed."
        }

        warnings += "Generic OBD cannot locate an EVAP leak or prove which valve failed."
        if (input.activeCodes.any { it.startsWith("P0455") || it.startsWith("P0457") }) {
            warnings += "A large-leak code is present; use a smoke or leak test rather than a parts guess."
            nextSteps += "Perform a smoke/leak test for the large-leak code."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "purge_not_commanded" } -> DiagnosticStatus.INCONCLUSIVE
            findings.any { it.id == "purge_no_trim_response" || it.id == "purge_stage_no_response" } -> DiagnosticStatus.INCONCLUSIVE
            findings.any { it.id == "purge_trim_response" } -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "A purge/trim response was observed. This does not locate a leak or prove the valve is healthy."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture did not exercise EVAP purge enough for a conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.EVAP_PURGE,
                metrics = buildMap {
                    if (analysis.purge.available) put("purge_pct", analysis.purge.median)
                    analysis.trim?.let { put("combined_trim_b1", it.median) }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = ResultConfidence.LOW,
            summary = summary,
            observations = observations,
            findings = findings,
            stages = stageSummaries,
            quality = quality,
            warnings = warnings,
            nextSteps = nextSteps,
            limitations = listOf(
                "Generic OBD exposes purge command, not purge-valve position or actual flow.",
                "A canister that is not saturated may produce little or no trim response.",
                "The test cannot locate a leak or prove which valve or hose failed.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val purge = AnalysisSupport.statsOrNull(stage, Signals.EVAP_PURGE) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            purge = purge,
            trim = AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1),
            rpm = rpm,
            map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList()),
            valid = purge.count >= MIN_SAMPLES && rpm.count >= MIN_SAMPLES,
        )
    }
}
