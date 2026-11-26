package com.carstalker.core

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.obd.PedalChannelCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedalChannelCheckTest {

    private fun capture(vararg series: Pair<Int, List<Double>>): CaptureData {
        val points = series.flatMap { (pid, values) ->
            values.mapIndexed { index, value -> CapturePoint(pid, index * 100L, value) }
        }
        val end = (series.maxOfOrNull { it.second.size } ?: 0) * 100L
        return CaptureData(points, series.map { it.first }, 0L, end)
    }

    @Test
    fun `pedal channels that move are recommended over a static throttle plate`() {
        val result = PedalChannelCheck.analyze(
            capture(
                0x11 to listOf(16.0, 16.0, 16.0),
                0x45 to listOf(93.0, 93.0, 93.0),
                0x47 to listOf(15.0, 15.0),
                0x49 to listOf(0.0, 55.0, 92.0),
                0x4A to listOf(2.0, 45.0, 80.0),
            ),
        )

        assertEquals(listOf(0x49, 0x4A), result.recommended)
        assertEquals(2, result.accelerator.size)
        assertTrue(result.throttlePlate.isEmpty())
        assertTrue(result.summary.contains("Pedal"))
    }

    @Test
    fun `both pedal and throttle are recommended when both move`() {
        val result = PedalChannelCheck.analyze(
            capture(
                0x11 to listOf(10.0, 40.0, 70.0),
                0x49 to listOf(0.0, 50.0, 90.0),
            ),
        )

        assertEquals(listOf(0x49, 0x11), result.recommended)
        assertEquals(0x49, result.recommendedPedal)
        assertEquals(0x11, result.recommendedThrottle)
        assertTrue(result.summary.contains("both", ignoreCase = true))
    }

    @Test
    fun `a car that only reports the throttle plate recommends that channel`() {
        val result = PedalChannelCheck.analyze(
            capture(
                0x11 to listOf(10.0, 40.0, 70.0),
                0x49 to listOf(0.0, 0.0, 0.0),
                0x4A to listOf(0.0, 0.0, 0.0),
            ),
        )

        assertEquals(listOf(0x11), result.recommended)
        assertEquals(1, result.throttlePlate.size)
    }

    @Test
    fun `no movement is reported honestly`() {
        val result = PedalChannelCheck.analyze(
            capture(
                0x11 to listOf(16.0, 16.1),
                0x49 to listOf(0.0, 0.1),
            ),
        )

        assertTrue(result.recommended.isEmpty())
        assertTrue(result.summary.contains("No channel moved"))
    }

    @Test
    fun `a single sample is not treated as movement`() {
        val result = PedalChannelCheck.analyze(capture(0x49 to listOf(80.0)))

        assertEquals(0.0, result.readings.single().range, 0.0)
        assertTrue(result.recommended.isEmpty())
    }

    @Test
    fun `release and full press are captured for interpolation`() {
        // 10 s capture: 0–2 s released at 12 %, then fully pressed at 88 %.
        val points = buildList {
            for (t in 0L..2_000L step 100L) add(CapturePoint(0x49, t, 12.0))
            for (t in 2_100L..10_000L step 100L) add(CapturePoint(0x49, t, 88.0))
        }
        val result = PedalChannelCheck.analyze(
            CaptureData(points, listOf(0x49), startedAt = 0L, endedAt = 10_000L),
        )

        val reading = result.readings.single()
        assertEquals(12.0, reading.releasedValue, 0.001)
        assertEquals(88.0, reading.pressedValue, 0.001)
        assertTrue(reading.needsInterpolation)
        assertEquals(76.0, reading.calibrationSpan, 0.001)
    }

    @Test
    fun `a channel that already spans zero to one hundred needs no interpolation`() {
        val points = buildList {
            for (t in 0L..2_000L step 100L) add(CapturePoint(0x49, t, 0.0))
            for (t in 2_100L..10_000L step 100L) add(CapturePoint(0x49, t, 100.0))
        }
        val result = PedalChannelCheck.analyze(
            CaptureData(points, listOf(0x49), startedAt = 0L, endedAt = 10_000L),
        )

        assertTrue(!result.readings.single().needsInterpolation)
    }
}

