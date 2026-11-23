package com.carstalker.app

import com.carstalker.app.data.DEFAULT_DASHBOARD_SIZE
import com.carstalker.app.data.DefaultDashboardPids
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.recommendedDashboardPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveParamsTest {

    @Test
    fun `automatic selection picks the ten highest weighted parameters`() {
        val ids = recommendedDashboardPids(emptySet())

        assertEquals(
            listOf(0x0C, 0x0D, 0x05, 0x04, LiveKeys.ADAPTER_VOLTAGE, 0x11, 0x2F, 0x0F, 0x10, 0x0B),
            ids,
        )
        assertEquals(DEFAULT_DASHBOARD_SIZE, ids.size)
        assertEquals(ids, DefaultDashboardPids)
    }

    @Test
    fun `unsupported parameters are replaced by the next best supported ones`() {
        // This car has no engine load, throttle, intake temperature or control module voltage.
        val supported = setOf(0x0C, 0x0D, 0x05, 0x0B, 0x10, 0x06, 0x07, 0x0E, 0x2F, 0x42)

        val ids = recommendedDashboardPids(supported)

        assertEquals(
            listOf(0x0C, 0x0D, 0x05, LiveKeys.ADAPTER_VOLTAGE, 0x2F, 0x10, 0x0B, 0x06, 0x07, 0x0E),
            ids,
        )
        assertFalse(0x04 in ids)
        assertFalse(0x11 in ids)
        assertFalse(0x42 in ids)
    }

    @Test
    fun `only available parameters are returned when fewer than ten are supported`() {
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
}
