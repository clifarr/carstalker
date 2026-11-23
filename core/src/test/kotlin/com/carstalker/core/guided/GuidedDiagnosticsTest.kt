package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedDiagnosticsTest {

    private fun stage(
        id: String,
        kind: StageKind,
        durationMs: Long = 20_000,
        hz: Double = 2.0,
        interruptions: Int = 0,
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
            ),
            interruptions = interruptions,
        )
    }

    private fun input(
        definition: DiagnosticDefinition,
        stages: List<StageCapture>,
        profile: VehicleProfile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
        prerequisites: PrerequisiteAnswers = PrerequisiteAnswers(
            engineWarmConfirmed = true,
            engineColdConfirmed = true,
            safeLocationConfirmed = true,
            closedLoopConfirmed = true,
        ),
        codes: List<String> = emptyList(),
        safetyStop: String? = null,
    ): AnalysisInput = AnalysisInput(
        definition = definition,
        capabilities = VehicleCapabilities(
            supportedPids = setOf(
                Signals.RPM,
                Signals.COOLANT,
                Signals.STFT_B1,
                Signals.LTFT_B1,
                Signals.STFT_B2,
                Signals.LTFT_B2,
                Signals.CONTROL_MODULE_VOLTAGE,
                Signals.MAP,
                Signals.BARO,
                Signals.THROTTLE,
                Signals.IAT,
                Signals.AMBIENT,
                Signals.SPEED,
                Signals.LOAD,
                Signals.FUEL_SYSTEM_STATUS,
            ),
            profile = profile,
            adapterVoltageAvailable = true,
        ),
        prerequisites = prerequisites,
        stages = stages,
        activeCodes = codes,
        safetyStopReason = safetyStop,
        generatedAt = 1_700_000_000_000,
    )

    private fun trimStage(
        id: String,
        kind: StageKind,
        rpm: Double,
        combinedTrim: Double,
        bank2Trim: Double? = null,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        values = buildMap {
            put(Signals.RPM, { _: Double -> rpm })
            put(Signals.COOLANT, { _: Double -> 90.0 })
            put(Signals.FUEL_SYSTEM_STATUS, { _: Double -> 2.0 })
            put(Signals.STFT_B1, { _: Double -> combinedTrim / 2.0 })
            put(Signals.LTFT_B1, { _: Double -> combinedTrim / 2.0 })
            bank2Trim?.let {
                put(Signals.STFT_B2, { _: Double -> it / 2.0 })
                put(Signals.LTFT_B2, { _: Double -> it / 2.0 })
            }
            put(Signals.SPEED, { _: Double -> 0.0 })
        },
    )

    // ---- Lean mixture ----------------------------------------------------

    @Test
    fun `idle-only positive trim supports unmetered air with alternatives`() {
        val result = LeanMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.leanMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, 24.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 6.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.firstOrNull { it.id == "unmetered_air" }
        assertNotNull(finding)
        assertTrue(finding!!.alternatives.any { it.contains("MAF", ignoreCase = true) })
        assertTrue(finding.nextCheck.contains("smoke", ignoreCase = true))
        assertTrue(result.observations.any { it.label.contains("Change from idle") })
    }

    @Test
    fun `persistent positive trims do not diagnose a vacuum leak with certainty`() {
        val result = LeanMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.leanMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, 22.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 20.0),
                ),
            ),
        )

        assertTrue(result.findings.any { it.id == "persistent_mixture_bias" })
        assertFalse(result.findings.any { it.id == "unmetered_air" })
        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.limitations.any { it.contains("cannot prove", ignoreCase = true) })
    }

    @Test
    fun `lean mixture missing trims is unable to run`() {
        val result = LeanMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.leanMixture,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.UNABLE_TO_RUN, result.status)
        assertTrue(result.quality.missingSignals.any { it.contains("trim", ignoreCase = true) })
    }

    @Test
    fun `bank separation is reported without merging the banks`() {
        val result = LeanMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.leanMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, 25.0, bank2Trim = 4.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 8.0, bank2Trim = 3.0),
                ),
            ),
        )

        assertTrue(result.findings.any { it.id == "bank_specific" })
        assertTrue(result.observations.any { it.label.contains("B2") })
    }

    @Test
    fun `lean mixture normal trims report no anomaly`() {
        val result = LeanMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.leanMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, 5.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 3.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(ResultConfidence.HIGH, result.confidence)
    }

    // ---- Thermostat ------------------------------------------------------

    private fun coolantStage(
        curve: (Double) -> Double,
        iat: Double = 20.0,
        durationMs: Long = 300_000,
        hz: Double = 1.0,
    ): StageCapture = stage(
        id = "cold_warmup",
        kind = StageKind.COLD_START_WARMUP,
        durationMs = durationMs,
        hz = hz,
        values = mapOf(
            Signals.COOLANT to curve,
            Signals.IAT to { _: Double -> iat },
            Signals.AMBIENT to { _: Double -> 20.0 },
            Signals.RPM to { _: Double -> 900.0 },
            Signals.SPEED to { _: Double -> 0.0 },
        ),
    )

    @Test
    fun `thermostat that plateaus low is suspicious but not certain`() {
        val result = ThermostatAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.thermostat,
                stages = listOf(coolantStage(curve = { t -> 20.0 + 45.0 * (1.0 - kotlin.math.exp(-t / 60.0)) })),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.firstOrNull { it.id == "thermostat_stuck_open" }
        assertNotNull(finding)
        assertTrue(finding!!.alternatives.any { it.contains("ambient", ignoreCase = true) })
    }

    @Test
    fun `normal warm-up reports no anomaly`() {
        val result = ThermostatAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.thermostat,
                stages = listOf(coolantStage(curve = { t -> 20.0 + 70.0 * (1.0 - kotlin.math.exp(-t / 90.0)) })),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertEquals(ResultConfidence.HIGH, result.confidence)
    }

    @Test
    fun `implausible coolant step points at measurement before thermostat`() {
        val result = ThermostatAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.thermostat,
                stages = listOf(
                    coolantStage(
                        curve = { t ->
                            when {
                                t < 100 -> 20.0
                                t < 101 -> 60.0
                                else -> 85.0
                            }
                        },
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.firstOrNull { it.id == "ect_signal_implausible" }
        assertNotNull(finding)
        assertTrue(finding!!.nextCheck.contains("connector", ignoreCase = true))
    }

    @Test
    fun `cold soak sensor disagreement is flagged with context`() {
        val result = ThermostatAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.thermostat,
                stages = listOf(
                    coolantStage(
                        curve = { t -> 20.0 + 70.0 * (1.0 - kotlin.math.exp(-t / 90.0)) },
                        iat = 45.0,
                    ),
                ),
            ),
        )

        assertTrue(result.findings.any { it.id == "temperature_plausibility" })
        assertTrue(result.findings.first { it.id == "temperature_plausibility" }.alternatives.any {
            it.contains("cold-soaked", ignoreCase = true)
        })
    }

    // ---- Charging --------------------------------------------------------

    private fun voltageStage(
        id: String,
        kind: StageKind,
        rpm: Double,
        volts: Double,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        durationMs = 10_000,
        values = mapOf(
            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> volts },
            Signals.RPM to { _: Double -> rpm },
            Signals.COOLANT to { _: Double -> 90.0 },
        ),
    )

    @Test
    fun `smart charging dip at idle is not an alternator verdict`() {
        val result = ChargingAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.charging,
                stages = listOf(
                    voltageStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 0.0, 12.4),
                    voltageStage("warm_idle", StageKind.WARM_IDLE, 800.0, 12.8),
                    voltageStage("elevated_rpm", StageKind.STEADY_RPM, 1_800.0, 14.2),
                    voltageStage("electrical_load", StageKind.ELECTRICAL_LOAD, 800.0, 13.8),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.firstOrNull { it.id == "reduced_output_at_idle" }
        assertNotNull(finding)
        assertTrue(finding!!.explanation.contains("not enough to declare", ignoreCase = true))
        assertTrue(finding.alternatives.any { it.contains("Smart charging", ignoreCase = true) })
    }

    @Test
    fun `normal charging voltages report no anomaly`() {
        val result = ChargingAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.charging,
                stages = listOf(
                    voltageStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 0.0, 12.5),
                    voltageStage("warm_idle", StageKind.WARM_IDLE, 800.0, 14.0),
                    voltageStage("elevated_rpm", StageKind.STEADY_RPM, 1_800.0, 14.2),
                    voltageStage("electrical_load", StageKind.ELECTRICAL_LOAD, 800.0, 13.9),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertEquals(ResultConfidence.HIGH, result.confidence)
    }

    // ---- MAP -------------------------------------------------------------

    private fun mapStage(
        id: String,
        kind: StageKind,
        map: Double,
        baro: Double?,
        rpm: Double,
        throttle: Double = 3.0,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        durationMs = 10_000,
        values = buildMap {
            put(Signals.MAP, { _: Double -> map })
            baro?.let { put(Signals.BARO, { _: Double -> it }) }
            put(Signals.RPM, { _: Double -> rpm })
            put(Signals.THROTTLE, { _: Double -> throttle })
        },
    )

    @Test
    fun `map baro mismatch is reported with unit and sensor alternatives`() {
        val result = MapPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mapPlausibility,
                stages = listOf(
                    mapStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 80.0, 101.0, 0.0),
                    mapStage("warm_idle", StageKind.WARM_IDLE, 32.0, 101.0, 800.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.firstOrNull { it.id == "map_baro_mismatch" }
        assertNotNull(finding)
        assertTrue(finding!!.alternatives.any { it.contains("Unit", ignoreCase = true) })
    }

    @Test
    fun `normal map plausibility reports no anomaly`() {
        val result = MapPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mapPlausibility,
                stages = listOf(
                    mapStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 101.0, 101.0, 0.0),
                    mapStage("warm_idle", StageKind.WARM_IDLE, 32.0, 101.0, 800.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertEquals(ResultConfidence.HIGH, result.confidence)
    }

    @Test
    fun `diesel map test is limited to key-on plausibility`() {
        val result = MapPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mapPlausibility,
                profile = VehicleProfile(FuelType.DIESEL, Aspiration.FORCED_INDUCTION),
                stages = listOf(
                    mapStage("key_on_engine_off", StageKind.KEY_ON_ENGINE_OFF, 101.0, 101.0, 0.0),
                    mapStage("warm_idle", StageKind.WARM_IDLE, 32.0, 101.0, 800.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertTrue(result.limitations.any { it.contains("Idle-vacuum", ignoreCase = true) })
    }

    // ---- Runner ----------------------------------------------------------

    private val runnerDefinition = DiagnosticDefinition(
        id = "test_idle",
        title = "Runner test",
        subtitle = "test",
        description = "test",
        phase = DiagnosticPhase.PHASE_1,
        estimatedMinutes = 1,
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM),
            SignalRequirement(Signals.COOLANT),
        ),
        stages = listOf(
            StageDefinition(
                id = "idle",
                title = "Idle",
                instruction = "Idle",
                condition = StageCondition(
                    kind = StageKind.WARM_IDLE,
                    rpmRange = 550..1_100,
                    minCoolantC = 70.0,
                    settlingMs = 8_000,
                    captureMs = 20_000,
                ),
                pids = listOf(Signals.RPM, Signals.COOLANT, Signals.STFT_B1, Signals.LTFT_B1),
            ),
        ),
        safety = SafetyRules(stopCodePrefixes = listOf("P030")),
    )

    private fun runner(definition: DiagnosticDefinition = runnerDefinition, codes: List<String> = emptyList()) =
        GuidedTestRunner(
            definition = definition,
            capabilities = VehicleCapabilities(
                supportedPids = setOf(
                    Signals.RPM,
                    Signals.COOLANT,
                    Signals.STFT_B1,
                    Signals.LTFT_B1,
                ),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
            prerequisites = PrerequisiteAnswers(
                engineWarmConfirmed = true,
                safeLocationConfirmed = true,
            ),
            activeCodes = codes,
        )

    private fun feedStable(runner: GuidedTestRunner, from: Long, to: Long, step: Long = 1_000L, rpm: Double = 800.0): Long {
        var time = from
        while (time <= to && !runner.runState.terminal) {
            runner.sample(Signals.RPM, rpm, time)
            runner.sample(Signals.COOLANT, 90.0, time)
            runner.sample(Signals.STFT_B1, 2.0, time)
            runner.sample(Signals.LTFT_B1, 3.0, time)
            runner.tick(time)
            time += step
        }
        return time
    }

    @Test
    fun `runner waits for settling then captures a complete stage`() {
        val runner = runner()
        runner.start(0)
        feedStable(runner, 0, 40_000)

        assertEquals(1, runner.completedCaptures.size)
        val capture = runner.completedCaptures.single()
        assertTrue(capture.capture.series(Signals.RPM).size >= 15)
        assertTrue(capture.completed)
        assertTrue(runner.runState == GuidedRunState.ANALYSING || runner.runState == GuidedRunState.COMPLETE)
    }

    @Test
    fun `runner does not capture while rpm is unstable`() {
        val runner = runner()
        runner.start(0)
        var time = 0L
        var flip = false
        while (time <= 40_000) {
            flip = !flip
            runner.sample(Signals.RPM, if (flip) 800.0 else 1_400.0, time)
            runner.sample(Signals.COOLANT, 90.0, time)
            runner.sample(Signals.STFT_B1, 2.0, time)
            runner.sample(Signals.LTFT_B1, 3.0, time)
            runner.tick(time)
            time += 1_000
        }
        assertTrue(runner.completedCaptures.isEmpty())
        assertTrue(runner.runState == GuidedRunState.WAITING_FOR_CONDITIONS || runner.runState == GuidedRunState.SETTLING)

        feedStable(runner, time, time + 40_000)
        assertEquals(1, runner.completedCaptures.size)
    }

    @Test
    fun `runner pauses when live data stops mid-stage`() {
        val runner = runner()
        runner.start(0)
        var time = feedStable(runner, 0, 20_000)
        assertTrue(runner.runState == GuidedRunState.CAPTURING || runner.completedCaptures.isNotEmpty())
        assertTrue(runner.completedCaptures.isEmpty())

        // Stop feeding samples and keep ticking: stale data must not be captured.
        var silence = 0L
        while (silence < 6_000) {
            runner.tick(time + silence)
            silence += 1_000
        }
        assertTrue(runner.completedCaptures.isEmpty())
        assertEquals(GuidedRunState.PAUSED, runner.runState)

        feedStable(runner, time + silence, time + silence + 40_000)
        assertEquals(1, runner.completedCaptures.size)
        assertTrue(runner.completedCaptures.single().interruptions >= 1)
    }

    @Test
    fun `cold-start plateau completion requires a real observation window`() {
        val definition = DiagnosticDefinition(
            id = "test_warmup",
            title = "Warm-up test",
            subtitle = "test",
            description = "test",
            phase = DiagnosticPhase.PHASE_1,
            estimatedMinutes = 1,
            requiredSignals = listOf(SignalRequirement(Signals.COOLANT), SignalRequirement(Signals.RPM)),
            stages = listOf(
                StageDefinition(
                    id = "warmup",
                    title = "Warm-up",
                    instruction = "Warm up",
                    condition = StageCondition(
                        kind = StageKind.COLD_START_WARMUP,
                        requireEngineRunning = true,
                        settlingMs = 0,
                        captureMs = 600_000,
                        completeOnCoolantPlateau = true,
                        plateauCoolantC = 75.0,
                        plateauWindowMs = 60_000,
                        plateauToleranceC = 2.0,
                    ),
                    pids = listOf(Signals.COOLANT, Signals.RPM),
                ),
            ),
        )
        val runner = GuidedTestRunner(
            definition = definition,
            capabilities = VehicleCapabilities(
                supportedPids = setOf(Signals.COOLANT, Signals.RPM),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
            prerequisites = PrerequisiteAnswers(engineColdConfirmed = true, safeLocationConfirmed = true),
        )
        runner.start(0)
        var time = 0L
        while (time <= 10_000) {
            runner.sample(Signals.RPM, 900.0, time)
            runner.sample(Signals.COOLANT, 90.0, time)
            runner.tick(time)
            time += 1_000
        }
        assertTrue(runner.completedCaptures.isEmpty())

        while (time <= 80_000) {
            runner.sample(Signals.RPM, 900.0, time)
            runner.sample(Signals.COOLANT, 90.0, time)
            runner.tick(time)
            time += 1_000
        }
        assertEquals(1, runner.completedCaptures.size)
    }

    @Test
    fun `manual pause discards the partial stage and resume restarts settling`() {
        val runner = runner()
        runner.start(0)
        var time = feedStable(runner, 0, 20_000)
        assertTrue(runner.completedCaptures.isEmpty())
        runner.pause()
        runner.tick(time)
        assertEquals(GuidedRunState.PAUSED, runner.runState)
        assertTrue(runner.snapshot(time).pausedByUser)

        runner.resume(time + 1_000)
        assertEquals(GuidedRunState.WAITING_FOR_CONDITIONS, runner.runState)
        time = feedStable(runner, time + 1_000, time + 50_000)
        assertEquals(1, runner.completedCaptures.size)
    }

    @Test
    fun `runner stops for a misfire code before any capture`() {
        val runner = runner(codes = listOf("P0301"))
        runner.start(0)

        assertEquals(GuidedRunState.SAFETY_STOPPED, runner.runState)
        assertTrue(runner.safetyReason!!.contains("P0301"))
        assertTrue(runner.completedCaptures.isEmpty())
    }

    @Test
    fun `optional stage can be skipped without fabricating samples`() {
        val definition = runnerDefinition.copy(
            stages = listOf(
                StageDefinition(
                    id = "optional",
                    title = "Optional",
                    instruction = "Optional",
                    condition = StageCondition(
                        kind = StageKind.STEADY_RPM,
                        rpmRange = 2_300..2_700,
                        minCoolantC = 70.0,
                        settlingMs = 1_000,
                        captureMs = 5_000,
                    ),
                    pids = listOf(Signals.RPM),
                    optional = true,
                ),
            ),
        )
        val runner = runner(definition)
        runner.start(0)
        runner.skipCurrentStage(0)

        assertEquals(listOf("optional"), runner.skippedStages)
    }

    @Test
    fun `runner and analyzer work end to end with a synthetic OBD source`() {
        val definition = DiagnosticRegistry.leanMixture
        val capabilities = VehicleCapabilities(
            supportedPids = setOf(
                Signals.RPM,
                Signals.COOLANT,
                Signals.FUEL_SYSTEM_STATUS,
                Signals.STFT_B1,
                Signals.LTFT_B1,
                Signals.SPEED,
            ),
            profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            adapterVoltageAvailable = true,
        )
        val prerequisites = PrerequisiteAnswers(
            engineWarmConfirmed = true,
            safeLocationConfirmed = true,
            closedLoopConfirmed = true,
        )
        val runner = GuidedTestRunner(definition, capabilities, prerequisites, listOf("P0171"))
        runner.start(0)

        fun feed(time: Long, rpm: Double, trim: Double) {
            runner.sample(Signals.RPM, rpm, time)
            runner.sample(Signals.COOLANT, 90.0, time)
            runner.sample(Signals.FUEL_SYSTEM_STATUS, 2.0, time)
            runner.sample(Signals.STFT_B1, trim / 2.0, time)
            runner.sample(Signals.LTFT_B1, trim / 2.0, time)
            runner.sample(Signals.SPEED, 0.0, time)
            runner.tick(time)
        }

        var time = 0L
        while (time <= 30_000) {
            feed(time, 800.0, 24.0)
            time += 1_000
        }
        while (time <= 60_000) {
            feed(time, 2_500.0, 6.0)
            time += 1_000
        }
        runner.skipCurrentStage(time)
        runner.tick(time + 1)

        assertEquals(2, runner.completedCaptures.size)
        val result = GuidedAnalysis.analyze(
            AnalysisInput(
                definition = definition,
                capabilities = capabilities,
                prerequisites = prerequisites,
                stages = runner.completedCaptures,
                activeCodes = listOf("P0171"),
                skippedStages = runner.skippedStages,
            ),
        )
        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "unmetered_air" })
        assertTrue(result.stages.all { it.valid })
    }

    // ---- Capability resolver --------------------------------------------

    @Test
    fun `capability resolver refuses a guide with missing required pids`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.leanMixture,
            VehicleCapabilities(
                supportedPids = setOf(Signals.RPM, Signals.COOLANT),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )

        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
        assertTrue(report.missingSignals.any { it.contains("trim", ignoreCase = true) })
    }

    @Test
    fun `capability resolver downgrades wideband catalyst to limited`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.catalyst,
            VehicleCapabilities(
                supportedPids = setOf(
                    Signals.RPM,
                    Signals.COOLANT,
                    Signals.WIDEBAND_B1S1,
                    Signals.CATALYST_B1S1,
                ),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )

        assertEquals(DiagnosticAvailability.LIMITED, report.availability)
        assertTrue(report.reasons.any { it.contains("Wideband", ignoreCase = true) })
    }

    @Test
    fun `capability resolver blocks lean guide on a diesel`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.leanMixture,
            VehicleCapabilities(
                supportedPids = setOf(
                    Signals.RPM,
                    Signals.COOLANT,
                    Signals.STFT_B1,
                    Signals.LTFT_B1,
                ),
                profile = VehicleProfile(FuelType.DIESEL, Aspiration.FORCED_INDUCTION),
            ),
        )

        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
        assertTrue(report.reasons.any { it.contains("diesel", ignoreCase = true) })
    }

    @Test
    fun `every registered guide is implemented`() {
        assertTrue(DiagnosticRegistry.planned().isEmpty())
        assertTrue(DiagnosticRegistry.all.all { it.implemented })
    }

    @Test
    fun `every stage requests the signals its condition evaluates`() {
        for (definition in DiagnosticRegistry.implemented()) {
            for (stage in definition.stages) {
                val condition = stage.condition
                if (condition.minCoolantC != null || condition.maxCoolantC != null ||
                    condition.kind == StageKind.COLD_START_WARMUP
                ) {
                    assertTrue(
                        "${definition.id}/${stage.id} checks coolant but does not request it",
                        stage.pids.contains(Signals.COOLANT),
                    )
                }
                if (condition.rpmRange != null || condition.requireEngineOff || condition.requireEngineRunning) {
                    assertTrue(
                        "${definition.id}/${stage.id} checks RPM but does not request it",
                        stage.pids.contains(Signals.RPM),
                    )
                }
                if (condition.minSpeedKph != null || condition.maxSpeedKph != null) {
                    assertTrue(
                        "${definition.id}/${stage.id} checks speed but does not request it",
                        stage.pids.contains(Signals.SPEED),
                    )
                }
                for (pid in stage.pids) {
                    assertTrue(
                        "${definition.id}/${stage.id} requests an unknown PID $pid",
                        pid == Signals.ADAPTER_VOLTAGE || com.carstalker.core.obd.PidCatalog.find(pid) != null,
                    )
                }
            }
        }
    }

    @Test
    fun `registry has unique ids and complete phase one coverage`() {
        val ids = DiagnosticRegistry.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(21, DiagnosticRegistry.implemented().size)
        assertEquals(5, DiagnosticRegistry.implemented().count { it.phase == DiagnosticPhase.PHASE_1 })
        assertEquals(5, DiagnosticRegistry.implemented().count { it.phase == DiagnosticPhase.PHASE_2 })
        assertEquals(6, DiagnosticRegistry.implemented().count { it.phase == DiagnosticPhase.PHASE_3 })
        assertEquals(5, DiagnosticRegistry.implemented().count { it.phase == DiagnosticPhase.PHASE_4 })
        assertTrue(DiagnosticRegistry.all.all { definition ->
            definition.implemented || definition.catalogueNote != null
        })
    }
}
