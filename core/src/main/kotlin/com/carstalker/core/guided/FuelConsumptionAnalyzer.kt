package com.carstalker.core.guided

/**
 * Fuel-consumption context interpreter.
 *
 * Without a stored same-condition baseline the result is a documented snapshot, not a verdict.
 * Fuel rate is preferred; an airflow-based estimate is clearly labelled when PID 0x5E is absent.
 */
object FuelConsumptionAnalyzer {

    private const val PETROL_AFR = 14.7
    private const val DIESEL_AFR = 14.5
    private const val PETROL_DENSITY_KG_PER_L = 0.745
    private const val DIESEL_DENSITY_KG_PER_L = 0.835
    private const val TRIM_FLAG = 12.0
    private const val MIN_SAMPLES = 5

    private data class StageAnalysis(
        val stage: StageCapture,
        val rpm: SeriesStats,
        val load: SeriesStats,
        val fuelRate: SeriesStats,
        val maf: SeriesStats,
        val trim: SeriesStats?,
        val fuelLevel: SeriesStats,
        val diesel: Boolean,
        val estimated: Boolean,
        val valid: Boolean,
    ) {
        val litresPerHour: Double?
            get() = when {
                fuelRate.available -> fuelRate.median
                maf.available -> maf.median / (if (diesel) DIESEL_AFR else PETROL_AFR) *
                    3600.0 / 1000.0 /
                    (if (diesel) DIESEL_DENSITY_KG_PER_L else PETROL_DENSITY_KG_PER_L)
                else -> null
            }
    }

    fun analyze(input: AnalysisInput): GuidedResult {
        val quality = AnalysisSupport.quality(
            input,
            keyPids = listOf(Signals.RPM, Signals.LOAD),
            minHz = 0.5,
        )
        val diesel = input.capabilities.profile.diesel
        val stages = input.definition.stages.mapNotNull { definitionStage ->
            input.stage(definitionStage.id)?.let { analyzeStage(it, diesel) }
        }
        val idle = stages.firstOrNull { it.stage.stageId == "warm_idle" }
        val elevated = stages.firstOrNull { it.stage.stageId == "steady_rpm" }

        val observations = mutableListOf<GuidedObservation>()
        val findings = mutableListOf<GuidedFinding>()
        val warnings = AnalysisSupport.baseWarnings(input).toMutableList()
        val nextSteps = mutableListOf<String>()

        fun observe(stage: StageAnalysis?) {
            stage ?: return
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
            stage.litresPerHour?.let { lph ->
                observations += GuidedObservation(
                    "${stage.stage.title} fuel flow",
                    "${AnalysisSupport.format(lph, 2)} L/h" +
                        if (stage.estimated) " (estimated from MAF)" else " (PID 0x5E)",
                )
            }
            stage.trim?.let {
                observations += GuidedObservation(
                    "${stage.stage.title} combined trim B1",
                    "${AnalysisSupport.formatSigned(it.median)} %",
                )
            }
            if (stage.fuelLevel.available) {
                observations += GuidedObservation(
                    "${stage.stage.title} fuel level",
                    AnalysisSupport.valueWithUnit(stage.fuelLevel.median, "%", 0),
                )
            }
        }

        observe(idle)
        observe(elevated)

        if (stages.none { it.litresPerHour != null }) {
            return AnalysisSupport.unable(
                input,
                quality,
                "No fuel-rate or MAF signal was captured, so fuel flow could not be estimated.",
                nextSteps = listOf("Confirm PID 0x5E or 0x10 is supported, then repeat the capture."),
            )
        }

        val estimated = stages.any { it.estimated && it.litresPerHour != null }
        if (estimated) {
            warnings += "Fuel flow was estimated from MAF; it is an approximation, not a measured fuel rate."
        }
        if (stages.none { it.fuelRate.available }) {
            warnings += "The vehicle did not report engine fuel rate (PID 0x5E)."
        }

        val idleLph = idle?.litresPerHour
        val elevatedLph = elevated?.litresPerHour
        if (idleLph != null && elevatedLph != null && idle?.rpm?.median != null && elevated?.rpm?.median != null) {
            val idlePerRpm = idleLph / idle.rpm.median.coerceAtLeast(1.0)
            val elevatedPerRpm = elevatedLph / elevated.rpm.median.coerceAtLeast(1.0)
            observations += GuidedObservation(
                "Fuel flow per 1,000 rpm",
                "idle ${AnalysisSupport.format(idlePerRpm * 1000, 2)} L/h, " +
                    "elevated ${AnalysisSupport.format(elevatedPerRpm * 1000, 2)} L/h",
            )
            if (elevatedPerRpm > idlePerRpm * 1.5) {
                findings += GuidedFinding(
                    id = "fuel_flow_per_rpm_high",
                    title = "Fuel flow rises faster than engine speed",
                    evidenceLevel = EvidenceLevel.WEAK,
                    confidence = ResultConfidence.LOW,
                    explanation = "Fuel flow per RPM was materially higher in the elevated stage. Load, enrichment, purge and airflow differences can explain this; it is not proof of excessive consumption.",
                    supporting = listOf(
                        "Idle ${AnalysisSupport.format(idlePerRpm * 1000, 2)} L/h per 1,000 rpm",
                        "Elevated ${AnalysisSupport.format(elevatedPerRpm * 1000, 2)} L/h per 1,000 rpm",
                    ),
                    alternatives = listOf("Higher engine load", "Enrichment strategy", "EVAP purge", "MAF/fuel-rate estimate error"),
                    nextCheck = "Compare with a same-condition baseline and a measured fuel-flow reference.",
                )
            }
        }

        val trim = stages.mapNotNull { it.trim }.firstOrNull { kotlin.math.abs(it.median) > TRIM_FLAG }
        if (trim != null) {
            findings += GuidedFinding(
                id = "fuel_trim_context",
                title = "Fuel trim is outside the normal band",
                evidenceLevel = EvidenceLevel.WEAK,
                confidence = ResultConfidence.LOW,
                explanation = "A fuel-trim deviation can affect consumption, but it can also be a normal correction for conditions. It does not prove a consumption fault.",
                supporting = listOf("Combined trim median ${AnalysisSupport.formatSigned(trim.median)} %"),
                alternatives = listOf("Lean/rich mixture", "Airflow measurement", "Fuel delivery", "O2 sensor"),
                nextCheck = "Run the lean or rich mixture guide if a mixture code is present.",
            )
        }

        warnings += "There is no stored same-condition baseline in this build; the result is a snapshot, not a verdict."
        nextSteps += "Save this capture as a baseline and repeat under the same engine, RPM, load and ambient conditions before judging a change."

        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            else -> DiagnosticStatus.INCONCLUSIVE
        }
        val summary = when (status) {
            DiagnosticStatus.SAFETY_STOPPED -> input.safetyStopReason.orEmpty()
            DiagnosticStatus.CANCELLED -> "Test cancelled."
            else -> "A fuelling snapshot was captured. Without a same-condition baseline it cannot say whether consumption is abnormal."
        }

