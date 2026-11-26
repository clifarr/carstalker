package com.carstalker.app

import com.carstalker.app.data.SAMPLING_STALL_THRESHOLD_MS
import com.carstalker.app.data.samplingGapMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingStallTest {

    @Test
    fun `a gap under the threshold is a slow cycle, not a stall`() {
        assertNull(samplingGapMs(lastProgressAt = 1_000, now = 1_000 + SAMPLING_STALL_THRESHOLD_MS - 1))
    }

    @Test
    fun `a gap at the threshold is reported`() {
        assertEquals(
            SAMPLING_STALL_THRESHOLD_MS,
            samplingGapMs(lastProgressAt = 1_000, now = 1_000 + SAMPLING_STALL_THRESHOLD_MS),
        )
    }

    @Test
    fun `a frozen process reports the real gap length`() {
        assertEquals(42_000L, samplingGapMs(lastProgressAt = 1_000, now = 43_000))
    }

    @Test
    fun `the first cycle and a backwards clock are not stalls`() {
        assertNull(samplingGapMs(lastProgressAt = 0, now = 60_000))
        assertNull(samplingGapMs(lastProgressAt = 60_000, now = 59_000))
    }
}
