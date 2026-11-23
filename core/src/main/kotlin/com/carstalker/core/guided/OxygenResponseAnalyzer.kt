package com.carstalker.core.guided

import com.carstalker.core.diagnose.SignalAnalysis
import com.carstalker.core.diagnose.SignalStats
import kotlin.math.abs

/**
 * Oxygen-sensor response interpreter.
 *
 * The sensor technology decides the rules: narrowband voltage switching is never applied to a
 * wideband/equivalence-ratio or pump-current channel. Sampling rate and heater/circuit codes are
 * treated as evidence-quality gates, not as component verdicts.
 */
object OxygenResponseAnalyzer {

    private const val NARROWBAND_SWITCH_V = 0.45
    private const val MIN_SWITCH_AMPLITUDE_V = 0.10
    private const val MIN_RESPONSE_AMPLITUDE_V = 0.15
    private const val MIN_O2_HZ = 2.0
    private const val WIDEBAND_RESPONSE = 0.05

    private data class StageAnalysis(
        val stage: StageCapture,
        val valid: Boolean,
        val rpm: SeriesStats,
        val coolant: SeriesStats,
        val throttle: SeriesStats,
        val narrowband: SignalStats?,
        val downstream: SignalStats?,
        val wideband: SeriesStats?,
        val o2Hz: Double,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.O2_B1S1),
            minHz = 1.0,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val response = stages.firstOrNull { it.stage.stageId == "throttle_response" }
        val narrowbandPresent = stages.any { it.narrowband != null }
        val widebandPresent = stages.any { it.wideband != null }
        val downstreamPresent = stages.any { it.downstream != null }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
            stage.narrowband?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} upstream O2",
                    "${AnalysisSupport.format(it.min, 3)}–${AnalysisSupport.format(it.max, 3)} V, " +
                        "${it.switchingCount} transition(s), ${AnalysisSupport.format(it.frequencyHz, 2)} Hz",
                )
            }
            stage.downstream?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} downstream O2",
                    "${AnalysisSupport.format(it.min, 3)}–${AnalysisSupport.format(it.max, 3)} V, " +
                        "${it.switchingCount} transition(s)",
                )
            }
            stage.wideband?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} wideband",
                    "${AnalysisSupport.format(it.min, 3)}–${AnalysisSupport.format(it.max, 3)} λ " +
                        "(median ${AnalysisSupport.format(it.median, 3)})",
                )
            }
            if (stage.o2Hz > 0) {
                observations += GuidedObservation(
                    "${stage.stage.title} O2 sample rate",
                    "${AnalysisSupport.format(stage.o2Hz, 1)} Hz",
                )
            }
        }

        observe(idle)
        observe(response)

        if (stages.isEmpty()) {
            return AnalysisSupport.unable(input, quality, "No oxygen-sensor stage was captured.")
        }

        val heaterCodes = input.activeCodes.filter { isHeaterOrCircuitCode(it) }
        if (heaterCodes.isNotEmpty()) {
            findings += GuidedFinding(
                id = "heater_or_circuit_code",
                title = "Heater or circuit code present",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The ECU reports an oxygen-sensor heater or circuit fault. A sensor can look slow or static because it is not at operating temperature or because its circuit is faulty, so response data must be interpreted after that fault is fixed.",
                supporting = listOf("Active code(s): ${heaterCodes.joinToString()}"),
                alternatives = listOf("Heater element", "Heater circuit/wiring", "Sensor connector", "ECU driver"),
                nextCheck = "Repair the heater/circuit fault and re-run the response test before condemning the sensor.",
            )
            warnings += "A heater/circuit code makes the live response test less reliable."
            nextSteps += "Fix the heater or circuit code first, then repeat the response test."
        }

        if (!narrowbandPresent && !widebandPresent) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No upstream oxygen channel was captured. Confirm the car reports a conventional O2 or wideband PID.",
                nextSteps = listOf("Confirm PID 0x14 or a wideband PID is supported, then repeat the test."),
            )
        }

        if (narrowbandPresent) {
            val lowRate = stages.filter { it.narrowband != null && it.o2Hz < MIN_O2_HZ }
            if (lowRate.isNotEmpty()) {
                warnings += "The upstream O2 channel was sampled below ${AnalysisSupport.format(MIN_O2_HZ, 1)} Hz; switching-rate conclusions are limited."
                findings += GuidedFinding(
                    id = "sampling_inadequate",
                    title = "Sampling rate is too low for a switching verdict",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "Narrowband switching and response time need a faster sample stream than this capture provided. The app will not call the sensor slow or stuck from an under-sampled trace.",
                    supporting = lowRate.map { "${it.stage.title}: ${AnalysisSupport.format(it.o2Hz, 1)} Hz" },
                    alternatives = listOf("Adapter throughput", "Too many active PIDs", "Protocol latency"),
                    nextCheck = "Repeat the test with fewer active PIDs or a faster adapter, then compare the switching rate.",
                )
            }

            val idleUp = idle?.narrowband
            val responseUp = response?.narrowband
            val idleAmplitude = idleUp?.amplitude ?: 0.0
            val responseAmplitude = responseUp?.amplitude ?: 0.0
            val sampledAdequately = stages.filter { it.narrowband != null }.all { it.o2Hz >= MIN_O2_HZ }

            if (sampledAdequately && idleUp != null && responseUp != null) {
                when {
                    idleAmplitude < MIN_SWITCH_AMPLITUDE_V && responseAmplitude < MIN_SWITCH_AMPLITUDE_V -> {
                        findings += GuidedFinding(
                            id = "narrowband_static",
                            title = "Upstream O2 shows no switching",
                            evidenceLevel = EvidenceLevel.MODERATE,
                            confidence = if (heaterCodes.isEmpty()) ResultConfidence.MEDIUM else ResultConfidence.LOW,
                            explanation = "The upstream narrowband voltage stayed nearly flat at idle and during a gentle throttle change. A stuck sensor, an open-loop condition, a circuit fault or an under-sampled adapter can all do this.",
                            supporting = listOf(
                                "Idle amplitude ${AnalysisSupport.format(idleAmplitude, 3)} V",
                                "Response amplitude ${AnalysisSupport.format(responseAmplitude, 3)} V",
                            ),
                            against = listOf("The engine may not have been in closed loop during the capture"),
                            alternatives = listOf(
                                "Stuck or contaminated O2 sensor",
                                "Heater/circuit fault",
                                "Open-loop or fuel-control fault",
                                "Adapter sampling limitation",
                            ),
                            nextCheck = "Confirm closed-loop status and heater codes, then compare the sensor with a known-good signal or scope.",
                        )
                        nextSteps += "Confirm closed loop and heater operation before replacing the upstream sensor."
                    }
                    idleAmplitude >= MIN_SWITCH_AMPLITUDE_V && responseAmplitude < MIN_RESPONSE_AMPLITUDE_V -> {
                        findings += GuidedFinding(
                            id = "narrowband_weak_response",
                            title = "Upstream O2 switches at idle but responds weakly",
                            evidenceLevel = EvidenceLevel.SUPPORTIVE,
                            confidence = ResultConfidence.LOW,
                            explanation = "The sensor switched at idle but its amplitude did not increase meaningfully during the throttle change. A lazy sensor, a small mixture change or a sampling limitation can explain it.",
                            supporting = listOf(
                                "Idle amplitude ${AnalysisSupport.format(idleAmplitude, 3)} V",
                                "Response amplitude ${AnalysisSupport.format(responseAmplitude, 3)} V",
                            ),
                            alternatives = listOf("Lazy sensor", "Small mixture change", "Sampling limitation", "Exhaust leak"),
                            nextCheck = "Repeat with a larger, safe throttle change and a faster sample rate before condemning the sensor.",
                        )
                    }
                }
            }

            val idleDown = idle?.downstream
            val responseDown = response?.downstream
            if (downstreamPresent && sampledAdequately && idleDown != null && idleUp != null) {
                val downstreamSwitching = idleDown.switchingCount + (responseDown?.switchingCount ?: 0)
                val upstreamSwitching = idleUp.switchingCount + (responseUp?.switchingCount ?: 0)
                if (upstreamSwitching >= 4 && downstreamSwitching >= upstreamSwitching * 0.7) {
                    findings += GuidedFinding(
                        id = "downstream_switching",
                        title = "Downstream O2 switches nearly as much as upstream",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "A healthy catalyst normally damps the downstream signal. Similar switching can indicate reduced catalyst conversion, but a lazy or contaminated downstream sensor can look the same.",
                        supporting = listOf(
                            "Upstream transitions $upstreamSwitching",
                            "Downstream transitions $downstreamSwitching",
                        ),
                        alternatives = listOf("Reduced catalyst conversion", "Downstream sensor response", "Exhaust leak", "Sampling limitation"),
                        nextCheck = "Compare the downstream trace with the catalyst monitor and inspect for exhaust leaks before condemning the converter.",
                    )
                }
            }
        }

        if (widebandPresent) {
            val idleLambda = idle?.wideband
            val responseLambda = response?.wideband
            if (idleLambda != null && responseLambda != null) {
                val change = abs(responseLambda.median - idleLambda.median)
                val spread = responseLambda.range
                if (change < WIDEBAND_RESPONSE && spread < WIDEBAND_RESPONSE) {
                    findings += GuidedFinding(
                        id = "wideband_no_response",
                        title = "Wideband signal shows little response",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "The wideband/equivalence-ratio signal changed very little during the throttle response. A small mixture change, a filtered signal or a sensor/circuit issue can explain it; narrowband switching rules do not apply.",
                        supporting = listOf(
                            "Idle median ${AnalysisSupport.format(idleLambda.median, 3)} λ",
                            "Response median ${AnalysisSupport.format(responseLambda.median, 3)} λ",
                        ),
                        alternatives = listOf("Small mixture change", "Filtered/calculated signal", "Sensor or circuit fault", "Sampling limitation"),
                        nextCheck = "Repeat with a safe, larger throttle change and verify the sensor type and PID scaling.",
                    )
                }
            }
        }

        if (narrowbandPresent && !widebandPresent) {
            warnings += "Narrowband voltage rules were applied because the car reports conventional O2 PIDs. Wideband rules are different."
        }
        if (widebandPresent && narrowbandPresent) {
            warnings += "Both narrowband and wideband channels were present; the interpretation used the narrowband channel and ignored the wideband channel for switching."
        }
        if (!downstreamPresent) {
            warnings += "No downstream O2 channel was reported; catalyst damping cannot be assessed."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "sampling_inadequate" } -> DiagnosticStatus.INCONCLUSIVE
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            narrowbandPresent && stages.any { (it.narrowband?.switchingCount ?: 0) > 0 } -> DiagnosticStatus.NO_ANOMALY_DETECTED
            widebandPresent && stages.any { it.wideband != null } -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "A switching upstream signal was captured. This does not prove the sensor is healthy under every condition."
            DiagnosticStatus.SUSPICIOUS -> "The oxygen-sensor response needs attention. The result lists the competing explanations."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture could not support a reliable response conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    analysis.narrowband?.let {
                        put("o2_switching_hz", it.frequencyHz)
                        put("o2_amplitude_v", it.amplitude)
                    }
                    analysis.wideband?.let { put("lambda_range", it.range) }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.MEDIUM
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
                "Narrowband switching is not a heater-performance test and does not measure response time in milliseconds.",
                "A downstream sensor is intentionally damped by a healthy catalyst.",
                "Wideband/equivalence-ratio and pump-current sensors use different rules and units.",
                "Heater and circuit codes must be fixed before the response can be trusted.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val narrowbandPoints = stage.series(Signals.O2_B1S1)
        val downstreamPoints = stage.series(Signals.O2_B1S2)
        val widebandPoints = stage.series(Signals.WIDEBAND_B1S1).ifEmpty {
            stage.series(Signals.WIDEBAND_CURRENT_B1S1)
        }
        val o2Hz = stage.averageHz(Signals.O2_B1S1).takeIf { it > 0 }
            ?: stage.averageHz(Signals.WIDEBAND_B1S1)
        return StageAnalysis(
            stage = stage,
            valid = stage.series(Signals.RPM).size >= 3 && stage.series(Signals.COOLANT).isNotEmpty(),
            rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList()),
            coolant = AnalysisSupport.statsOrNull(stage, Signals.COOLANT) ?: AnalysisSupport.stats(emptyList()),
            throttle = AnalysisSupport.statsOrNull(stage, Signals.THROTTLE) ?: AnalysisSupport.stats(emptyList()),
            narrowband = narrowbandPoints.takeIf { it.isNotEmpty() }?.let { SignalAnalysis.stats(it) },
            downstream = downstreamPoints.takeIf { it.isNotEmpty() }?.let { SignalAnalysis.stats(it) },
            wideband = widebandPoints.takeIf { it.isNotEmpty() }?.let { AnalysisSupport.stats(it) },
            o2Hz = o2Hz,
        )
    }

    private fun isHeaterOrCircuitCode(code: String): Boolean {
        val normalized = code.trim().uppercase()
        return normalized.startsWith("P0135") ||
            normalized.startsWith("P0141") ||
            normalized.startsWith("P0155") ||
            normalized.startsWith("P0161") ||
            normalized.startsWith("P0130") ||
            normalized.startsWith("P0131") ||
            normalized.startsWith("P0132") ||
            normalized.startsWith("P0133") ||
            normalized.startsWith("P0134")
    }
}
