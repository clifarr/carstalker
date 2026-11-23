package com.carstalker.core.guided

import com.carstalker.core.diagnose.CapturePoint

/**
 * Charging-system interpreter.
 *
 * Voltage is evaluated by condition, not against one universal "normal" number. A low idle
 * reading that recovers at higher engine speed is reported as reduced output at idle, with smart
 * charging and load management listed before an alternator verdict.
 */
object ChargingAnalyzer {

    private const val RESTING_LOW = 11.8
    private const val RESTING_HIGH = 13.2
    private const val RUNNING_LOW = 13.2
    private const val RUNNING_HIGH = 15.0
    private const val DIP = 12.0
    private const val MIN_SAMPLES = 5

    private data class VoltageStage(
        val stage: StageCapture,
        val voltage: SeriesStats,
        val rpm: SeriesStats,
        val adapterOnly: Boolean,
        val valid: Boolean,
        val notes: List<String>,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.CONTROL_MODULE_VOLTAGE, Signals.ADAPTER_VOLTAGE),
            minHz = 0.5,
        )
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it, definitionStage) }
        }
        val koeo = stages.firstOrNull { it.stage.stageId == "key_on_engine_off" }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "elevated_rpm" }
        val load = stages.firstOrNull { it.stage.stageId == "electrical_load" }
        val running = listOfNotNull(idle, elevated, load).filter { it.valid }
        val adapterOnly = stages.any { it.adapterOnly }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: VoltageStage?) {
            stage ?: return
            if (stage.voltage.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} voltage",
                    "${AnalysisSupport.format(stage.voltage.median, 2)} V " +
                        "(range ${AnalysisSupport.format(stage.voltage.min, 2)}–${AnalysisSupport.format(stage.voltage.max, 2)} V)",
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
        observe(elevated)
        observe(load)

        if (stages.isEmpty()) {
            return unable(input, quality, "No voltage stages were captured.")
        }
        if (adapterOnly) {
            warnings += "Only adapter voltage (`ATRV`) was available. It is supplementary and can read lower than the ECU's control-module voltage."
        }

        val koeoVoltage = koeo?.takeIf { it.valid }?.voltage
        val idleVoltage = idle?.takeIf { it.valid }?.voltage
        val elevatedVoltage = elevated?.takeIf { it.valid }?.voltage
        val loadVoltage = load?.takeIf { it.valid }?.voltage
        val bestRunning = elevatedVoltage ?: idleVoltage ?: loadVoltage

        if (koeoVoltage != null) {
            when {
                koeoVoltage.median < RESTING_LOW -> findings += GuidedFinding(
                    id = "resting_voltage_low",
                    title = "Resting voltage is low",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Voltage with the engine off was below the configured resting range. That can be a discharged battery, a connection problem, a parasitic load or a measurement offset; it does not identify which.",
                    supporting = listOf("Engine-off median ${AnalysisSupport.format(koeoVoltage.median, 2)} V"),
                    alternatives = listOf(
                        "Battery state of charge or age",
                        "Terminal/ground connection resistance",
                        "Parasitic draw",
                        "Recent electrical load or cold temperature",
                        "Measurement offset",
                    ),
                    nextCheck = "Measure at the battery posts with a known-good meter, then load-test the battery.",
                )
                koeoVoltage.median > RESTING_HIGH -> findings += GuidedFinding(
                    id = "resting_voltage_high",
                    title = "Resting voltage is unusually high",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "The engine-off voltage was above the usual resting range. Surface charge after charging, a charger still connected or a measurement offset can explain it.",
                    supporting = listOf("Engine-off median ${AnalysisSupport.format(koeoVoltage.median, 2)} V"),
                    alternatives = listOf("Surface charge", "Charger connected", "Measurement offset"),
                    nextCheck = "Switch off any charger, wait, and re-measure at the battery posts.",
                )
            }
        }

        val overcharge = running.filter { it.voltage.max > 15.5 || it.voltage.median > RUNNING_HIGH }
        if (overcharge.isNotEmpty()) {
            val worst = overcharge.maxByOrNull { it.voltage.median }!!
            findings += GuidedFinding(
                id = "overcharging",
                title = "Charging voltage is too high",
                evidenceLevel = EvidenceLevel.STRONG,
                confidence = ResultConfidence.HIGH,
                explanation = "Running voltage exceeded the safe range. Stop and have the charging system checked; overcharging can damage the battery and electronics.",
                supporting = listOf(
                    "${worst.stage.title} median ${AnalysisSupport.format(worst.voltage.median, 2)} V",
                    "Maximum ${AnalysisSupport.format(worst.voltage.max, 2)} V",
                ),
                alternatives = listOf("Voltage regulator/charging control", "Wiring or reference error", "Measurement offset"),
                nextCheck = "Stop the test and measure at the battery with a known-good meter. Do not continue driving with confirmed overcharging.",
            )
            warnings += "Running voltage exceeded 15 V. Stop and have the charging system assessed."
            nextSteps += "Stop and have the charging system checked before driving further."
        }

        if (idleVoltage != null && elevatedVoltage != null) {
            when {
                idleVoltage.median < RUNNING_LOW && elevatedVoltage.median >= RUNNING_LOW -> findings += GuidedFinding(
                    id = "reduced_output_at_idle",
                    title = "Reduced charging output at idle",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = if (adapterOnly) ResultConfidence.LOW else ResultConfidence.MEDIUM,
                    explanation = "Voltage was low at idle but recovered at a moderate engine speed. Smart charging, load management, a weak battery or a belt/connection issue can produce this. It is not enough to declare the alternator failed.",
                    supporting = listOf(
                        "Idle median ${AnalysisSupport.format(idleVoltage.median, 2)} V",
                        "Elevated-RPM median ${AnalysisSupport.format(elevatedVoltage.median, 2)} V",
                    ),
                    alternatives = listOf(
                        "Smart charging / start-stop load management",
                        "Battery state of charge or age",
                        "Belt slip or pulley ratio",
                        "Wiring/ground resistance",
                        "Alternator output at low RPM",
                    ),
                    nextCheck = "Check the battery state, belt and connections, then measure charging current at idle with a clamp meter or a shop tester.",
                )
                idleVoltage.median < RUNNING_LOW && elevatedVoltage.median < RUNNING_LOW -> findings += GuidedFinding(
                    id = "charging_underperformance",
                    title = "Charging voltage is low across conditions",
                    evidenceLevel = EvidenceLevel.MODERATE,
                    confidence = if (adapterOnly) ResultConfidence.LOW else ResultConfidence.MEDIUM,
                    explanation = "Voltage stayed below the configured charging range at idle and at higher engine speed. That supports a charging-system investigation, but smart charging, wiring, belt and battery state still have to be ruled out.",
                    supporting = listOf(
                        "Idle median ${AnalysisSupport.format(idleVoltage.median, 2)} V",
                        "Elevated-RPM median ${AnalysisSupport.format(elevatedVoltage.median, 2)} V",
                    ),
                    alternatives = listOf(
                        "Smart charging or load management",
                        "Belt/alternator drive",
                        "Wiring or ground resistance",
                        "Battery state of charge",
                        "Alternator/regulator fault",
                    ),
                    nextCheck = "Measure at the battery posts and alternator B+ with a known-good meter, and check the belt and grounds.",
                )
            }
        } else if (bestRunning != null && bestRunning.median < RUNNING_LOW) {
            findings += GuidedFinding(
                id = "charging_voltage_low",
                title = "Running voltage is below the charging range",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "A running voltage below the configured range supports checking the charging system, but only one condition was captured and smart charging may explain it.",
                supporting = listOf("Median ${AnalysisSupport.format(bestRunning.median, 2)} V"),
                alternatives = listOf("Smart charging", "Battery state", "Wiring/ground", "Alternator/regulator"),
                nextCheck = "Repeat with idle, elevated RPM and electrical load before drawing a conclusion.",
            )
            nextSteps += "Repeat the charging stages so idle, elevated RPM and load can be compared."
        }

        if (loadVoltage != null && loadVoltage.median < RUNNING_LOW) {
            findings += GuidedFinding(
                id = "load_voltage_low",
                title = "Voltage falls under electrical load",
                evidenceLevel = EvidenceLevel.MODERATE,
                confidence = if (adapterOnly) ResultConfidence.LOW else ResultConfidence.MEDIUM,
                explanation = "Voltage dropped below the charging range with normal electrical load applied. That can be a charging-system limitation, a battery that cannot buffer the load, or wiring resistance.",
                supporting = listOf("Loaded idle median ${AnalysisSupport.format(loadVoltage.median, 2)} V"),
                alternatives = listOf("Alternator output margin", "Battery condition", "Wiring/ground resistance", "Normal load management"),
                nextCheck = "Compare loaded voltage at the battery and at the alternator B+ terminal.",
            )
            nextSteps += "Compare loaded voltage at the battery and at the alternator output."
        }

        val dips = running.flatMap { it.stage.series(Signals.CONTROL_MODULE_VOLTAGE).ifEmpty { it.stage.series(Signals.ADAPTER_VOLTAGE) } }
            .filter { it.value < DIP }
        if (dips.isNotEmpty() && running.isNotEmpty()) {
            findings += GuidedFinding(
                id = "voltage_dips",
                title = "Voltage dips while running",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "Short dips below the configured threshold were captured. Intermittent loads, a loose connection or a failing battery/alternator can cause this; one capture cannot separate them.",
                supporting = listOf("${dips.size} sample(s) below ${AnalysisSupport.format(DIP, 1)} V"),
                alternatives = listOf("Intermittent load", "Loose terminal/ground", "Battery condition", "Alternator fault"),
                nextCheck = "Repeat with a longer passive recording and inspect terminals and grounds.",
            )
        }

        val runningValues = running.map { it.voltage.median }
        val normal = findings.none { it.evidenceLevel != EvidenceLevel.WEAK } &&
            (koeoVoltage == null || koeoVoltage.median in RESTING_LOW..RESTING_HIGH) &&
            runningValues.isNotEmpty() &&
            runningValues.all { it in RUNNING_LOW..RUNNING_HIGH }

        if (normal) {
            nextSteps += "No charging anomaly was detected in this capture. Re-test if the battery warning light returns."
        } else if (findings.isEmpty()) {
            nextSteps += "Not enough valid stages for a conclusion. Repeat with the engine off, idling and at a moderate engine speed."
        } else {
            nextSteps += "Check the battery, terminals, grounds and belt before replacing the alternator."
        }

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            !quality.requiredSignalsPresent || running.isEmpty() && koeoVoltage == null -> DiagnosticStatus.UNABLE_TO_RUN
            normal -> DiagnosticStatus.NO_ANOMALY_DETECTED
            findings.any { it.evidenceLevel == EvidenceLevel.STRONG || it.evidenceLevel == EvidenceLevel.MODERATE } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            else -> DiagnosticStatus.INCONCLUSIVE
        }

        val confidence = when {
            status == DiagnosticStatus.NO_ANOMALY_DETECTED && !adapterOnly && stages.count { it.valid } >= 3 -> ResultConfidence.HIGH
            status == DiagnosticStatus.NO_ANOMALY_DETECTED -> ResultConfidence.MEDIUM
            findings.isNotEmpty() && !adapterOnly -> ResultConfidence.MEDIUM
            findings.isNotEmpty() -> ResultConfidence.LOW
            else -> ResultConfidence.LOW
        }

        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason
                ?: "The test stopped for safety. Check the charging system before continuing."
            DiagnosticStatus.CANCELLED -> "The charging test was cancelled."
            DiagnosticStatus.UNABLE_TO_RUN -> "No usable voltage data was captured. Confirm the car reports control-module voltage or that adapter voltage is available."
            DiagnosticStatus.NO_ANOMALY_DETECTED -> if (koeoVoltage != null) {
                "Voltage stayed within the configured resting and running ranges. No charging anomaly was detected in this test."
            } else {
                "Running voltage stayed within the configured range. The engine-off stage was not captured, so the resting comparison is missing."
            }
            DiagnosticStatus.SUSPICIOUS -> findings.first().explanation
            else -> "The voltage data did not match a clear pattern. Repeat all conditions, including electrical load, before acting."
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
                AnalysisSupport.stageSummary(
                    stage = stage.stage,
                    valid = stage.valid,
                    keyPid = Signals.RPM,
                    metrics = buildMap {
                        if (stage.voltage.available) put("voltageV", stage.voltage.median)
                        if (stage.rpm.available) put("rpm", stage.rpm.median)
                    },
                    notes = stage.notes,
                )
            },
            quality = quality.copy(requiredSignalsPresent = quality.requiredSignalsPresent || stages.any { it.voltage.available }),
            limitations = (AnalysisSupport.baseLimitations(input) + buildList {
                add("Control-module voltage is not a battery-post or alternator B+ measurement.")
                add("Smart charging and start-stop systems may intentionally reduce alternator output.")
                if (adapterOnly) add("Adapter voltage was used; it can read lower than ECU voltage.")
            }).distinct(),
            warnings = warnings.distinct(),
            nextSteps = nextSteps.distinct(),
            generatedAt = input.generatedAt,
            profile = input.capabilities.profile,
            stagesSkipped = input.skippedStages,
        )
    }

    private fun analyzeStage(stage: StageCapture, definition: StageDefinition): VoltageStage {
        val voltagePoints: List<CapturePoint> = AnalysisSupport.voltageSeries(stage)
        val voltage = AnalysisSupport.stats(voltagePoints)
        val rpm = AnalysisSupport.stats(stage.series(Signals.RPM))
        val adapterOnly = AnalysisSupport.usesAdapterVoltage(stage)
        val notes = mutableListOf<String>()
        var valid = voltage.count >= MIN_SAMPLES
        if (!valid) notes += "Not enough voltage samples."
        val expectedRpm = definition.condition.rpmRange
        if (expectedRpm != null && rpm.available && rpm.median !in expectedRpm.first.toDouble()..expectedRpm.last.toDouble()) {
            valid = false
            notes += "Median RPM ${AnalysisSupport.format(rpm.median, 0)} was outside ${expectedRpm.first}–${expectedRpm.last}."
        }
        if (definition.condition.requireEngineOff && rpm.available && rpm.median > 100) {
            valid = false
            notes += "The engine was running during the engine-off stage."
        }
        if (stage.interruptions > 0) notes += "${stage.interruptions} interruption(s); mixed samples were discarded."
        return VoltageStage(stage, voltage, rpm, adapterOnly, valid, notes)
    }

    private fun unable(input: AnalysisInput, quality: CaptureQualitySummary, message: String): GuidedResult =
        GuidedResult(
            diagnosticId = input.definition.id,
            definitionVersion = input.definition.version,
            title = input.definition.title,
            status = if (input.safetyStopReason != null) DiagnosticStatus.SAFETY_STOPPED else DiagnosticStatus.UNABLE_TO_RUN,
            confidence = ResultConfidence.LOW,
            summary = input.safetyStopReason ?: message,
            quality = quality.copy(requiredSignalsPresent = false),
            limitations = AnalysisSupport.baseLimitations(input),
            warnings = AnalysisSupport.baseWarnings(input),
            nextSteps = listOf("Confirm voltage is reported, then repeat the engine-off and running stages."),
            generatedAt = input.generatedAt,
            profile = input.capabilities.profile,
            stagesSkipped = input.skippedStages,
        )
}
