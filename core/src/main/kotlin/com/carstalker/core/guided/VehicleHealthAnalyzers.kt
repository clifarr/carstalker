package com.carstalker.core.guided

import kotlin.math.abs

/**
 * Passive readiness and used-car summaries.
 *
 * These guides use the last DTC/monitor scan plus a short key-on capture. They deliberately avoid
 * claiming that incomplete readiness proves tampering or that a clean scan guarantees compliance.
 */
object ReadinessAnalyzer {

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM),
            minHz = 0.2,
        )
        val monitor = input.scan.monitor
        if (monitor == null) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No readiness monitor data is available. Read codes first, then repeat this check.",
                nextSteps = listOf("Read codes so the ECU's readiness monitor states are available."),
            )
        }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        observations += GuidedObservation("MIL", if (monitor.milOn) "On" else "Off")
        val supported = monitor.monitors.filter { it.supported }
        val incomplete = supported.filterNot { it.complete }
        supported.forEach { monitorState ->
            observations += GuidedObservation(
                "Monitor: ${monitorState.name}",
                if (monitorState.complete) "Complete" else "Incomplete",
            )
        }
        if (supported.isEmpty()) {
            observations += GuidedObservation("Supported monitors", "None reported")
        }

        val stage = input.stages.firstOrNull { it.stageId == "key_on_engine_off" }
        fun counter(pid: Int, label: String, unit: String) {
            val stats = AnalysisSupport.statsOrNull(stage, pid) ?: return
            observations += GuidedObservation(label, AnalysisSupport.valueWithUnit(stats.median, unit, 0))
        }
        counter(Signals.WARMUPS_SINCE_CLEARED, "Warm-ups since codes cleared", "")
        counter(Signals.DISTANCE_SINCE_CLEARED, "Distance since codes cleared", "km")
        counter(Signals.ENGINE_RUN_TIME_MIL, "Time with MIL on", "min")
        counter(Signals.TIME_SINCE_CLEARED, "Time since codes cleared", "min")

        val allCodes = input.scan.allCodes
        if (allCodes.isNotEmpty()) {
            observations += GuidedObservation("Codes in the last scan", allCodes.joinToString())
        }

        if (monitor.milOn) {
            findings += GuidedFinding(
                id = "mil_on",
                title = "The MIL is on",
                evidenceLevel = EvidenceLevel.STRONG,
                confidence = ResultConfidence.HIGH,
                explanation = "The ECU reports an illuminated malfunction indicator lamp. Read and diagnose the stored codes before presenting the vehicle for an emissions inspection.",
                supporting = listOf("MIL on"),
                alternatives = listOf("Stored/pending fault", "Permanent code awaiting self-clear"),
                nextCheck = "Read the codes, diagnose the fault and complete the relevant drive cycle before retesting.",
            )
            nextSteps += "Diagnose the stored codes before an emissions inspection."
        }
        if (incomplete.isNotEmpty()) {
            findings += GuidedFinding(
                id = "readiness_incomplete",
                title = "Some readiness monitors are incomplete",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "Incomplete monitors are normal after a battery disconnect, code clear or recent repair. They are not proof that codes were deliberately cleared, and they can cause an emissions inspection to be rejected.",
                supporting = incomplete.map { it.name },
                alternatives = listOf("Recent battery disconnect", "Recent code clear", "Drive cycle not completed", "Monitor not supported"),
                nextCheck = "Complete the manufacturer's general drive cycle and re-check readiness.",
            )
            nextSteps += "Complete a general drive cycle and re-check readiness."
        }
        if (input.scan.permanentCodes.isNotEmpty()) {
            findings += GuidedFinding(
                id = "permanent_codes",
                title = "Permanent codes are present",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "Permanent codes remain until the ECU's own self-test clears them. Clearing codes manually does not remove them.",
                supporting = listOf(input.scan.permanentCodes.joinToString()),
                alternatives = listOf("Unrepaired fault", "Repair not yet verified by the ECU"),
                nextCheck = "Repair the fault and complete the relevant drive cycle; do not rely on clearing codes.",
            )
        }
        if (input.scan.storedCodes.isNotEmpty() || input.scan.pendingCodes.isNotEmpty()) {
            findings += GuidedFinding(
                id = "stored_or_pending_codes",
                title = "Stored or pending codes are present",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "Stored or pending codes mean the ECU has seen a fault. They should be diagnosed before an emissions inspection or purchase decision.",
                supporting = listOf((input.scan.storedCodes + input.scan.pendingCodes).distinct().joinToString()),
                alternatives = listOf("Intermittent fault", "Repair not yet verified", "Pending monitor failure"),
                nextCheck = "Diagnose the reported codes and verify the repair with a drive cycle.",
            )
        }
        if (input.scan.mode06CountsAvailable && input.scan.mode06Failures > 0) {
            findings += GuidedFinding(
                id = "mode06_failures",
                title = "Mode 06 reports failing monitor tests",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "The ECU's own monitor results include failing tests. These are useful evidence, but their identifiers and limits must be interpreted for this vehicle.",
                supporting = listOf("${input.scan.mode06Failures} failing test(s)"),
                alternatives = listOf("Genuine monitor failure", "Unverified identifier/scaling", "Monitor not ready"),
                nextCheck = "Review the Mode 06 results with service information for this vehicle.",
            )
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            monitor.milOn || input.scan.storedCodes.isNotEmpty() || input.scan.permanentCodes.isNotEmpty() -> DiagnosticStatus.SUSPICIOUS
            incomplete.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.NO_ANOMALY_DETECTED
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "Supported readiness monitors are complete and no codes were reported in the last scan. This is not a compliance guarantee."
            DiagnosticStatus.SUSPICIOUS -> "The scan shows a condition that should be diagnosed before an emissions inspection or purchase decision."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "Readiness is incomplete. That can be normal after a disconnect or repair, but the monitors need to run."
        }

        return AnalysisSupport.buildResult(
            input = input,
            status = status,
            confidence = when {
                status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.HIGH
                status == DiagnosticStatus.SUSPICIOUS -> ResultConfidence.MEDIUM
                else -> ResultConfidence.LOW
            },
            summary = summary,
            observations = observations,
            findings = findings,
            stages = stage?.let {
                listOf(AnalysisSupport.stageSummary(it, valid = it.series(Signals.RPM).isNotEmpty(), keyPid = Signals.RPM))
            }.orEmpty(),
            quality = quality,
            warnings = warnings,
            nextSteps = nextSteps,
            limitations = listOf(
                "Readiness monitor names and support vary by vehicle and protocol.",
                "The app cannot prove why a monitor is incomplete.",
                "Permanent codes may remain after a repair until the ECU clears them.",
                "A clean scan does not replace a state emissions inspection.",
            ),
        )
    }
}