        val stageSummaries = stages.map { analysis ->
            AnalysisSupport.stageSummary(
                stage = analysis.stage,
                valid = analysis.valid,
                keyPid = Signals.RPM,
                metrics = buildMap {
                    analysis.litresPerHour?.let { put("fuel_lph", it) }
                    analysis.trim?.let { put("combined_trim_b1", it.median) }
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
                "Without a stored baseline this test can only document the current fuelling context.",
                "Fuel rate may be calculated by the ECU rather than measured directly.",
                "MAF-based estimates are approximations and are labelled as such.",
                "Short captures cannot represent long-term consumption.",
            ),
        )
    }

    private fun analyzeStage(stage: StageCapture, diesel: Boolean): StageAnalysis {
        val rpm = AnalysisSupport.statsOrNull(stage, Signals.RPM) ?: AnalysisSupport.stats(emptyList())
        val load = AnalysisSupport.statsOrNull(stage, Signals.LOAD) ?: AnalysisSupport.stats(emptyList())
        val fuelRate = AnalysisSupport.statsOrNull(stage, Signals.FUEL_RATE) ?: AnalysisSupport.stats(emptyList())
        val maf = AnalysisSupport.statsOrNull(stage, Signals.MAF) ?: AnalysisSupport.stats(emptyList())
        return StageAnalysis(
            stage = stage,
            rpm = rpm,
            load = load,
            fuelRate = fuelRate,
            maf = maf,
            trim = AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1),
            fuelLevel = AnalysisSupport.statsOrNull(stage, Signals.FUEL_LEVEL) ?: AnalysisSupport.stats(emptyList()),
            diesel = diesel,
            estimated = !fuelRate.available && maf.available,
            valid = (fuelRate.count >= MIN_SAMPLES || maf.count >= MIN_SAMPLES) && rpm.count >= MIN_SAMPLES,
        )
    }
}
