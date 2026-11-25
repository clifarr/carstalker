package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.diagnose.CatalystWizard
import com.carstalker.core.obd.Mode06Parser
import com.carstalker.core.obd.MonitorDataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-11-24 review findings. These encode the rule that missing or
 * contradictory evidence must never become a clean or confident result.
 */
class DiagnosticTruthTest {

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
        Signals.RPM, Signals.COOLANT, Signals.STFT_B1, Signals.LTFT_B1,
        Signals.STFT_B2, Signals.LTFT_B2, Signals.FUEL_SYSTEM_STATUS,
        Signals.MAP, Signals.BARO, Signals.THROTTLE, Signals.RELATIVE_THROTTLE,
        Signals.THROTTLE_B, Signals.PEDAL_D, Signals.PEDAL_E, Signals.COMMANDED_THROTTLE,
        Signals.IAT, Signals.SPEED, Signals.LOAD, Signals.CONTROL_MODULE_VOLTAGE,
        Signals.O2_B1S1, Signals.O2_B1S2, Signals.MAF, Signals.FUEL_PRESSURE,
        Signals.EGR_COMMANDED, Signals.EGR_ERROR, Signals.EVAP_PURGE,
        Signals.WARMUPS_SINCE_CLEARED, Signals.DISTANCE_SINCE_CLEARED,
    )

    private fun input(
        definition: DiagnosticDefinition,
        stages: List<StageCapture>,
        scan: ScanContext = ScanContext(),
        codes: List<String> = emptyList(),
    ): AnalysisInput = AnalysisInput(
        definition = definition,
        capabilities = VehicleCapabilities(
            supportedPids = allPids,
            profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            adapterVoltageAvailable = true,
        ),
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

    private fun trimStage(id: String, kind: StageKind, rpm: Double, trim: Double): StageCapture = stage(
        id = id,
        kind = kind,
        values = mapOf(
            Signals.RPM to { _: Double -> rpm },
            Signals.COOLANT to { _: Double -> 90.0 },
            Signals.SPEED to { _: Double -> 0.0 },
            Signals.FUEL_SYSTEM_STATUS to { _: Double -> 2.0 },
            Signals.STFT_B1 to { _: Double -> trim / 2.0 },
            Signals.LTFT_B1 to { _: Double -> trim / 2.0 },
        ),
    )

    @Test
    fun `positive trims never produce a negative-trim finding`() {
        val result = RichMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.richMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, 48.4),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, 12.5),
                ),
            ),
        )

        assertFalse(result.status == DiagnosticStatus.SUSPICIOUS)
        assertTrue(result.findings.none { it.id.contains("rich") })
        assertTrue(result.summary.contains("positive", ignoreCase = true) || result.summary.contains("No negative", ignoreCase = true))
        assertTrue(result.warnings.any { it.contains("positive", ignoreCase = true) })
    }

    @Test
    fun `negative trims still produce a qualified rich finding`() {
        val result = RichMixtureAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.richMixture,
                stages = listOf(
                    trimStage("warm_idle", StageKind.WARM_IDLE, 800.0, -24.0),
                    trimStage("steady_rpm", StageKind.STEADY_RPM, 2_500.0, -6.0),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.SUSPICIOUS, result.status)
        assertTrue(result.findings.any { it.id == "idle_rich_bias" })
        assertTrue(result.findings.first { it.id == "idle_rich_bias" }.explanation.contains("not proof", ignoreCase = true))
    }

    @Test
    fun `flat throttle response is not called healthy`() {
        val result = ThrottlePedalAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.throttlePedal,
                stages = listOf(
                    stage(
                        "warm_idle",
                        StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.THROTTLE to { _: Double -> 2.0 },
                            Signals.PEDAL_D to { _: Double -> 3.9 },
                            Signals.PEDAL_E to { _: Double -> 2.0 },
                        ),
                    ),
                    stage(
                        "throttle_response",
                        StageKind.FREE_REV,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.THROTTLE to { _: Double -> 2.0 },
                            Signals.PEDAL_D to { _: Double -> 3.9 },
                            Signals.PEDAL_E to { _: Double -> 2.0 },
                        ),
                    ),
                ),
            ),
        )

        assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
        assertFalse(result.status == DiagnosticStatus.NO_ANOMALY_DETECTED)
        assertTrue(result.summary.contains("not exercised", ignoreCase = true))
        val response = result.stages.first { it.stageId == "throttle_response" }
        assertEquals(StageOutcome.NOT_EXERCISED, response.outcome)
        assertEquals(false, response.stimulusObserved)
    }

    @Test
    fun `moving throttle response can be assessed`() {
        val result = ThrottlePedalAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.throttlePedal,
                stages = listOf(
                    stage(
                        "warm_idle",
                        StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.THROTTLE to { _: Double -> 2.0 },
                            Signals.PEDAL_D to { _: Double -> 3.9 },
                            Signals.PEDAL_E to { _: Double -> 2.0 },
                        ),
                    ),
                    stage(
                        "throttle_response",
                        StageKind.FREE_REV,
                        values = mapOf(
                            Signals.RPM to { t: Double -> 800.0 + (t * 80.0).coerceAtMost(1_500.0) },
                            Signals.THROTTLE to { t: Double -> 2.0 + (t * 1.0).coerceAtMost(25.0) },
                            Signals.PEDAL_D to { t: Double -> 3.9 + (t * 1.0).coerceAtMost(25.0) },
                            Signals.PEDAL_E to { t: Double -> 2.0 + (t * 1.0).coerceAtMost(25.0) },
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.stages.first { it.stageId == "throttle_response" }.stimulusObserved == true)
    }

    @Test
    fun `mode 06 unit mismatch is unverified and excluded from failures`() {
        // Catalyst B1 threshold voltage reported with a count unit (UAS 0x24).
        val payload = intArrayOf(0x46, 0x21, 0x01, 0x24, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x05)
        val parsed = Mode06Parser.parse(payload, requestedMid = 0x21, source = null)
        assertEquals(1, parsed.size)
        val row = parsed.first()
        assertFalse(row.mappingTrusted)
        assertNull(row.passed)
        assertTrue(row.label.startsWith("Unidentified"))
        assertTrue(row.unit == "count")
    }

    @Test
    fun `mode 06 matching unit is trusted and can fail`() {
        // Catalyst B1 threshold voltage in volts, below the ECU minimum.
        val payload = intArrayOf(0x46, 0x21, 0x01, 0x0C, 0x00, 0x0A, 0x00, 0x1E, 0x00, 0x64)
        val row = Mode06Parser.parse(payload, requestedMid = 0x21, source = null).first()
        assertTrue(row.mappingTrusted)
        assertEquals(false, row.passed)
        assertEquals("V", row.unit)
    }

    @Test
    fun `mode 06 parses every block in a multi-block response`() {
        val payload = "46 01 01 0C 00 41 00 1E 00 64 01 05 10 00 48 00 00 00 64 01 0C 01 00 00 00 00 00 05"
            .split(" ").map { it.toInt(16) }.toIntArray()
        val rows = Mode06Parser.parse(payload, requestedMid = 0x01, source = null)
        assertEquals(3, rows.size)
        assertEquals(0x01, rows[0].tid)
        assertEquals(0x05, rows[1].tid)
        assertEquals(0x0C, rows[2].tid)
    }

    @Test
    fun `mode 06 not requested is not zero failures`() {
        val scan = ScanContext()
        assertFalse(scan.mode06CountsAvailable)
        assertEquals(MonitorDataState.NOT_REQUESTED, scan.mode06State)
        assertEquals(0, scan.mode06Failures)
    }

    @Test
    fun `catalyst safety gate blocks every danger combination`() {
        for (engineHealthy in listOf(false, true)) {
            for (fuelLevelOk in listOf(false, true)) {
                for (flashing in listOf(false, true)) {
                    val gate = CatalystWizard.safetyGate(
                        engineHealthy = engineHealthy,
                        fuelLevelOk = fuelLevelOk,
                        flashingMil = flashing,
                    )
                    if (flashing) {
                        assertFalse("Flashing MIL must always block", gate.canProceed)
                        assertNotNull(gate.blockedReason)
                    } else if (!engineHealthy || !fuelLevelOk) {
                        assertFalse(gate.canProceed)
                        assertNotNull(gate.blockedReason)
                    } else {
                        assertTrue(gate.canProceed)
                        assertNull(gate.blockedReason)
                    }
                }
            }
        }
        val misfire = CatalystWizard.safetyGate(
            engineHealthy = true,
            fuelLevelOk = true,
            flashingMil = false,
            activeMisfireCodes = listOf("P0301"),
        )
        assertFalse(misfire.canProceed)
        assertTrue(misfire.blockedReason!!.contains("P0301"))
    }

    @Test
    fun `interrupted symptom marker is preserved as invalidated evidence`() {
        val definition = DiagnosticRegistry.intermittentPowerLoss
        val capabilities = VehicleCapabilities(
            supportedPids = allPids,
            profile = VehicleProfile(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED),
            adapterVoltageAvailable = true,
        )
        val runner = GuidedTestRunner(
            definition = definition,
            capabilities = capabilities,
            prerequisites = PrerequisiteAnswers(safeLocationConfirmed = true, passengerPresent = true),
        )
        runner.start(0)
        runner.sample(Signals.RPM, 2_000.0, 100)
        runner.sample(Signals.LOAD, 50.0, 100)
        runner.sample(Signals.SPEED, 60.0, 100)
        runner.tick(100)
        runner.tick(2_500)
        runner.tick(3_000)
        assertEquals(GuidedRunState.CAPTURING, runner.snapshot(3_000).state)
        runner.markSymptom(3_500)
        assertEquals(1, runner.snapshot(3_600).markerCount)
        runner.pause()
        val paused = runner.snapshot(3_700)
        assertEquals(0, paused.markerCount)
        assertEquals(1, paused.invalidatedMarkerCount)
        assertTrue(paused.message.contains("discarded", ignoreCase = true))
    }

    @Test
    fun `oxygen response without a stimulus is not called healthy`() {
        val result = OxygenResponseAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.oxygenResponse,
                stages = listOf(
                    stage(
                        "warm_idle",
                        StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.THROTTLE to { _: Double -> 5.0 },
                            Signals.O2_B1S1 to { s: Double -> if (s.toInt() % 2 == 0) 0.1 else 0.9 },
                        ),
                    ),
                    stage(
                        "throttle_response",
                        StageKind.FREE_REV,
                        values = mapOf(
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                            Signals.THROTTLE to { _: Double -> 5.0 },
                            Signals.O2_B1S1 to { s: Double -> if (s.toInt() % 2 == 0) 0.1 else 0.9 },
                        ),
                    ),
                ),
            ),
        )

        assertFalse(result.status == DiagnosticStatus.NO_ANOMALY_DETECTED)
        assertEquals(StageOutcome.NOT_EXERCISED, result.stages.first { it.stageId == "throttle_response" }.outcome)
    }

    @Test
    fun `map response stage is marked not exercised when nothing moves`() {
        val result = MapPlausibilityAnalyzer.analyze(
            input(
                definition = DiagnosticRegistry.mapPlausibility,
                stages = listOf(
                    stage(
                        "key_on_engine_off",
                        StageKind.KEY_ON_ENGINE_OFF,
                        values = mapOf(
                            Signals.MAP to { _: Double -> 101.0 },
                            Signals.RPM to { _: Double -> 0.0 },
                        ),
                    ),
                    stage(
                        "warm_idle",
                        StageKind.WARM_IDLE,
                        values = mapOf(
                            Signals.MAP to { _: Double -> 32.0 },
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.COOLANT to { _: Double -> 90.0 },
                        ),
                    ),
                    stage(
                        "throttle_response",
                        StageKind.FREE_REV,
                        values = mapOf(
                            Signals.MAP to { _: Double -> 32.0 },
                            Signals.RPM to { _: Double -> 800.0 },
                            Signals.THROTTLE to { _: Double -> 2.0 },
                        ),
                    ),
                ),
            ),
        )

        val response = result.stages.first { it.stageId == "throttle_response" }
        assertEquals(StageOutcome.NOT_EXERCISED, response.outcome)
        assertTrue(result.warnings.any { it.contains("not exercised", ignoreCase = true) })
    }
}
