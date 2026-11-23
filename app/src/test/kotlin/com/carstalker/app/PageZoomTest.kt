package com.carstalker.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.carstalker.app.ui.screens.PageZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageZoomTest {

    private val pageSize = IntSize(1000, 1000)
    private val center = Offset(500f, 500f)

    @Test
    fun zoomingKeepsTheContentUnderTheGestureCentroid() {
        val centroid = Offset(750f, 500f)

        val zoomed = PageZoom().transformed(centroid, Offset.Zero, zoom = 2f, viewSize = pageSize)

        assertEquals(2f, zoomed.scale, 0.0001f)
        assertEquals(-250f, zoomed.offset.x, 0.0001f)
        assertEquals(0f, zoomed.offset.y, 0.0001f)
        val pinned = center + (centroid - center) * zoomed.scale + zoomed.offset
        assertEquals(centroid.x, pinned.x, 0.0001f)
        assertEquals(centroid.y, pinned.y, 0.0001f)
    }

    @Test
    fun panningIsClampedToThePageBounds() {
        val zoomed = PageZoom().transformed(center, Offset.Zero, zoom = 2f, viewSize = pageSize)

        val panned = zoomed.transformed(center, Offset(5000f, -5000f), zoom = 1f, viewSize = pageSize)

        assertEquals(500f, panned.offset.x, 0.0001f)
        assertEquals(-500f, panned.offset.y, 0.0001f)
    }

    @Test
    fun zoomStaysWithinBounds() {
        val tooFar = PageZoom().transformed(center, Offset.Zero, zoom = 10f, viewSize = pageSize)
        assertEquals(4f, tooFar.scale, 0.0001f)
        assertTrue(tooFar.isZoomed)

        val tooClose = PageZoom().transformed(center, Offset.Zero, zoom = 0.25f, viewSize = pageSize)
        assertEquals(1f, tooClose.scale, 0.0001f)
        assertEquals(Offset.Zero, tooClose.offset)
        assertFalse(tooClose.isZoomed)
    }

    @Test
    fun gesturesBeforeThePageIsMeasuredAreIgnored() {
        val unchanged = PageZoom().transformed(
            centroid = Offset(10f, 10f),
            pan = Offset(20f, 20f),
            zoom = 2f,
            viewSize = IntSize.Zero,
        )

        assertEquals(PageZoom(), unchanged)
    }

    @Test
    fun nonFiniteGestureValuesAreIgnored() {
        val zoomed = PageZoom().transformed(center, Offset.Zero, zoom = 2f, viewSize = pageSize)

        assertEquals(zoomed, zoomed.transformed(Offset.Unspecified, Offset.Zero, zoom = 1f, viewSize = pageSize))
        assertEquals(zoomed, zoomed.transformed(center, Offset.Zero, zoom = Float.NaN, viewSize = pageSize))
        assertEquals(
            zoomed,
            zoomed.transformed(center, Offset(Float.NaN, 0f), zoom = 1f, viewSize = pageSize),
        )
    }
}