object UsedCarInspectionAnalyzer {

    private const val TRIM_FLAG = 15.0
    private const val VOLTAGE_LOW = 12.0

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM),
            minHz = 0.2,
        )
        val monitor = input.scan.monitor
        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        observations += GuidedObservation(
            "Last scan",
            if (input.scan.hasScan) {
                val mode06 = if (input.scan.mode06CountsAvailable) {
                    "${input.scan.mode06Failures} Mode 06 failure(s)"
                } else {
                    "Mode 06 not checked"
                }
                "${input.scan.allCodes.size} code(s), $mode06"
            } else {
                "No scan data available"
            },
        )
        if (monitor != null) {
            val supported = monitor.monitors.filter { it.supported }
            val incomplete = supported.filterNot { it.complete }
            observations += GuidedObservation("MIL", if (monitor.milOn) "On" else "Off")
            observations += GuidedObservation(
                "Readiness",
                if (incomplete.isEmpty()) "All supported monitors complete" else "${incomplete.size} incomplete: ${incomplete.joinToString { it.name }}",
            )
        } else {
            warnings += "No readiness monitor data was available; read codes before relying on this summary."
        }
        if (input.scan.allCodes.isNotEmpty()) {
            observations += GuidedObservation("Codes", input.scan.allCodes.joinToString())
        }
        if (!input.scan.mode06CountsAvailable) {
            warnings += "Mode 06 self-tests were not read for this scan. They are not the same as zero failures."
            nextSteps += "Open Codes and read Mode 06 self-tests before relying on a clean summary."
        }

        val keyOn = input.stages.firstOrNull { it.stageId == "key_on_engine_off" }
        val idle = input.stages.firstOrNull { it.stageId == "warm_idle" }
        AnalysisSupport.statsOrNull(keyOn, Signals.WARMUPS_SINCE_CLEARED)?.let {
            observations += GuidedObservation("Warm-ups since cleared", AnalysisSupport.format(it.median, 0))
        }
        AnalysisSupport.statsOrNull(keyOn, Signals.DISTANCE_SINCE_CLEARED)?.let {
            observations += GuidedObservation("Distance since cleared", AnalysisSupport.valueWithUnit(it.median, "km", 0))
        }
        AnalysisSupport.statsOrNull(keyOn, Signals.TIME_SINCE_CLEARED)?.let {
            observations += GuidedObservation("Time since cleared", AnalysisSupport.valueWithUnit(it.median, "min", 0))
        }
        val voltage = AnalysisSupport.statsOrNull(idle, Signals.CONTROL_MODULE_VOLTAGE)
            ?: AnalysisSupport.statsOrNull(keyOn, Signals.CONTROL_MODULE_VOLTAGE)
        voltage?.let {
            observations += GuidedObservation("Voltage", AnalysisSupport.valueWithUnit(it.median, "V", 2))
        }
        val coolant = AnalysisSupport.statsOrNull(idle, Signals.COOLANT)
        coolant?.let {
            observations += GuidedObservation("Idle coolant", AnalysisSupport.valueWithUnit(it.median, "°C", 1))
        }
        val trimStage = idle ?: keyOn
        val trim = trimStage?.let { AnalysisSupport.combinedTrim(it, Signals.STFT_B1, Signals.LTFT_B1) }
        trim?.let {
            observations += GuidedObservation("Combined trim B1", "${AnalysisSupport.formatSigned(it.median)} %")
        }

        if (input.scan.allCodes.isNotEmpty()) {
            findings += GuidedFinding(
                id = "codes_present",
                title = "Codes were reported in the last scan",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = ResultConfidence.MEDIUM,
                explanation = "Codes are a starting point for negotiation and diagnosis. They do not prove the severity of the fault, and a recently cleared ECU can hide history.",
                supporting = listOf(input.scan.allCodes.joinToString()),
                alternatives = listOf("Intermittent fault", "Repair not verified", "Recently cleared history"),
                nextCheck = "Ask for the full code history and have the vehicle inspected by a qualified mechanic.",
            )
            nextSteps += "Have the vehicle inspected by a qualified mechanic before purchase."
        }
        if (monitor?.milOn == true) {
            findings += GuidedFinding(
                id = "mil_on",
                title = "The MIL is on",
                evidenceLevel = EvidenceLevel.STRONG,
                confidence = ResultConfidence.HIGH,
                explanation = "An illuminated MIL means the ECU has an active fault. Treat it as a reason for a full diagnosis, not a cosmetic issue.",
                supporting = listOf("MIL on"),
                alternatives = listOf("Active fault", "Permanent code awaiting self-clear"),
                nextCheck = "Diagnose the fault before purchase and confirm the repair with a drive cycle.",
            )
        }
        if (monitor != null && monitor.monitors.any { it.supported && !it.complete }) {
            findings += GuidedFinding(
                id = "readiness_incomplete",
                title = "Readiness is incomplete",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "Incomplete monitors can be normal after a battery disconnect, but they can also hide a recent code clear. They are not proof of tampering.",
                supporting = monitor.monitors.filter { it.supported && !it.complete }.map { it.name },
                alternatives = listOf("Recent battery disconnect", "Recent repair", "Drive cycle not completed", "Recent code clear"),
                nextCheck = "Ask why readiness is incomplete and complete a drive cycle before an emissions inspection.",
            )
        }
        if (voltage != null && voltage.median < VOLTAGE_LOW) {
            findings += GuidedFinding(
                id = "voltage_low",
                title = "Voltage was low during the idle capture",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "Low voltage can indicate a battery or charging issue. It is a context finding, not a diagnosis.",
                supporting = listOf("Median voltage ${AnalysisSupport.format(voltage.median, 2)} V"),
                alternatives = listOf("Battery state", "Charging system", "Connection resistance", "Measurement offset"),
                nextCheck = "Have the battery and charging system tested.",
            )
        }
        if (trim != null && abs(trim.median) > TRIM_FLAG) {
            findings += GuidedFinding(
                id = "trim_context",
                title = "Fuel trim is outside the normal band",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "A fuel-trim deviation can indicate a mixture or airflow issue, but it can also be a normal correction for conditions.",
                supporting = listOf("Combined trim median ${AnalysisSupport.formatSigned(trim.median)} %"),
                alternatives = listOf("Vacuum/intake leak", "Fuel delivery", "Airflow measurement", "O2 sensor"),
                nextCheck = "Run the lean or rich mixture guide if a mixture code is present.",
            )
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "mil_on" || it.id == "codes_present" } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            !input.scan.hasScan -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.NO_ANOMALY_DETECTED
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "The passive summary found no codes or readiness issues. A mechanical inspection is still required."
            DiagnosticStatus.SUSPICIOUS -> "The summary found a condition that should be diagnosed before purchase."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The passive summary is incomplete; read codes and complete the readiness monitors."
        }

        val stageSummaries = listOfNotNull(keyOn, idle).map {
            AnalysisSupport.stageSummary(it, valid = it.series(Signals.RPM).isNotEmpty(), keyPid = Signals.RPM)
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
            nextSteps = nextSteps + "Arrange a mechanical inspection before purchase.",
            limitations = listOf(
                "This is a data summary, not a full pre-purchase inspection.",
                "The app cannot verify mileage, service history, body condition or safety systems.",
                "Readiness and counter data depend on what the ECU exposes.",
            ),
        )
    }
}
