package com.carstalker.core.guided

/** Routes a captured session to its diagnostic-specific interpreter. */
object GuidedAnalysis {

    fun analyze(input: AnalysisInput): GuidedResult = when (input.definition.id) {
        DiagnosticRegistry.leanMixture.id -> LeanMixtureAnalyzer.analyze(input)
        DiagnosticRegistry.thermostat.id -> ThermostatAnalyzer.analyze(input)
        DiagnosticRegistry.charging.id -> ChargingAnalyzer.analyze(input)
        DiagnosticRegistry.mapPlausibility.id -> MapPlausibilityAnalyzer.analyze(input)
        DiagnosticRegistry.richMixture.id -> RichMixtureAnalyzer.analyze(input)
        DiagnosticRegistry.oxygenResponse.id -> OxygenResponseAnalyzer.analyze(input)
        DiagnosticRegistry.misfire.id -> MisfireAnalyzer.analyze(input)
        DiagnosticRegistry.mafPlausibility.id -> MafPlausibilityAnalyzer.analyze(input)
        DiagnosticRegistry.crankNoStart.id -> CrankNoStartAnalyzer.analyze(input)
        DiagnosticRegistry.boostControl.id -> BoostControlAnalyzer.analyze(input)
        DiagnosticRegistry.fuelPressure.id -> FuelPressureAnalyzer.analyze(input)
        DiagnosticRegistry.egr.id -> EgrAnalyzer.analyze(input)
        DiagnosticRegistry.throttlePedal.id -> ThrottlePedalAnalyzer.analyze(input)
        DiagnosticRegistry.evapPurge.id -> EvapPurgeAnalyzer.analyze(input)
        DiagnosticRegistry.dpfHealth.id -> DpfHealthAnalyzer.analyze(input)
        DiagnosticRegistry.fuelConsumption.id -> FuelConsumptionAnalyzer.analyze(input)
        DiagnosticRegistry.intermittentPowerLoss.id -> IntermittentPowerLossAnalyzer.analyze(input)
        DiagnosticRegistry.readiness.id -> ReadinessAnalyzer.analyze(input)
        DiagnosticRegistry.usedCarInspection.id -> UsedCarInspectionAnalyzer.analyze(input)
        DiagnosticRegistry.healthBaseline.id -> HealthBaselineAnalyzer.analyze(input)
        DiagnosticRegistry.catalyst.id -> unavailable(
            input,
            "The catalyst guide uses its dedicated Mode 06 and observation flow. Open it from the Diagnose tab.",
        )
        else -> unavailable(
            input,
            input.definition.catalogueNote ?: "This guide is defined but its analysis path is not implemented yet.",
        )
    }

    private fun unavailable(input: AnalysisInput, message: String): GuidedResult {
        val status = when {
            input.safetyStopReason != null -> DiagnosticStatus.SAFETY_STOPPED
            input.cancelled -> DiagnosticStatus.CANCELLED
            else -> DiagnosticStatus.UNABLE_TO_RUN
        }
        return GuidedResult(
            diagnosticId = input.definition.id,
            definitionVersion = input.definition.version,
            title = input.definition.title,
            status = status,
            confidence = ResultConfidence.LOW,
            summary = input.safetyStopReason ?: message,
            quality = CaptureQualitySummary(
                requiredSignalsPresent = false,
                samplingAdequate = false,
                notes = listOf(message),
            ),
            limitations = AnalysisSupport.baseLimitations(input),
            warnings = AnalysisSupport.baseWarnings(input),
            nextSteps = listOf("Use the implemented guide for this vehicle, or check back when this workflow ships."),
            generatedAt = input.generatedAt,
            profile = input.capabilities.profile,
            stagesSkipped = input.skippedStages,
        )
    }
}
