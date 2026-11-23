package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint
import kotlin.math.abs

/**
 * Thermostat vs coolant-temperature-sensor interpreter.
 *
 * Warm-up time, plateau temperature and implausible transitions are evaluated separately. The
 * rules never turn a single temperature into a thermostat verdict, and a cold-soak mismatch is
 * reported as a plausibility concern rather than proof of a failed sensor.
 */
object ThermostatAnalyzer {

    private const val PLATEAU_MIN_C = 75.0
    private const val PLATEAU_MAX_C = 105.0
    private const val STUCK_OPEN_C = 70.0
    private const val SLOW_WARMUP_SECONDS = 600.0
    private const val JUMP_C = 8.0
    private const val JUMP_WINDOW_MS = 3_000L
    private const val DROP_C = 15.0
    private const val DROP_WINDOW_MS = 5_000L
    private const val SOAK_DIFFERENCE_C = 12.0
    private const val OVERHEAT_C = 110.0

    private data class Transition(val fromC: Double, val toC: Double, val deltaMs: Long)

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(input, keyPids = listOf(Signals.COOLANT), minHz = 0.05)
        val stage = input.stage("cold_warmup")
        val coolantPoints = stage?.series(Signals.COOLANT).orEmpty().sortedBy { it.timestampMs }
        val iatPoints = stage?.series(Signals.IAT).orEmpty().sortedBy { it.timestampMs }
        val ambientPoints = stage?.series(Signals.AMBIENT).orEmpty().sortedBy { it.timestampMs }
        val rpm = AnalysisSupport.stats(stage?.series(Signals.RPM).orEmpty())
        val coolant = AnalysisSupport.stats(coolantPoints)

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        if (coolantPoints.isEmpty()) {
            return GuidedResult(
                diagnosticId = input.definition.id,
                definitionVersion = input.definition.version,
                title = input.definition.title,
                status = if (input.safetyStopReason != null) DiagnosticStatus.SAFETY_STOPPED else DiagnosticStatus.UNABLE_TO_RUN,
                confidence = ResultConfidence.LOW,
                summary = input.safetyStopReason
                    ?: "No coolant-temperature samples were captured. The car may not report PID 0x05, or the capture did not run.",
                quality = quality.copy(requiredSignalsPresent = false),
                limitations = AnalysisSupport.baseLimitations(input),
                warnings = warnings,
                nextSteps = listOf("Confirm the car reports coolant temperature, then re-run the warm-up capture."),
                generatedAt = input.generatedAt,
                profile = input.capabilities.profile,
                stagesSkipped = input.skippedStages,
            )
        }

        val initial = coolantPoints.first().value
        val final = coolantPoints.last().value
        val max = coolant.max
        val min = coolant.min
        val startMs = coolantPoints.first().timestampMs
        val endMs = coolantPoints.last().timestampMs
        val durationSeconds = (endMs - startMs) / 1000.0
        val lastWindowMs = 60_000L
        val plateauPoints = coolantPoints.filter { it.timestampMs >= endMs - lastWindowMs }
            .ifEmpty { coolantPoints.takeLast((coolantPoints.size * 0.2).toInt().coerceAtLeast(3)) }
        val plateau = AnalysisSupport.median(plateauPoints.map { it.value })
        val timeTo70 = timeTo(coolantPoints, 70.0)
        val timeTo80 = timeTo(coolantPoints, 80.0)
        val transitions = transitions(coolantPoints)
        val maxJump = transitions.filter { it.deltaMs in 200..JUMP_WINDOW_MS }.maxByOrNull { it.toC - it.fromC }
        val maxDrop = transitions.filter { it.deltaMs in 200..DROP_WINDOW_MS }.minByOrNull { it.toC - it.fromC }

        val iatInitial = iatPoints.firstOrNull()?.value
        val ambientInitial = ambientPoints.firstOrNull()?.value
        val ambient = input.prerequisites.ambientTemperatureC ?: ambientInitial ?: iatInitial
        val coldSoakConfirmed = input.prerequisites.engineColdConfirmed
        val coldSoakPlausible = coldSoakConfirmed && initial < 50.0
        val coldSoakInvalidReason = when {
            !coldSoakConfirmed -> "The engine was not confirmed cold-soaked, so the cold-start comparison is disabled."
            initial >= 50.0 -> "Coolant started at ${AnalysisSupport.format(initial)} °C, so this was not a cold start."
            else -> null
        }

