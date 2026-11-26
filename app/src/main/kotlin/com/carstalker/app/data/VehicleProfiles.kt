package com.carstalker.app.data

import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.FuelType

/**
 * Switches to [vehicleKey]'s stored profile. A car never inherits the previous car's fuel,
 * aspiration or nickname, even when both fall back to the same "unknown" key.
 */
fun AppSettings.withVehicleProfile(vehicleKey: String): AppSettings {
    if (vehicleKey.isBlank()) return this
    val stored = vehicleProfiles[vehicleKey]
    val targetFuel = stored?.fuelType ?: FuelType.UNKNOWN
    val targetAspiration = stored?.aspiration ?: Aspiration.UNKNOWN
    if (profileVehicleKey == vehicleKey &&
        fuelType == targetFuel &&
        aspiration == targetAspiration
    ) {
        return this
    }
    return copy(
        profileVehicleKey = vehicleKey,
        fuelType = targetFuel,
        aspiration = targetAspiration,
    )
}

/**
 * Repairs the simulated demo car's profile once after the old global fallback could copy a real
 * car's name and fuel type onto it. Later demo edits are preserved.
 */
fun AppSettings.withDemoProfile(vehicleKey: String): AppSettings {
    if (demoProfileSanitized) return this
    return copy(
        vehicleProfiles = vehicleProfiles + (
            vehicleKey to VehicleProfileEntry(
                fuelType = FuelType.PETROL,
                aspiration = Aspiration.NATURALLY_ASPIRATED,
            )
        ),
        demoProfileSanitized = true,
        fuelType = FuelType.PETROL,
        aspiration = Aspiration.NATURALLY_ASPIRATED,
    )
}
