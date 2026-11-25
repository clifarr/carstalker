package com.carstalker.app.ui.components

import kotlin.math.roundToLong

/**
 * Visible time window of a recorded chart, in the recording's absolute timestamps.
 *
 * Zoom and pan are expressed as fractions of the chart width so the same logic works for any
 * screen size and can be tested without a layout.
 */
internal data class ChartWindow(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    fun isZoomed(fullStartMs: Long, fullEndMs: Long): Boolean =
        durationMs < (fullEndMs - fullStartMs).coerceAtLeast(1L) - 1L

    /**
     * Applies one pinch/pan update. [centroidFraction] is the gesture centroid across the chart
     * (0 = left edge, 1 = right edge) and [panFraction] is the horizontal drag as a fraction of
     * the chart width. The time under the centroid stays pinned, and the result never leaves
     * the recording.
     */
    fun transformed(
        fullStartMs: Long,
        fullEndMs: Long,
        centroidFraction: Float,
        panFraction: Float,
        zoom: Float,
    ): ChartWindow {
        if (!centroidFraction.isFinite() || !panFraction.isFinite() || !zoom.isFinite() || zoom <= 0f) {
            return this
        }
        val fullSpan = (fullEndMs - fullStartMs).coerceAtLeast(1L)
        val currentSpan = durationMs.toDouble()
        val minSpan = (fullSpan.toDouble() / MAX_CHART_ZOOM).coerceAtLeast(1.0)
        val focus = centroidFraction.coerceIn(0f, 1f).toDouble()
        val newSpan = (currentSpan / zoom).coerceIn(minSpan, fullSpan.toDouble())
        val focusTime = startMs + focus * currentSpan
        // A rightward drag moves the content right, so the window shows earlier samples.
        val newStart = focusTime - focus * newSpan - panFraction * newSpan
        return of(newStart, newSpan, fullStartMs, fullSpan)
    }

    /** Keeps the current zoom and centres the window on [timeMs]. */
    fun centeredOn(timeMs: Long, fullStartMs: Long, fullEndMs: Long): ChartWindow {
        val fullSpan = (fullEndMs - fullStartMs).coerceAtLeast(1L)
        val span = durationMs.coerceIn(1L, fullSpan)
        return of(timeMs - span / 2.0, span.toDouble(), fullStartMs, fullSpan)
    }

    companion object {
        /** Fifty times zoom is enough to separate individual samples on a long drive. */
        const val MAX_CHART_ZOOM = 50f

        fun full(startMs: Long, endMs: Long): ChartWindow =
            ChartWindow(startMs, (endMs - startMs).coerceAtLeast(1L) + startMs)

        private fun of(
            start: Double,
            span: Double,
            fullStartMs: Long,
            fullSpan: Long,
        ): ChartWindow {
            val spanLong = span.roundToLong().coerceIn(1L, fullSpan)
            val startLong = start.roundToLong().coerceIn(fullStartMs, fullStartMs + fullSpan - spanLong)
            return ChartWindow(startLong, startLong + spanLong)
        }
    }
}