class PedalDashboardApplyTest {

    private val bothMoved = PedalChannelCheck.analyze(
        CaptureData(
            points = listOf(
                CapturePoint(0x11, 0L, 10.0),
                CapturePoint(0x11, 100L, 60.0),
                CapturePoint(0x49, 0L, 0.0),
                CapturePoint(0x49, 100L, 90.0),
            ),
            requestedPids = listOf(0x11, 0x49),
            startedAt = 0L,
            endedAt = 100L,
        ),
    )

    private val honda = PedalChannelCheck.analyze(
        CaptureData(
            points = listOf(
                CapturePoint(0x11, 0L, 16.0),
                CapturePoint(0x11, 100L, 16.0),
                CapturePoint(0x49, 0L, 0.0),
                CapturePoint(0x49, 100L, 90.0),
            ),
            requestedPids = listOf(0x11, 0x49),
            startedAt = 0L,
            endedAt = 100L,
        ),
    )

    @Test
    fun `a static throttle plate on the dashboard is replaced, not duplicated`() {
        val base = listOf(0x0C, 0x11, 0x0D)
        val replace = PedalChannelCheck.staticThrottlePlateOn(base, honda)

        assertEquals(0x11, replace)
        assertEquals(
            listOf(0x0C, 0x49, 0x0D),
            PedalChannelCheck.applyToDashboard(base, 0x49, replace, max = 10),
        )
    }

    @Test
    fun `a channel that moved is not replaced`() {
        val moving = PedalChannelCheck.analyze(
            CaptureData(
                points = listOf(CapturePoint(0x11, 0L, 10.0), CapturePoint(0x11, 100L, 60.0)),
                requestedPids = listOf(0x11),
                startedAt = 0L,
                endedAt = 100L,
            ),
        )

        assertEquals(null, PedalChannelCheck.staticThrottlePlateOn(listOf(0x11), moving))
    }

    @Test
    fun `a full dashboard without a static plate cannot take another value`() {
        val base = (1..10).toList()

        assertEquals(null, PedalChannelCheck.applyToDashboard(base, 0x49, null, max = 10))
        assertEquals(base, PedalChannelCheck.applyToDashboard(base, 0x03, null, max = 10))
        assertEquals(
            listOf(0x49, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            PedalChannelCheck.applyToDashboard(base, 0x49, replacePid = 1, max = 10),
        )
    }

    @Test
    fun `both recommendations are added without duplicating`() {
        val update = PedalChannelCheck.applyRecommendations(
            base = listOf(0x0C, 0x0D),
            pids = listOf(0x49, 0x11),
            result = bothMoved,
            max = 10,
        )

        assertEquals(listOf(0x0C, 0x0D, 0x49, 0x11), update.pids)
        assertEquals(listOf(0x49, 0x11), update.added)
        assertTrue(update.replaced.isEmpty())
        assertTrue(update.skipped.isEmpty())
    }

    @Test
    fun `both recommendations replace a static throttle plate`() {
        val update = PedalChannelCheck.applyRecommendations(
            base = listOf(0x0C, 0x45, 0x0D),
            pids = listOf(0x49, 0x11),
            result = bothMoved,
            max = 10,
        )

        assertEquals(listOf(0x0C, 0x49, 0x0D, 0x11), update.pids)
        assertEquals(listOf(0x49, 0x11), update.added)
        assertEquals(listOf(0x45), update.replaced)
    }

    @Test
    fun `a full dashboard reports both recommendations as skipped`() {
        val update = PedalChannelCheck.applyRecommendations(
            base = (1..10).toList(),
            pids = listOf(0x49, 0x11),
            result = bothMoved,
            max = 10,
        )

        assertTrue(update.added.isEmpty())
        assertEquals(listOf(0x49, 0x11), update.skipped)
    }

    @Test
    fun `a value already on the dashboard is left alone`() {
        val base = listOf(0x49, 0x11)

        assertEquals(base, PedalChannelCheck.applyToDashboard(base, 0x49, replacePid = 0x11, max = 10))
    }
}
