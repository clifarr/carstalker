package com.carstalker.core.guided

import kotlin.math.abs

/**
 * MAP plausibility interpreter.
 *
 * Key-on manifold pressure is compared with barometric pressure (or a clearly labelled nominal
 * reference when BARO is absent). Idle-vacuum and throttle-response rules are applied only to a
 * naturally aspirated petrol engine; diesel and forced-induction profiles get the limited
 * key-on plausibility interpretation instead.
 */
object MapPlausibilityAnalyzer {

    private const val BARO_MISMATCH_KPA = 7.0
    private const val BARO_MATCH_KPA = 5.0
    private const val NOMINAL_BARO_KPA = 101.3
    private const val IDLE_VACUUM_MIN_KPA = 15.0
    private const val IDLE_VACUUM_TYPICAL_MIN_KPA = 25.0
    private const val IDLE_VACUUM_TYPICAL_MAX_KPA = 75.0
    private const val RESPONSE_MIN_KPA = 10.0
    private const val MIN_SAMPLES = 5

    private data class MapStage(
        val stage: StageCapture,
        val map: SeriesStats,
        val baro: SeriesStats,
        val rpm: SeriesStats,
        val throttle: SeriesStats,
        val valid: Boolean,
        val notes: List<String>,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.MAP, Signals.RPM),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it, definitionStage) }
        }
        val koeo = stages.firstOrNull { it.stage.stageId == "key_on_engine_off" }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val response = stages.firstOrNull { it.stage.stageId == "throttle_response" }
        val responseExercised = response != null &&
            response.map.count >= MIN_SAMPLES &&
            (response.throttle.range >= 5.0 || response.rpm.range >= 300.0)

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()
        val profile = input.capabilities.profile
        val fullPetrolRules = profile.petrolLike && profile.naturallyAspirated
        val engineLimited = !fullPetrolRules

        fun observe(stage: MapStage?) {
            stage ?: return
            if (stage.map.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} MAP",
                    "${AnalysisSupport.format(stage.map.median, 1)} kPa " +
                        "(range ${AnalysisSupport.format(stage.map.min, 1)}–${AnalysisSupport.format(stage.map.max, 1)})",
                )
            }
            if (stage.baro.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} barometric",
                    AnalysisSupport.valueWithUnit(stage.baro.median, "kPa", 1),
                )
            }
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
        }

        observe(koeo)
        observe(idle)
        observe(response)

        if (stages.isEmpty() || stages.all { !it.map.available }) {
            return GuidedResult(
                diagnosticId = input.definition.id,
                definitionVersion = input.definition.version,
                title = input.definition.title,
                status = if (input.safetyStopReason != null) DiagnosticStatus.SAFETY_STOPPED else DiagnosticStatus.UNABLE_TO_RUN,
                confidence = ResultConfidence.LOW,
                summary = input.safetyStopReason
                    ?: "No manifold-pressure samples were captured. Confirm the car reports PID 0x0B, then re-run.",
                quality = quality.copy(requiredSignalsPresent = false),
                limitations = AnalysisSupport.baseLimitations(input),
                warnings = warnings,
                nextSteps = listOf("Confirm MAP is reported, then repeat the engine-off and idle stages."),
                generatedAt = input.generatedAt,
                profile = profile,
                stagesSkipped = input.skippedStages,
            )
        }

        val koeoMap = koeo?.takeIf { it.valid }?.map
        val koeoBaro = koeo?.baro?.takeIf { it.available }
        val idleMap = idle?.takeIf { it.valid }?.map
        val idleBaro = idle?.baro?.takeIf { it.available }
        val baroReference = koeoBaro?.median ?: idleBaro?.median

        if (engineLimited) {
            warnings += when {
                profile.diesel -> "Diesel MAP behaviour differs; only the key-on plausibility part is interpreted."
                profile.forcedInduction -> "Forced induction changes manifold pressure; only the key-on plausibility part is interpreted."
                else -> "Engine type is not set; idle-vacuum rules are disabled. Set it in Settings for the full test."
            }
        }

        if (koeoMap != null) {
            if (koeoBaro != null) {
                val difference = abs(koeoMap.median - koeoBaro.median)
                when {
                    difference > BARO_MISMATCH_KPA -> findings += GuidedFinding(
                        id = "map_baro_mismatch",
                        title = "MAP and barometric pressure disagree",
                        evidenceLevel = EvidenceLevel.MODERATE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "With the engine off, manifold pressure should be close to atmospheric pressure. The mismatch is larger than the configured tolerance, so check the sensor, wiring/reference and the barometric source before blaming the engine.",
                        supporting = listOf(
                            "MAP ${AnalysisSupport.format(koeoMap.median, 1)} kPa",
                            "BARO ${AnalysisSupport.format(koeoBaro.median, 1)} kPa",
                            "Difference ${AnalysisSupport.format(difference, 1)} kPa",
                        ),
                        against = listOf("BARO may be stale, rounded or derived rather than measured"),
                        alternatives = listOf(
                            "MAP sensor bias or failure",
                            "MAP wiring/reference voltage",
                            "Stale or inferred BARO value",
                            "Unit/scaling mismatch",
                        ),
                        nextCheck = "Compare MAP with a known-good pressure source or a second scan tool, and inspect the MAP connector and reference voltage.",
                    )
                    difference > BARO_MATCH_KPA -> findings += GuidedFinding(
                        id = "map_baro_drift",
                        title = "MAP/BARO agreement is marginal",
                        evidenceLevel = EvidenceLevel.WEAK,
                        confidence = ResultConfidence.LOW,
                        explanation = "Engine-off MAP is close to barometric pressure but outside the tight tolerance. Sensor tolerance, BARO rounding and weather changes can explain it.",
                        supporting = listOf("Difference ${AnalysisSupport.format(difference, 1)} kPa"),
                        alternatives = listOf("Sensor tolerance", "BARO rounding", "Weather change"),
                        nextCheck = "Repeat the key-on capture and compare with a second pressure reference.",
                    )
                }
            } else {
                val difference = abs(koeoMap.median - NOMINAL_BARO_KPA)
                when {
                    koeoMap.median < 50 || koeoMap.median > 120 -> findings += GuidedFinding(
                        id = "map_absolute_implausible",
                        title = "Engine-off MAP is implausible",
                        evidenceLevel = EvidenceLevel.MODERATE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "Manifold pressure with the engine off is far outside the range expected near atmospheric pressure. That points to a sensor, wiring or scaling problem.",
                        supporting = listOf("Engine-off MAP ${AnalysisSupport.format(koeoMap.median, 1)} kPa"),
                        alternatives = listOf("MAP sensor/wiring", "Scaling or unit mismatch", "ECU default value"),
                        nextCheck = "Check the MAP sensor part number, connector and scaling; compare with a known-good pressure reference.",
                    )
                    difference > 12 -> findings += GuidedFinding(
                        id = "map_absolute_uncertain",
                        title = "Engine-off MAP differs from the nominal reference",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "BARO is not reported, so the comparison uses a nominal sea-level reference. Altitude, weather and sensor tolerance can explain the difference; it is not proof of a fault.",
                        supporting = listOf(
                            "Engine-off MAP ${AnalysisSupport.format(koeoMap.median, 1)} kPa",
                            "Nominal reference ${AnalysisSupport.format(NOMINAL_BARO_KPA, 1)} kPa",
                        ),
                        alternatives = listOf("Altitude", "Weather", "Sensor tolerance", "MAP wiring/scaling"),
                        nextCheck = "Compare with local barometric pressure or a second pressure reference.",
                    )
                }
            }
        }

        if (fullPetrolRules && idleMap != null) {
            val reference = baroReference ?: NOMINAL_BARO_KPA
            val vacuum = reference - idleMap.median
            when {
                idleMap.median < 10.0 -> findings += GuidedFinding(
                    id = "map_idle_implausible",
                    title = "Idle MAP is implausibly low",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Idle manifold pressure is below the range a running engine can physically produce. Check the sensor, wiring and scaling.",
                    supporting = listOf("Idle MAP ${AnalysisSupport.format(idleMap.median, 1)} kPa"),
                    alternatives = listOf("MAP sensor/wiring", "Scaling mismatch", "Sensor vacuum leak"),
                    nextCheck = "Inspect the MAP sensor and connector, and compare with a known-good pressure reading.",
                )
                vacuum < IDLE_VACUUM_MIN_KPA -> findings += GuidedFinding(
                    id = "low_idle_vacuum",
                    title = "Idle vacuum is lower than expected",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Idle manifold pressure is close to atmospheric, so manifold vacuum is low. A throttle that is open too far, an intake leak, an engine-condition problem or a biased sensor can do this.",
                    supporting = listOf(
                        "Idle MAP ${AnalysisSupport.format(idleMap.median, 1)} kPa",
                        "Reference ${AnalysisSupport.format(reference, 1)} kPa",
                        "Vacuum ${AnalysisSupport.format(vacuum, 1)} kPa",
                    ),
                    alternatives = listOf(
                        "Throttle plate/adaptation",
                        "Intake leak",
                        "Engine mechanical condition",
                        "MAP sensor bias",
                    ),
                    nextCheck = "Check for intake leaks and throttle adaptation, then compare MAP with a known-good gauge.",
                )
                vacuum < IDLE_VACUUM_TYPICAL_MIN_KPA -> findings += GuidedFinding(
                    id = "idle_vacuum_marginal",
                    title = "Idle vacuum is on the low side",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "Idle vacuum is below the typical band but not implausible. Throttle calibration, altitude and sensor tolerance can explain it.",
                    supporting = listOf("Vacuum ${AnalysisSupport.format(vacuum, 1)} kPa"),
                    alternatives = listOf("Throttle calibration", "Altitude", "Sensor tolerance"),
                    nextCheck = "Repeat at the same ambient conditions and compare with a second pressure reference.",
                )
                vacuum > IDLE_VACUUM_TYPICAL_MAX_KPA -> findings += GuidedFinding(
                    id = "high_idle_vacuum",
                    title = "Idle vacuum is unusually high",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "Very high idle vacuum can indicate an intake restriction or a sensor/reference problem. It is a weak signal on its own.",
                    supporting = listOf("Vacuum ${AnalysisSupport.format(vacuum, 1)} kPa"),
                    alternatives = listOf("Intake restriction", "Sensor/reference offset", "Altitude"),
                    nextCheck = "Inspect the intake path and compare with a known-good pressure reference.",
                )
            }
        }

        if (response != null && response.map.available) {
            val mapRange = response.map.range
            val throttleRange = response.throttle.range
            val rpmRange = response.rpm.range
            if (!responseExercised) {
                warnings += "No throttle or RPM movement was captured in the response stage; the MAP response check was not exercised and is excluded from the conclusion."
                nextSteps += "Repeat the gentle throttle blip so RPM or throttle actually moves, then re-run the test."
            }
            when {
                !responseExercised -> Unit
                throttleRange > 10.0 && mapRange < RESPONSE_MIN_KPA -> findings += GuidedFinding(
                    id = "poor_map_response",
                    title = "MAP barely responds to throttle",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Throttle position moved substantially while manifold pressure barely changed. That supports a MAP response or plumbing problem, but the capture may also have missed the fastest part of the transition.",
                    supporting = listOf(
                        "Throttle range ${AnalysisSupport.format(throttleRange, 1)} %",
                        "MAP range ${AnalysisSupport.format(mapRange, 1)} kPa",
                    ),
                    alternatives = listOf("MAP sensor response", "Blocked/damaged vacuum hose", "Capture timing", "Throttle data mismatch"),
                    nextCheck = "Repeat the blip with a helper watching live MAP, and inspect the MAP hose and connector.",
                )
                throttleRange == 0.0 && rpmRange > 500 && mapRange < 5.0 -> findings += GuidedFinding(
                    id = "map_static",
                    title = "MAP looks static while RPM changes",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Engine speed changed but manifold pressure stayed essentially flat. A stuck sensor, a blocked hose or a sampling problem can cause that.",
                    supporting = listOf(
                        "RPM range ${AnalysisSupport.format(rpmRange, 0)} rpm",
                        "MAP range ${AnalysisSupport.format(mapRange, 1)} kPa",
                    ),
                    alternatives = listOf("MAP sensor stuck", "Blocked vacuum hose", "Sampling/decoding problem"),
                    nextCheck = "Inspect the MAP hose and connector, then compare live MAP with a known-good gauge.",
                )
            }
        }

        val koeoNormal = when {
            koeoMap == null -> false
            koeoBaro != null -> abs(koeoMap.median - koeoBaro.median) <= BARO_MATCH_KPA
            else -> koeoMap.median in 50.0..120.0 && abs(koeoMap.median - NOMINAL_BARO_KPA) <= 12.0
        }
        val idleReference = baroReference ?: NOMINAL_BARO_KPA
        val idleVacuum = idleMap?.let { idleReference - it.median }
        val idleNormal = when {
            !fullPetrolRules -> true
            idleMap == null -> false
            else -> idleMap.median >= 10.0 && idleVacuum != null &&
                idleVacuum in IDLE_VACUUM_TYPICAL_MIN_KPA..IDLE_VACUUM_TYPICAL_MAX_KPA
        }
        val normal = findings.none { it.evidenceLevel != EvidenceLevel.WEAK } && koeoNormal && idleNormal

        if (normal) {
            nextSteps += if (engineLimited) {
                "Key-on plausibility looked normal. Set the engine type and repeat for the idle-vacuum interpretation."
            } else {
                "No MAP anomaly was detected in this capture. Re-test if the code returns or drivability changes."
            }
        } else if (findings.isEmpty()) {
            nextSteps += "Not enough valid stages for a conclusion. Repeat with the engine off and warm idle."
        } else {
            nextSteps += "Check the MAP sensor, connector, vacuum hose and scaling before replacing parts."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            !quality.requiredSignalsPresent -> DiagnosticStatus.UNABLE_TO_RUN
            normal -> DiagnosticStatus.NO_ANOMALY_DETECTED
            findings.any { it.evidenceLevel == EvidenceLevel.MODERATE || it.evidenceLevel == EvidenceLevel.STRONG } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.INCONCLUSIVE
        }

        val confidence = when {
            status == DiagnosticStatus.NO_ANOMALY_DETECTED && fullPetrolRules && idleMap != null -> ResultConfidence.HIGH
            status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.MEDIUM
            findings.any { it.evidenceLevel == EvidenceLevel.MODERATE } && fullPetrolRules -> ResultConfidence.MEDIUM
            findings.isNotEmpty() -> ResultConfidence.LOW
            else -> ResultConfidence.LOW
        }

        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason
                ?: "The test stopped for safety. Check the engine before continuing."
            DiagnosticStatus.CANCELLED -> "The MAP test was cancelled."
            DiagnosticStatus.UNABLE_TO_RUN -> "MAP or RPM data was missing, so the test could not run."
            DiagnosticStatus.NO_ANOMALY_DETECTED -> if (engineLimited) {
                "Engine-off MAP plausibility looked normal. Idle-vacuum rules were not applied for this engine profile."
            } else {
                "Engine-off MAP agreed with the pressure reference and idle vacuum was in the typical band. No MAP anomaly was detected in this test."
            }
            DiagnosticStatus.SUSPICIOUS -> findings.first().explanation
            else -> "The MAP data did not match a clear pattern. Repeat with the engine off and warm idle before acting."
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
            stages = stages.map { stage ->
                val isResponse = stage.stage.stageId == "throttle_response"
                AnalysisSupport.stageSummary(
                    stage = stage.stage,
                    valid = if (isResponse) stage.valid && responseExercised else stage.valid,
                    keyPid = Signals.MAP,
                    outcome = if (isResponse && !responseExercised) StageOutcome.NOT_EXERCISED else stage.stage.outcome,
                    stimulusObserved = if (isResponse) responseExercised else null,
                    metrics = buildMap {
                        if (stage.map.available) put("mapKpa", stage.map.median)
                        if (stage.baro.available) put("baroKpa", stage.baro.median)
                        if (stage.rpm.available) put("rpm", stage.rpm.median)
                        if (stage.throttle.available) put("throttlePercent", stage.throttle.median)
                    },
                    notes = stage.notes,
                )
            },
            quality = quality,
            limitations = (AnalysisSupport.baseLimitations(input) + buildList {
                if (engineLimited) add("Idle-vacuum and throttle-response rules were not applied to this engine profile.")
                add("Altitude, weather and intake restrictions shift absolute pressure readings.")
                add("A MAP/BARO mismatch can be a sensor, wiring, reference-voltage or unit problem.")
            }).distinct(),
            warnings = warnings.distinct(),
            nextSteps = nextSteps.distinct(),
            generatedAt = input.generatedAt,
            profile = profile,
            stagesSkipped = input.skippedStages,
        )
    }

    private fun analyzeStage(stage: StageCapture, definition: StageDefinition): MapStage {
        val map = AnalysisSupport.stats(stage.series(Signals.MAP))
        val baro = AnalysisSupport.stats(stage.series(Signals.BARO))
        val rpm = AnalysisSupport.stats(stage.series(Signals.RPM))
        val throttle = AnalysisSupport.stats(stage.series(Signals.THROTTLE))
        val notes = mutableListOf<String>()
        var valid = map.count >= MIN_SAMPLES
        if (!valid) notes += "Not enough MAP samples."
        val expectedRpm = definition.condition.rpmRange
        if (expectedRpm != null && rpm.available && rpm.median !in expectedRpm.first.toDouble()..expectedRpm.last.toDouble()) {
            valid = false
            notes += "Median RPM ${AnalysisSupport.format(rpm.median, 0)} was outside ${expectedRpm.first}–${expectedRpm.last}."
        }
        if (definition.condition.requireEngineOff && rpm.available && rpm.median > 100) {
            valid = false
            notes += "The engine was running during the engine-off stage."
        }
        if (definition.kind == StageKind.FREE_REV &&
            throttle.range < 5.0 &&
            rpm.range < 300.0
        ) {
            valid = false
            notes += "No throttle or RPM movement was captured; the response check was not evaluable."
        }
        if (stage.interruptions > 0) notes += "${stage.interruptions} interruption(s); mixed samples were discarded."
        return MapStage(stage, map, baro, rpm, throttle, valid, notes)
    }
}
