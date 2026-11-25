package com.carstalker.app

import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.DefaultDashboardPids
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.dashboardParam
import com.carstalker.app.data.migrateDashboardPids
import com.carstalker.app.data.toPid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomPidTest {

    @Test
    fun `builds decodable pid from formula`() {
        val pid = CustomPid(
            pidHex = "0B",
            name = "Boost",
            shortName = "Boost",
            unit = "kPa",
            min = 0.0,
            max = 250.0,
            decimals = 1,
            formula = "A",
        ).toPid()

        assertEquals(0x0B, pid!!.id)
        assertEquals("Boost", pid.name)
        assertEquals(101.0, pid.decode(intArrayOf(101))!!, 0.001)
        assertEquals(true, pid.isCustom)
    }

    @Test
    fun `uses only the bytes the formula needs`() {
        val oneByte = CustomPid("0B", "A", "A", "kPa", formula = "A").toPid()!!
        val twoBytes = CustomPid("0C", "B", "B", "rpm", formula = "(A*256+B)/4").toPid()!!

        assertEquals(101.0, oneByte.decode(intArrayOf(101))!!, 0.001)
        assertEquals(1726.0, twoBytes.decode(intArrayOf(0x1A, 0xF8))!!, 0.001)
    }

    @Test
    fun `adapter voltage is its own parameter, not PID 42`() {
        val adapter = dashboardParam(LiveKeys.ADAPTER_VOLTAGE)!!
        assertEquals(-1, adapter.id)
        assertEquals("V", adapter.unit)
        assertEquals("Battery", adapter.shortName)
        // It is a built-in measurement, not a user-defined PID.
        assertEquals(false, adapter.isCustom)

        // Real PID 42 stays a normal, selectable OBD parameter.
        val moduleVoltage = dashboardParam(0x42)!!
        assertEquals(0x42, moduleVoltage.id)
        assertEquals("Control module voltage", moduleVoltage.name)
    }

    @Test
    fun `default dashboard uses the adapter measurement instead of PID 42`() {
        assertEquals(true, LiveKeys.ADAPTER_VOLTAGE in DefaultDashboardPids)
        assertEquals(false, 0x42 in DefaultDashboardPids)
    }

    @Test
    fun `pinned PID 42 from older builds migrates to the adapter measurement`() {
        assertEquals(
            listOf(LiveKeys.ADAPTER_VOLTAGE, 0x0C),
            migrateDashboardPids(listOf(0x42, 0x0C)),
        )
        assertEquals(listOf(0x0C), migrateDashboardPids(listOf(0x0C)))
    }

    @Test
    fun `rejects invalid definitions`() {
        assertNull(CustomPid("00", "Zero", "Zero", "", formula = "A").toPid())
        assertNull(CustomPid("GG", "Bad", "Bad", "", formula = "A").toPid())
        assertNull(CustomPid("0B", "Bad formula", "Bad", "", formula = "A+").toPid())
    }
}
