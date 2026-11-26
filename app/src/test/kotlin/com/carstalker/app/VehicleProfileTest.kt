package com.carstalker.app

import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.VehicleProfileEntry
import com.carstalker.app.data.withDemoProfile
import com.carstalker.app.data.withVehicleProfile
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {

    @Test
    fun `switching to an unknown car does not inherit the previous car settings`() {
        val honda = VehicleProfileEntry(
            fuelType = FuelType.DIESEL,
            aspiration = Aspiration.NATURALLY_ASPIRATED,
            nickname = "Honda Civic",
        )
        val settings = AppSettings(
            profileVehicleKey = "HONDA-CAL",
            fuelType = FuelType.DIESEL,
            aspiration = Aspiration.NATURALLY_ASPIRATED,
            vehicleProfiles = mapOf("HONDA-CAL" to honda),
        )

        val demo = settings.withVehicleProfile("DEMO-VIN")

        assertEquals("DEMO-VIN", demo.profileVehicleKey)
        assertEquals(FuelType.UNKNOWN, demo.fuelType)
        assertEquals(Aspiration.UNKNOWN, demo.aspiration)
        assertNull(demo.vehicleProfiles["DEMO-VIN"])
        assertEquals("Honda Civic", demo.vehicleProfiles["HONDA-CAL"]?.nickname)
    }

    @Test
    fun `switching to a car uses its own stored profile`() {
        val settings = AppSettings(
            profileVehicleKey = "HONDA-CAL",
            fuelType = FuelType.DIESEL,
            vehicleProfiles = mapOf(
                "DEMO-VIN" to VehicleProfileEntry(
                    fuelType = FuelType.PETROL,
                    aspiration = Aspiration.NATURALLY_ASPIRATED,
                    nickname = "Demo BRZ",
                ),
            ),
        )

        val demo = settings.withVehicleProfile("DEMO-VIN")

        assertEquals(FuelType.PETROL, demo.fuelType)
        assertEquals(Aspiration.NATURALLY_ASPIRATED, demo.aspiration)
        assertEquals("Demo BRZ", demo.vehicleProfiles["DEMO-VIN"]?.nickname)
    }

    @Test
    fun `reconnecting to the same unknown car reloads its stored profile`() {
        val settings = AppSettings(
            profileVehicleKey = "unknown",
            fuelType = FuelType.PETROL,
            aspiration = Aspiration.NATURALLY_ASPIRATED,
            vehicleProfiles = mapOf(
                "unknown" to VehicleProfileEntry(
                    fuelType = FuelType.DIESEL,
                    aspiration = Aspiration.FORCED_INDUCTION,
                    nickname = "Honda Civic",
                ),
            ),
        )

        val loaded = settings.withVehicleProfile("unknown")

        assertEquals(FuelType.DIESEL, loaded.fuelType)
        assertEquals(Aspiration.FORCED_INDUCTION, loaded.aspiration)
        assertEquals("Honda Civic", loaded.vehicleProfiles["unknown"]?.nickname)
    }

    @Test
    fun `the demo profile is repaired once and later edits are preserved`() {
        val polluted = AppSettings(
            profileVehicleKey = "DEMO-VIN",
            fuelType = FuelType.DIESEL,
            vehicleProfiles = mapOf(
                "DEMO-VIN" to VehicleProfileEntry(
                    fuelType = FuelType.DIESEL,
                    aspiration = Aspiration.NATURALLY_ASPIRATED,
                    nickname = "Honda Civic",
                ),
            ),
        )

        val repaired = polluted.withDemoProfile("DEMO-VIN")

        assertEquals(FuelType.PETROL, repaired.fuelType)
        assertEquals(Aspiration.NATURALLY_ASPIRATED, repaired.aspiration)
        assertNull(repaired.vehicleProfiles["DEMO-VIN"]?.nickname)
        assertTrue(repaired.demoProfileSanitized)

        val edited = repaired.copy(
            vehicleProfiles = repaired.vehicleProfiles + (
                "DEMO-VIN" to VehicleProfileEntry(
                    fuelType = FuelType.PETROL,
                    aspiration = Aspiration.NATURALLY_ASPIRATED,
                    nickname = "Demo BRZ",
                )
            ),
        )
        val laterConnect = edited.withDemoProfile("DEMO-VIN")
        assertEquals("Demo BRZ", laterConnect.vehicleProfiles["DEMO-VIN"]?.nickname)
    }
}
