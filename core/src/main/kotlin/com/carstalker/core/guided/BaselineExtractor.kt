package com.carstalker.core.guided

/** Builds the comparable metrics and conditions for a longitudinal health baseline. */
object BaselineExtractor {

    const val WARM_IDLE = "warm_idle"
    const val STEADY_RPM = "steady_rpm"

    fun fromCaptures(
        captures: List<StageCapture>,
        profile: VehicleProfile,
        vehicleKey: String,
        createdAt: Long,
        definitionVersion: String = "1.0.0",
    ): BaselineSnapshot? {
        val idle = captures.firstOrNull { it.stageId == WARM_IDLE } ?: return null
        val steady = captures.firstOrNull { it.stageId == STEADY_RPM }
        val metrics = mutableMapOf<String, Double>()

        fun addStage(stage: StageCapture, prefix: String) {
            AnalysisSupport.statsOrNull(stage, Signals.RPM)?.let { metrics["$prefix.rpm"] = it.median }
            AnalysisSupport.statsOrNull(stage, Signals.COOLANT)?.let { metrics["$prefix.coolant"] = it.median }
            AnalysisSupport.statsOrNull(stage, Signals.LOAD)?.let { metrics["$prefix.load"] = it.median }
            AnalysisSupport.statsOrNull(stage, Signals.MAF)?.let { metrics["$prefix.maf"] = it.median }
            AnalysisSupport.statsOrNull(stage, Signals.MAP)?.let { metrics["$prefix.map"] = it.median }
            AnalysisSupport.combinedTrim(stage, Signals.STFT_B1, Signals.LTFT_B1)?.let {
                metrics["$prefix.trim_b1"] = it.median
            }
            val voltage = AnalysisSupport.statsOrNull(stage, Signals.CONTROL_MODULE_VOLTAGE)
                ?: AnalysisSupport.statsOrNull(stage, Signals.ADAPTER_VOLTAGE)
            voltage?.let { metrics["$prefix.voltage"] = it.median }
            AnalysisSupport.statsOrNull(stage, Signals.FUEL_RATE)?.let { metrics["$prefix.fuel_rate"] = it.median }
        }

        addStage(idle, WARM_IDLE)
        steady?.let { addStage(it, STEADY_RPM) }
        if (metrics.isEmpty()) return null

        val conditions = buildMap {
            AnalysisSupport.statsOrNull(idle, Signals.RPM)?.let { put("rpm", it.median) }
            AnalysisSupport.statsOrNull(idle, Signals.COOLANT)?.let { put("coolant", it.median) }
            AnalysisSupport.statsOrNull(idle, Signals.LOAD)?.let { put("load", it.median) }
            AnalysisSupport.statsOrNull(idle, Signals.IAT)?.let { put("iat", it.median) }
            AnalysisSupport.statsOrNull(idle, Signals.AMBIENT)?.let { put("ambient", it.median) }
        }

        return BaselineSnapshot(
            vehicleKey = vehicleKey,
            profile = profile,
            createdAt = createdAt,
            definitionVersion = definitionVersion,
            conditions = conditions,
            metrics = metrics,
        )
    }
}
