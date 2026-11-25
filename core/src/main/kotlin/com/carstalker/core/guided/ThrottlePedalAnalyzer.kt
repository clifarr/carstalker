package com.carstalker.core.guided

import com.carstalker.core.diagnose.SignalAnalysis

/**
 * Throttle/pedal correlation interpreter.
 *
 * The test compares channels that should track each other during a gentle stationary throttle
 * response. A disagreement is a reason to inspect the circuit, not a diagnosis of a specific
 * drive-by-wire component.
 */
object ThrottlePedalAnalyzer {

    private const val CORRELATION_FLAG = 0.5
    private const val PEDAL_CORRELATION_FLAG = 0.8
    private const val THROTTLE_MOVEMENT_PCT = 5.0
    private const val RPM_MOVEMENT = 500.0
    private const val IDLE_THROTTLE_HIGH = 20.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val throttle: SeriesStats,
        val relativeThrottle: SeriesStats,
        val throttleB: SeriesStats,
        val pedalD: SeriesStats,
        val pedalE: SeriesStats,
        val commandedThrottle: SeriesStats,
        val rpm: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val response = stages.firstOrNull { it.stage.stageId == "throttle_response" }
        val stimulusObserved = response != null &&
            response.throttle.count >= MIN_SAMPLES &&
            (
                response.throttle.range >= THROTTLE_MOVEMENT_PCT ||
                    response.pedalD.range >= THROTTLE_MOVEMENT_PCT ||
                    response.pedalE.range >= THROTTLE_MOVEMENT_PCT ||
                    response.rpm.range >= RPM_MOVEMENT
                )
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.THROTTLE),
            minHz = 0.8,
            stimulusObserved = stimulusObserved,
        )

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.throttle.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} throttle",
                    "${AnalysisSupport.format(stage.throttle.median, 1)} % " +
                        "(range ${AnalysisSupport.format(stage.throttle.min, 1)}–${AnalysisSupport.format(stage.throttle.max, 1)})",
                )
            }
            if (stage.pedalD.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} pedal D",
                    "${AnalysisSupport.format(stage.pedalD.median, 1)} % " +
                        "(range ${AnalysisSupport.format(stage.pedalD.min, 1)}–${AnalysisSupport.format(stage.pedalD.max, 1)})",
                )
            }
            if (stage.pedalE.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} pedal E",
                    AnalysisSupport.valueWithUnit(stage.pedalE.median, "%", 1),
                )
            }
            if (stage.commandedThrottle.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} commanded throttle",
                    AnalysisSupport.valueWithUnit(stage.commandedThrottle.median, "%", 1),
                )
            }
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
        }

        observe(idle)
        observe(response)

        if (stages.isEmpty() || stages.all { !it.throttle.available }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No throttle-position samples were captured. Confirm PID 0x11 is supported.",
                nextSteps = listOf("Confirm PID 0x11 is supported, then repeat the test."),
            )
        }

        val safetyCodes = input.activeCodes.filter {
            it.startsWith("P0120") || it.startsWith("P0121") || it.startsWith("P0122") ||
                it.startsWith("P0123") || it.startsWith("P0124") || it.startsWith("P2135")
        }
        if (safetyCodes.isNotEmpty()) {
            warnings += "Drive-by-wire codes (${safetyCodes.joinToString()}) are present. Have the vehicle assessed professionally and avoid normal driving until resolved."
            nextSteps += "Have the drive-by-wire fault assessed professionally before normal driving."
        }

        if (!stimulusObserved) {
            warnings += "No throttle, pedal or RPM movement was observed in the response stage. The channels were not exercised, so a healthy correlation cannot be claimed."
            nextSteps += "Repeat the gentle throttle blip (or a passenger-assisted road test) so the channels actually move, then re-run the test."
        }

        if (stimulusObserved && response != null && response.throttle.available && response.rpm.available) {
            val throttleMovement = response.throttle.range
            val rpmMovement = response.rpm.range
            if (rpmMovement > RPM_MOVEMENT && throttleMovement < THROTTLE_MOVEMENT_PCT) {
                findings += GuidedFinding(
                    id = "throttle_not_moving",
                    title = "Throttle signal barely moved while RPM changed",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Engine speed changed during the throttle response but the throttle-position channel barely moved. PID 0x11 may be a calculated/absolute value, or the sensor/circuit may not be reporting correctly.",
                    supporting = listOf(
                        "Throttle range ${AnalysisSupport.format(throttleMovement, 1)} %",
                        "RPM range ${AnalysisSupport.format(rpmMovement, 0)} rpm",
                    ),
                    alternatives = listOf(
                        "PID 0x11 is not the actuator position on this vehicle",
                        "Throttle position sensor/circuit",
                        "Pedal-to-throttle mapping/adaptation",
                        "Sampling or scaling issue",
                    ),
                    nextCheck = "Compare PID 0x11 with relative throttle, commanded throttle and pedal channels; inspect the sensor circuit with service information.",
                )
                nextSteps += "Compare throttle, commanded-throttle and pedal channels before replacing a sensor."
            }
        }

        val correlationFindings = mutableListOf<GuidedFinding>()
        fun correlate(
            id: String,
            title: String,
            aPid: Int,
            bPid: Int,
            threshold: Double,
            explanation: String,
            alternatives: List<String>,
            nextCheck: String,
        ) {
            val stage = response ?: idle ?: return
            val a = stage.stage.series(aPid)
            val b = stage.stage.series(bPid)
            if (a.size < MIN_SAMPLES || b.size < MIN_SAMPLES) return
            val correlation = SignalAnalysis.correlation(a, b) ?: return
            observations += GuidedObservation(
                "$title correlation",
                AnalysisSupport.format(correlation, 2),
            )
            if (correlation < threshold) {
                correlationFindings += GuidedFinding(
                    id = id,
                    title = title,
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = explanation,
                    supporting = listOf("Pearson correlation ${AnalysisSupport.format(correlation, 2)} (threshold ${AnalysisSupport.format(threshold, 1)})"),
                    alternatives = alternatives,
                    nextCheck = nextCheck,
                )
            }
        }

        if (stimulusObserved) correlate(
            id = "pedal_throttle_disagreement",
            title = "Pedal and throttle channels do not track",
            aPid = Signals.PEDAL_D,
            bPid = Signals.THROTTLE,
            threshold = CORRELATION_FLAG,
            explanation = "The accelerator-pedal and throttle-position channels did not move together during the response. Mapping, idle control and different PID meanings can explain some of this, but a sensor or circuit fault remains possible.",
            alternatives = listOf("Normal pedal-to-throttle mapping", "Throttle position sensor/circuit", "Pedal sensor/circuit", "Sampling difference"),
            nextCheck = "Compare both channels at several steady pedal positions and inspect the circuits.",
        )
        if (stimulusObserved) correlate(
            id = "throttle_redundant_disagreement",
            title = "Throttle channels disagree",
            aPid = Signals.THROTTLE,
            bPid = Signals.THROTTLE_B,
            threshold = CORRELATION_FLAG,
            explanation = "Two throttle-position channels did not track. Redundant sensors are often scaled differently, so this is a reason to inspect the circuit and service data, not a verdict.",
            alternatives = listOf("Different scaling/reference", "Throttle sensor/circuit", "Wiring/connector", "Sampling difference"),
            nextCheck = "Check the throttle sensor circuits and compare the scaled values with service information.",
        )
        if (stimulusObserved) correlate(
            id = "pedal_redundant_disagreement",
            title = "Pedal channels disagree",
            aPid = Signals.PEDAL_D,
            bPid = Signals.PEDAL_E,
            threshold = PEDAL_CORRELATION_FLAG,
            explanation = "The two accelerator-pedal channels did not track closely. Redundant pedal sensors normally move together; a sensor, wiring or reference fault can break that.",
            alternatives = listOf("Pedal sensor/circuit", "Wiring/connector", "Reference voltage", "Sampling difference"),
            nextCheck = "Inspect the pedal connector and compare both channels with service information.",
        )
        if (stimulusObserved) correlate(
            id = "commanded_throttle_disagreement",
            title = "Commanded and reported throttle disagree",
            aPid = Signals.COMMANDED_THROTTLE,
            bPid = Signals.THROTTLE,
            threshold = CORRELATION_FLAG,
            explanation = "The commanded throttle actuator and the reported throttle position did not track. An actuator, sensor or adaptation issue can cause this; the PIDs may also refer to different quantities.",
            alternatives = listOf("Actuator/sensor fault", "Different PID meaning", "Adaptation", "Wiring"),
            nextCheck = "Compare the channels at steady positions and inspect the actuator/sensor circuit with service information.",
        )
        findings += correlationFindings

        if (idle?.throttle?.available == true && idle.throttle.median > IDLE_THROTTLE_HIGH) {
            findings += GuidedFinding(
                id = "idle_throttle_high",
                title = "Closed-throttle position reads high",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The throttle channel was above the configured idle guide. Some vehicles report an absolute or calculated value, so this is not automatically a stuck throttle.",
                supporting = listOf("Idle throttle median ${AnalysisSupport.format(idle.throttle.median, 1)} %"),
                alternatives = listOf("PID scaling/meaning", "Throttle adaptation", "Sensor/circuit", "Pedal not fully released"),
                nextCheck = "Confirm the pedal is fully released and compare with relative throttle and service data.",
            )
        }

        if (!stages.any { it.pedalD.available || it.pedalE.available }) {
            warnings += "No accelerator-pedal channel was reported; pedal-to-throttle correlation was not possible."
        }
        if (!stages.any { it.throttleB.available }) {
            warnings += "No second throttle channel was reported; redundant-sensor correlation was not possible."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            correlationFindings.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            stimulusObserved -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "The available throttle/pedal channels moved together during an observed throttle response. This does not prove every drive-by-wire channel is healthy."
            DiagnosticStatus.SUSPICIOUS -> "A throttle/pedal channel disagreement was found. Treat it as a reason for professional assessment, not a parts order."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The throttle response was not exercised, so there is no correlation to interpret. Repeat the blip so the channels move."
        }

        val stageSummaries = stages.map { analysis ->
            val isResponse = analysis.stage.stageId == "throttle_response"
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = if (isResponse) analysis.valid && stimulusObserved else analysis.valid,
                keyPid = Signals.THROTTLE,
                outcome = when {
                    isResponse && !stimulusObserved -> StageOutcome.NOT_EXERCISED
                    else -> analysis.stage.outcome
                },
                stimulusObserved = if (isResponse) stimulusObserved else null,
                metrics = buildMap {
                    if (analysis.throttle.available) put("throttle_pct", analysis.throttle.median)
                    if (analysis.pedalD.available) put("pedal_d_pct", analysis.pedalD.median)
                    if (isResponse) {
                        put("throttle_range_pct", analysis.throttle.range)
                        put("rpm_range", analysis.rpm.range)
                    }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.MEDIUM
                correlationFindings.isNotEmpty() -> ResultConfidence.LOW
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
                "PID 0x11 is not guaranteed to represent both redundant throttle channels.",
                "Pedal and throttle channels may be intentionally scaled or offset differently.",
                "A correlation check cannot prove a sensor is good; it only shows whether the channels track.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val throttle = AnalysisSupport.statsOrNull(stage, Signals.THROTTLE) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            throttle = throttle,
            relativeThrottle = AnalysisSupport.statsOrNull(stage, Signals.RELATIVE_THROTTLE) ?: AnalysisSupport.stats(emptyList()),
            throttleB = AnalysisSupport.statsOrNull(stage, Signals.THROTTLE_B) ?: AnalysisSupport.stats(emptyList()),
            pedalD = AnalysisSupport.statsOrNull(stage, Signals.PEDAL_D) ?: AnalysisSupport.stats(emptyList()),
            pedalE = AnalysisSupport.statsOrNull(stage, Signals.PEDAL_E) ?: AnalysisSupport.stats(emptyList()),
            commandedThrottle = AnalysisSupport.statsOrNull(stage, Signals.COMMANDED_THROTTLE) ?: AnalysisSupport.stats(emptyList()),
            rpm = rpm,
            valid = throttle.count >= MIN_SAMPLES && rpm.count >= MIN_SAMPLES,
        )
    }
}
