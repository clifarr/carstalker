package com.carstalker.core.guided

import com.carstalker.core.diagnose.SignalAnalysis
import kotlin.math.abs

/**
 * EGR command/response interpreter.
 *
 * Commanded EGR and EGR error are generic OBD values, but they are not measured valve position or
 * airflow. The analyzer only reports whether the command changed, whether the ECU reported a large
 * error, and whether MAP/MAF moved in a plausible direction.
 */
object EgrAnalyzer {

    private const val MIN_COMMAND_RANGE = 2.0
    private const val ERROR_FLAG = 20.0
    private const val MAP_RESPONSE_KPA = 2.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val commanded: SeriesStats,
        val error: SeriesStats,
        val map: SeriesStats,
        val maf: SeriesStats,
        val rpm: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.EGR_COMMANDED),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val commandedPresent = stages.any { it.commanded.count >= MIN_SAMPLES }
        val errorPresent = stages.any { it.error.count >= MIN_SAMPLES }
        if (!commandedPresent && !errorPresent) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No commanded-EGR or EGR-error channel was captured.",
                nextSteps = listOf("Confirm PID 0x2C or 0x2D is supported, then repeat the test."),
            )
        }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.commanded.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} commanded EGR",
                    "${AnalysisSupport.format(stage.commanded.median, 1)} % " +
                        "(range ${AnalysisSupport.format(stage.commanded.min, 1)}–${AnalysisSupport.format(stage.commanded.max, 1)})",
                )
            }
            if (stage.error.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} EGR error",
                    "${AnalysisSupport.formatSigned(stage.error.median, 1)} % " +
                        "(range ${AnalysisSupport.format(stage.error.min, 1)}–${AnalysisSupport.format(stage.error.max, 1)})",
                )
            }
            if (stage.map.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAP",
                    AnalysisSupport.valueWithUnit(stage.map.median, "kPa", 1),
                )
            }
            if (stage.maf.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAF",
                    AnalysisSupport.valueWithUnit(stage.maf.median, "g/s", 1),
                )
            }
        }

        stages.forEach { observe(it) }

        if (!commandedPresent) {
            warnings += "Only EGR error was reported; the test cannot see how much EGR the ECU requested."
        }

        val commandStats = stages.mapNotNull { it.commanded.takeIf { stats -> stats.count >= MIN_SAMPLES } }
        val commandRange = commandStats.maxOfOrNull { it.range } ?: 0.0
        val commandedHigh = commandStats.maxOfOrNull { it.max } ?: 0.0
        if (commandedPresent && commandRange < MIN_COMMAND_RANGE) {
            findings += GuidedFinding(
                id = "egr_not_commanded",
                title = "EGR was not commanded during this test",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The ECU kept the EGR command essentially constant. Many vehicles only enable EGR under specific speed, load and temperature conditions, so a stationary capture may not exercise it. This is not evidence that the valve is stuck.",
                supporting = listOf("Commanded range ${AnalysisSupport.format(commandRange, 1)} %"),
                alternatives = listOf("EGR conditions not met", "EGR disabled by a fault/strategy", "Commanded PID not updating"),
                nextCheck = "Repeat under the manufacturer's EGR enable conditions or use a bidirectional scan tool to command the valve.",
            )
            nextSteps += "Repeat under the manufacturer's EGR enable conditions or use a bidirectional tool."
        }

        if (errorPresent) {
            val worstError = stages.mapNotNull { it.error.takeIf { stats -> stats.count >= MIN_SAMPLES } }
                .maxByOrNull { abs(it.median) }
            if (worstError != null && abs(worstError.median) > ERROR_FLAG) {
                findings += GuidedFinding(
                    id = "egr_error_high",
                    title = "Commanded and reported EGR disagree",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "The ECU reported a large EGR error. That supports a flow or control investigation, but the error value is calculated by the ECU and is not a direct valve-position measurement.",
                    supporting = listOf("EGR error median ${AnalysisSupport.formatSigned(worstError.median, 1)} %"),
                    alternatives = listOf(
                        "Carbon buildup or restricted passage",
                        "Stuck or slow EGR valve",
                        "EGR position sensor",
                        "Wiring/connector",
                        "Enable conditions or calibration",
                    ),
                    nextCheck = "Inspect the EGR passages and valve, and compare the commanded position with a bidirectional test if available.",
                )
                nextSteps += "Inspect EGR passages/valve and use a bidirectional test if available."
            }
        }

        if (commandedPresent && commandRange >= MIN_COMMAND_RANGE) {
            val mapResponse = stages.mapNotNull { stage ->
                if (stage.commanded.count < MIN_SAMPLES || stage.map.count < MIN_SAMPLES) return@mapNotNull null
                val high = highCommandMap(stage)
                val low = lowCommandMap(stage)
                if (high == null || low == null) null else high - low
            }
            val maxResponse = mapResponse.maxOrNull()
            if (maxResponse != null && maxResponse < MAP_RESPONSE_KPA) {
                findings += GuidedFinding(
                    id = "egr_no_map_response",
                    title = "No clear MAP response to EGR command",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "Manifold pressure changed little between low and high EGR command. A blocked passage, a valve that does not move or an enable-condition mismatch can explain it; MAP noise and small command changes can too.",
                    supporting = listOf("Largest high-command minus low-command MAP change ${AnalysisSupport.format(maxResponse, 1)} kPa"),
                    alternatives = listOf("Blocked EGR passage", "Valve not moving", "Command too small", "MAP noise", "Enable conditions"),
                    nextCheck = "Compare EGR command with MAP/MAF under a larger commanded change or inspect the passages.",
                )
            } else if (maxResponse != null) {
                observations += GuidedObservation(
                    "MAP response to EGR",
                    "MAP changed ${AnalysisSupport.format(maxResponse, 1)} kPa between low and high command.",
                )
            }
        }

        if (input.capabilities.profile.diesel) {
            warnings += "Diesel EGR strategies differ; the test only checks command/response consistency."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "egr_error_high" } -> DiagnosticStatus.SUSPICIOUS
            findings.any { it.id == "egr_not_commanded" } -> DiagnosticStatus.INCONCLUSIVE
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.NO_ANOMALY_DETECTED
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "EGR command changed and the captured airflow/pressure response was consistent with the command."
            DiagnosticStatus.SUSPICIOUS -> "The ECU reported a large EGR error. The result points to a flow/control check, not an automatic valve replacement."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture did not exercise EGR enough for a conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    if (analysis.commanded.available) put("egr_command_pct", analysis.commanded.median)
                    if (analysis.error.available) put("egr_error_pct", analysis.error.median)
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.MEDIUM
                findings.any { it.id == "egr_error_high" } -> ResultConfidence.MEDIUM
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
                "The ECU may not command EGR during a stationary test.",
                "Commanded EGR and EGR error are not measured valve position or flow.",
                "Carbon buildup, a stuck valve, a sensor fault and a wiring fault can look similar.",
            ),
        )
    }

    private fun highCommandMap(stage: StageAnalysis): Double? {
        val points = stage.stage.series(Signals.EGR_COMMANDED)
        val mapPoints = stage.stage.series(Signals.MAP)
        if (points.size < MIN_SAMPLES || mapPoints.size < MIN_SAMPLES) return null
        val threshold = points.map { it.value }.sorted()[points.size * 2 / 3]
        val values = mapPoints.filter { map ->
            val command = SignalAnalysis.interpolate(points, map.timestampMs) ?: return@filter false
            command >= threshold
        }.map { it.value }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun lowCommandMap(stage: StageAnalysis): Double? {
        val points = stage.stage.series(Signals.EGR_COMMANDED)
        val mapPoints = stage.stage.series(Signals.MAP)
        if (points.size < MIN_SAMPLES || mapPoints.size < MIN_SAMPLES) return null
        val threshold = points.map { it.value }.sorted()[points.size / 3]
        val values = mapPoints.filter { map ->
            val command = SignalAnalysis.interpolate(points, map.timestampMs) ?: return@filter false
            command <= threshold
        }.map { it.value }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val commanded = AnalysisSupport.statsOrNull(stage, Signals.EGR_COMMANDED) ?: AnalysisSupport.stats(emptyList())
        val error = AnalysisSupport.statsOrNull(stage, Signals.EGR_ERROR) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            commanded = commanded,
            error = error,
            map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList()),
            maf = AnalysisSupport.statsOrNull(stage, Signals.MAF) ?: AnalysisSupport.stats(emptyList()),
            rpm = rpm,
            valid = (commanded.count >= MIN_SAMPLES || error.count >= MIN_SAMPLES) && rpm.count >= MIN_SAMPLES,
        )
    }
}
