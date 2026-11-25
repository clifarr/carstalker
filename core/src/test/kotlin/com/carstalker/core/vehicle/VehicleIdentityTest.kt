package com.carstalker.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleIdentityTest {

    @Test
    fun `empty identity is explicitly unidentified`() {
        val identity = VehicleIdentity()
        assertFalse(identity.isKnown)
        assertEquals("Unidentified vehicle", identity.displayName)
    }

    @Test
    fun `calibration or ECU name is a vehicle, not a guessed model`() {
        assertEquals("Vehicle", VehicleIdentity(calibrationId = "D2Q0A00A").displayName)
        assertEquals("Vehicle", VehicleIdentity(ecuName = "ECM-ENG").displayName)
        assertEquals("Vehicle", VehicleIdentity(cvn = listOf("12345678")).displayName)
    }

    @Test
    fun `known VIN still produces the friendly name`() {
        val identity = VehicleIdentity(vin = "JF1ZCAC10D2000001")
        assertTrue(identity.isKnown)
        assertTrue(identity.displayName.contains("Subaru"))
    }
}
