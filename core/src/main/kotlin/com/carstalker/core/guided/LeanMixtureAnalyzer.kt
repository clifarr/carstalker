package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint
import kotlin.math.abs

/**
 * Lean-mixture interpreter.
 *
 * Compares the robust centre of `STFT + LTFT` at a warm idle and a steady elevated RPM. The rules
 * are deliberately conservative: an idle-only positive trim is *consistent with* unmetered air,
 * but the result always lists airflow measurement, fuel delivery, exhaust leaks and sensor errors
 * as competing explanations.
 */
object LeanMixtureAnalyzer {

    private const val IDLE_TRIM_FLAG = 15.0
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
        val speed: SeriesStats,
        val closedLoopFraction: Double?,
        val combined: Map<Int, BankCombined>,
        val notes: List<String>,
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
            input.stage(definitionStage.id)?.let { analyzeStage(it, definitionStage) }
        }
        val idle = analyses.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = analyses.firstOrNull { it.stage.stageId == "steady_rpm" }
        val cruise = analyses.firstOrNull { it.stage.stageId == "optional_cruise" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observeStage(stage: StageAnalysis?) {
            stage ?: return
            val bank1 = stage.bank(1)
            if (stage.rpm.count > 0) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
            if (stage.coolant.count > 0) {
                observations += GuidedObservation(
                    "${stage.stage.title} coolant",
                    AnalysisSupport.valueWithUnit(stage.coolant.median, "°C", 1),
                )
            }
            if (bank1 != null) {
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(bank1.stats.median)} % " +
                        "(p10 ${AnalysisSupport.formatSigned(bank1.stats.p10)}, p90 ${AnalysisSupport.formatSigned(bank1.stats.p90)})",
                )
            }
            stage.bank(2)?.let { bank2 ->
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B2",
                    "${AnalysisSupport.formatSigned(bank2.stats.median)} % " +
                        "(p10 ${AnalysisSupport.formatSigned(bank2.stats.p10)}, p90 ${AnalysisSupport.formatSigned(bank2.stats.p90)})",
                )
            }
            stage.closedLoopFraction?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} closed loop",
                    "${AnalysisSupport.format(it * 100, 0)} % of samples",
                )
            }
            stage.stage.series(Signals.FUEL_SYSTEM_STATUS).lastOrNull()?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} fuel system",
                    AnalysisSupport.fuelSystemLabel(it.value),
                )
            }
        }

        observeStage(idle)
        observeStage(elevated)
        observeStage(cruise)

        val idleBank1 = idle?.bank(1)?.stats
        val elevatedBank1 = elevated?.bank(1)?.stats
        val cruiseBank1 = cruise?.bank(1)?.stats
        val highBank1 = elevatedBank1 ?: cruiseBank1
        val highStageLabel = if (elevatedBank1 != null) "elevated RPM" else "cruise"

        if (idleBank1 != null && highBank1 != null) {
            val change = idleBank1.median - highBank1.median
            observations += GuidedObservation(
                "Change from idle to $highStageLabel",
                "${AnalysisSupport.formatSigned(-change)} percentage points",
            )
        }

        val missing = quality.missingSignals
        val usable = analyses.any { it.validTrims }
        val bothStages = idle?.validTrims == true && (elevated?.validTrims == true || cruise?.validTrims == true)

        if (missing.isNotEmpty()) {
            warnings += "Required signals are missing: ${missing.joinToString()}."
        }
        if (!bothStages) {
            warnings += "Both a warm-idle and an elevated-RPM/cruise stage are needed for the strongest comparison."
        }
        if (input.definition.stages.any { it.id == "steady_rpm" } && elevated?.validTrims != true) {
            warnings += "The steady-RPM stage was not valid; a cruise stage can substitute only if it was captured."
        }
        if (analyses.none { it.closedLoopFraction != null }) {
            warnings += "Closed-loop fuelling was not confirmed because fuel-system status is not reported."
        } else if (analyses.any { (it.closedLoopFraction ?: 1.0) < 0.6 }) {
            warnings += "Less than 60 % of the samples were in closed loop; trims may not represent fuelling correction."
        }

        if (idleBank1 != null && highBank1 != null) {
            val idleHigh = idleBank1.median
            val highHigh = highBank1.median
            val difference = idleHigh - highHigh

            if (idleHigh > IDLE_TRIM_FLAG && difference > TRIM_CHANGE_FLAG) {
                findings += GuidedFinding(
                    id = "unmetered_air",
                    title = "Consistent with unmetered air",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = if (bothStages && difference > 15) ResultConfidence.HIGH else ResultConfidence.MEDIUM,
                    explanation = "Bank 1 combined trim is strongly positive at idle and improves materially when airflow rises. That is the classic shape of air entering after the airflow meter, but the pattern is not proof of a specific leak.",
                    supporting = listOf(
                        "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleHigh)} %",
                        "At $highStageLabel it fell to ${AnalysisSupport.formatSigned(highHigh)} % (${AnalysisSupport.formatSigned(difference)} points lower)",
                    ),
                    against = listOf("A single capture cannot locate the leak"),
                    alternatives = listOf(
                        "PCV or intake hose leak",
                        "MAF/MAP measurement bias",
                        "Fuel delivery limitation",
                        "Exhaust leak before the O2 sensor",
                        "O2 sensor or wiring error",
                    ),
                    nextCheck = "Inspect intake plumbing, the PCV system and vacuum hoses. A smoke test is the most direct confirmation.",
                )
                nextSteps += "Inspect and smoke-test the intake tract and PCV system before replacing parts."
            } else if (idleHigh > IDLE_TRIM_FLAG && highHigh > IDLE_TRIM_FLAG && abs(difference) <= TRIM_CHANGE_FLAG) {
                findings += GuidedFinding(
                    id = "persistent_mixture_bias",
                    title = "Persistent positive mixture bias",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = if (bothStages) ResultConfidence.MEDIUM else ResultConfidence.LOW,
                    explanation = "Trims stay positive at both idle and higher airflow. That is a persistent lean bias, not a leak that only affects idle. Airflow measurement, fuel delivery, intake leaks and exhaust leaks remain possible.",
                    supporting = listOf(
                        "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleHigh)} %",
                        "$highStageLabel combined trim ${AnalysisSupport.formatSigned(highHigh)} %",
                    ),
                    alternatives = listOf(
                        "MAF or MAP under-reporting airflow",
                        "Fuel pressure/volume limitation",
                        "Intake or exhaust leak present across the range",
                        "O2 sensor bias",
                    ),
                    nextCheck = "Check MAF/MAP plausibility and fuel pressure/volume before condemning a sensor.",
                )
                nextSteps += "Check airflow measurement and fuel delivery, including pressure and volume under load."
            } else if (highHigh > IDLE_TRIM_FLAG && highHigh - idleHigh > TRIM_CHANGE_FLAG) {
                findings += GuidedFinding(
                    id = "load_dependent_bias",
                    title = "Trim worsens as airflow rises",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "The correction grows as airflow rises. That supports investigating fuel supply or airflow measurement under load rather than a simple idle-only vacuum leak.",
                    supporting = listOf(
                        "Warm-idle combined trim ${AnalysisSupport.formatSigned(idleHigh)} %",
                        "$highStageLabel combined trim ${AnalysisSupport.formatSigned(highHigh)} %",
                    ),
                    alternatives = listOf(
                        "Fuel pump volume/pressure limitation",
                        "Restricted fuel filter or line",
                        "MAF under-reporting at higher flow",
                        "Intake restriction",
                    ),
                    nextCheck = "Measure fuel pressure and volume under load, and compare MAF/MAP against expected airflow.",
                )
                nextSteps += "Measure fuel pressure/volume under load and verify MAF/MAP at higher airflow."
            }
        }

        val idleBank2 = idle?.bank(2)?.stats
        val highBank2 = elevated?.bank(2)?.stats ?: cruise?.bank(2)?.stats
        if (idleBank1 != null && idleBank2 != null && abs(idleBank1.median - idleBank2.median) > TRIM_CHANGE_FLAG) {
            findings += GuidedFinding(
                id = "bank_specific",
                title = "One bank differs from the other",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The two banks correct differently at idle. That points to a bank-specific cause: a leak, injector, sensor, wiring or exhaust issue affecting one side.",
                supporting = listOf(
                    "Bank 1 idle combined trim ${AnalysisSupport.formatSigned(idleBank1.median)} %",
                    "Bank 2 idle combined trim ${AnalysisSupport.formatSigned(idleBank2.median)} %",
                ),
                alternatives = listOf(
                    "Bank-specific intake/vacuum leak",
                    "Injector or fuel-delivery difference",
                    "Bank-specific O2 sensor or exhaust leak",
                ),
                nextCheck = "Inspect the intake and exhaust on the bank with the larger correction; compare injector balance if accessible.",
            )
            nextSteps += "Focus the leak and injector checks on the bank with the larger correction."
        } else if (idleBank2 == null) {
            warnings += "Bank 2 trims were not reported; bank-specific faults cannot be assessed."
        }

        val negative = listOfNotNull(idleBank1, highBank1).any { it.median < -NORMAL_BAND }
        if (negative) {
            warnings += "Negative trims suggest a rich condition; this guide is intended for lean codes such as P0171/P0174."
        }

        val normal = findings.isEmpty() && bothStages &&
            listOfNotNull(idleBank1, highBank1).all { abs(it.median) <= NORMAL_BAND }
        if (normal) {
            nextSteps += "No mixture anomaly was detected. Re-check pending/freeze-frame data and re-run after a drive cycle if the code returns."
        } else if (findings.isEmpty()) {
            nextSteps += "Re-run with a valid warm-idle and steady-RPM stage; keep the engine in closed loop."
        } else {
            nextSteps += "Re-run the test after any repair and compare the same stages."
        }

        if (listOfNotNull(idleBank1, highBank1).any { it.median > 25 }) {
            warnings += "A correction above +25 % is large. Avoid hard driving until the cause is found."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            !quality.requiredSignalsPresent || !usable -> DiagnosticStatus.UNABLE_TO_RUN
            normal -> DiagnosticStatus.NO_ANOMALY_DETECTED
            findings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            else -> DiagnosticStatus.INCONCLUSIVE
        }

        val confidence = when {
            status == DiagnosticStatus.NO_ANOMALY_DETECTED && bothStages -> ResultConfidence.HIGH
            findings.any { it.confidence == ResultConfidence.HIGH } -> ResultConfidence.HIGH
            findings.isNotEmpty() && bothStages -> ResultConfidence.MEDIUM
            findings.isNotEmpty() -> ResultConfidence.LOW
            else -> ResultConfidence.LOW
        }

        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason
                ?: "The test stopped for safety. Fix the reported fault before testing."
            DiagnosticStatus.CANCELLED -> "The test was cancelled before enough data was captured."
            DiagnosticStatus.UNABLE_TO_RUN -> "The car did not provide enough valid trim data. ${missing.firstOrNull()?.let { "Missing: $it." } ?: ""}".trim()
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "Fuel trims stayed within the configured band at warm idle and higher airflow. No lean-mixture anomaly was detected in this test."
            DiagnosticStatus.SUSPICIOUS -> findings.first().explanation
            else -> "The data was not strong enough to compare idle and elevated fuel trims. Re-run both stages with a warm, closed-loop engine."
        }

        val stageSummaries = analyses.map { analysis ->
            val bank1 = analysis.bank(1)
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.validTrims,
                metrics = buildMap {
                    if (analysis.rpm.available) put("rpm", analysis.rpm.median)
                    if (analysis.coolant.available) put("coolantC", analysis.coolant.median)
                    bank1?.let { put("combinedTrimB1", it.stats.median) }
                    analysis.bank(2)?.let { put("combinedTrimB2", it.stats.median) }
                    analysis.closedLoopFraction?.let { put("closedLoopFraction", it) }
                },
                notes = analysis.notes,
            )
        }

        val limitations = AnalysisSupport.baseLimitations(input) + buildList {
            if (elevated?.validTrims != true) add("No valid steady-RPM stage; the comparison relies on idle alone or a cruise substitute.")
            if (idle?.validTrims != true) add("No valid warm-idle stage; the idle-only pattern could not be evaluated.")
            if (idleBank2 == null) add("Bank 2 trims were not available.")
        }

        return GuidedResult(
            diagnosticId = input.definition.id,
            definitionVersion = input.definition.version,
            title = input.definition.title,
            status = status,
            confidence = confidence,
            summary = summary,
            observations = observations,
            findings = findings.sortedBy { it.evidenceLevel.ordinal },
            stages = stageSummaries,
            quality = quality,
            limitations = limitations.distinct(),
            warnings = warnings.distinct(),
            nextSteps = nextSteps.distinct(),
            generatedAt = input.generatedAt,
            profile = input.capabilities.profile,
            stagesSkipped = input.skippedStages,
        )
    }

    private fun analyzeStage(stage: StageCapture, definition: StageDefinition): StageAnalysis {
        val rpm = AnalysisSupport.stats(stage.series(Signals.RPM))
        val coolant = AnalysisSupport.stats(stage.series(Signals.COOLANT))
        val speed = AnalysisSupport.stats(stage.series(Signals.SPEED))
        val closedLoop = AnalysisSupport.closedLoopFraction(stage)
        val notes = mutableListOf<String>()

        val combined = buildMap {
            val bank1 = AnalysisSupport.combinedTrim(
                stage.series(Signals.STFT_B1),
                stage.series(Signals.LTFT_B1),
            )
            if (bank1.isNotEmpty()) put(1, BankCombined(1, bank1, AnalysisSupport.stats(bank1)))

            val bank2 = AnalysisSupport.combinedTrim(
                stage.series(Signals.STFT_B2),
                stage.series(Signals.LTFT_B2),
            )
            if (bank2.isNotEmpty()) put(2, BankCombined(2, bank2, AnalysisSupport.stats(bank2)))
        }

        var valid = true
        val expectedRpm = definition.condition.rpmRange
        if (rpm.count < 5) {
            valid = false
            notes += "Not enough RPM samples."
        } else if (expectedRpm != null && rpm.median !in expectedRpm.first.toDouble()..expectedRpm.last.toDouble()) {
            valid = false
            notes += "Median RPM ${AnalysisSupport.format(rpm.median, 0)} was outside ${expectedRpm.first}–${expectedRpm.last}."
        }
        if (definition.condition.minCoolantC != null && coolant.available && coolant.median < definition.condition.minCoolantC) {
            valid = false
            notes += "Coolant was below the warm-engine target."
        }
        if (definition.condition.minSpeedKph != null && speed.available && speed.median < definition.condition.minSpeedKph) {
            valid = false
            notes += "Vehicle speed was below the cruise target."
        }
        if (closedLoop != null && closedLoop < 0.6) {
            valid = false
            notes += "Closed loop was active for only ${AnalysisSupport.format(closedLoop * 100, 0)} % of samples."
        }
        if (combined.values.none { it.stats.count >= MIN_TRIM_SAMPLES }) {
            valid = false
            notes += "Not enough paired STFT/LTFT samples."
        }
        if (stage.interruptions > 0) {
            notes += "${stage.interruptions} interruption(s); mixed samples were discarded."
        }

        return StageAnalysis(
            stage = stage,
            valid = valid,
            rpm = rpm,
            coolant = coolant,
            speed = speed,
            closedLoopFraction = closedLoop,
            combined = combined,
            notes = notes,
        )
    }
}
