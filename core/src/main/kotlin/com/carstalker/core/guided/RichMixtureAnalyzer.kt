package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint
import kotlin.math.abs

/**
 * Rich-mixture interpreter for P0172/P0175.
 *
 * Negative combined trims are compared at warm idle and a steady elevated RPM. The rules are
 * deliberately conservative: an idle-only negative trim is consistent with purge vapour, an
 * injector or airflow bias, but the result always lists the competing explanations.
 */
object RichMixtureAnalyzer {

    private const val RICH_TRIM_FLAG = -10.0
    private const val TRIM_CHANGE_FLAG = 10.0
    private const val NORMAL_BAND = 10.0
    private const val MIN_TRIM_SAMPLES = 8

    private data class BankCombined(
        val bank: Int,
        val points: List<CapturePoint>,
        val stats: SeriesStats,
    )

    private data class StageAnalysis(
        val stage: StageCapture,
        val valid: Boolean,
        val rpm: SeriesStats,
        val coolant: SeriesStats,
        val closedLoopFraction: Double?,
        val combined: Map<Int, BankCombined>,
        val upstreamO2: SeriesStats?,
        val maf: SeriesStats?,
    ) {
        fun bank(bank: Int): BankCombined? = combined[bank]?.takeIf { it.stats.count >= MIN_TRIM_SAMPLES }
        val validTrims: Boolean get() = valid && combined.values.any { it.stats.count >= MIN_TRIM_SAMPLES }
    }

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.STFT_B1, Signals.LTFT_B1),
        )
        val analyses = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val idle = analyses.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = analyses.firstOrNull { it.stage.stageId == "steady_rpm" }

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
            if (stage.coolant.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} coolant",
                    AnalysisSupport.valueWithUnit(stage.coolant.median, "°C", 1),
                )
            }
            stage.bank(1)?.let { bank ->
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(bank.stats.median)} % " +
                        "(p10 ${AnalysisSupport.formatSigned(bank.stats.p10)}, p90 ${AnalysisSupport.formatSigned(bank.stats.p90)})",
                )
            }
            stage.bank(2)?.let { bank ->
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B2",
                    "${AnalysisSupport.formatSigned(bank.stats.median)} % " +
                        "(p10 ${AnalysisSupport.formatSigned(bank.stats.p10)}, p90 ${AnalysisSupport.formatSigned(bank.stats.p90)})",
                )
            }
            stage.closedLoopFraction?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} closed loop",
                    "${AnalysisSupport.format(it * 100, 0)} % of samples",
                )
            }
            stage.upstreamO2?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} upstream O2",
                    "${AnalysisSupport.format(it.median, 3)} V (range ${AnalysisSupport.format(it.min, 3)}–${AnalysisSupport.format(it.max, 3)} V)",
                )
            }
            stage.maf?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} MAF",
                    AnalysisSupport.valueWithUnit(it.median, "g/s", 1),
                )
            }
        }

        observe(idle)
        observe(elevated)

        val missing = quality.missingSignals
        if (missing.isNotEmpty()) warnings += "Required signals are missing: ${missing.joinToString()}."
        if (idle?.validTrims != true && elevated?.validTrims != true) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No valid fuel-trim stage was captured. Confirm the car reports STFT/LTFT and repeat the warm-idle stage.",
                nextSteps = listOf("Confirm PID 0x06/0x07 are supported, then repeat the warm-idle stage."),
            )
        }
        if (analyses.any { (it.closedLoopFraction ?: 1.0) < 0.6 }) {
            warnings += "Less than 60 % of the samples were in closed loop; trims may not represent fuelling correction."
        }

        val idleBank1 = idle?.bank(1)?.stats
        val elevatedBank1 = elevated?.bank(1)?.stats
        val idleBank2 = idle?.bank(2)?.stats
        val elevatedBank2 = elevated?.bank(2)?.stats
        val bank1 = idleBank1 ?: elevatedBank1
        val bank2 = idleBank2 ?: elevatedBank2

        // Sign-aware preconditions: a rich-mixture finding requires an actual negative trim.
        // Positive trims are the opposite pattern and must never be described as rich.
        val negativeBank1 = listOfNotNull(idleBank1, elevatedBank1).any { it.median <= RICH_TRIM_FLAG }
        val negativeBank2 = listOfNotNull(idleBank2, elevatedBank2).any { it.median <= RICH_TRIM_FLAG }
        val anyNegative = negativeBank1 || negativeBank2
        val positivePattern = listOfNotNull(idleBank1, elevatedBank1, idleBank2, elevatedBank2)
            .any { it.median >= NORMAL_BAND }
        val allWithinBand = listOfNotNull(idleBank1, elevatedBank1, idleBank2, elevatedBank2)
            .all { abs(it.median) <= NORMAL_BAND }

        if (!anyNegative) {
            if (positivePattern) {
                warnings += "The captured trims are positive. That is a lean-running pattern, not the negative-trim pattern this guide interprets."
                nextSteps += "Use the lean-mixture guide (P0171/P0174) for positive fuel trims."
            }
            val summary = when {
                allWithinBand -> "No negative fuel-trim pattern was observed. Combined trims stayed inside the configured normal band."
                positivePattern -> "No negative fuel-trim pattern was observed. The captured trims are positive, which points to a lean-running question instead."
                else -> "No negative fuel-trim pattern was observed in this capture."
            }
            val status = if (input.safetyStopReason != null) {
                DiagnosticStatus.SAFETY_STOPPED
            } else if (input.cancelled) {
                DiagnosticStatus.CANCELLED
            } else if (allWithinBand) {
                DiagnosticStatus.NO_ANOMALY_DETECTED
            } else {
                DiagnosticStatus.INCONCLUSIVE
            }
            val stageSummaries = analyses.map { analysis ->
                AnalysisSupport.stageSummary(
                    stage = analysis.stage,
                    valid = analysis.validTrims,
                    keyPid = Signals.RPM,
                    metrics = buildMap {
                        analysis.bank(1)?.let { put("combined_trim_b1", it.stats.median) }
                        analysis.bank(2)?.let { put("combined_trim_b2", it.stats.median) }
                    },
                )
            }
            return AnalysisSupport.buildResult(
                input = input,
                status = status,
                confidence = if (allWithinBand) ResultConfidence.MEDIUM else ResultConfidence.LOW,
                summary = summary,
                observations = observations,
                findings = emptyList(),
                stages = stageSummaries,
                quality = quality,
                warnings = warnings,
                nextSteps = nextSteps,
                limitations = listOf(
                    "This guide interprets negative (rich) fuel trims. It does not test a lean-running fault.",
                    "Positive trims are reported as a different pattern, not as a clean engine verdict.",
                ),
            )
        }

        if (idleBank1 != null && elevatedBank1 != null) {
            val idleValue = idleBank1.median
            val elevatedValue = elevatedBank1.median
            val change = elevatedValue - idleValue

            when {
                idleValue <= RICH_TRIM_FLAG && change > TRIM_CHANGE_FLAG -> {
                    findings += GuidedFinding(
                        id = "idle_rich_bias",
                        title = "Rich bias concentrated at idle",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "Bank 1 combined trim is strongly negative at idle and improves as airflow rises. That is consistent with a fuel source or airflow bias that matters most at low airflow, but it is not proof of a specific part.",
                        supporting = listOf(
                            "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleValue)} %",
                            "At elevated RPM it rose to ${AnalysisSupport.formatSigned(elevatedValue)} % (${AnalysisSupport.formatSigned(change)} points)",
                        ),
                        against = listOf("A single capture cannot identify which cylinder or fuel path is responsible"),
                        alternatives = listOf(
                            "EVAP purge vapour entering the intake",
                            "Leaking or over-delivering injector",
                            "MAF over-reporting at low airflow",
                            "Fuel pressure regulator or return issue",
                            "O2 sensor bias",
                        ),
                        nextCheck = "Check purge command and EVAP behaviour first, then compare injector balance and MAF/MAP at idle.",
                    )
                    nextSteps += "Check the EVAP purge command and injector balance before replacing a sensor."
                }
                idleValue <= RICH_TRIM_FLAG && elevatedValue <= RICH_TRIM_FLAG && abs(change) <= TRIM_CHANGE_FLAG -> {
                    findings += GuidedFinding(
                        id = "persistent_rich_bias",
                        title = "Persistent negative mixture bias",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "Trims stay negative at both idle and higher airflow. That is a persistent rich bias; airflow over-reporting, fuel pressure, injectors, purge and sensor bias remain possible.",
                        supporting = listOf(
                            "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleValue)} %",
                            "Elevated-RPM combined trim ${AnalysisSupport.formatSigned(elevatedValue)} %",
                        ),
                        alternatives = listOf(
                            "MAF over-reporting airflow",
                            "High fuel pressure or leaking injector",
                            "EVAP purge adding fuel vapour",
                            "O2 sensor bias",
                            "Intake restriction",
                        ),
                        nextCheck = "Check MAF/MAP plausibility, fuel pressure and purge behaviour before condemning a sensor or injector.",
                    )
                    nextSteps += "Check airflow measurement, fuel pressure and EVAP purge before replacing parts."
                }
                idleValue <= RICH_TRIM_FLAG && elevatedValue <= RICH_TRIM_FLAG && change < -TRIM_CHANGE_FLAG -> {
                    findings += GuidedFinding(
                        id = "load_rich_bias",
                        title = "Trim becomes richer as airflow rises",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "The negative correction grows as airflow rises. That supports investigating fuel delivery or airflow measurement under load rather than an idle-only vapour source.",
                        supporting = listOf(
                            "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleValue)} %",
                            "Elevated-RPM combined trim ${AnalysisSupport.formatSigned(elevatedValue)} %",
                        ),
                        alternatives = listOf(
                            "Fuel pressure too high",
                            "Injector delivery imbalance",
                            "MAF over-reporting at higher flow",
                            "Intake restriction",
                        ),
                        nextCheck = "Measure fuel pressure and volume under load, and compare MAF/MAP against expected airflow.",
                    )
                    nextSteps += "Measure fuel pressure and volume under load and verify MAF/MAP at higher airflow."
                }
            }
        }

        if (bank1 != null && bank2 != null && (bank1.median <= RICH_TRIM_FLAG || bank2.median <= RICH_TRIM_FLAG) &&
            abs(bank1.median - bank2.median) > TRIM_CHANGE_FLAG
        ) {
            findings += GuidedFinding(
                id = "bank_specific_rich",
                title = "One bank is correcting more than the other",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The banks correct differently. That points to a bank-specific fuel, airflow, sensor or exhaust cause rather than a shared fuel-pressure problem.",
                supporting = listOf(
                    "Bank 1 median ${AnalysisSupport.formatSigned(bank1.median)} %",
                    "Bank 2 median ${AnalysisSupport.formatSigned(bank2.median)} %",
                ),
                alternatives = listOf(
                    "Bank-specific injector",
                    "Bank-specific O2 sensor or exhaust leak",
                    "Bank-specific intake/PCV path",
                ),
                nextCheck = "Compare injector balance and O2 response on the bank with the larger correction.",
            )
            nextSteps += "Focus injector and O2 checks on the bank with the larger correction."
        } else if (idleBank2 == null && elevatedBank2 == null) {
            warnings += "Bank 2 trims were not reported; bank-specific faults cannot be assessed."
        }

        val upstream = idle?.upstreamO2 ?: elevated?.upstreamO2
        if (upstream != null && upstream.median > 0.75 && anyNegative) {
            findings += GuidedFinding(
                id = "o2_rich_feedback",
                title = "Upstream O2 feedback sits on the rich side",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The upstream narrowband voltage was mostly high during the capture. That is consistent with a rich mixture, but a biased or contaminated sensor can produce the same reading.",
                supporting = listOf("Median upstream O2 ${AnalysisSupport.format(upstream.median, 3)} V"),
                alternatives = listOf("Genuine rich mixture", "O2 sensor bias/contamination", "Sensor circuit fault"),
                nextCheck = "Confirm the rich condition with fuel trims and a known-good sensor before replacing the O2 sensor.",
            )
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "Fuel trims stayed within the configured normal band at both stages."
            DiagnosticStatus.SUSPICIOUS -> buildString {
                append("Negative fuel-trim behaviour was captured")
                listOfNotNull(idleBank1, elevatedBank1).firstOrNull()?.let {
                    append(" (${AnalysisSupport.formatSigned(it.median)} % at idle)")
                }
                append(". The evidence ranks plausible causes; it does not prove one.")
            }
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture contained negative trim values but not enough valid data for a conclusion."
        }

        val stageSummaries = analyses.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.validTrims,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    analysis.bank(1)?.let { put("combined_trim_b1", it.stats.median) }
                    analysis.bank(2)?.let { put("combined_trim_b2", it.stats.median) }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                findings.isNotEmpty() -> ResultConfidence.MEDIUM
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
                "Negative trims can be caused by airflow over-reporting, fuel pressure, injectors, EVAP purge, an intake restriction or sensor bias.",
                "The guide does not prove a leaking injector or a failed purge valve.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val combined = buildMap {
            AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1)?.let { put(1, it) }
            AnalysisSupport.combinedTrim(stage, Signals.STFT_B2, Signals.LTFT_B2)?.let { put(2, it) }
        }
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: emptyStats()
        val coolant = AnalysisSupport.statsOrNull(stage, Signals.COOLANT) ?: emptyStats()
        val valid = rpm.count >= 3 && coolant.median >= 60.0
        return StageAnalysis(
            stage = stage,
            valid = valid,
            rpm = rpm,
            coolant = coolant,
            closedLoopFraction = AnalysisSupport.closedLoopFraction(stage),
            combined = combined.mapValues { (bank, stats) ->
                BankCombined(bank, stage.series(if (bank == 1) Signals.STFT_B1 else Signals.STFT_B2), stats)
            },
            upstreamO2 = AnalysisSupport.statsOrNull(stage, Signals.O2_B1S1),
            maf = AnalysisSupport.statsOrNull(stage, Signals.MAF),
        )
    }

    private fun emptyStats(): SeriesStats = AnalysisSupport.stats(emptyList())
}
