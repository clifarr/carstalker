package com.carstalker.app

import com.carstalker.app.data.DEFAULT_DASHBOARD_SIZE
import com.carstalker.app.data.DefaultDashboardPids
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.MAX_DASHBOARD_PIDS
import com.carstalker.app.data.isSampleStale
import com.carstalker.app.data.movedDashboardPids
import com.carstalker.app.data.recommendedDashboardPids
import com.carstalker.app.data.replacedDashboardPids
import com.carstalker.app.data.toggledDashboardPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveParamsTest {

    @Test
    fun `automatic selection picks the six highest weighted parameters`() {
        val ids = recommendedDashboardPids(emptySet())

        assertEquals(
            listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, LiveKeys.ADAPTER_VOLTAGE),
            ids,
        )
        assertEquals(DEFAULT_DASHBOARD_SIZE, ids.size)
        assertEquals(ids, DefaultDashboardPids)
        assertEquals("adapter voltage is the sixth priority", LiveKeys.ADAPTER_VOLTAGE, ids.last())
    }

    @Test
    fun `unsupported parameters are replaced by the next best supported ones`() {
        // This car has no engine load, throttle, intake temperature or control module voltage.
        val supported = setOf(0x0C, 0x0D, 0x05, 0x0B, 0x10, 0x06, 0x07, 0x0E, 0x2F, 0x42)

        val ids = recommendedDashboardPids(supported)

        assertEquals(
            listOf(0x0C, 0x0D, 0x05, LiveKeys.ADAPTER_VOLTAGE, 0x2F, 0x10),
            ids,
        )
        assertFalse(0x04 in ids)
        assertFalse(0x11 in ids)
        assertFalse(0x42 in ids)
        assertFalse(0x0B in ids)
    }

    @Test
    fun `only available parameters are returned when fewer than six are supported`() {
        assertEquals(
            listOf(0x0C, 0x0D, LiveKeys.ADAPTER_VOLTAGE),
            recommendedDashboardPids(setOf(0x0C, 0x0D)),
        )
    }

    @Test
    fun `limit caps the automatic selection`() {
        assertEquals(
            listOf(0x0C, 0x0D, 0x05),
            recommendedDashboardPids(emptySet(), limit = 3),
        )
    }

    @Test
    fun `a dashboard cannot grow past the hard cap`() {
        val full = (0 until MAX_DASHBOARD_PIDS).toList()

        assertNull(toggledDashboardPids(full, 99))
        assertEquals(full.drop(1), toggledDashboardPids(full, 0))
        assertEquals(full.drop(1) + 99, toggledDashboardPids(full.drop(1), 99))
    }

    @Test
    fun `moving a value puts it before the drop target`() {
        assertEquals(
            listOf(0x0C, 0x05, 0x0D, 0x04),
            movedDashboardPids(listOf(0x0C, 0x0D, 0x05, 0x04), 0x0D, 0x04),
        )
        assertEquals(
            listOf(0x0C, 0x04, 0x0D, 0x05),
            movedDashboardPids(listOf(0x0C, 0x0D, 0x05, 0x04), 0x04, 0x0D),
        )
        assertNull(movedDashboardPids(listOf(0x0C), 0x0D, 0x0C))
    }

    @Test
    fun `replacing keeps the position and refuses duplicates`() {
        assertEquals(
            listOf(0x0C, 0x0B, 0x05),
            replacedDashboardPids(listOf(0x0C, 0x0D, 0x05), 0x0D, 0x0B),
        )
        assertNull(replacedDashboardPids(listOf(0x0C, 0x0D, 0x05), 0x0D, 0x0C))
    }

    @Test
    fun `a parameter is stale only after it has missed its own sample window`() {
        assertFalse(isSampleStale(lastSampleAt = 0L, now = 60_000L))
        assertFalse(isSampleStale(lastSampleAt = 10_000L, now = 19_999L))
        assertTrue(isSampleStale(lastSampleAt = 10_000L, now = 20_001L))
        assertTrue(isSampleStale(lastSampleAt = 1_000L, now = 2_500L, thresholdMs = 1_000L))
    }
}