        observations += GuidedObservation("Initial coolant", AnalysisSupport.valueWithUnit(initial, "°C", 1))
        ambient?.let { observations += GuidedObservation("Ambient / intake reference", AnalysisSupport.valueWithUnit(it, "°C", 1)) }
        observations += GuidedObservation("Plateau (last 60 s)", AnalysisSupport.valueWithUnit(plateau, "°C", 1))
        observations += GuidedObservation("Maximum coolant", AnalysisSupport.valueWithUnit(max, "°C", 1))
        observations += GuidedObservation(
            "Time to 70 °C",
            timeTo70?.let { "${AnalysisSupport.format(it, 0)} s" } ?: "Not reached during the capture",
        )
        timeTo80?.let { observations += GuidedObservation("Time to 80 °C", "${AnalysisSupport.format(it, 0)} s") }
        observations += GuidedObservation("Capture duration", "${AnalysisSupport.format(durationSeconds, 0)} s")
        if (rpm.available) observations += GuidedObservation("Median engine speed", AnalysisSupport.valueWithUnit(rpm.median, "rpm", 0))

        maxJump?.let {
            observations += GuidedObservation(
                "Largest upward step",
                "${AnalysisSupport.format(it.toC - it.fromC)} °C in ${AnalysisSupport.format(it.deltaMs / 1000.0, 1)} s",
            )
        }
        maxDrop?.let {
            observations += GuidedObservation(
                "Largest downward step",
                "${AnalysisSupport.format(it.toC - it.fromC)} °C in ${AnalysisSupport.format(it.deltaMs / 1000.0, 1)} s",
            )
        }

        val tooShort = coolantPoints.size < 20 || durationSeconds < 60.0
        if (tooShort) {
            warnings += "The warm-up capture was too short for a trend; at least 60 seconds and 20 samples are required."
        }

        if (coldSoakPlausible && iatInitial != null && abs(initial - iatInitial) > SOAK_DIFFERENCE_C) {
            findings += GuidedFinding(
                id = "temperature_plausibility",
                title = "Cold-soak temperatures disagree",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "Coolant and intake-air temperatures differed by more than expected at a cold start. That can be a sensor/wiring concern, an air pocket, a warm engine that was not really cold-soaked, or different sensor locations. It is not proof of a failed coolant sensor.",
                supporting = listOf(
                    "Initial coolant ${AnalysisSupport.format(initial)} °C",
                    "Initial intake air ${AnalysisSupport.format(iatInitial)} °C",
                    "Difference ${AnalysisSupport.format(abs(initial - iatInitial))} °C",
                ),
                against = listOf("Sensor locations and soak history can differ"),
                alternatives = listOf(
                    "Engine not fully cold-soaked",
                    "Intake sensor heat soak",
                    "Coolant sensor/wiring bias",
                    "Air pocket near the coolant sensor",
                ),
                nextCheck = "Repeat after a true overnight cold soak and compare both sensors with an infrared thermometer or a second scan tool.",
            )
            nextSteps += "Verify the cold soak and compare both temperature sensors with an independent measurement."
        } else if (!coldSoakConfirmed) {
            warnings += coldSoakInvalidReason ?: "Cold-soak comparison disabled."
        } else if (initial >= 50.0) {
            warnings += coldSoakInvalidReason ?: "Cold-soak comparison disabled."
        }

        val implausibleJump = maxJump != null && maxJump.toC - maxJump.fromC > JUMP_C
        val implausibleDrop = maxDrop != null && maxDrop.fromC - maxDrop.toC > DROP_C
        if (implausibleJump || implausibleDrop) {
            val direction = if (implausibleJump) "upward" else "downward"
            val transition = if (implausibleJump) maxJump!! else maxDrop!!
            findings += GuidedFinding(
                id = "ect_signal_implausible",
                title = "Coolant signal has an implausible step",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The coolant reading made an abrupt $direction step that is not physically plausible for a coolant system over that time. That points to measurement, wiring or data corruption before it points to a thermostat.",
                supporting = listOf(
                    "${AnalysisSupport.format(transition.fromC)} °C → ${AnalysisSupport.format(transition.toC)} °C",
                    "Step occurred within ${AnalysisSupport.format(transition.deltaMs / 1000.0, 1)} s",
                ),
                against = listOf("A thermostat opening usually changes temperature gradually"),
                alternatives = listOf(
                    "Coolant sensor connector or wiring",
                    "Sensor signal corruption",
                    "Air pocket or coolant loss",
                    "Adapter/ECU data glitch",
                ),
                nextCheck = "Check the coolant-sensor connector and wiring, then repeat the capture. Compare with an independent thermometer.",
            )
            nextSteps += "Inspect the coolant sensor connector/wiring and repeat the capture before replacing the thermostat."
        }

