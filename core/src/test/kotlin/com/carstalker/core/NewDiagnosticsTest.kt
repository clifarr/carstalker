package com.carstalker.core

import com.carstalker.core.dtc.DtcCatalog
import com.carstalker.core.dtc.DtcKnowledge
import com.carstalker.core.elm.ElmResponseParser
import com.carstalker.core.log.LogColumn
import com.carstalker.core.log.WideLogFormat
import com.carstalker.core.log.WideLogMeta
import com.carstalker.core.obd.Ecu
import com.carstalker.core.obd.FreezeFrameParser
import com.carstalker.core.obd.Mode06Parser
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.report.DiagnosticReport
import com.carstalker.core.report.ReportDtc
import com.carstalker.core.report.ReportMeasurement
import com.carstalker.core.report.ReportModule
import com.carstalker.core.report.ReportMonitor
import com.carstalker.core.report.ReportVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewDiagnosticsTest {

    @Test
    fun `parser keeps the can source address`() {
        val response = ElmResponseParser.parse(
            "7E8 04 41 0C 1A F8\r7E9 04 41 0D 32 00\r\r>",
        )
        assertEquals(2, response.messages.size)
        assertEquals(0x7E8, response.messages[0].source)
        assertEquals(0x7E9, response.messages[1].source)
        assertEquals(0x41, response.messages[0].payload[0])
        assertEquals(0x41, response.messages[1].payload[0])
    }

    @Test
    fun `parser keeps 29-bit source and payload`() {
        val response = ElmResponseParser.parse("18DAF110 04 41 0D 32 00\r\r>")
        assertEquals(0x18DAF110, response.messages.single().source)
        assertEquals(0x41, response.messages.single().payload[0])
    }

    @Test
    fun `freeze frame decodes dtc and values`() {
        val dtc = FreezeFrameParser.decodeDtc(intArrayOf(0x42, 0x02, 0x00, 0x03, 0x01), 0)
        assertEquals("P0301", dtc?.code)

        val rpmPid = PidCatalog.find(0x0C)!!
        val rpm = FreezeFrameParser.decodeValue(
            intArrayOf(0x42, 0x0C, 0x00, 0x1A, 0xF8),
            rpmPid,
            0,
        )
        assertEquals(1726.0, rpm!!, 0.001)
    }

    @Test
    fun `mode 06 decodes scaling limits and pass fail`() {
        val passing = intArrayOf(
            0x46,
            0x01, 0x01, 0x0A, 0x0B, 0xB0, 0x0B, 0xB0, 0x0B, 0xB0,
            0x01, 0x05, 0x10, 0x00, 0x48, 0x00, 0x00, 0x00, 0x64,
        )
        val results = Mode06Parser.parse(passing, 0x01, null)
        assertEquals(2, results.size)
        assertEquals(365.0, results[0].value, 0.1)
        assertEquals("mV", results[0].unit)
        assertEquals(true, results[0].passed)
        assertEquals(72.0, results[1].value, 0.001)
        assertEquals(true, results[1].passed)

        val failing = intArrayOf(
            0x46,
            0xA1, 0x0C, 0x24, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x05,
        )
        val fail = Mode06Parser.parse(failing, 0xA1, null).single()
        assertEquals(false, fail.passed)
        assertEquals(10.0, fail.value, 0.001)
        assertEquals(5.0, fail.max!!, 0.001)
    }

    @Test
    fun `mode 06 parses three blocks`() {
        val payload = intArrayOf(
            0x46,
            0x01, 0x01, 0x0A, 0x0B, 0xB0, 0x0B, 0xB0, 0x0B, 0xB0,
            0x01, 0x05, 0x10, 0x00, 0x48, 0x00, 0x00, 0x00, 0x64,
            0x01, 0x0C, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05,
        )
        assertEquals(3, Mode06Parser.parse(payload, 0x01, null).size)

        val parsed = ElmResponseParser.parse(
            "46 01 01 0A 0B B0 0B B0 0B B0 01 05 10 00 48 00 00 00 64 01 0C 24 00 00 00 00 00 05\r\r>",
        )
        assertEquals(28, parsed.messages.single().payload.size)
    }

    @Test
    fun `mode 06 handles signed uas`() {
        val payload = intArrayOf(
            0x46,
            0x01, 0x0C, 0x81, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00,
        )
        val result = Mode06Parser.parse(payload, 0x01, null).single()
        assertEquals(-1.0, result.value, 0.001)
        assertEquals(-1.0, result.min!!, 0.001)
    }

    @Test
    fun `generic dtc catalogue is loaded from the cc0 resource`() {
        assertTrue(DtcCatalog.genericCount > 9_000)
        assertEquals("Cylinder 1 Misfire Detected", DtcCatalog.describe("P0301"))
        assertEquals("Lost Communication With ECM/PCM A", DtcCatalog.describe("U0100"))
        assertTrue(DtcCatalog.isGeneric("P0301"))
        assertFalse(DtcCatalog.isGeneric("P1234"))
    }

    @Test
    fun `dtc knowledge gives causes and next checks`() {
        val guidance = DtcKnowledge.forCode("p0171")
        assertNotNull(guidance)
        assertTrue(guidance!!.causes.isNotEmpty())
        assertTrue(guidance.nextSteps.any { it.contains("fuel", ignoreCase = true) })
    }

    @Test
    fun `ecu names are generic and source based`() {
        assertEquals("Engine", Ecu.name(null))
        assertEquals("Engine", Ecu.name(0x7E8))
        assertEquals("ECU 0x7E9", Ecu.name(0x7E9))
        assertEquals("Engine", Ecu.name(0x18DAF110))
        assertEquals("ECU 0x18DAF120", Ecu.name(0x18DAF120))
        assertTrue(Ecu.isEngine(null))
        assertTrue(Ecu.isEngine(0x7E8))
        assertTrue(Ecu.isEngine(0x18DAF110))
        assertFalse(Ecu.isEngine(0x7E9))
    }

    @Test
    fun `wmi lookup identifies common makes`() {
        assertEquals("Subaru", com.carstalker.core.vehicle.Wmi.make("JF1ZCAC10D2000001"))
        assertEquals("Honda", com.carstalker.core.vehicle.Wmi.make("1HGCM82633A004352"))
        assertEquals("BMW", com.carstalker.core.vehicle.Wmi.make("WBA3A5C51DF000000"))
        assertEquals("Tesla", com.carstalker.core.vehicle.Wmi.make("5YJ3E1EA7KF000000"))
        assertEquals(null, com.carstalker.core.vehicle.Wmi.make("XXX00000000000000"))
    }

    @Test
    fun `vehicle identity combines year and make`() {
        val identity = com.carstalker.core.vehicle.VehicleIdentity(vin = "JF1ZCAC10D2000001")
        assertEquals("2013 Subaru", identity.displayName)
        assertEquals(2013, identity.modelYear)
        assertEquals("Subaru", identity.make)
    }

    @Test
    fun `wide log writes a preamble and one row per tick`() {
        val columns = listOf(
            LogColumn(0x0C, "RPM", "rpm"),
            LogColumn(0x0D, "Speed", "km/h"),
        )
        val meta = WideLogMeta(
            startedAt = 1_700_000_000_000,
            appVersion = "0.2.0",
            adapter = "OBDLink MX+",
            protocol = "ISO 15765-4 CAN",
            vin = "JF1ZCAC10D2000001",
            columns = columns,
        )
        val header = WideLogFormat.header(meta)
        assertTrue(header.startsWith(WideLogFormat.MARKER))
        assertTrue(header.contains("# vin=JF1ZCAC10D2000001"))
        assertTrue(header.contains("RPM [rpm]"))
        assertTrue(header.contains("Speed [km/h]"))

        val row = WideLogFormat.row(
            timestampMs = 1_700_000_001_500,
            elapsedMs = 1_500,
            columns = columns,
            values = mapOf(0x0C to 1726.0),
        )
        assertTrue(row.contains(",1.500,1726,"))
        assertTrue(row.endsWith(",\n"))
    }

    @Test
    fun `report text contains modules freeze frames and monitor results`() {
        val report = DiagnosticReport(
            generatedAt = 1_700_000_000_000,
            appVersion = "0.2.0",
            shopName = "Test Garage",
            vehicle = ReportVehicle(vin = "JF1ZCAC10D2000001", year = 2013),
            adapter = "Demo",
            protocol = "CAN",
            modules = listOf(
                ReportModule(
                    name = "Engine",
                    address = "0x7E8",
                    dtcs = listOf(ReportDtc("P0301", "Cylinder 1 misfire", "Stored")),
                ),
            ),
            freezeFrames = listOf(
                com.carstalker.core.report.ReportFreezeFrame(
                    frame = 0,
                    dtc = "P0301",
                    measurements = listOf(ReportMeasurement("Engine speed", "1726", "rpm")),
                ),
            ),
            monitors = listOf(ReportMonitor("Catalyst", "Ready")),
        )
        val text = report.toPlainText()
        assertTrue(text.contains("Test Garage"))
        assertTrue(text.contains("P0301"))
        assertTrue(text.contains("FREEZE FRAMES"))
        assertTrue(text.contains("Catalyst: Ready"))
    }

    @Test
    fun `DPF pressure PID decodes signed differential pressure`() {
        val pid = PidCatalog.find(0x7A)!!
        val positive = pid.decode(intArrayOf(0x01, 0x00, 0x64, 0x01, 0x2C, 0x00, 0xC8))
        assertEquals(1.0, positive!!, 0.001)

        val negative = pid.decode(intArrayOf(0x01, 0xFF, 0x9C, 0x01, 0x2C, 0x00, 0xC8))
        assertEquals(-1.0, negative!!, 0.001)
    }

    @Test
    fun `DPF temperature PID decodes the first supported sensor`() {
        val pid = PidCatalog.find(0x7C)!!
        val first = pid.decode(intArrayOf(0x01, 0x09, 0xC4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertEquals(210.0, first!!, 0.001)

        val second = pid.decode(intArrayOf(0x02, 0x00, 0x00, 0x0B, 0xB8, 0x00, 0x00, 0x00, 0x00))
        assertEquals(260.0, second!!, 0.001)
    }

}
