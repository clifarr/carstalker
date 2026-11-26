package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedLaterPhasesTest {

    private fun stage(
        id: String,
        kind: StageKind,
        durationMs: Long = 20_000,
        hz: Double = 2.0,
        markers: List<Long> = emptyList(),
        values: Map<Int, (Double) -> Double>,
    ): StageCapture {
        val start = 1_000L
        val samples = (durationMs / 1000.0 * hz).toInt().coerceAtLeast(1)
        val points = mutableListOf<CapturePoint>()
        for (index in 0 until samples) {
            val timestamp = start + (index * 1000.0 / hz).toLong()
            val seconds = (timestamp - start) / 1000.0
            for ((pid, generator) in values) {
                points += CapturePoint(pid, timestamp, generator(seconds))
            }
        }
        return StageCapture(
            stageId = id,
            title = id.replace('_', ' '),
            kind = kind,
            capture = CaptureData(
                points = points,
                requestedPids = values.keys.toList(),
                startedAt = start,
                endedAt = start + durationMs,
                markers = markers,
            ),
        )
    }

    private val allPids = setOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.MAF,
        Signals.MAP,
        Signals.LOAD,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.DPF_PRESSURE_B1,
        Signals.DPF_PRESSURE_B2,
        Signals.DPF_TEMPERATURE,
    )

    private fun input(
        definition: DiagnosticDefinition,
        stages: List<StageCapture>,
        profile: VehicleProfile = VehicleProfile(FuelType.DIESEL, Aspiration.FORCED_INDUCTION),
        codes: List<String> = emptyList(),
        baseline: BaselineSnapshot? = null,
        vehicleKey: String = "",
        capabilities: VehicleCapabilities = VehicleCapabilities(
            supportedPids = allPids,
            profile = profile,
            adapterVoltageAvailable = true,
        ),
    ): AnalysisInput = AnalysisInput(
        definition = definition,
        capabilities = capabilities,
        prerequisites = PrerequisiteAnswers(engineWarmConfirmed = true, safeLocationConfirmed = true),
        stages = stages,
        activeCodes = codes,
        baseline = baseline,
        vehicleKey = vehicleKey,
        generatedAt = 1_700_000_000_000,
    )

    private fun dpfStage(
        id: String,
        kind: StageKind,
        rpm: Double,
        pressure: Double,
        temperature: Double,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.DPF_PRESSURE_B1 to { _: Double -> pressure },
            Signals.DPF_TEMPERATURE to { _: Double -> temperature },
            Signals.COOLANT to { _: Double -> if (rpm > 100) 90.0 else 20.0 },
            Signals.SPEED to { _: Double -> 0.0 },
            Signals.MAF to { _: Double -> if (rpm > 100) 10.0 + rpm / 100.0 else 0.0 },
            Signals.MAP to { _: Double -> if (rpm > 100) 35.0 else 100.0 },
            Signals.LOAD to { _: Double -> if (rpm > 100) 30.0 else 0.0 },
        ),
    )

    // ---- DPF -------------------------------------------------------------

    @Test
    fun `responsive DPF pressure with plausible zero reports no anomaly`() {
        val result = DpfHealthAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.dpfHealth,
                stages = listOf(
                    dpfStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 0.0, 0.2, 30.0),
                    dpfStage("warm_idle", StageKind.WARM_IDLE, 800.0, 1.0, 180.0),
                    dpfStage("steady_rpm", StageKind.STEADY_RPM, 2_200.0, 4.0, 250.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertTrue(result.observations.any { it.label.contains("DPF pressure") })
    }

    @Test
    fun `engine-off DPF pressure offset is suspicious`() {
        val result = DpfHealthAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.dpfHealth,
                stages = listOf(
                    dpfStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 0.0, 5.0, 30.0),
                    dpfStage("warm_idle", StageKind.WARM_IDLE, 800.0, 6.0, 180.0),
                    dpfStage("steady_rpm", StageKind.STEADY_RPM, 2_200.0, 9.0, 250.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "dpf_sensor_offset" })
    }

    @Test
    fun `static DPF pressure is flagged`() {
        val result = DpfHealthAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.dpfHealth,
                stages = listOf(
                    dpfStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 0.0, 2.0, 30.0),
                    dpfStage("warm_idle", StageKind.WARM_IDLE, 800.0, 2.0, 180.0),
                    dpfStage("steady_rpm", StageKind.STEADY_RPM, 2_200.0, 2.0, 250.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "dpf_pressure_static" })
    }

    @Test
    fun `DPF guide is unsupported on a petrol engine`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.dpfHealth,
            VehicleCapabilities(
                supportedPids = allPids,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )
        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
    }

    @Test
    fun `DPF guide is limited without a temperature channel`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.dpfHealth,
            VehicleCapabilities(
                supportedPids = allPids - Signals.DPF_TEMPERATURE,
                profile = VehicleProfile(FuelType.DIESEL, Aspiration.FORCED_INDUCTION),
            ),
        )
        assertEquals(DiagnosticAvailability.LIMITED, report.availability)
        assertTrue(report.reasons.any { it.contains("temperature", true) })
    }

    // ---- Intermittent power loss ----------------------------------------

    private fun powerLossStage(
        markers: List<Long> = emptyList(),
        throttleEvent: Double = 29.0,
    ): StageCapture = stage(
        id = "passive_capture",
        kind = StageKind.PASSIVE_CAPTURE,
        durationMs = 30_000,
        hz = 2.0,
        markers = markers,
        values = mapOf(
            Signals.RPM to { s -> if (s in 9.0..12.0) 900.0 else 2_000.0 },
            Signals.LOAD to { s -> if (s in 9.0..12.0) 10.0 else 40.0 },
            Signals.SPEED to { _: Double -> 60.0 },
            Signals.THROTTLE to { s -> if (s in 9.0..12.0) throttleEvent else 30.0 },
            Signals.MAP to { s -> if (s in 9.0..12.0) 40.0 else 80.0 },
            Signals.CONTROL_MODULE_VOLTAGE to { s -> if (s in 9.0..12.0) 12.5 else 14.0 },
            Signals.STFT_B1 to { s -> if (s in 9.0..12.0) 20.0 else 0.0 },
            Signals.LTFT_B1 to { _: Double -> 0.0 },
        ),
    )

    @Test
    fun `marked power-loss event is suspicious`() {
        val result = IntermittentPowerLossAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.intermittentPowerLoss,
                stages = listOf(powerLossStage(markers = listOf(11_000L))),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "power_loss_event" })
        assertTrue(result.findings.any { it.id == "boost_loss_event" })
    }

    @Test
    fun `passive capture without a marker is inconclusive`() {
        val result = IntermittentPowerLossAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.intermittentPowerLoss,
                stages = listOf(powerLossStage()),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.warnings.any { it.contains("No symptom marker", true) })
    }

    @Test
    fun `driver lift is not counted as a power-loss event`() {
        val result = IntermittentPowerLossAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.intermittentPowerLoss,
                stages = listOf(powerLossStage(markers = listOf(11_000L), throttleEvent = 5.0)),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )

        assertTrue(result.findings.none { it.id == "power_loss_event" })
        assertTrue(result.warnings.any { it.contains("throttle reduction", true) })
    }

    @Test
    fun `intermittent guide needs speed and marks itself limited`() {
        val missingSpeed = CapabilityResolver.resolve(
            DiagnosticRegistry.intermittentPowerLoss,
            VehicleCapabilities(
                supportedPids = allPids - Signals.SPEED,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )
        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, missingSpeed.availability)

        val limited = CapabilityResolver.resolve(
            DiagnosticRegistry.intermittentPowerLoss,
            VehicleCapabilities(
                supportedPids = allPids,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )
        assertEquals(DiagnosticAvailability.LIMITED, limited.availability)
        assertTrue(limited.reasons.any { it.contains("passenger", true) })
    }

    @Test
    fun `runner stores a symptom marker in the passive capture`() {
        val definition = DiagnosticDefinition(
            id = "marker_test",
            title = "Marker test",
            subtitle = "",
            description = "",
            phase = DiagnosticPhase.PHASE_4,
            estimatedMinutes = 1,
            requiredSignals = listOf(SignalRequirement(Signals.RPM)),
            stages = listOf(
                DefinitionSupport.passiveCapture(
                    listOf(Signals.RPM, Signals.LOAD, Signals.SPEED),
                    captureMs = 5_000,
                ),
            ),
            safety = SafetyRules(maxRpm = 5_000, minVoltage = 8.0),
        )
        val runner = GuidedTestRunner(
            definition = definition,
            capabilities = VehicleCapabilities(
                supportedPids = setOf(Signals.RPM, Signals.LOAD, Signals.SPEED),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
            prerequisites = PrerequisiteAnswers(safeLocationConfirmed = true, passengerPresent = true),
        )
        runner.start(0)
        for (time in 100L..8_000L step 500) {
            runner.sample(Signals.RPM, 2_000.0, time)
            runner.sample(Signals.LOAD, 40.0, time)
            runner.sample(Signals.SPEED, 60.0, time)
            runner.sample(Signals.ADAPTER_VOLTAGE, 13.8, time)
            runner.tick(time)
            if (time == 4_100L) runner.markSymptom(time)
        }
        runner.tick(8_001)
        assertEquals(1, runner.completedCaptures.size)
        assertTrue(runner.completedCaptures.first().markers.isNotEmpty())
    }


    // ---- Health baseline -------------------------------------------------

    private fun baselineStages(mafIdle: Double = 5.0, coolant: Double = 90.0): List<StageCapture> = listOf(
        stage(
            id = "warm_idle",
            kind = StageKind.WARM_IDLE,
            values = mapOf(
                Signals.RPM to { _: Double -> 800.0 },
                Signals.COOLANT to { _: Double -> coolant },
                Signals.SPEED to { _: Double -> 0.0 },
                Signals.LOAD to { _: Double -> 20.0 },
                Signals.MAF to { _: Double -> mafIdle },
                Signals.MAP to { _: Double -> 30.0 },
                Signals.STFT_B1 to { _: Double -> 0.0 },
                Signals.LTFT_B1 to { _: Double -> 0.0 },
                Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 14.0 },
            ),
        ),
        stage(
            id = "steady_rpm",
            kind = StageKind.STEADY_RPM,
            values = mapOf(
                Signals.RPM to { _: Double -> 2_500.0 },
                Signals.COOLANT to { _: Double -> coolant },
                Signals.SPEED to { _: Double -> 0.0 },
                Signals.LOAD to { _: Double -> 40.0 },
                Signals.MAF to { _: Double -> 15.0 },
                Signals.MAP to { _: Double -> 35.0 },
                Signals.STFT_B1 to { _: Double -> 0.0 },
                Signals.LTFT_B1 to { _: Double -> 0.0 },
                Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 14.1 },
            ),
        ),
    )

    @Test
    fun `health baseline without a stored baseline is inconclusive`() {
        val result = HealthBaselineAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.healthBaseline,
                stages = baselineStages(),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
                vehicleKey = "VIN1",
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.nextSteps.any { it.contains("baseline", true) })
    }

    @Test
    fun `matching baseline reports no anomaly`() {
        val profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED)
        val baseline = BaselineExtractor.fromCaptures(
            captures = baselineStages(),
            profile = profile,
            vehicleKey = "VIN1",
            createdAt = 1_600_000_000_000,
        )!!
        val result = HealthBaselineAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.healthBaseline,
                stages = baselineStages(),
                profile = profile,
                baseline = baseline,
                vehicleKey = "VIN1",
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
    }

    @Test
    fun `baseline airflow shift is suspicious`() {
        val profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED)
        val baseline = BaselineExtractor.fromCaptures(
            captures = baselineStages(mafIdle = 5.0),
            profile = profile,
            vehicleKey = "VIN1",
            createdAt = 1_600_000_000_000,
        )!!
        val result = HealthBaselineAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.healthBaseline,
                stages = baselineStages(mafIdle = 8.0),
                profile = profile,
                baseline = baseline,
                vehicleKey = "VIN1",
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "baseline_airflow_shift" })
    }

    @Test
    fun `baseline condition mismatch is inconclusive`() {
        val profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED)
        val baseline = BaselineExtractor.fromCaptures(
            captures = baselineStages(coolant = 90.0),
            profile = profile,
            vehicleKey = "VIN1",
            createdAt = 1_600_000_000_000,
        )!!
        val result = HealthBaselineAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.healthBaseline,
                stages = baselineStages(coolant = 70.0),
                profile = profile,
                baseline = baseline,
                vehicleKey = "VIN1",
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.warnings.any { it.contains("Conditions did not match", true) })
    }

    @Test
    fun `health baseline needs RPM and coolant`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.healthBaseline,
            VehicleCapabilities(
                supportedPids = allPids - Signals.COOLANT,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )
        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
    }

}