        if (plateau < STUCK_OPEN_C && !tooShort) {
            findings += GuidedFinding(
                id = "thermostat_stuck_open",
                title = "Consistent with a thermostat opening early",
                evidenceLevel = if (coldSoakPlausible) EvidenceLevel.MODERATE else EvidenceLevel.SUPPORTIVE,
                confidence = if (coldSoakPlausible) ResultConfidence.MEDIUM else ResultConfidence.LOW,
                explanation = "The coolant temperature plateaued below the usual operating range. A thermostat that opens too early is one explanation, but low ambient temperature, cabin heating, light load and a biased sensor can also keep the reading low.",
                supporting = listOf(
                    "Plateau ${AnalysisSupport.format(plateau)} °C",
                    timeTo70?.let { "Time to 70 °C: ${AnalysisSupport.format(it, 0)} s" }
                        ?: "70 °C was not reached",
                ),
                against = buildList {
                    if (!coldSoakPlausible) add("The cold-soak precondition was not satisfied")
                    if (ambient != null && ambient < 5) add("Cold ambient temperature (${AnalysisSupport.format(ambient)} °C)")
                },
                alternatives = listOf(
                    "Thermostat opening early / stuck open",
                    "Cold ambient or cabin heating",
                    "Coolant sensor bias",
                    "Low engine load during warm-up",
                ),
                nextCheck = "Compare the coolant reading with an infrared thermometer at the thermostat housing and confirm the thermostat setpoint for this engine.",
            )
            nextSteps += "Verify the thermostat setpoint and compare the coolant reading with an independent thermometer."
        } else if (plateau < PLATEAU_MIN_C && !tooShort) {
            findings += GuidedFinding(
                id = "low_operating_temperature",
                title = "Operating temperature is lower than typical",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "The plateau is below the typical range but close enough that ambient conditions, load and sensor tolerance may explain it. This is a weak signal, not a thermostat verdict.",
                supporting = listOf("Plateau ${AnalysisSupport.format(plateau)} °C"),
                alternatives = listOf("Cold ambient", "Cabin heating", "Sensor tolerance", "Thermostat opening early"),
                nextCheck = "Repeat on a warmer day or after a longer drive before acting on it.",
            )
            nextSteps += "Repeat the warm-up on a warmer day or after a longer drive before replacing parts."
        }

        if (timeTo70 == null || timeTo70 > SLOW_WARMUP_SECONDS) {
            if (!tooShort) {
                findings += GuidedFinding(
                    id = "slow_warmup",
                    title = "Slow warm-up",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "The engine took longer than the configured limit to reach 70 °C. That is consistent with a thermostat opening early, but cold ambient, low load and cabin heating also slow warm-up.",
                    supporting = listOf(
                        timeTo70?.let { "Time to 70 °C: ${AnalysisSupport.format(it, 0)} s" }
                            ?: "70 °C was not reached within the capture",
                    ),
                    alternatives = listOf("Cold ambient", "Low load", "Cabin heating", "Thermostat opening early"),
                    nextCheck = "Repeat with the cabin heat off and compare the warm-up curve.",
                )
                nextSteps += "Repeat with cabin heat off and a similar ambient temperature."
            }
        }

