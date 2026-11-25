package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint
import kotlin.math.abs

/**
 * Intermittent power-loss interpreter for a passive drive capture with manual symptom markers.
 *
 * The marker is intentionally treated as approximate: the analyzer compares a short pre-event
 * window with a short post-event window, checks whether the driver lifted, and only then reports
 * a power-loss pattern. It never claims a specific failed component.
 */
object IntermittentPowerLossAnalyzer {

    private const val PRE_WINDOW_MS = 3_000L
    private const val EVENT_WINDOW_MS = 3_000L
    private const val RPM_DROP = 500.0
    private const val LOAD_DROP = 20.0
    private const val MAP_DROP = 15.0
    private const val VOLTAGE_DIP = 1.0
    private const val LOW_VOLTAGE = 11.0
    private const val TRIM_SPIKE = 15.0
    private const val THROTTLE_LIFT = 10.0
    private const val MIN_SAMPLES = 3

    private data class MarkerAnalysis(
        val marker: Long,
        val index: Int,
        val preRpm: SeriesStats,
        val eventRpm: SeriesStats,
        val preLoad: SeriesStats,
        val eventLoad: SeriesStats,
        val preMap: SeriesStats,
        val eventMap: SeriesStats,
        val preMaf: SeriesStats,
        val eventMaf: SeriesStats,
        val preVoltage: SeriesStats,
        val eventVoltage: SeriesStats,
        val preTrim: SeriesStats?,
        val eventTrim: SeriesStats?,
        val preThrottle: SeriesStats,
        val eventThrottle: SeriesStats,
        val driverLift: Boolean,
    ) {
        val rpmDrop: Double get() = preRpm.median - eventRpm.min
        val loadDrop: Double get() = preLoad.median - eventLoad.min
        val mapDrop: Double get() = preMap.median - eventMap.min
        val mafDrop: Double get() = preMaf.median - eventMaf.min
        val voltageDip: Double get() = preVoltage.median - eventVoltage.min
        val trimSpike: Double? get() = if (preTrim != null && eventTrim != null) {
            eventTrim.median - preTrim.median
        } else {
            null
        }
    }

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.LOAD),
            minHz = 0.5,
        )
        val stage = input.stages.firstOrNull { it.kind == StageKind.PASSIVE_CAPTURE }
            ?: input.stage("passive_capture")
        if (stage == null || stage.series(Signals.RPM).size < MIN_SAMPLES) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No passive drive capture was recorded. The test needs live RPM and vehicle speed.",
                nextSteps = listOf("Repeat the drive capture with a passenger and mark the symptom when it occurs."),
            )
        }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        val overallRpm = AnalysisSupport.stats(stage.series(Signals.RPM))
        val overallLoad = AnalysisSupport.stats(stage.series(Signals.LOAD))
        val overallSpeed = AnalysisSupport.stats(stage.series(Signals.SPEED))
        observations += GuidedObservation(
            "Capture duration",
            "${AnalysisSupport.format(stage.durationMs / 1000.0, 0)} s",
        )
        if (overallRpm.available) {
            observations += GuidedObservation(
                "RPM range",
                "${AnalysisSupport.format(overallRpm.min, 0)}–${AnalysisSupport.format(overallRpm.max, 0)} rpm",
            )
        }
        if (overallLoad.available) {
            observations += GuidedObservation(
                "Load range",
                "${AnalysisSupport.format(overallLoad.min, 1)}–${AnalysisSupport.format(overallLoad.max, 1)} %",
            )
        }
        if (overallSpeed.available) {
            observations += GuidedObservation(
                "Speed range",
                "${AnalysisSupport.format(overallSpeed.min, 0)}–${AnalysisSupport.format(overallSpeed.max, 0)} km/h",
            )
        }
        val invalidated = stage.invalidatedMarkers
        observations += GuidedObservation(
            "Symptom markers",
            buildString {
                append("${stage.markers.size} valid")
                if (invalidated.isNotEmpty()) append(", ${invalidated.size} invalidated")
            },
        )

        if (stage.markers.isEmpty()) {
            if (invalidated.isNotEmpty()) {
                warnings += "A symptom marker was recorded, but its capture segment was interrupted and invalidated. It is not presented as no marker."
                nextSteps += "Repeat with a passenger who can tap Mark symptom, and keep the required driving condition until the stage completes."
                return AnalysisSupport.buildResult(
                    input = input,
                    status = DiagnosticStatus.INCONCLUSIVE,
                    confidence = ResultConfidence.LOW,
                    summary = "A symptom marker was recorded, but the segment around it was discarded after an interruption, so there is no valid event window to compare.",
                    observations = observations,
                    stages = listOf(
                        AnalysisSupport.stageSummary(
                            stage,
                            valid = false,
                            keyPid = Signals.RPM,
                            outcome = StageOutcome.INTERRUPTED,
                            notes = listOf("Marker recorded but invalidated by an interruption."),
                        ),
                    ),
                    quality = quality,
                    warnings = warnings,
                    nextSteps = nextSteps,
                    limitations = listOf(
                        "The symptom marker is manual; an invalidated segment cannot be used as evidence.",
                        "A short capture may not reproduce an intermittent fault.",
                    ),
                )
            }
            warnings += "No symptom marker was recorded. The capture documents the drive but cannot localize the event."
            nextSteps += "Repeat with a passenger who can tap Mark symptom when the power loss occurs."
            return AnalysisSupport.buildResult(
                input = input,
                status = DiagnosticStatus.INCONCLUSIVE,
                confidence = ResultConfidence.LOW,
                summary = "No symptom was marked during the drive capture, so there is no event to compare.",
                observations = observations,
                stages = listOf(
                    AnalysisSupport.stageSummary(
                        stage,
                        valid = false,
                        keyPid = Signals.RPM,
                        outcome = StageOutcome.NOT_EXERCISED,
                        notes = listOf("No symptom marker was entered."),
                    ),
                ),
                quality = quality,
                warnings = warnings,
                nextSteps = nextSteps,
                limitations = listOf(
                    "The symptom marker is manual; an unmarked event cannot be localized.",
                    "A short capture may not reproduce an intermittent fault.",
                ),
            )
        }

        val analyses = stage.markers.sorted().mapIndexedNotNull { index, marker ->
            analyzeMarker(stage, marker, index + 1)
        }
        if (analyses.isEmpty()) {
            return AnalysisSupport.unable(
                input,
                quality,
                "The marked events did not have enough samples before or after them for a comparison.",
                nextSteps = listOf("Repeat with a longer capture and a faster adapter sample rate."),
            )
        }

        analyses.forEach { analysis ->
            observations += GuidedObservation(
                "Marker ${analysis.index}",
                "RPM ${AnalysisSupport.format(analysis.preRpm.median, 0)} → ${AnalysisSupport.format(analysis.eventRpm.min, 0)} rpm, " +
                    "load ${AnalysisSupport.format(analysis.preLoad.median, 1)} → ${AnalysisSupport.format(analysis.eventLoad.min, 1)} %, " +
                    "throttle ${AnalysisSupport.format(analysis.preThrottle.median, 1)} → ${AnalysisSupport.format(analysis.eventThrottle.min, 1)} %",
            )
            if (analysis.preMap.available && analysis.eventMap.available) {
                observations += GuidedObservation(
                    "Marker ${analysis.index} MAP",
                    "${AnalysisSupport.format(analysis.preMap.median, 1)} → ${AnalysisSupport.format(analysis.eventMap.min, 1)} kPa",
                )
            }
            if (analysis.preVoltage.available && analysis.eventVoltage.available) {
                observations += GuidedObservation(
                    "Marker ${analysis.index} voltage",
                    "${AnalysisSupport.format(analysis.preVoltage.median, 2)} → ${AnalysisSupport.format(analysis.eventVoltage.min, 2)} V",
                )
            }
            analysis.trimSpike?.let {
                observations += GuidedObservation(
                    "Marker ${analysis.index} trim change",
                    "${AnalysisSupport.formatSigned(it)} percentage points",
                )
            }
        }

        val powerLoss = analyses.filter { !it.driverLift && (it.rpmDrop > RPM_DROP || it.loadDrop > LOAD_DROP) }
        val boostLoss = analyses.filter { !it.driverLift && it.preMap.available && it.eventMap.available && it.mapDrop > MAP_DROP }
        val electrical = analyses.filter {
            it.preVoltage.available && it.eventVoltage.available &&
                (it.voltageDip > VOLTAGE_DIP || it.eventVoltage.min < LOW_VOLTAGE)
        }
        val fuelEvent = analyses.filter { analysis ->
            val spike = analysis.trimSpike ?: return@filter false
            abs(spike) > TRIM_SPIKE
        }
        val driverLift = analyses.filter { it.driverLift }

        if (powerLoss.isNotEmpty()) {
            findings += GuidedFinding(
                id = "power_loss_event",
                title = "RPM or load dropped at a marked event",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "A marked event coincided with a material RPM or load drop while the throttle did not drop enough to explain it as a normal lift. That supports investigating an ignition, fuel, boost, electrical or mechanical cause, but one capture cannot identify it.",
                supporting = powerLoss.map {
                    "Marker ${it.index}: RPM drop ${AnalysisSupport.format(it.rpmDrop, 0)} rpm, load drop ${AnalysisSupport.format(it.loadDrop, 1)} points"
                },
                against = listOf("Driver input and road conditions can change the signals around the marker"),
                alternatives = listOf(
                    "Ignition misfire/coil/plug",
                    "Fuel delivery/pressure",
                    "Boost leak or control",
                    "Electrical/voltage dip",
                    "Transmission or traction control",
                    "Mechanical engine fault",
                ),
                nextCheck = "Correlate the marked event with stored/pending codes and a longer capture focused on the affected signals.",
            )
            nextSteps += "Check stored/pending codes and repeat the capture with the most relevant signals prioritized."
        }

        if (boostLoss.isNotEmpty()) {
            findings += GuidedFinding(
                id = "boost_loss_event",
                title = "Manifold pressure dropped at a marked event",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "MAP fell sharply around a marked event. A boost leak, turbo control issue, throttle/load change or sensor problem can produce this; it is not proof of a failed turbo.",
                supporting = boostLoss.map {
                    "Marker ${it.index}: MAP drop ${AnalysisSupport.format(it.mapDrop, 1)} kPa"
                },
                alternatives = listOf("Boost leak", "Turbo/wastegate control", "Throttle/load change", "MAP sensor"),
                nextCheck = "Review boost-related codes and repeat with requested/actual boost data if the vehicle exposes it.",
            )
        }

        if (electrical.isNotEmpty()) {
            findings += GuidedFinding(
                id = "electrical_dip_event",
                title = "Voltage dipped at a marked event",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "Control-module voltage fell around a marked event. An intermittent connection, charging-system issue or heavy load can cause this; it may also be a consequence rather than the cause of the power loss.",
                supporting = electrical.map {
                    "Marker ${it.index}: minimum ${AnalysisSupport.format(it.eventVoltage.min, 2)} V"
                },
                alternatives = listOf("Battery/charging", "Connection/ground", "Intermittent load", "Measurement artefact"),
                nextCheck = "Run the charging guide and inspect battery/ground connections.",
            )
        }

        if (fuelEvent.isNotEmpty()) {
            findings += GuidedFinding(
                id = "fuel_trim_event",
                title = "Fuel trim moved sharply at a marked event",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The combined fuel trim changed around a marked event. A fuel-delivery or airflow disturbance can do this, but the marker timing and sample rate are approximate.",
                supporting = fuelEvent.map {
                    "Marker ${it.index}: ${AnalysisSupport.formatSigned(it.trimSpike ?: 0.0)} points"
                },
                alternatives = listOf("Fuel delivery", "Airflow measurement", "Sensor response", "Marker timing"),
                nextCheck = "Repeat with a longer capture and review fuel-pressure/airflow data around the event.",
            )
        }

        if (driverLift.isNotEmpty()) {
            warnings += "${driverLift.size} marked event(s) included a large throttle reduction; they were not counted as power-loss patterns."
        }
        if (powerLoss.isEmpty() && boostLoss.isEmpty() && electrical.isEmpty() && fuelEvent.isEmpty()) {
            warnings += "No clear signal change was found around the marked events. The symptom may be too brief for the adapter's sample rate or may not have reproduced."
            nextSteps += "Repeat with a faster sample rate or a longer capture if the symptom is reproducible."
        }
        warnings += "The symptom marker is manual; event timing depends on the passenger and adapter sample rate."
        warnings += "A short drive capture cannot prove a specific component failed."

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            powerLoss.isNotEmpty() || boostLoss.isNotEmpty() || electrical.isNotEmpty() || fuelEvent.isNotEmpty() ->
                DiagnosticStatus.SUSPICIOUS
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.SUSPICIOUS ->
                "A marked event coincided with a measurable signal change. The result points to the next capture/check; it does not prove a component failed."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The marked events did not show a clear signal change that this capture can interpret."
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = ResultConfidence.LOW,
            summary = summary,
            observations = observations,
            findings = findings,
            stages = listOf(
                AnalysisSupport.stageSummary(
                    stage = stage,
                    valid = true,
                    keyPid = Signals.RPM,
                    metrics = buildMap {
                        put("markers", stage.markers.size.toDouble())
                        put("rpm_stddev", overallRpm.stdDev)
                    },
                ),
            ),
            quality = quality,
            warnings = warnings,
            nextSteps = nextSteps,
            limitations = listOf(
                "The symptom marker is manual; event timing depends on the passenger and adapter sample rate.",
                "A short capture may not reproduce an intermittent fault.",
                "The test cannot distinguish ignition, fuel, boost, electrical, transmission or traction-control causes.",
            ),
        )
    }

    private fun analyzeMarker(stage: StageCapture, marker: Long, index: Int): MarkerAnalysis? {
        val preStart = marker - PRE_WINDOW_MS
        val preEnd = marker - 200
        val eventEnd = marker + EVENT_WINDOW_MS
        val preRpm = stats(stage, Signals.RPM, preStart, preEnd) ?: return null
        val eventRpm = stats(stage, Signals.RPM, marker, eventEnd) ?: return null
        val preLoad = stats(stage, Signals.LOAD, preStart, preEnd) ?: return null
        val eventLoad = stats(stage, Signals.LOAD, marker, eventEnd) ?: return null
        if (preRpm.count < MIN_SAMPLES || eventRpm.count < MIN_SAMPLES) return null

        val preThrottle = stats(stage, Signals.THROTTLE, preStart, preEnd) ?: emptyStats()
        val eventThrottle = stats(stage, Signals.THROTTLE, marker, eventEnd) ?: emptyStats()
        val throttleDrop = if (preThrottle.available && eventThrottle.available) {
            preThrottle.median - eventThrottle.min
        } else {
            0.0
        }

        val preTrimPoints = AnalysisSupport.combinedTrim(
            points(stage, Signals.STFT_B1, preStart, preEnd),
            points(stage, Signals.LTFT_B1, preStart, preEnd),
        )
        val eventTrimPoints = AnalysisSupport.combinedTrim(
            points(stage, Signals.STFT_B1, marker, eventEnd),
            points(stage, Signals.LTFT_B1, marker, eventEnd),
        )

        return MarkerAnalysis(
            marker = marker,
            index = index,
            preRpm = preRpm,
            eventRpm = eventRpm,
            preLoad = preLoad,
            eventLoad = eventLoad,
            preMap = stats(stage, Signals.MAP, preStart, preEnd) ?: emptyStats(),
            eventMap = stats(stage, Signals.MAP, marker, eventEnd) ?: emptyStats(),
            preMaf = stats(stage, Signals.MAF, preStart, preEnd) ?: emptyStats(),
            eventMaf = stats(stage, Signals.MAF, marker, eventEnd) ?: emptyStats(),
            preVoltage = stats(stage, Signals.CONTROL_MODULE_VOLTAGE, preStart, preEnd) ?: emptyStats(),
            eventVoltage = stats(stage, Signals.CONTROL_MODULE_VOLTAGE, marker, eventEnd) ?: emptyStats(),
            preTrim = preTrimPoints.takeIf { it.size >= MIN_SAMPLES }?.let { AnalysisSupport.stats(it) },
            eventTrim = eventTrimPoints.takeIf { it.size >= MIN_SAMPLES }?.let { AnalysisSupport.stats(it) },
            preThrottle = preThrottle,
            eventThrottle = eventThrottle,
            driverLift = preThrottle.available && eventThrottle.available && throttleDrop > THROTTLE_LIFT,
        )
    }

    private fun stats(stage: StageCapture, pid: Int, start: Long, end: Long): SeriesStats? {
        val values = points(stage, pid, start, end)
        return values.takeIf { it.size >= MIN_SAMPLES }?.let { AnalysisSupport.stats(it) }
    }

    private fun points(stage: StageCapture, pid: Int, start: Long, end: Long): List<CapturePoint> =
        stage.series(pid).filter { it.timestampMs in start..end }

    private fun emptyStats(): SeriesStats = AnalysisSupport.stats(emptyList())
}
