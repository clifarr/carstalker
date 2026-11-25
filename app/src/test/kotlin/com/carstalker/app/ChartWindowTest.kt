package com.carstalker.app

import com.carstalker.app.ui.components.ChartWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartWindowTest {

    private val fullStart = 1_000L
    private val fullEnd = 11_000L

    @Test
    fun `zooming keeps the time under the centroid pinned`() {
        val window = ChartWindow.full(fullStart, fullEnd)

        val zoomed = window.transformed(fullStart, fullEnd, centroidFraction = 0.75f, panFraction = 0f, zoom = 2f)

        assertEquals(5_000L, zoomed.durationMs)
        assertEquals(4_750L, zoomed.startMs)
        assertEquals(9_750L, zoomed.endMs)
        // The sample at 75 % of the original window (8 500 ms) is still under the same finger.
        val pinned = zoomed.startMs + 0.75 * zoomed.durationMs
        assertEquals(8_500.0, pinned, 1.0)
    }

    @Test
    fun `panning moves the window and is clamped to the recording`() {
        val zoomed = ChartWindow.full(fullStart, fullEnd)
            .transformed(fullStart, fullEnd, centroidFraction = 0.5f, panFraction = 0f, zoom = 2f)

        val panned = zoomed.transformed(fullStart, fullEnd, centroidFraction = 0.5f, panFraction = 0.1f, zoom = 1f)
        assertEquals(zoomed.startMs - 500L, panned.startMs)

        val clamped = zoomed.transformed(fullStart, fullEnd, centroidFraction = 0.5f, panFraction = 100f, zoom = 1f)
        assertEquals(fullStart, clamped.startMs)
        assertEquals(zoomed.durationMs, clamped.durationMs)

        val clampedRight = zoomed.transformed(fullStart, fullEnd, centroidFraction = 0.5f, panFraction = -100f, zoom = 1f)
        assertEquals(fullEnd - zoomed.durationMs, clampedRight.startMs)
    }

    @Test
    fun `zoom stays between the full recording and the maximum zoom`() {
        val full = ChartWindow.full(fullStart, fullEnd)

        val tooFar = full.transformed(fullStart, fullEnd, 0.5f, 0f, zoom = 10_000f)
        assertEquals(200L, tooFar.durationMs) // 10 000 ms / 50

        val tooClose = full.transformed(fullStart, fullEnd, 0.5f, 0f, zoom = 0.01f)
        assertEquals(full, tooClose)
        assertFalse(tooClose.isZoomed(fullStart, fullEnd))
        assertTrue(tooFar.isZoomed(fullStart, fullEnd))
    }

    @Test
    fun `scrubbing recentres the window on the playhead`() {
        val zoomed = ChartWindow.full(fullStart, fullEnd)
            .transformed(fullStart, fullEnd, centroidFraction = 0f, panFraction = 0f, zoom = 4f)

        val centred = zoomed.centeredOn(6_000L, fullStart, fullEnd)

        assertEquals(zoomed.durationMs, centred.durationMs)
        assertEquals(6_000.0, centred.startMs + centred.durationMs / 2.0, 1.0)
    }

    @Test
    fun `non-finite gestures are ignored`() {
        val window = ChartWindow.full(fullStart, fullEnd)

        assertEquals(window, window.transformed(fullStart, fullEnd, Float.NaN, 0f, 2f))
        assertEquals(window, window.transformed(fullStart, fullEnd, 0.5f, Float.NaN, 2f))
        assertEquals(window, window.transformed(fullStart, fullEnd, 0.5f, 0f, Float.NaN))
        assertEquals(window, window.transformed(fullStart, fullEnd, 0.5f, 0f, 0f))
    }

    @Test
    fun `a single-sample recording still has a usable window`() {
        val window = ChartWindow.full(5_000L, 5_000L)
        assertEquals(1L, window.durationMs)
        assertEquals(5_000L, window.startMs)
        assertEquals(5_001L, window.endMs)
    }
}