        if (max > OVERHEAT_C || plateau > OVERHEAT_C) {
            findings += GuidedFinding(
                id = "overheating",
                title = "Coolant temperature is too high",
                evidenceLevel = EvidenceLevel.STRONG,
                confidence = ResultConfidence.HIGH,
                explanation = "The coolant temperature exceeded the safe test limit. Stop driving and let the engine cool; this is not a thermostat-vs-sensor question until the overheating is assessed.",
                supporting = listOf(
                    "Maximum ${AnalysisSupport.format(max)} °C",
                    "Plateau ${AnalysisSupport.format(plateau)} °C",
                ),
                alternatives = listOf("Coolant loss", "Thermostat stuck closed", "Fan/control fault", "Sensor error"),
                nextCheck = "Stop the engine, let it cool, and check coolant level and the cooling fan. Do not open the system while hot.",
            )
            warnings += "Coolant exceeded the safe limit. Stop driving and have the cooling system assessed."
            nextSteps += "Stop and assess the cooling system before further driving."
        }

        val normal = findings.isEmpty() && !tooShort &&
            initial < 70.0 &&
            plateau in PLATEAU_MIN_C..PLATEAU_MAX_C &&
            (timeTo70 == null || timeTo70 <= SLOW_WARMUP_SECONDS)

        if (findings.isEmpty() && !normal && !tooShort) {
            nextSteps += "The curve did not match a clear pattern. Repeat after a true cold soak and note ambient temperature and cabin-heat use."
        }
        if (normal) {
            nextSteps += "No warm-up anomaly was detected in this capture. If P0128 returns, compare the coolant reading with an independent thermometer."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            tooShort -> DiagnosticStatus.INCONCLUSIVE
            findings.any { it.id == "overheating" || it.id == "ect_signal_implausible" } -> DiagnosticStatus.SUSPICIOUS
            findings.any { it.id == "thermostat_stuck_open" } -> DiagnosticStatus.SUSPICIOUS
            findings.any { it.id == "slow_warmup" } -> DiagnosticStatus.SUSPICIOUS
            normal -> DiagnosticStatus.NO_ANOMALY_DETECTED
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.INCONCLUSIVE
        }

        val confidence = when {
            status == DiagnosticStatus.NO_ANOMALY_DETECTED && coldSoakPlausible -> ResultConfidence.HIGH
            status == DiagnosticStatus.SUSPICIOUS && coldSoakPlausible -> ResultConfidence.MEDIUM
            findings.isNotEmpty() -> ResultConfidence.LOW
            else -> ResultConfidence.LOW
        }

        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason
                ?: "The test stopped for safety. Let the engine cool before continuing."
            DiagnosticStatus.CANCELLED -> "The warm-up capture was cancelled."
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "The coolant curve reached a normal plateau without an implausible step. No warm-up anomaly was detected in this capture."
            DiagnosticStatus.SUSPICIOUS -> findings.first().explanation
            else -> findings.firstOrNull()?.explanation
                ?: "The warm-up data was not strong enough for a conclusion. A true cold soak and a longer capture improve it."
        }

        val limitations = AnalysisSupport.baseLimitations(input) + buildList {
            if (!coldSoakPlausible) add("Cold-soak comparison was disabled; only the warm-up trend and plateau were interpreted.")
            add("Thermostat setpoints, ambient temperature, cabin heating and engine design change the warm-up curve.")
            add("A thermostat and a coolant sensor can be faulty at the same time.")
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
            stages = listOfNotNull(
                stage?.let {
                    AnalysisSupport.stageSummary(
                        stage = it,
                        valid = !tooShort,
                        keyPid = Signals.COOLANT,
                        metrics = buildMap {
                            put("initialC", initial)
                            put("plateauC", plateau)
                            put("maxC", max)
                            timeTo70?.let { value -> put("timeTo70s", value) }
                        },
                        notes = listOfNotNull(coldSoakInvalidReason),
                    )
                },
            ),
            quality = quality,
            limitations = limitations.distinct(),
            warnings = warnings.distinct(),
            nextSteps = nextSteps.distinct(),
            generatedAt = input.generatedAt,
            profile = input.capabilities.profile,
            stagesSkipped = input.skippedStages,
        )
    }

    private fun timeTo(points: List<CapturePoint>, target: Double): Double? {
        val first = points.firstOrNull() ?: return null
        val hit = points.firstOrNull { it.value >= target } ?: return null
        return (hit.timestampMs - first.timestampMs) / 1000.0
    }

    private fun transitions(points: List<CapturePoint>): List<Transition> =
        points.zipWithNext { previous, next ->
            Transition(previous.value, next.value, next.timestampMs - previous.timestampMs)
        }
}
