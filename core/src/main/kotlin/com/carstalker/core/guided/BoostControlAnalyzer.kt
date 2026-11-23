package com.carstalker.core.guided

import kotlin.math.abs

/**
 * Limited boost-tracking interpreter.
 *
 * Generic OBD does not expose a requested-boost target on most vehicles, so the app compares
 * measured manifold pressure with barometric pressure and checks whether a gentle throttle
 * response produces any measurable rise. It never claims measured-under-requested boost.
 */
object BoostControlAnalyzer {

    private const val NOMINAL_BARO_KPA = 101.3
    private const val SENSOR_MISMATCH_KPA = 7.0
    private const val BOOST_RESPONSE_KPA = 10.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val map: SeriesStats,
        val baro: SeriesStats,
        val rpm: SeriesStats,
        val throttle: SeriesStats,
        val load: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.MAP, Signals.RPM),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val keyOn = stages.firstOrNull { it.stage.stageId == "key_on_engine_off" }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val response = stages.firstOrNull { it.stage.stageId == "throttle_response" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
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

        observe(keyOn)
        observe(idle)
        observe(response)

        if (stages.isEmpty() || stages.all { !it.map.available }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No manifold-pressure samples were captured. Confirm MAP (PID 0x0B) is supported.",
                nextSteps = listOf("Confirm PID 0x0B is supported, then repeat the key-on and idle stages."),
            )
        }

        val baroReference = keyOn?.baro?.takeIf { it.available }?.median
            ?: idle?.baro?.takeIf { it.available }?.median
        if (baroReference == null) {
            warnings += "Barometric pressure was not reported. A nominal sea-level reference was used, so altitude and weather reduce confidence."
        }
        val reference = baroReference ?: NOMINAL_BARO_KPA

        val keyOnMap = keyOn?.map?.takeIf { it.count >= MIN_SAMPLES }
        if (keyOnMap != null && baroReference != null) {
            val difference = abs(keyOnMap.median - baroReference)
            if (difference > SENSOR_MISMATCH_KPA) {
                findings += GuidedFinding(
                    id = "boost_sensor_mismatch",
                    title = "Engine-off MAP and barometric pressure disagree",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "With the engine off, manifold pressure should be close to atmospheric. A larger mismatch points to the MAP sensor, its wiring/reference or the barometric source before any boost-control conclusion.",
                    supporting = listOf(
                        "Key-on MAP ${AnalysisSupport.format(keyOnMap.median, 1)} kPa",
                        "BARO ${AnalysisSupport.format(baroReference, 1)} kPa",
                        "Difference ${AnalysisSupport.format(difference, 1)} kPa",
                    ),
                    alternatives = listOf("MAP sensor/wiring", "BARO stale or inferred", "Unit/scaling mismatch", "Altitude/weather"),
                    nextCheck = "Verify MAP against a known-good pressure reference and inspect the sensor connector before testing the turbo system.",
                )
                nextSteps += "Verify MAP sensor plausibility before diagnosing boost control."
            }
        }

        val idleMap = idle?.map?.takeIf { it.count >= MIN_SAMPLES }
        val responseMap = response?.map?.takeIf { it.count >= MIN_SAMPLES }
        if (idleMap != null) {
            val idleBoost = idleMap.median - reference
            observations += GuidedObservation(
                "Idle pressure relative to reference",
                "${AnalysisSupport.formatSigned(idleBoost, 1)} kPa",
            )
            if (idleBoost > BOOST_RESPONSE_KPA) {
                findings += GuidedFinding(
                    id = "idle_pressure_high",
                    title = "Idle manifold pressure is above the reference",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "Idle pressure above atmospheric is unusual for a conventional engine and can indicate a sensor/reference problem or that the capture was not taken at idle. It is not an overboost diagnosis.",
                    supporting = listOf("Idle MAP ${AnalysisSupport.format(idleMap.median, 1)} kPa; reference ${AnalysisSupport.format(reference, 1)} kPa"),
                    alternatives = listOf("MAP sensor bias", "BARO reference error", "Not at idle", "Forced-induction anti-lag/strategy"),
                    nextCheck = "Repeat at a confirmed warm idle and verify MAP/BARO with a known-good reference.",
                )
            }
        }

        if (idleMap != null && responseMap != null) {
            val rise = responseMap.max - idleMap.median
            if (rise < BOOST_RESPONSE_KPA) {
                findings += GuidedFinding(
                    id = "no_boost_response",
                    title = "No measurable boost rise during the free rev",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "Manifold pressure did not rise meaningfully during the throttle response. A stationary free rev may not produce enough exhaust flow to spool the turbo, so this is not evidence of underboost.",
                    supporting = listOf(
                        "Idle MAP ${AnalysisSupport.format(idleMap.median, 1)} kPa",
                        "Response maximum ${AnalysisSupport.format(responseMap.max, 1)} kPa",
                        "Rise ${AnalysisSupport.format(rise, 1)} kPa",
                    ),
                    alternatives = listOf(
                        "Free rev did not spool the turbo",
                        "Boost leak or stuck actuator",
                        "MAP sensor response",
                        "Throttle did not open enough",
                    ),
                    nextCheck = "If the symptom occurs under load, arrange a passenger-operated road capture or a workshop boost test.",
                )
                nextSteps += "Use a passenger-operated road capture or a workshop boost test before condemning the turbo."
            } else {
                observations += GuidedObservation(
                    "Boost response",
                    "MAP rose ${AnalysisSupport.format(rise, 1)} kPa during the throttle response.",
                )
            }
        } else {
            warnings += "A warm-idle and throttle-response stage are both needed to judge boost response."
        }

        warnings += "Requested boost is not available through generic OBD; this test cannot prove underboost versus target."
        if (input.capabilities.profile.naturallyAspirated) {
            warnings += "The vehicle profile says naturally aspirated; boost-control interpretation does not apply."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "boost_sensor_mismatch" } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            idleMap != null && responseMap != null -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "A measurable manifold-pressure response was captured and the engine-off sensor check was consistent. This is not a requested-versus-actual boost test."
            DiagnosticStatus.SUSPICIOUS -> "A boost-sensor plausibility issue was found. Resolve it before testing the turbo system."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The limited boost test did not produce a definitive result; a road or workshop test may be needed."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.MAP,
                metrics = buildMap {
                    if (analysis.map.available) put("map_kpa", analysis.map.median)
                    if (analysis.baro.available) put("baro_kpa", analysis.baro.median)
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
                "Requested boost is not a generic OBD-II PID; this test cannot prove underboost versus target.",
                "A stationary free rev may not produce enough exhaust flow to spool the turbocharger.",
                "MAP sensor bias, barometric assumptions and altitude affect the result.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val map = AnalysisSupport.statsOrNull(stage, Signals.MAP) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            map = map,
            baro = AnalysisSupport.statsOrNull(stage, Signals.BARO) ?: AnalysisSupport.stats(emptyList()),
            rpm = rpm,
            throttle = AnalysisSupport.statsOrNull(stage, Signals.THROTTLE) ?: AnalysisSupport.stats(emptyList()),
            load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList()),
            valid = map.count >= MIN_SAMPLES && rpm.count >= MIN_SAMPLES,
        )
    }
}
