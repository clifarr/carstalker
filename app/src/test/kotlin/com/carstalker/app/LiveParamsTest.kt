package com.carstalker.app

import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.DEFAULT_DASHBOARD_SIZE
import com.carstalker.app.data.DashboardConfig
import com.carstalker.app.data.DefaultDashboardConfig
import com.carstalker.app.data.DefaultDashboardPids
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.MAX_DASHBOARD_PIDS
import com.carstalker.app.data.PedalCalibration
import com.carstalker.app.data.applyPedalCalibration
import com.carstalker.app.data.calibratedPedalPids
import com.carstalker.app.data.hasPedalCalibration
import com.carstalker.app.data.lastVehicleLabel
import com.carstalker.app.data.isSampleStale
import com.carstalker.app.data.movedDashboardPids
import com.carstalker.app.data.otherSupportedPids
import com.carstalker.app.data.recommendedDashboardPids
import com.carstalker.app.data.replacedDashboardPids
import com.carstalker.app.data.toggledDashboardPids
import com.carstalker.app.data.VehicleProfileEntry
import com.carstalker.app.transport.demo.DemoSupportedPids
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

    private fun pedalDashboard(enabled: Boolean = true): DashboardConfig =
        DefaultDashboardConfig.copy(pedalInterpolation = enabled)

    @Test
    fun `pedal calibration maps the released and pressed readings to zero and one hundred`() {
        val settings = AppSettings(
            dashboards = listOf(pedalDashboard()),
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 12.0, pressedValue = 88.0, vehicleKey = "unknown"),
            ),
        )

        assertEquals(0.0, applyPedalCalibration(0x49, 12.0, settings), 0.001)
        assertEquals(100.0, applyPedalCalibration(0x49, 88.0, settings), 0.001)
        assertEquals(50.0, applyPedalCalibration(0x49, 50.0, settings), 0.001)
        // Values outside the captured range are clamped instead of exceeding 0–100 %.
        assertEquals(0.0, applyPedalCalibration(0x49, 5.0, settings), 0.001)
        assertEquals(100.0, applyPedalCalibration(0x49, 95.0, settings), 0.001)
        // A channel without a calibration is left untouched.
        assertEquals(42.0, applyPedalCalibration(0x0C, 42.0, settings), 0.001)
    }

    @Test
    fun `pedal calibration is off when the dashboard has it off`() {
        val settings = AppSettings(
            dashboards = listOf(pedalDashboard(enabled = false)),
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 12.0, pressedValue = 88.0, vehicleKey = "unknown"),
            ),
        )

        assertEquals(12.0, applyPedalCalibration(0x49, 12.0, settings), 0.001)
    }

    @Test
    fun `pedal interpolation follows the active dashboard`() {
        val on = DashboardConfig(
            id = "on",
            name = "On",
            pids = listOf(0x49),
            pedalInterpolation = true,
        )
        val off = DashboardConfig(
            id = "off",
            name = "Off",
            pids = listOf(0x49),
            pedalInterpolation = false,
        )
        val settings = AppSettings(
            dashboards = listOf(on, off),
            activeDashboardId = on.id,
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 12.0, pressedValue = 88.0, vehicleKey = "unknown"),
            ),
        )

        assertEquals(50.0, applyPedalCalibration(0x49, 50.0, settings), 0.001)
        assertEquals(50.0, applyPedalCalibration(0x49, 50.0, settings.copy(activeDashboardId = off.id)), 0.001)
    }

    @Test
    fun `last vehicle label hides an unidentified car`() {
        val settings = AppSettings(
            lastVin = null,
            profileVehicleKey = "unknown",
        )

        assertNull(settings.lastVehicleLabel())
    }

    @Test
    fun `last vehicle label ignores a VIN from another car`() {
        val settings = AppSettings(
            lastVin = "JF1ZCAC10D2000001",
            profileVehicleKey = "unknown",
        )

        assertNull(settings.lastVehicleLabel())
    }

    @Test
    fun `last vehicle label uses the VIN when it matches the profile`() {
        val vin = "JF1ZCAC10D2000001"
        val label = AppSettings(
            lastVin = vin,
            profileVehicleKey = vin,
        ).lastVehicleLabel()

        assertTrue(label != null)
        assertTrue(label!!.name.contains("Subaru"))
        assertNull(label.detail)
    }

    @Test
    fun `last vehicle label survives a disconnect`() {
        val vin = "JF1ZCAC10D2000001"
        val label = AppSettings(
            lastVin = vin,
            profileVehicleKey = vin,
        ).lastVehicleLabel()

        assertTrue(label != null)
        assertTrue(label!!.name.contains("Subaru"))
    }

    @Test
    fun `last vehicle label finds a nickname stored under the calibration id`() {
        val label = AppSettings(
            profileVehicleKey = "CAL-123",
            vehicleProfiles = mapOf(
                "CAL-123" to VehicleProfileEntry(nickname = "Honda Civic"),
            ),
        ).lastVehicleLabel()

        assertTrue(label != null)
        assertEquals("Honda Civic", label!!.name)
        assertNull(label.detail)
    }

    @Test
    fun `last vehicle label prefers the nickname and keeps the VIN as detail`() {
        val vin = "JF1ZCAC10D2000001"
        val label = AppSettings(
            lastVin = vin,
            profileVehicleKey = vin,
            vehicleProfiles = mapOf(
                vin to VehicleProfileEntry(nickname = "Honda Civic"),
            ),
        ).lastVehicleLabel()

        assertTrue(label != null)
        assertEquals("Honda Civic", label!!.name)
        assertTrue(label.detail!!.contains("Subaru"))
    }

    @Test
    fun `last vehicle label shows a nickname for a car without a VIN`() {
        val label = AppSettings(
            profileVehicleKey = "unknown",
            vehicleProfiles = mapOf(
                "unknown" to VehicleProfileEntry(nickname = "Honda Civic"),
            ),
        ).lastVehicleLabel()

        assertTrue(label != null)
        assertEquals("Honda Civic", label!!.name)
        assertNull(label.detail)
    }

    @Test
    fun `calibrated pedal channels are scoped to the current car`() {
        val settings = AppSettings(
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 12.0, pressedValue = 88.0, vehicleKey = "VIN-A"),
                PedalCalibration(0x4A, releasedValue = 10.0, pressedValue = 90.0, vehicleKey = "VIN-B"),
            ),
            profileVehicleKey = "VIN-A",
        )

        assertEquals(setOf(0x49), settings.calibratedPedalPids())
        assertTrue(settings.hasPedalCalibration())
        assertFalse(settings.copy(profileVehicleKey = "VIN-C").hasPedalCalibration())
    }

    @Test
    fun `pedal interpolation does nothing without a calibration`() {
        val settings = AppSettings(dashboards = listOf(pedalDashboard()))

        assertEquals(50.0, applyPedalCalibration(0x49, 50.0, settings), 0.001)
    }

    @Test
    fun `a calibration with no usable span is ignored`() {
        val settings = AppSettings(
            dashboards = listOf(pedalDashboard()),
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 10.0, pressedValue = 10.5, vehicleKey = "unknown"),
            ),
        )

        assertEquals(10.2, applyPedalCalibration(0x49, 10.2, settings), 0.001)
    }

    @Test
    fun `a calibration from another car is not applied`() {
        val settings = AppSettings(
            dashboards = listOf(pedalDashboard()),
            pedalCalibrations = listOf(
                PedalCalibration(0x49, releasedValue = 12.0, pressedValue = 88.0, vehicleKey = "OTHER-VIN"),
            ),
            profileVehicleKey = "unknown",
        )

        assertEquals(12.0, applyPedalCalibration(0x49, 12.0, settings), 0.001)
    }

    @Test
    fun `other supported pids exclude the catalogue custom definitions and range markers`() {
        val custom = CustomPid(
            pidHex = "91",
            name = "Unknown 0x91",
            shortName = "0x91",
            unit = "",
            formula = "A",
        )

        // 0x0C and 0xA6 are catalogue parameters, 0x91 is the custom one, 0x20 is a range marker
        // and 0x01/0x02 are read by the app itself (monitor status, freeze-frame DTC).
        val supported = setOf(0x01, 0x02, 0x0C, 0x20, 0x91, 0x9A, 0xA6)

        assertEquals(listOf(0x9A), otherSupportedPids(supported, listOf(custom)))
    }

    @Test
    fun `other supported pids is empty when support is not known yet`() {
        assertEquals(emptyList<Int>(), otherSupportedPids(emptySet()))
    }

    @Test
    fun `the demo car exposes only catalogue-less codes as other supported pids`() {
        // Decoded from the demo transport's own bitmaps, so the two cannot drift apart.
        val supported = DemoSupportedPids.decoded()

        // OBD standard, fuel type, absolute evap pressure and secondary O2 trim: all standard,
        // none in the catalogue. Readiness (01), range markers and known codes are filtered out.
        assertEquals(listOf(0x1C, 0x51, 0x53, 0x55), otherSupportedPids(supported))
    }
}
