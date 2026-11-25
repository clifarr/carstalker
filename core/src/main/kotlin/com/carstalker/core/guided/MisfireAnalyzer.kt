package com.carstalker.core.guided

import kotlin.math.abs

/**
 * Misfire context interpreter.
 *
 * Generic OBD does not guarantee a verified cylinder-specific misfire monitor, so this analyzer
 * uses the codes the ECU actually reported plus RPM stability, load and trims. It never turns an
 * arbitrary Mode 06 number into a cylinder count.
 */
object MisfireAnalyzer {

    private const val RPM_INSTABILITY_STDDEV = 45.0
    private const val RPM_INSTABILITY_RANGE = 250.0
    private const val TRIM_FLAG = 12.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val rpm: SeriesStats,
        val load: SeriesStats,
        val coolant: SeriesStats,
        val voltage: SeriesStats,
        val combinedTrim: SeriesStats?,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.LOAD),
            minHz = 0.8,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "steady_rpm" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        val misfireCodes = input.activeCodes.map { it.trim().uppercase() }.filter { it.startsWith("P030") }
        val cylinderCodes = misfireCodes.filter { it.length >= 5 && it[4] in '1'..'8' }
        val randomCode = misfireCodes.any { it == "P0300" }

        if (misfireCodes.isNotEmpty()) {
            observations += GuidedObservation("Misfire codes reported", misfireCodes.joinToString())
        } else {
            observations += GuidedObservation("Misfire codes reported", "None in the last scan")
        }
        if (input.scan.mode06CountsAvailable) {
            input.scan.mode06Failures.takeIf { it > 0 }?.let {
                observations += GuidedObservation("Mode 06 failing tests", "$it reported by the ECU")
                warnings += "Mode 06 failures were present, but cylinder-specific misfire identifiers are not assumed by this test."
            }
        } else {
            observations += GuidedObservation("Mode 06 failing tests", "Not checked")
            warnings += "Mode 06 was not read. That is not the same as zero failures."
        }

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    "${AnalysisSupport.format(stage.rpm.median, 0)} rpm " +
                        "(range ${AnalysisSupport.format(stage.rpm.min, 0)}–${AnalysisSupport.format(stage.rpm.max, 0)}, σ ${AnalysisSupport.format(stage.rpm.stdDev, 0)})",
                )
            }
            if (stage.load.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} load",
                    AnalysisSupport.valueWithUnit(stage.load.median, "%", 1),
                )
            }
            stage.combinedTrim?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(it.median)} %",
                )
            }
            if (stage.voltage.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} voltage",
                    AnalysisSupport.valueWithUnit(stage.voltage.median, "V", 2),
                )
            }
        }

        observe(idle)
        observe(elevated)

        if (stages.isEmpty() || stages.all { !it.rpm.available }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No RPM data was captured. The misfire context test needs a live engine-speed signal.",
                nextSteps = listOf("Confirm PID 0x0C is supported, then repeat the warm-idle stage."),
            )
        }

        if (cylinderCodes.isNotEmpty()) {
            val cylinders = cylinderCodes.mapNotNull { it.lastOrNull()?.digitToIntOrNull() }.distinct().sorted()
            findings += GuidedFinding(
                id = "cylinder_codes",
                title = "Cylinder-specific misfire code(s) reported",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The ECU flagged a specific cylinder. That is useful targeting evidence, but it does not prove whether the cause is ignition, fuel, air, compression or wiring.",
                supporting = listOf("Codes: ${cylinderCodes.joinToString()}"),
                alternatives = listOf(
                    "Spark plug or ignition coil",
                    "Injector or fuel delivery",
                    "Intake/vacuum leak affecting that cylinder",
                    "Compression or valve problem",
                    "Wiring or ECU driver",
                ),
                nextCheck = "With the engine off, inspect the plug and, if compatible, swap the coil/plug with a known-good cylinder, then repeat the capture.",
            )
            nextSteps += "With the engine off, inspect and, if compatible, swap ignition components between cylinders, then repeat."
            if (cylinders.isNotEmpty()) {
                observations += GuidedObservation("Cylinders flagged", cylinders.joinToString { "Cylinder $it" })
            }
        }

        if (randomCode) {
            findings += GuidedFinding(
                id = "random_misfire",
                title = "Random/multiple misfire reported",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "P0300 means the ECU did not attribute the misfire to one cylinder. Ignition, fuel, air, EGR, mechanical and wiring causes all remain possible.",
                supporting = listOf("P0300 reported"),
                alternatives = listOf(
                    "Worn plugs/coils across cylinders",
                    "Fuel pressure or injector issue",
                    "Intake/vacuum leak",
                    "EGR or PCV problem",
                    "Mechanical condition",
                ),
                nextCheck = "Check service history and ignition components first, then verify fuel pressure and intake integrity.",
            )
            nextSteps += "Check ignition service history, fuel pressure and intake integrity for a random misfire."
        }

        val unstable = stages.filter { stage ->
            stage.rpm.available && (
                stage.rpm.stdDev > RPM_INSTABILITY_STDDEV ||
                    stage.rpm.range > RPM_INSTABILITY_RANGE
                )
        }
        if (unstable.isNotEmpty()) {
            val worst = unstable.maxByOrNull { it.rpm.stdDev }!!
            findings += GuidedFinding(
                id = "rpm_instability",
                title = "Engine speed was not stable during the capture",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "RPM variation exceeded the configured stability guide. A misfire can cause this, but mounts, idle control, accessory load and the capture conditions can too.",
                supporting = listOf(
                    "${worst.stage.title}: σ ${AnalysisSupport.format(worst.rpm.stdDev, 0)} rpm, range ${AnalysisSupport.format(worst.rpm.range, 0)} rpm",
                ),
                alternatives = listOf("Misfire", "Idle-control/adaptation", "Engine mount", "Accessory load", "Sensor noise"),
                nextCheck = "Repeat with a stable idle and compare RPM/trim behaviour; a cylinder balance or compression test may be needed.",
            )
        }

        val abnormalTrim = stages.mapNotNull { it.combinedTrim }.firstOrNull { abs(it.median) > TRIM_FLAG }
        if (abnormalTrim != null) {
            findings += GuidedFinding(
                id = "trim_context",
                title = "Fuel trim is outside the normal band",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "A fuel-trim deviation can accompany a misfire and can also cause one. It does not identify the failed component.",
                supporting = listOf("Combined trim median ${AnalysisSupport.formatSigned(abnormalTrim.median)} %"),
                alternatives = listOf("Vacuum/intake leak", "Fuel delivery", "Airflow measurement", "O2 sensor"),
                nextCheck = "Run the lean or rich mixture guide to separate the fuel-control pattern before replacing ignition parts.",
            )
        }

        val lowVoltage = stages.mapNotNull { it.voltage }.firstOrNull { it.median < 12.0 }
        if (lowVoltage != null) {
            findings += GuidedFinding(
                id = "low_voltage_context",
                title = "Voltage was low during the capture",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "Low voltage can cause or worsen misfires, especially at idle. It is a context finding, not a diagnosis.",
                supporting = listOf("Median voltage ${AnalysisSupport.format(lowVoltage.median, 2)} V"),
                alternatives = listOf("Battery state", "Charging system", "Connection resistance", "Measurement offset"),
                nextCheck = "Run the charging and electrical health guide before condemning ignition components.",
            )
        }

        val stable = idle?.rpm?.let { it.stdDev <= RPM_INSTABILITY_STDDEV && it.range <= RPM_INSTABILITY_RANGE } == true
        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.prerequisites.activeMisfire -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            misfireCodes.isNotEmpty() || findings.any { it.id == "rpm_instability" } -> DiagnosticStatus.SUSPICIOUS
            stable && misfireCodes.isEmpty() -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason
                ?: "An active misfire is a stop condition. Do not continue load testing."
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "No misfire code was reported and the captured idle was stable. An intermittent misfire can still be stored."
            DiagnosticStatus.SUSPICIOUS -> "Misfire-related evidence was found. The result targets the next check without proving a component."
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The capture did not contain enough stable data for a misfire conclusion."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    put("rpm_stddev", analysis.rpm.stdDev)
                    analysis.combinedTrim?.let { put("combined_trim_b1", it.median) }
                },
            )
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                misfireCodes.isNotEmpty() -> ResultConfidence.MEDIUM
                stable -> ResultConfidence.MEDIUM
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
                "Cylinder-specific misfire counts are only shown when their Mode 06 identifiers are verified; this build does not assume them.",
                "A misfire that is not present during the capture can still be stored by the ECU.",
                "Compression, valve, injector and ignition causes cannot be separated from live data alone.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture): StageAnalysis {
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        val load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList())
        val coolant = AnalysisSupport.statsOrNull(stage, Signals.COOLANT) ?: AnalysisSupport.stats(emptyList())
        val voltage = AnalysisSupport.statsOrNull(stage, Signals.CONTROL_MODULE_VOLTAGE)
            ?: AnalysisSupport.statsOrNull(stage, Signals.ADAPTER_VOLTAGE)
            ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            rpm = rpm,
            load = load,
            coolant = coolant,
            voltage = voltage,
            combinedTrim = AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1),
            valid = rpm.count >= MIN_SAMPLES,
        )
    }
}
