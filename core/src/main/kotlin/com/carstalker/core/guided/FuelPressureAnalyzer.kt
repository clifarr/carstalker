package com.carstalker.core.guided

/**
 * Measured fuel/rail pressure interpreter.
 *
 * Low-pressure fuel pressure, relative rail pressure and gauge rail pressure are different
 * circuits and reference frames. The analyzer uses one channel at a time and never compares
 * requested and actual values unless both are actually present.
 */
object FuelPressureAnalyzer {

    private const val PRESSURE_DROP_FRACTION = 0.20
    private const val PRESSURE_RISE_FRACTION = 0.25
    private const val UNSTABLE_FRACTION = 0.15
    private const val STATIC_RANGE_KPA = 1.0
    private const val MIN_SAMPLES = 5

    private data class Channel(
        val pid: Int,
        val label: String,
        val note: String,
    )

    private data class StageAnalysis(
        val stage: StageCapture,
        val channel: Channel?,
        val pressure: SeriesStats,
        val rpm: SeriesStats,
        val load: SeriesStats,
        val valid: Boolean,
    )

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.FUEL_PRESSURE),
            minHz = 0.5,
        )
        val channels = listOf(
            Channel(Signals.FUEL_PRESSURE, "Fuel pressure (low-pressure representation)", "PID 0x0A"),
            Channel(Signals.RAIL_PRESSURE, "Fuel rail pressure (relative)", "PID 0x22"),
            Channel(Signals.RAIL_GAUGE_PRESSURE, "Fuel rail gauge pressure", "PID 0x23"),
        )
        val available = channels.filter { channel ->
            input.stages.any { it.series(channel.pid).isNotEmpty() }
        }
        if (available.isEmpty()) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No fuel or rail pressure channel was captured. Generic OBD may not expose fuel pressure on this vehicle.",
                nextSteps = listOf("Check whether PID 0x0A, 0x22 or 0x23 is supported; use a mechanical gauge if not."),
            )
        }

        val channel = available.first()
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it, channel) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "steady_rpm" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        observations += GuidedObservation("Pressure channel", "${channel.label} (${channel.note})")
        if (available.size > 1) {
            warnings += "Multiple pressure channels were present; only ${channel.label} was used so units and reference frames are not mixed."
        }

        fun observe(stage: StageAnalysis?) {
            stage ?: return
            if (stage.pressure.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} pressure",
                    "${AnalysisSupport.format(stage.pressure.median, 0)} kPa " +
                        "(range ${AnalysisSupport.format(stage.pressure.min, 0)}–${AnalysisSupport.format(stage.pressure.max, 0)})",
                )
            }
            if (stage.rpm.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} RPM",
                    AnalysisSupport.valueWithUnit(stage.rpm.median, "rpm", 0),
                )
            }
            if (stage.load.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} load",
                    AnalysisSupport.valueWithUnit(stage.load.median, "%", 1),
                )
            }
        }

        observe(idle)
        observe(elevated)

        val idlePressure = idle?.pressure?.takeIf { it.count >= MIN_SAMPLES }
        val elevatedPressure = elevated?.pressure?.takeIf { it.count >= MIN_SAMPLES }
        val allPressure = listOfNotNull(idlePressure, elevatedPressure)
        if (allPressure.isEmpty()) {
            return AnalysisSupport.unable(
                input,
                quality,
                "Pressure samples were too sparse to assess stability.",
                nextSteps = listOf("Repeat the warm-idle stage and confirm the pressure PID is updating."),
            )
        }

        val static = allPressure.all { it.range < STATIC_RANGE_KPA }
        if (static) {
            findings += GuidedFinding(
                id = "pressure_static",
                title = "Pressure signal is nearly static",
                evidenceLevel = EvidenceLevel.SUPPORTIVE,
                confidence = ResultConfidence.LOW,
                explanation = "The pressure reading barely moved across idle and elevated RPM. A regulated system can be stable, but a default/frozen sensor value or a non-updating PID can look the same.",
                supporting = allPressure.map {
                    "${it.count} samples, range ${AnalysisSupport.format(it.range, 2)} kPa"
                },
                alternatives = listOf("Normal pressure regulation", "Sensor default/frozen value", "PID not updating", "Wiring/scaling issue"),
                nextCheck = "Compare with a mechanical gauge or a second scan tool and confirm the PID changes with key-on/engine-off.",
            )
        }

        if (idlePressure != null && elevatedPressure != null) {
            val change = elevatedPressure.median - idlePressure.median
            val fraction = if (idlePressure.median != 0.0) change / idlePressure.median else 0.0
            when {
                fraction < -PRESSURE_DROP_FRACTION -> {
                    findings += GuidedFinding(
                        id = "pressure_drops_with_demand",
                        title = "Pressure falls as demand rises",
                        evidenceLevel = EvidenceLevel.MODERATE,
                        confidence = ResultConfidence.MEDIUM,
                        explanation = "Measured pressure dropped materially between idle and a higher-demand stage. That supports investigating supply volume, a restriction, a pump or a regulator, but it does not prove which part failed.",
                        supporting = listOf(
                            "Idle median ${AnalysisSupport.format(idlePressure.median, 0)} kPa",
                            "Elevated median ${AnalysisSupport.format(elevatedPressure.median, 0)} kPa",
                            "Change ${AnalysisSupport.format(fraction * 100, 0)} %",
                        ),
                        alternatives = listOf(
                            "Fuel pump volume/condition",
                            "Filter or line restriction",
                            "Pressure regulator/control valve",
                            "Sensor or wiring",
                            "Low-pressure vs high-pressure circuit mismatch",
                        ),
                        nextCheck = "Measure pressure and volume under load with the correct gauge for the circuit, and check the filter and supply.",
                    )
                    nextSteps += "Measure fuel pressure/volume under load with the correct gauge and inspect the filter/supply."
                }
                fraction > PRESSURE_RISE_FRACTION -> {
                    findings += GuidedFinding(
                        id = "pressure_rises_with_demand",
                        title = "Pressure rises materially with demand",
                        evidenceLevel = EvidenceLevel.SUPPORTIVE,
                        confidence = ResultConfidence.LOW,
                        explanation = "Measured pressure rose between idle and the higher-demand stage. Some systems intentionally raise pressure, but a control/regulator or sensor issue can also do this.",
                        supporting = listOf(
                            "Idle median ${AnalysisSupport.format(idlePressure.median, 0)} kPa",
                            "Elevated median ${AnalysisSupport.format(elevatedPressure.median, 0)} kPa",
                        ),
                        alternatives = listOf("Normal demand-controlled pressure", "Regulator/control valve", "Sensor scaling", "Different circuit reference"),
                        nextCheck = "Compare the reading with the manufacturer's requested-pressure strategy or a mechanical gauge.",
                    )
                }
            }

            val unstable = listOfNotNull(idlePressure, elevatedPressure).filter {
                it.median > 0 && it.range / it.median > UNSTABLE_FRACTION
            }
            if (unstable.isNotEmpty()) {
                findings += GuidedFinding(
                    id = "pressure_unstable",
                    title = "Pressure reading is unstable",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.LOW,
                    explanation = "Pressure varied substantially within a stage. Pulsation, a control valve, a sensor or a sampling artefact can cause this; it is not automatically a failing pump.",
                    supporting = unstable.map {
                        "Range ${AnalysisSupport.format(it.range, 0)} kPa around a median of ${AnalysisSupport.format(it.median, 0)} kPa"
                    },
                    alternatives = listOf("Normal pulsation", "Control valve", "Sensor/wiring", "Sampling artefact"),
                    nextCheck = "Repeat with a faster sample rate and compare with a mechanical gauge.",
                )
            }
        } else {
            warnings += "Both a warm-idle and a steady-RPM stage are needed to compare pressure under demand."
        }

        val codeContext = input.activeCodes.map { it.uppercase() }
        if (codeContext.any { it.startsWith("P0087") } && findings.any { it.id == "pressure_drops_with_demand" }) {
            warnings += "P0087 (low fuel pressure) is consistent with the measured drop, but the cause still needs physical confirmation."
        }
        if (codeContext.any { it.startsWith("P0088") }) {
            warnings += "P0088 (high fuel pressure) is present; interpret absolute pressure only with the manufacturer's specification."
        }
        if (codeContext.any { it.startsWith("P0191") } && findings.any { it.id == "pressure_unstable" }) {
            warnings += "P0191 (pressure range/performance) is consistent with the unstable reading."
        }

        warnings += "Requested-versus-actual pressure is only available when the vehicle exposes both values in the same circuit; this build does not assume a requested-pressure PID."

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            findings.any { it.id == "pressure_drops_with_demand" } -> DiagnosticStatus.SUSPICIOUS
            findings.isNotEmpty() -> DiagnosticStatus.INCONCLUSIVE
            idlePressure != null && elevatedPressure != null -> DiagnosticStatus.NO_ANOMALY_DETECTED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.NO_ANOMALY_DETECTED -> "Measured pressure was stable between the captured stages. This does not prove fuel volume under real load."
            DiagnosticStatus.SUSPICIOUS -> "Measured pressure behaviour supports a fuel-supply investigation; it does not identify the failed part."
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "The pressure capture was not conclusive. A mechanical gauge or a requested-pressure channel may be needed."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    if (analysis.pressure.available) put("pressure_kpa", analysis.pressure.median)
                },
            )
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
            nextSteps = nextSteps,
            limitations = listOf(
                "Generic OBD may expose low-pressure fuel pressure, relative rail pressure or gauge rail pressure, not all three.",
                "Without a requested-pressure PID the test can only check stability and response.",
                "Sensor location, units and reference frames differ between low- and high-pressure circuits.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture, channel: Channel): StageAnalysis {
        val pressure = AnalysisSupport.statsOrNull(stage, channel.pid) ?: AnalysisSupport.stats(emptyList())
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            channel = channel,
            pressure = pressure,
            rpm = rpm,
            load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList()),
            valid = pressure.count >= MIN_SAMPLES && rpm.count >= MIN_SAMPLES,
        )
    }
}
