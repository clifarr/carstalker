package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.obd.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedPhase2Test {

    private fun stage(
        id: String,
        kind: StageKind,
        durationMs: Long = 20_000,
        hz: Double = 2.0,
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
        )
    }

    private val allPids = setOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.STFT_B2,
        Signals.LTFT_B2,
        Signals.FUEL_SYSTEM_STATUS,
        Signals.MAP,
        Signals.BARO,
        Signals.THROTTLE,
        Signals.RELATIVE_THROTTLE,
        Signals.THROTTLE_B,
        Signals.PEDAL_D,
        Signals.PEDAL_E,
        Signals.COMMANDED_THROTTLE,
        Signals.IAT,
        Signals.SPEED,
        Signals.LOAD,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.O2_B1S1,
        Signals.O2_B1S2,
        Signals.MAF,
        Signals.FUEL_PRESSURE,
        Signals.RAIL_PRESSURE,
        Signals.RAIL_GAUGE_PRESSURE,
        Signals.EGR_COMMANDED,
        Signals.EGR_ERROR,
        Signals.EVAP_PURGE,
        Signals.FUEL_RATE,
        Signals.FUEL_LEVEL,
        Signals.WARMUPS_SINCE_CLEARED,
        Signals.DISTANCE_SINCE_CLEARED,
        Signals.ENGINE_RUN_TIME_MIL,
        Signals.TIME_SINCE_CLEARED,
    )

    private fun input(
        definition: DiagnosticDefinition,
        stages: List<StageCapture>,
        profile: VehicleProfile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
        codes: List<String> = emptyList(),
        scan: ScanContext = ScanContext(),
        capabilities: VehicleCapabilities = VehicleCapabilities(
            supportedPids = allPids,
            profile = profile,
            adapterVoltageAvailable = true,
        ),
    ): AnalysisInput = AnalysisInput(
        definition = definition,
        capabilities = capabilities,
        prerequisites = PrerequisiteAnswers(
            engineWarmConfirmed = true,
            safeLocationConfirmed = true,
            closedLoopConfirmed = true,
        ),
        stages = stages,
        activeCodes = codes,
        scan = scan,
        generatedAt = 1_700_000_000_000,
    )

    private fun trimStage(
        id: String,
        kind: StageKind,
        rpm: Double,
        combinedTrim: Double,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.COOLANT to { _: Double -> 90.0 },
            Signals.SPEED to { _: Double -> 0.0 },
            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
            Signals.STFT_B1 to { _: Double -> combinedTrim / 2.0 },
            Signals.LTFT_B1 to { _: Double -> combinedTrim / 2.0 },
        ),
    )

    // ---- Rich mixture ----------------------------------------------------

    @Test
    fun `idle-only negative trim supports an idle-specific rich source`() {
        val result = RichMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.richMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, -24.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, -4.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "idle_rich_bias" })
        assertTrue(result.findings.first { it.id == "idle_rich_bias" }.alternatives.any { it.contains("purge", true) })
    }

    @Test
    fun `persistent negative trims do not diagnose a single part`() {
        val result = RichMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.richMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, -22.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, -20.0),
                ),
            ),
        )

        assertTrue(result.findings.any { it.id == "persistent_rich_bias" })
        assertFalse(result.findings.any { it.id == "idle_rich_bias" })
        assertTrue(result.limitations.any { it.contains("does not prove", true) })
    }

    @Test
    fun `normal rich-mixture trims report no anomaly`() {
        val result = RichMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.richMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, -3.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, -2.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertTrue(result.findings.isEmpty())
    }

    // ---- Oxygen response -------------------------------------------------

    private fun o2Stage(
        id: String,
        kind: StageKind,
        rpm: Double,
        hz: Double = 4.0,
        o2: (Double) -> Double,
    ): StageCapture = stage(
        id = id,
        kind = kind,
        hz = hz,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.COOLANT to { _: Double -> 90.0 },
            Signals.SPEED to { _: Double -> 0.0 },
            Signals.THROTTLE to { _: Double -> 5.0 },
            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
            Signals.O2_B1S1 to o2,
            Signals.O2_B1S2 to { _: Double -> 0.45 },
        ),
    )

    @Test
    fun `static narrowband signal is suspicious`() {
        val result = OxygenResponseAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.oxygenResponse,
                stages = listOf(
                    o2Stage("warm_idle", StageKind.WARM_IDLE, 800.0) { 0.45 },
                    o2Stage("throttle_response", StageKind.FREE_REV, 2_500.0) { 0.45 },
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "narrowband_static" })
    }

    @Test
    fun `switching narrowband signal reports no anomaly`() {
        val result = OxygenResponseAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.oxygenResponse,
                stages = listOf(
                    o2Stage("warm_idle", StageKind.WARM_IDLE, 800.0) { s -> if (s.toInt() % 2 == 0) 0.1 else 0.9 },
                    o2Stage("throttle_response", StageKind.FREE_REV, 2_500.0) { s -> if (s.toInt() % 2 == 0) 0.2 else 0.8 },
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
    }

    @Test
    fun `low O2 sample rate refuses a switching verdict`() {
        val result = OxygenResponseAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.oxygenResponse,
                stages = listOf(
                    o2Stage("warm_idle", StageKind.WARM_IDLE, 800.0, hz = 1.0) { s -> if (s.toInt() % 2 == 0) 0.1 else 0.9 },
                    o2Stage("throttle_response", StageKind.FREE_REV, 2_500.0, hz = 1.0) { s -> if (s.toInt() % 2 == 0) 0.1 else 0.9 },
                ),
            ),
        )

        assertTrue(result.findings.any { it.id == "sampling_inadequate" })
        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
    }

    // ---- Misfire ---------------------------------------------------------

    @Test
    fun `cylinder-specific misfire code is reported with a swap next step`() {
        val result = MisfireAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.misfire,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 14.0 },
                        ),
                    ),
                ),
                codes = listOf("P0302"),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.first { it.id == "cylinder_codes" }
        assertTrue(finding.supporting.any { it.contains("P0302") })
        assertTrue(finding.nextCheck.contains("engine off", true))
    }

    // ---- MAF -------------------------------------------------------------

    private fun mafStage(id: String, kind: StageKind, rpm: Double, maf: Double, map: Double): StageCapture = stage(
        id = id,
        kind = kind,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.MAF to { _: Double -> maf },
            Signals.MAP to { _: Double -> map },
            Signals.LOAD to { _: Double -> 30.0 },
            Signals.IAT to { _: Double -> 20.0 },
            Signals.THROTTLE to { _: Double -> 10.0 },
            Signals.COOLANT to { _: Double -> 90.0 },
            Signals.SPEED to { _: Double -> 0.0 },
            Signals.STFT_B1 to { _: Double -> 0.0 },
            Signals.LTFT_B1 to { _: Double -> 0.0 },
        ),
    )

    @Test
    fun `static MAF is flagged`() {
        val result = MafPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mafPlausibility,
                stages = listOf(
                    mafStage("warm_idle", StageKind.WARM_IDLE, 800.0, 5.0, 30.0),
                    mafStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 5.0, 30.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "maf_static" })
    }

    @Test
    fun `MAF that scales with RPM is plausible`() {
        val result = MafPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mafPlausibility,
                stages = listOf(
                    mafStage("warm_idle", StageKind.WARM_IDLE, 800.0, 5.0, 30.0),
                    mafStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 15.0, 30.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
    }

    // ---- Crank/no-start --------------------------------------------------

    private fun crankStage(rpm: Double, voltage: Double): StageCapture = stage(
        id = "cranking",
        kind = StageKind.CRANKING,
        durationMs = 8_000,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> voltage },
            Signals.MAP to { _: Double -> 100.0 },
            Signals.COOLANT to { _: Double -> 20.0 },
            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 1.0 },
        ),
    )

    @Test
    fun `missing cranking RPM is reported as insufficient data`() {
        val result = CrankNoStartAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.crankNoStart,
                stages = listOf(crankStage(0.0, 11.0)),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        val finding = result.findings.first { it.id == "no_cranking_rpm" }
        assertTrue(finding.explanation.contains("not evidence of a crank-sensor failure", true))
        assertTrue(result.limitations.any { it.contains("not a crank-sensor verdict", true) })
    }

    @Test
    fun `cranking RPM with acceptable voltage stays inconclusive`() {
        val result = CrankNoStartAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.crankNoStart,
                stages = listOf(crankStage(220.0, 10.5)),
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.observations.any { it.label == "Cranking rotation" })
    }

    // ---- Boost -----------------------------------------------------------

    @Test
    fun `engine-off MAP and BARO mismatch is flagged before boost conclusions`() {
        val result = BoostControlAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.boostControl,
                stages = listOf(
                    stage(
                        id = "key_on_engine_off",
                        kind = StageKind.KEY_ON_ENGINE_OFF,
                        values = mapOf(
                            Signals.MAP to { _: Double -> 80.0 },
                            Signals.BARO to { _: Double -> 101.0 },
                            Signals.RPM to { _: Double -> 0.0 },
                            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 12.4 },
                        ),
                    ),
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.MAP to { _: Double -> 30.0 },
                            Signals.BARO to { _: Double -> 101.0 },
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.THROTTLE to { _: Double -> 5.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                        ),
                    ),
                ),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "boost_sensor_mismatch" })
    }

    // ---- Fuel pressure ---------------------------------------------------

    @Test
    fun `pressure drop with demand is suspicious`() {
        val result = FuelPressureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.fuelPressure,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.FUEL_PRESSURE to { _: Double -> 400.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                            Signals.STFT_B1 to { _: Double -> 0.0 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
                        ),
                    ),
                    stage(
                        id = "steady_rpm",
                        kind = StageKind.STEADY_RPM,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 2_500.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.FUEL_PRESSURE to { _: Double -> 250.0 },
                            Signals.LOAD to { _: Double -> 40.0 },
                            Signals.STFT_B1 to { _: Double -> 0.0 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "pressure_drops_with_demand" })
    }

    // ---- EGR -------------------------------------------------------------

    @Test
    fun `large EGR error supports a flow investigation`() {
        val result = EgrAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.egr,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.EGR_COMMANDED to { _: Double -> 20.0 },
                            Signals.EGR_ERROR to { _: Double -> 30.0 },
                            Signals.MAP to { _: Double -> 40.0 },
                            Signals.MAF to { _: Double -> 10.0 },
                            Signals.THROTTLE to { _: Double -> 5.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "egr_error_high" })
    }

    // ---- Throttle/pedal --------------------------------------------------

    @Test
    fun `correlated throttle and pedal channels report no anomaly`() {
        val result = ThrottlePedalAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.throttlePedal,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.THROTTLE to { _: Double -> 5.0 },
                            Signals.PEDAL_D to { _: Double -> 0.0 },
                            Signals.PEDAL_E to { _: Double -> 0.0 },
                            Signals.THROTTLE_B to { _: Double -> 5.0 },
                            Signals.COMMANDED_THROTTLE to { _: Double -> 5.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                        ),
                    ),
                    stage(
                        id = "throttle_response",
                        kind = StageKind.FREE_REV,
                        values = mapOf(
                            Signals.RPM to { s -> 800.0 + s * 80.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.THROTTLE to { s -> 5.0 + s * 2.0 },
                            Signals.PEDAL_D to { s -> 5.0 + s * 2.0 },
                            Signals.PEDAL_E to { s -> 5.0 + s * 2.0 },
                            Signals.THROTTLE_B to { s -> 5.0 + s * 2.0 },
                            Signals.COMMANDED_THROTTLE to { s -> 5.0 + s * 2.0 },
                            Signals.LOAD to { _: Double -> 30.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
    }

    // ---- EVAP ------------------------------------------------------------

    @Test
    fun `purge command with the expected trim response reports no anomaly`() {
        val result = EvapPurgeAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.evapPurge,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.EVAP_PURGE to { _: Double -> 0.0 },
                            Signals.STFT_B1 to { _: Double -> 0.0 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
                            Signals.MAP to { _: Double -> 35.0 },
                        ),
                    ),
                    stage(
                        id = "steady_rpm",
                        kind = StageKind.STEADY_RPM,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 2_000.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.EVAP_PURGE to { s -> 10.0 + s },
                            Signals.STFT_B1 to { s -> -s * 0.5 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
                            Signals.MAP to { _: Double -> 40.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.NO_ANOMALY_DETECTED, result.status)
        assertTrue(result.findings.any { it.id == "purge_trim_response" })
    }

    // ---- Fuel consumption ------------------------------------------------

    @Test
    fun `fuel consumption without a baseline is a snapshot`() {
        val result = FuelConsumptionAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.fuelConsumption,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.LOAD to { _: Double -> 20.0 },
                            Signals.FUEL_RATE to { _: Double -> 0.8 },
                            Signals.MAF to { _: Double -> 4.0 },
                            Signals.MAP to { _: Double -> 35.0 },
                            Signals.STFT_B1 to { _: Double -> 0.0 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.FUEL_LEVEL to { _: Double -> 50.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.observations.any { it.label.contains("fuel flow") })
        assertTrue(result.nextSteps.any { it.contains("baseline", true) })
    }

    // ---- Readiness / used car -------------------------------------------

    private fun monitorStatus(vararg monitors: MonitorStatus.Monitor) = MonitorStatus(false, monitors.toList())

    @Test
    fun `incomplete readiness is reported without claiming tampering`() {
        val result = ReadinessAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.readiness,
                stages = listOf(
                    stage(
                        id = "key_on_engine_off",
                        kind = StageKind.KEY_ON_ENGINE_OFF,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 0.0 },
                            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 12.4 },
                            Signals.COOLANT to { _: Double -> 20.0 },
                            Signals.WARMUPS_SINCE_CLEARED to { _: Double -> 5.0 },
                            Signals.DISTANCE_SINCE_CLEARED to { _: Double -> 120.0 },
                            Signals.ENGINE_RUN_TIME_MIL to { _: Double -> 0.0 },
                            Signals.TIME_SINCE_CLEARED to { _: Double -> 1_000.0 },
                        ),
                    ),
                ),
                scan = ScanContext(
                    monitor = monitorStatus(
                        MonitorStatus.Monitor("Catalyst", true, false),
                        MonitorStatus.Monitor("EVAP system", true, true),
                        MonitorStatus.Monitor("Misfire", false, false),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertTrue(result.findings.any { it.id == "readiness_incomplete" })
        assertTrue(result.limitations.any { it.contains("cannot prove why", true) })
    }

    @Test
    fun `used-car summary flags stored codes`() {
        val result = UsedCarInspectionAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.usedCarInspection,
                stages = listOf(
                    stage(
                        id = "warm_idle",
                        kind = StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.SPEED to { _: Double -> 0.0 },
                            Signals.STFT_B1 to { _: Double -> 0.0 },
                            Signals.LTFT_B1 to { _: Double -> 0.0 },
                            Signals.CONTROL_MODULE_VOLTAGE to { _: Double -> 14.0 },
                            Signals.MAP to { _: Double -> 35.0 },
                            Signals.MAF to { _: Double -> 4.0 },
                        ),
                    ),
                ),
                scan = ScanContext(
                    monitor = monitorStatus(MonitorStatus.Monitor("Catalyst", true, true)),
                    storedCodes = listOf("P0420"),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "codes_present" })
    }

    // ---- Capability + runner --------------------------------------------

    @Test
    fun `boost guide is unsupported on a naturally aspirated engine`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.boostControl,
            VehicleCapabilities(
                supportedPids = allPids,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )
        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
    }

    @Test
    fun `boost guide is limited even with forced induction`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.boostControl,
            VehicleCapabilities(
                supportedPids = allPids,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
            ),
        )
        assertEquals(DiagnosticAvailability.LIMITED, report.availability)
        assertTrue(report.reasons.any { it.contains("requested boost", true) })
    }

    @Test
    fun `EVAP guide needs the purge command`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.evapPurge,
            VehicleCapabilities(
                supportedPids = allPids - Signals.EVAP_PURGE,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )
        assertEquals(DiagnosticAvailability.VEHICLE_UNSUPPORTED, report.availability)
    }

    @Test
    fun `readiness guide asks for a scan before starting`() {
        val report = CapabilityResolver.resolve(
            DiagnosticRegistry.readiness,
            VehicleCapabilities(
                supportedPids = allPids,
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
        )
        assertEquals(DiagnosticAvailability.NEEDS_SCAN, report.availability)
        assertFalse(report.availability.startable)
        assertTrue(report.reasons.any { it.contains("Read codes", true) })
    }

    @Test
    fun `every implemented guide has an analysis branch`() {
        for (definition in DiagnosticRegistry.implemented()) {
            val result = GuidedAnalysis.analyze(
                AnalysisInput(
                    definition = definition,
                    capabilities = VehicleCapabilities(
                        supportedPids = allPids,
                        profile = VehicleProfile(FuelType.PETROL, Aspiration.FORCED_INDUCTION),
                    ),
                    prerequisites = PrerequisiteAnswers(safeLocationConfirmed = true),
                    stages = emptyList(),
                ),
            )
            assertTrue(
                "${definition.id} has no analysis branch",
                result.diagnosticId == definition.id,
            )
        }
    }

    @Test
    fun `runner captures a cranking stage without requiring a running engine`() {
        val definition = DiagnosticDefinition(
            id = "crank_test",
            title = "Crank test",
            subtitle = "",
            description = "",
            phase = DiagnosticPhase.PHASE_2,
            estimatedMinutes = 1,
            requiredSignals = listOf(SignalRequirement(Signals.RPM)),
            stages = listOf(
                DefinitionSupport.cranking(
                    listOf(Signals.RPM, Signals.CONTROL_MODULE_VOLTAGE),
                    captureMs = 4_000,
                ),
            ),
            safety = SafetyRules(minVoltage = 7.5, maxRpm = 1_500),
        )
        val runner = GuidedTestRunner(
            definition = definition,
            capabilities = VehicleCapabilities(
                supportedPids = setOf(Signals.RPM, Signals.CONTROL_MODULE_VOLTAGE),
                profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            ),
            prerequisites = PrerequisiteAnswers(safeLocationConfirmed = true),
        )
        runner.start(0)
        for (time in 100L..8_000L step 500) {
            runner.sample(Signals.RPM, 200.0, time)
            runner.sample(Signals.CONTROL_MODULE_VOLTAGE, 10.5, time)
            runner.tick(time)
        }
        runner.tick(8_001)
        assertEquals(GuidedRunState.COMPLETE, runner.runState)
        assertEquals(1, runner.completedCaptures.size)
        assertTrue(runner.completedCaptures.first().series(Signals.RPM).isNotEmpty())
    }
}
