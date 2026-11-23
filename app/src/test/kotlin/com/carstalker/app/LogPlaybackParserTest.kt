package com.carstalker.app

import com.carstalker.app.data.LogPlaybackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPlaybackParserTest {

    private val csv = sequenceOf(
        "timestamp_iso,pid,value,unit",
        "2026-11-22T10:00:00.000+01:00,Engine speed,800,rpm",
        "2026-11-22T10:00:00.000+01:00,Vehicle speed,0,km/h",
        "2026-11-22T10:00:00.500+01:00,Engine speed,1500,rpm",
        "2026-11-22T10:00:00.500+01:00,Vehicle speed,12,km/h",
        "2026-11-22T10:00:01.000+01:00,Engine speed,2500,rpm",
        "2026-11-22T10:00:01.000+01:00,Vehicle speed,28,km/h",
        "2026-11-22T10:00:01.500+01:00,\"Trim, short term\",-3.5,%",
    )

    @Test
    fun `parses series and duration`() {
        val data = LogPlaybackParser.parse(csv)
        assertEquals(3, data.series.size)
        assertEquals(1_500L, data.durationMs)
        assertEquals(7, data.sampleCount)

        val rpm = data.series.first { it.name == "Engine speed" }
        assertEquals(3, rpm.points.size)
        assertEquals(800.0, rpm.min, 0.001)
        assertEquals(2500.0, rpm.max, 0.001)
        assertEquals(1600.0, rpm.average, 0.001)
        assertEquals(1500.0, rpm.valueAt(data.startMs + 500)!!, 0.001)
        assertEquals(800.0, rpm.valueAt(data.startMs + 499)!!, 0.001)
        assertNull(rpm.valueAt(data.startMs - 1))
    }

    @Test
    fun `handles quoted names with commas`() {
        val data = LogPlaybackParser.parse(csv)
        val trim = data.series.first { it.name == "Trim, short term" }
        assertEquals("%", trim.unit)
        assertEquals(-3.5, trim.points.single().value, 0.001)
    }

    @Test
    fun `parses wide format with units in the header`() {
        val wide = sequenceOf(
            "# CarStalker log v2",
            "# started_at=2026-11-22T10:00:00.000+01:00",
            "# app_version=0.2.0",
            "# vin=JF1ZCAC10D2000001",
            "timestamp_iso,elapsed_s,Engine speed [rpm],Vehicle speed [km/h]",
            "2026-11-22T10:00:00.000+01:00,0.000,800,0",
            "2026-11-22T10:00:00.500+01:00,0.500,1500,12",
            "2026-11-22T10:00:01.000+01:00,1.000,2500,28",
        )
        val data = LogPlaybackParser.parse(wide)
        assertEquals(2, data.series.size)
        assertEquals(1_000L, data.durationMs)
        assertEquals(6, data.sampleCount)

        val rpm = data.series.first { it.name == "Engine speed" }
        assertEquals("rpm", rpm.unit)
        assertEquals(3, rpm.points.size)
        assertEquals(2500.0, rpm.max, 0.001)
        assertEquals(1500.0, rpm.valueAt(data.startMs + 500)!!, 0.001)
    }

    @Test
    fun `ignores malformed rows and empty input`() {
        val data = LogPlaybackParser.parse(
            sequenceOf(
                "timestamp_iso,pid,value,unit",
                "not-a-time,Engine speed,1000,rpm",
                "2026-11-22T10:00:00Z,Engine speed,not-a-number,rpm",
                "",
            ),
        )
        assertTrue(data.isEmpty)
        assertEquals(0, data.sampleCount)
    }
}
