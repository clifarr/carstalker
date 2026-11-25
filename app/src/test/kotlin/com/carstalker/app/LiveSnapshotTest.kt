package com.carstalker.app

import com.carstalker.app.service.LiveSnapshot
import com.carstalker.app.service.freshnessAt
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSnapshotTest {

    @Test
    fun `an adapter-only dashboard ignores a leftover ecu timestamp`() {
        val snapshot = LiveSnapshot(
            lastEcuAt = 1_000,
            lastUpdatedAt = 20_000,
            ecuExpected = false,
        )

        assertEquals(20_000L, snapshot.freshnessAt())
    }

    @Test
    fun `an ecu dashboard uses the ecu timestamp`() {
        val snapshot = LiveSnapshot(
            lastEcuAt = 30_000,
            lastUpdatedAt = 31_000,
            ecuExpected = true,
        )

        assertEquals(30_000L, snapshot.freshnessAt())
    }

    @Test
    fun `before the first ecu sample the adapter timestamp bridges the wait`() {
        val snapshot = LiveSnapshot(
            lastEcuAt = 0,
            lastUpdatedAt = 12_000,
            ecuExpected = true,
        )

        assertEquals(12_000L, snapshot.freshnessAt())
    }

    @Test
    fun `an empty snapshot has no freshness`() {
        assertEquals(0L, LiveSnapshot().freshnessAt())
    }
}
