package com.carstalker.core

import com.carstalker.core.dtc.DtcDecoder
import com.carstalker.core.obd.MonitorStatusParser
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.obd.PidSupport
import com.carstalker.core.obd.FormulaParser
import com.carstalker.core.vehicle.Mode09Decoder
import com.carstalker.core.vehicle.Vin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecodingTest {

    @Test
    fun `decodes rpm`() {
        val rpm = PidCatalog.find(0x0C)!!.decode(intArrayOf(0x1A, 0xF8))
        assertEquals(1726.0, rpm!!, 0.001)
    }

    @Test
    fun `decodes speed coolant and voltage`() {
        assertEquals(50.0, PidCatalog.find(0x0D)!!.decode(intArrayOf(0x32))!!, 0.001)
        assertEquals(83.0, PidCatalog.find(0x05)!!.decode(intArrayOf(0x7B))!!, 0.001)
        assertEquals(12.345, PidCatalog.find(0x42)!!.decode(intArrayOf(0x30, 0x39))!!, 0.001)
    }

    @Test
    fun `decodes fuel trim with negative values`() {
        assertEquals(-25.0, PidCatalog.find(0x06)!!.decode(intArrayOf(0x60))!!, 0.001)
        assertEquals(0.0, PidCatalog.find(0x06)!!.decode(intArrayOf(0x80))!!, 0.001)
    }

    @Test
    fun `rejects truncated frames`() {
        assertNull(PidCatalog.find(0x0C)!!.decode(intArrayOf(0x1A)))
    }

    @Test
    fun `decodes throttle and pedal channels`() {
        // SAE J1979: A * 100 / 255 for PIDs 11, 45, 47, 49 and 4A.
        assertEquals(0.0, PidCatalog.find(0x11)!!.decode(intArrayOf(0x00))!!, 0.001)
        assertEquals(100.0, PidCatalog.find(0x11)!!.decode(intArrayOf(0xFF))!!, 0.001)
        assertEquals(50.2, PidCatalog.find(0x11)!!.decode(intArrayOf(0x80))!!, 0.01)
        assertEquals(29.8, PidCatalog.find(0x45)!!.decode(intArrayOf(0x4C))!!, 0.01)
        assertEquals(29.8, PidCatalog.find(0x47)!!.decode(intArrayOf(0x4C))!!, 0.01)
        assertEquals(29.8, PidCatalog.find(0x49)!!.decode(intArrayOf(0x4C))!!, 0.01)
    }

    @Test
    fun `decodes supported pid bitmap`() {
        val supported = PidSupport.decode(0x00, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(0x01 in supported)
        assertTrue(0x0C in supported)
        assertTrue(0x20 in supported)
        assertFalse(0x02 in supported)
        assertFalse(0x1D in supported)
    }

    @Test
    fun `decodes dtc codes`() {
        val codes = DtcDecoder.decode(intArrayOf(0x01, 0x33, 0x03, 0x01, 0x00, 0x00))
        assertEquals(listOf("P0133", "P0301"), codes.map { it.code })
        assertTrue(codes.first().description.contains("O2"))
    }

    @Test
    fun `decodes chassis and network codes`() {
        assertEquals("C0035", DtcDecoder.decode(intArrayOf(0x40, 0x35)).single().code)
        assertEquals("U0100", DtcDecoder.decode(intArrayOf(0xC1, 0x00)).single().code)
        assertEquals("B0100", DtcDecoder.decode(intArrayOf(0x81, 0x00)).single().code)
    }

    @Test
    fun `decodes readiness monitors`() {
        val status = MonitorStatusParser.parse(intArrayOf(0x82, 0x07, 0x65, 0x04))!!
        assertTrue(status.milOn)
        val misfire = status.monitors.first { it.name == "Misfire" }
        assertTrue(misfire.supported)
        assertFalse(misfire.complete)
        assertTrue(status.monitors.first { it.name == "Components" }.complete)
        assertFalse(status.monitors.first { it.name == "Catalyst" }.supported)
    }

    @Test
    fun `evaluates custom pid formulas`() {
        assertEquals(1726.0, FormulaParser.compile("(A*256+B)/4").evaluate(intArrayOf(0x1A, 0xF8)), 0.001)
        assertEquals(0.0, FormulaParser.compile("A/2-64").evaluate(intArrayOf(0x80)), 0.001)
        assertEquals(7.0, FormulaParser.compile("min(A,10)+max(B,3)").evaluate(intArrayOf(4, 3)), 0.001)
        assertEquals(4.0, FormulaParser.compile("sqrt(A)").evaluate(intArrayOf(16)), 0.001)
        assertEquals(5.0, FormulaParser.compile("abs(A-10)").evaluate(intArrayOf(5)), 0.001)
        assertEquals(3.0, FormulaParser.compile("ceil(A/2)").evaluate(intArrayOf(5)), 0.001)
        assertTrue(FormulaParser.isValid("((A*256)+B)/100"))
        assertEquals(1, FormulaParser.compile("A/2").requiredBytes)
        assertEquals(2, FormulaParser.compile("(A*256+B)/4").requiredBytes)
        assertEquals(4, FormulaParser.compile("A+B+C+D").requiredBytes)
        assertFalse(FormulaParser.isValid("A+"))
        assertFalse(FormulaParser.isValid("nonsense(A)"))
    }

    @Test
    fun `decodes vin across frames`() {
        val vin = "1HGCM82633A004352"
        val messages = listOf(
            intArrayOf(0x49, 0x02, 0x01) + vin.take(4).map { it.code }.toIntArray(),
            intArrayOf(0x49, 0x02, 0x02) + vin.drop(4).map { it.code }.toIntArray(),
        )
        assertEquals(vin, Mode09Decoder.decodeVin(messages))
    }

    @Test
    fun `derives model year from vin`() {
        assertEquals(2003, Vin.modelYear("1HGCM82633A004352", currentYear = 2026))
        assertEquals(2015, Vin.modelYear("1HGCM8263FA004352", currentYear = 2026))
        assertNotNull(Vin.modelYear("1HGCM82633A004352"))
        assertTrue(Vin.isValid("1HGCM82633A004352"))
    }
}

class UnitsTest {

    @Test
    fun `converts metric values for imperial display`() {
        val speed = com.carstalker.core.obd.PidCatalog.find(0x0D)!!
        val coolant = com.carstalker.core.obd.PidCatalog.find(0x05)!!
        val boost = com.carstalker.core.obd.PidCatalog.find(0x0B)!!

        val mph = com.carstalker.core.obd.Units.display(speed, 100.0, com.carstalker.core.obd.UnitSystem.IMPERIAL)
        assertEquals(62.1371, mph.value, 0.001)
        assertEquals("mph", mph.unit)

        val fahrenheit = com.carstalker.core.obd.Units.display(coolant, 90.0, com.carstalker.core.obd.UnitSystem.IMPERIAL)
        assertEquals(194.0, fahrenheit.value, 0.001)
        assertEquals("°F", fahrenheit.unit)

        val psi = com.carstalker.core.obd.Units.display(boost, 100.0, com.carstalker.core.obd.UnitSystem.IMPERIAL)
        assertEquals(14.5038, psi.value, 0.001)
        assertEquals("psi", psi.unit)

        val metric = com.carstalker.core.obd.Units.display(speed, 100.0, com.carstalker.core.obd.UnitSystem.METRIC)
        assertEquals(100.0, metric.value, 0.001)
        assertEquals("km/h", metric.unit)
    }
}

class ElmProtocolsTest {

    @Test
    fun `maps automatic and manual protocol codes`() {
        assertEquals("ISO 15765-4 CAN (11-bit, 500k) (auto)", com.carstalker.core.elm.ElmProtocols.name("A6"))
        assertEquals("ISO 15765-4 CAN (11-bit, 500k)", com.carstalker.core.elm.ElmProtocols.name("6"))
        assertEquals("ISO 9141-2 (auto)", com.carstalker.core.elm.ElmProtocols.name("A3"))
        assertEquals("Automatic (searching)", com.carstalker.core.elm.ElmProtocols.name("A0"))
        assertEquals("Automatic (searching)", com.carstalker.core.elm.ElmProtocols.name("0"))
        assertEquals("Unknown", com.carstalker.core.elm.ElmProtocols.name(""))
    }
}
