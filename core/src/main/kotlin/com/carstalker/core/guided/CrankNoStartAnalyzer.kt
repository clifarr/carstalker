package com.carstalker.core.guided

/**
 * Crank/no-start data-quality interpreter.
 *
 * The useful output is what the adapter actually saw during cranking: RPM, voltage and manifold
 * pressure. Missing RPM is reported as missing data, never as proof of a failed crank sensor.
 */
object CrankNoStartAnalyzer {

    private const val CRANKING_RPM_MIN = 50.0
    private const val CRANKING_VOLTAGE_LOW = 9.5
    private const val RESTING_VOLTAGE_LOW = 11.8
    private const val MIN_SAMPLES = 3

    private data class StageAnalysis(
        val stage: StageCapture,
        val rpm: SeriesStats,
        val voltage: SeriesStats,
        val map: SeriesStats,
        val coolant: SeriesStats,
        val fuelStatus: Double?,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.CONTROL_MODULE_VOLTAGE),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val keyOn = stages.firstOrNull { it.stage.stageId == "key_on_engine_off" }
        val cranking = stages.firstOrNull { it.stage.stageId == "cranking" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    "${AnalysisSupport.format(stage.rpm.median, 0)} rpm " +
                        "(range ${AnalysisSupport.format(stage.rpm.min, 0)}–${AnalysisSupport.format(stage.rpm.max, 0)})",
                )
            }
            if (stage.voltage.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} voltage",
                    "${AnalysisSupport.format(stage.voltage.median, 2)} V " +
                        "(min ${AnalysisSupport.format(stage.voltage.min, 2)} V)",
                )
            }
            if (stage.map.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAP",
                    AnalysisSupport.valueWithUnit(stage.map.median, "kPa", 1),
                )
            }
            if (stage.coolant.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} coolant",
                    AnalysisSupport.valueWithUnit(stage.coolant.median, "°C", 1),
                )
            }
            stage.fuelStatus?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} fuel system",
                    AnalysisSupport.fuelSystemLabel(it),
                )
            }
        }

        observe(keyOn)
        observe(cranking)

        if (cranking == null || cranking.stage.series(Signals.RPM).size < MIN_SAMPLES) {
            warnings += "Cranking RPM was not sampled often enough to judge whether the ECU saw engine rotation."
            findings += GuidedFinding(
                id = "cranking_data_missing",
                title = "Cranking data is insufficient",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The adapter did not capture enough RPM samples during cranking. Many ELM327 adapters miss the short cranking window or reconnect too slowly. This is not evidence of a crank-sensor failure.",
                supporting = listOf("Fewer than $MIN_SAMPLES cranking RPM samples"),
                alternatives = listOf(
                    "Adapter polling/reconnect latency",
                    "ECU does not report RPM during cranking",
                    "Engine did not crank",
                    "Crank/cam signal fault",
                ),
                nextCheck = "Repeat with a faster adapter or a scope on the crank sensor; do not replace the sensor from this capture alone.",
            )
            nextSteps += "Repeat with a faster adapter or use a scope before diagnosing the crank sensor."
        }

        val crankingRpm = cranking?.rpm?.takeIf { it.count >= MIN_SAMPLES }
        if (crankingRpm != null) {
            when {
                crankingRpm.median < CRANKING_RPM_MIN -> {
                    findings += GuidedFinding(
                        id = "no_cranking_rpm",
                        title = "No meaningful cranking RPM was observed",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "The ECU reported little or no engine rotation during cranking. That can be a crank signal problem, but it can also be an adapter that missed the cranking window, a starter that did not turn the engine or an ECU that does not report RPM while cranking. This is not evidence of a crank-sensor failure on its own.",
                        supporting = listOf("Cranking RPM median ${AnalysisSupport.format(crankingRpm.median, 0)} rpm"),
                        alternatives = listOf(
                            "Adapter missed the cranking window",
                            "Starter/engine not turning",
                            "Crank or cam signal fault",
                            "ECU does not report cranking RPM",
                        ),
                        nextCheck = "Confirm the engine physically turns, then check for a crank/cam signal with a scope or a faster scan tool.",
                    )
                    nextSteps += "Confirm the engine turns and check crank/cam signal with a scope."
                }
                else -> {
                    observations += GuidedObservation(
                        "Cranking rotation",
                        "RPM was reported (${AnalysisSupport.format(crankingRpm.median, 0)} rpm), so the ECU saw engine rotation.",
                    )
                }
            }
        }

        val crankingVoltage = cranking?.voltage?.takeIf { it.count >= MIN_SAMPLES }
        if (crankingVoltage != null && crankingVoltage.min < CRANKING_VOLTAGE_LOW) {
            findings += GuidedFinding(
                id = "cranking_voltage_low",
                title = "Voltage dropped low during cranking",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "Cranking voltage fell below the configured guide. A weak battery, high starter current, a bad connection or a measurement offset can do this. It is not a no-start diagnosis by itself.",
                supporting = listOf(
                    "Cranking minimum ${AnalysisSupport.format(crankingVoltage.min, 2)} V",
                    "Median ${AnalysisSupport.format(crankingVoltage.median, 2)} V",
                ),
                alternatives = listOf("Battery state/age", "Starter current draw", "Terminal/ground resistance", "Measurement offset"),
                nextCheck = "Measure voltage at the battery posts during cranking and load-test the battery.",
            )
            nextSteps += "Measure battery voltage at the posts during cranking and load-test the battery."
        }

        val restingVoltage = keyOn?.voltage?.takeIf { it.count >= MIN_SAMPLES }
        if (restingVoltage != null && restingVoltage.median < RESTING_VOLTAGE_LOW) {
            findings += GuidedFinding(
                id = "resting_voltage_low",
                title = "Resting voltage is low before cranking",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "Key-on voltage was below the usual resting range. A discharged battery, a parasitic load or a measurement offset can explain it.",
                supporting = listOf("Key-on median ${AnalysisSupport.format(restingVoltage.median, 2)} V"),
                alternatives = listOf("Battery state of charge", "Parasitic draw", "Connection resistance", "Measurement offset"),
                nextCheck = "Charge or load-test the battery and repeat the cranking capture.",
            )
        }

        val relevantCodes = input.activeCodes.filter {
            it.startsWith("P0335") || it.startsWith("P0336") || it.startsWith("P0340") || it.startsWith("P0341")
        }
        if (relevantCodes.isNotEmpty()) {
            findings += GuidedFinding(
                id = "crank_cam_code",
                title = "Crank/cam signal code reported",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The ECU reports a crank or cam signal code. That supports a signal-circuit investigation, but the capture still cannot prove the sensor versus wiring versus mechanical timing.",
                supporting = listOf("Codes: ${relevantCodes.joinToString()}"),
                alternatives = listOf("Sensor", "Wiring/connector", "Reluctor/tone wheel", "Timing/mechanical", "ECU input"),
                nextCheck = "Check the crank/cam sensor circuit and signal with a scope or service information.",
            )
            nextSteps += "Check the crank/cam circuit and signal before replacing the sensor."
        }

        if (input.activeCodes.isEmpty() && input.scan.allCodes.isEmpty()) {
            warnings += "No codes were available in the scan context; the result is based on live cranking data only."
        }
        warnings += "Generic OBD cannot measure fuel pressure, spark or compression directly."

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "no_cranking_rpm" || it.id == "cranking_voltage_low" } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            crankingRpm != null -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.SUSPICIOUS -> "Cranking data shows a limitation or low voltage. The result does not prove a failed component."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "Cranking data was captured, but generic OBD cannot identify the no-start cause on its own."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    if (analysis.rpm.available) put("rpm_median", analysis.rpm.median)
                    if (analysis.voltage.available) put("voltage_min", analysis.voltage.min)
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
                "ELM327 adapters can miss cranking samples or reconnect too slowly to see RPM.",
                "RPM = 0 may mean no crank signal, a non-reporting ECU or a sampling limitation; it is not a crank-sensor verdict.",
                "Generic OBD does not measure fuel pressure, spark or compression directly.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        val voltage = AnalysisSupport.statsOrNull(stage, Signals.CONTROL_MODULE_VOLTAGE)
            ?: AnalysisSupport.statsOrNull(stage, Signals.ADAPTER_VOLTAGE)
            ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            rpm = rpm,
            voltage = voltage,
            map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList()),
            coolant = AnalysisSupport.statsOrNull(stage, Signals.COOLANT) ?: AnalysisSupport.stats(emptyList()),
            fuelStatus = stage.series(Signals.FUEL_SYSTEM_STATUS).lastOrNull()?.value,
            valid = rpm.count >= MIN_SAMPLES || voltage.count >= MIN_SAMPLES,
        )
    }
}
