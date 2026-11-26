package com.carstalker.app

import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.DashboardConfig
import com.carstalker.app.data.DefaultDashboardConfig
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.VehicleProfileEntry
import com.carstalker.app.data.blankDashboard
import com.carstalker.app.data.migratePedalInterpolation
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.FuelType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigTest {

    @Test
    fun `blank dashboard starts empty and without gauges`() {
        val dashboard = blankDashboard("volts", "Voltage watch")
        assertTrue(dashboard.isBlank)
        assertFalse(dashboard.showGauges)
        assertTrue(dashboard.resolvePids(emptySet()).isEmpty())
    }

    @Test
    fun `automatic dashboard resolves recommended values`() {
        val settings = AppSettings()
        assertTrue(settings.activeDashboard.automatic)
        val pids = settings.resolvedDashboardPids(emptySet())
        assertTrue(pids.isNotEmpty())
        assertTrue(LiveKeys.ADAPTER_VOLTAGE in pids)
    }

    @Test
    fun `active dashboard is resolved by id and falls back safely`() {
        val voltage = DashboardConfig(
            id = "volts",
            name = "Voltage",
            pids = listOf(LiveKeys.ADAPTER_VOLTAGE),
            automatic = false,
            showGauges = false,
        )
        val settings = AppSettings(
            dashboards = listOf(DefaultDashboardConfig, voltage),
            activeDashboardId = "volts",
        )
        assertEquals(voltage, settings.activeDashboard)
        assertEquals(listOf(LiveKeys.ADAPTER_VOLTAGE), settings.resolvedDashboardPids(emptySet()))

        val missing = settings.copy(activeDashboardId = "gone")
        assertEquals(DefaultDashboardConfig, missing.activeDashboard)
    }

    @Test
    fun `vehicle profile entry serializes for settings storage`() {
        val entry = VehicleProfileEntry(FuelType.PETROL, Aspiration.NATURALLY_ASPIRATED)
        val encoded = Json.encodeToString(mapOf("VIN" to entry))
        val decoded = Json.decodeFromString<Map<String, VehicleProfileEntry>>(encoded)
        assertEquals(entry, decoded["VIN"])
    }

    @Test
    fun `pedal interpolation is per dashboard and off by default`() {
        val dashboard = DashboardConfig(id = "plain", name = "Plain")
        assertFalse(dashboard.pedalInterpolation)

        val encoded = Json.encodeToString(dashboard.copy(pedalInterpolation = true))
        val decoded = Json.decodeFromString<DashboardConfig>(encoded)
        assertTrue(decoded.pedalInterpolation)
    }

    @Test
    fun `legacy global pedal switch migrates only when a calibration exists`() {
        val dashboards = listOf(DefaultDashboardConfig, blankDashboard("blank", "Blank"))

        assertTrue(
            migratePedalInterpolation(dashboards, legacyEnabled = true, hasCalibration = true)
                .all { it.pedalInterpolation },
        )
        assertFalse(
            migratePedalInterpolation(dashboards, legacyEnabled = true, hasCalibration = false)
                .any { it.pedalInterpolation },
        )
        assertFalse(
            migratePedalInterpolation(dashboards, legacyEnabled = false, hasCalibration = true)
                .any { it.pedalInterpolation },
        )
        assertFalse(
            migratePedalInterpolation(dashboards, legacyEnabled = null, hasCalibration = true)
                .any { it.pedalInterpolation },
        )
    }

    @Test
    fun `manual dashboard keeps its own parameter list`() {
        val dashboard = DashboardConfig(
            id = "custom",
            name = "Custom",
            pids = listOf(0x0C, 0x05),
            automatic = false,
        )
        assertEquals(listOf(0x0C, 0x05), dashboard.resolvePids(setOf(0x0C)))
    }
}
