package com.carstalker.core

import com.carstalker.core.obd.LiveDataFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDataFreshnessTest {

    @Test
    fun `an adapter that still answers but an ecu that does not goes offline`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(1_000)
        freshness.miss(1_500)

        assertFalse(freshness.isOffline(3_499, ecuExpected = true))
        assertTrue(freshness.isOffline(3_500, ecuExpected = true))
    }

    @Test
    fun `each ecu sample restarts the window`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(0)
        freshness.sample(2_000)
        freshness.miss(3_000)

        assertFalse(freshness.isOffline(4_499, ecuExpected = true))
        assertTrue(freshness.isOffline(4_500, ecuExpected = true))
    }

    @Test
    fun `a scheduling gap without a failed request is not an outage`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(0)
        freshness.sample(1_000)

        assertFalse(freshness.isOffline(9_000, ecuExpected = true))

        freshness.miss(9_000)
        assertTrue(freshness.isOffline(9_001, ecuExpected = true))
    }

    @Test
    fun `a voltage-only dashboard never goes offline`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(0)
        freshness.miss(1_000)

        assertFalse(freshness.isOffline(60_000, ecuExpected = false))
    }

    @Test
    fun `a new polling session gets a fresh grace window`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(0)
        freshness.sample(1_000)

        freshness.start(10_000)
        freshness.miss(10_500)

        assertFalse(freshness.isOffline(12_000, ecuExpected = true))
        assertTrue(freshness.isOffline(12_500, ecuExpected = true))
    }

    @Test
    fun `reset forgets the last sample`() {
        val freshness = LiveDataFreshness(timeoutMs = 2_500)
        freshness.start(0)
        freshness.sample(1_000)
        freshness.miss(1_100)
        freshness.reset()

        assertFalse(freshness.isOffline(60_000, ecuExpected = true))
    }
}
