package com.carstalker.core

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.diagnose.CatalystAnswers
import com.carstalker.core.diagnose.CatalystInput
import com.carstalker.core.diagnose.CatalystWizard
import com.carstalker.core.diagnose.Confidence
import com.carstalker.core.diagnose.Likelihood
import com.carstalker.core.diagnose.SignalAnalysis
import com.carstalker.core.obd.Mode06Report
import com.carstalker.core.obd.Mode06Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CatalystWizardTest {

    private fun capture(
        upstream: (Double) -> Double,
        downstream: (Double) -> Double,
        durationMs: Long = 20_000,
        hz: Double = 10.0,
    ): CaptureData {
        val points = mutableListOf<CapturePoint>()
        val samples = (durationMs / 1000.0 * hz).toInt()
        val start = 1_000L
        for (index in 0 until samples) {
            val timeMs = start + (index * 1000.0 / hz).toLong()
            val seconds = (timeMs - start) / 1000.0
            points += CapturePoint(CatalystWizard.UPSTREAM_PID, timeMs, upstream(seconds))
            points += CapturePoint(CatalystWizard.DOWNSTREAM_PID, timeMs, downstream(seconds))
        }
        return CaptureData(
            points = points,
            requestedPids = listOf(
                CatalystWizard.UPSTREAM_PID,
                CatalystWizard.DOWNSTREAM_PID,
                CatalystWizard.RPM_PID,
                CatalystWizard.COOLANT_PID,
            ),
            startedAt = start,
            endedAt = start + durationMs,
        )
    }

    private fun square(seconds: Double, delay: Double = 0.0): Double {
        val phase = (((seconds - delay) % 1.0) + 1.0) % 1.0
        return if (phase < 0.5) 0.90 else 0.10
    }

    private fun mode06(passed: Boolean?): Mode06Report = Mode06Report(
        results = listOf(
            Mode06Result(
                mid = 0x21,
                midName = "Catalyst B1",
                tid = 0x01,
                tidName = "Catalyst efficiency",
                uas = 0x24,
                unit = "count",
                value = if (passed == false) 10.0 else 2.0,
                min = 0.0,
                max = if (passed == false) 5.0 else 20.0,
                passed = passed,
                source = null,
            ),
        ),
        readAt = 1,
    )

    private fun input(
        capture: CaptureData?,
        mode06: Mode06Report = Mode06Report(),
        codes: List<String> = listOf("P0420"),
        answers: CatalystAnswers = CatalystAnswers(engineHealthyConfirmed = true, fuelLevelOk = true),
    ) = CatalystInput(codes, mode06, capture, answers)

    @Test
    fun `bad catalyst mirrors upstream and fails mode 06`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { square(it, delay = 0.12) }),
                mode06 = mode06(false),
            ),
        )

        assertEquals("catalyst_worn", result.primaryCause?.id)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.summary.contains("catalyst", ignoreCase = true))
        assertTrue(result.warnings.any { it.contains("second", ignoreCase = true) })
    }

    @Test
    fun `healthy catalyst stays steady and passes mode 06`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { 0.65 + 0.02 * sin(it * 2 * PI * 0.1) }),
                mode06 = mode06(true),
            ),
        )

        assertEquals("catalyst_ok", result.primaryCause?.id)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.summary.contains("healthy", ignoreCase = true))
    }

    @Test
    fun `stuck downstream sensor is blamed before the converter`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { 0.05 }),
                mode06 = mode06(false),
            ),
        )

        assertEquals("downstream_o2_sensor", result.primaryCause?.id)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.warnings.any { it.contains("sensor", ignoreCase = true) })
    }

    @Test
    fun `misfire code stops the catalyst diagnosis`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { square(it, delay = 0.12) }),
                mode06 = mode06(false),
                codes = listOf("P0420", "P0301"),
            ),
        )

        assertEquals("engine_root_cause", result.primaryCause?.id)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.warnings.any { it.contains("misfire", ignoreCase = true) })
    }

    @Test
    fun `exhaust leak stops the catalyst diagnosis`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { square(it, delay = 0.12) }),
                mode06 = mode06(false),
                answers = CatalystAnswers(
                    engineHealthyConfirmed = true,
                    fuelLevelOk = true,
                    exhaustLeakSuspected = com.carstalker.core.diagnose.Answer.YES,
                ),
            ),
        )

        assertEquals("exhaust_leak", result.primaryCause?.id)
        assertTrue(result.summary.contains("exhaust", ignoreCase = true))
    }

    @Test
    fun `mode 06 fail with a steady downstream is conflicting evidence`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { 0.65 }),
                mode06 = mode06(false),
            ),
        )

        assertTrue(result.causes.any { it.id == "conflicting_evidence" })
        assertTrue(result.summary.contains("disagree", ignoreCase = true))
    }

    @Test
    fun `mode 06 fail without a capture is possible not confirmed`() {
        val result = CatalystWizard.analyze(input(capture = null, mode06 = mode06(false)))

        assertTrue(result.causes.any { it.id == "catalyst_possible" })
        assertTrue(result.summary.contains("did not confirm", ignoreCase = true))
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `missing capture is inconclusive`() {
        val result = CatalystWizard.analyze(input(capture = null, mode06 = Mode06Report()))

        assertEquals("inconclusive", result.primaryCause?.id)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.nextSteps.any { it.contains("re-run", ignoreCase = true) })
    }

    @Test
    fun `signal analysis measures switching and correlation`() {
        val upstream = capture({ square(it) }, { square(it) }).series(CatalystWizard.UPSTREAM_PID)
        val downstream = capture(
            { square(it) },
            { square(it, delay = 0.05) },
            hz = 20.0,
        ).series(CatalystWizard.DOWNSTREAM_PID)
        val stats = SignalAnalysis.stats(upstream)
        val correlation = SignalAnalysis.correlation(upstream, downstream)

        assertTrue(stats.switchingCount >= 30)
        assertTrue(stats.frequencyHz > 0.8)
        assertTrue(stats.amplitude > 0.7)
        assertTrue(correlation != null && correlation > 0.5)
    }

    @Test
    fun `cause ordering puts likely causes first`() {
        val result = CatalystWizard.analyze(
            input(
                capture = capture({ square(it) }, { square(it, delay = 0.12) }),
                mode06 = mode06(false),
            ),
        )
        val likelihoods = result.causes.map { it.likelihood }
        assertEquals(Likelihood.LIKELY, likelihoods.first())
    }
}
