package com.carstalker.core.obd

import com.carstalker.core.obd.Pid.Category

/**
 * Curated catalogue of generic (SAE J1979) live-data PIDs that every OBD-II vehicle must
 * implement when supported. Descriptions follow the public standard.
 */
object PidCatalog {

    val standard: List<Pid> = listOf(
        Pid.of(0x03, "Fuel system status", "Fuel system", "", 0.0, 255.0, Category.FUEL, weight = 10) { it.a().toDouble() },
        Pid.of(0x04, "Calculated engine load", "Engine load", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 88) { it.a() * 100.0 / 255.0 },
        Pid.of(0x05, "Engine coolant temperature", "Coolant", "°C", -40.0, 215.0, Category.TEMPERATURE, weight = 95) { it.a() - 40.0 },
        Pid.of(0x06, "Short term fuel trim (bank 1)", "STFT B1", "%", -100.0, 99.2, Category.FUEL, 1, weight = 60) { it.a() * 100.0 / 128.0 - 100.0 },
        Pid.of(0x07, "Long term fuel trim (bank 1)", "LTFT B1", "%", -100.0, 99.2, Category.FUEL, 1, weight = 58) { it.a() * 100.0 / 128.0 - 100.0 },
        Pid.of(0x08, "Short term fuel trim (bank 2)", "STFT B2", "%", -100.0, 99.2, Category.FUEL, 1, weight = 6) { it.a() * 100.0 / 128.0 - 100.0 },
        Pid.of(0x09, "Long term fuel trim (bank 2)", "LTFT B2", "%", -100.0, 99.2, Category.FUEL, 1, weight = 5) { it.a() * 100.0 / 128.0 - 100.0 },
        Pid.of(0x0A, "Fuel pressure", "Fuel pressure", "kPa", 0.0, 765.0, Category.FUEL, weight = 36) { it.a() * 3.0 },
        Pid.of(0x0B, "Intake manifold pressure", "MAP", "kPa", 0.0, 255.0, Category.ENGINE, weight = 64) { it.a().toDouble() },
        Pid.of(0x0C, "Engine speed", "RPM", "rpm", 0.0, 8000.0, Category.ENGINE, 0, 2, weight = 100) { (it.a() * 256.0 + it.b()) / 4.0 },
        Pid.of(0x0D, "Vehicle speed", "Speed", "km/h", 0.0, 255.0, Category.DRIVETRAIN, weight = 98) { it.a().toDouble() },
        Pid.of(0x0E, "Timing advance", "Timing", "°", -64.0, 63.5, Category.ENGINE, 1, weight = 50) { it.a() / 2.0 - 64.0 },
        Pid.of(0x0F, "Intake air temperature", "Intake", "°C", -40.0, 215.0, Category.TEMPERATURE, weight = 72) { it.a() - 40.0 },
        Pid.of(0x10, "Mass air flow", "MAF", "g/s", 0.0, 655.35, Category.ENGINE, 2, 2, weight = 70) { (it.a() * 256.0 + it.b()) / 100.0 },
        Pid.of(0x11, "Throttle plate position", "Throttle", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 84) { it.a() * 100.0 / 255.0 },
        Pid.of(0x14, "O2 sensor B1S1 voltage", "O2 B1S1", "V", 0.0, 1.275, Category.EMISSIONS, 3, weight = 8) { it.a() / 200.0 },
        Pid.of(0x15, "O2 sensor B1S2 voltage", "O2 B1S2", "V", 0.0, 1.275, Category.EMISSIONS, 3, weight = 7) { it.a() / 200.0 },
        Pid.of(0x1F, "Run time since engine start", "Run time", "s", 0.0, 65535.0, Category.ENGINE, 0, 2, weight = 30) { it.a() * 256.0 + it.b() },
        Pid.of(0x21, "Distance traveled with MIL on", "MIL distance", "km", 0.0, 65535.0, Category.EMISSIONS, 0, 2, weight = 4) { it.a() * 256.0 + it.b() },
        Pid.of(0x22, "Fuel rail pressure (relative)", "Rail pressure", "kPa", 0.0, 5177.0, Category.FUEL, 1, 2, weight = 34) { (it.a() * 256.0 + it.b()) * 0.079 },
        Pid.of(0x24, "Wideband O2 B1S1 equivalence ratio", "λ B1S1", "λ", 0.0, 2.0, Category.EMISSIONS, 3, 2, weight = 9) { (it.a() * 256.0 + it.b()) / 32768.0 },
        Pid.of(0x25, "Wideband O2 B1S2 equivalence ratio", "λ B1S2", "λ", 0.0, 2.0, Category.EMISSIONS, 3, 2, weight = 7) { (it.a() * 256.0 + it.b()) / 32768.0 },
        Pid.of(0x23, "Fuel rail gauge pressure", "Rail gauge", "kPa", 0.0, 655350.0, Category.FUEL, 0, 2, weight = 11) { (it.a() * 256.0 + it.b()) * 10.0 },
        Pid.of(0x2C, "Commanded EGR", "EGR", "%", 0.0, 100.0, Category.EMISSIONS, 1, weight = 18) { it.a() * 100.0 / 255.0 },
        Pid.of(0x2D, "EGR error", "EGR error", "%", -100.0, 99.2, Category.EMISSIONS, 1, weight = 16) { it.a() * 100.0 / 128.0 - 100.0 },
        Pid.of(0x2E, "Commanded evaporative purge", "EVAP purge", "%", 0.0, 100.0, Category.EMISSIONS, 1, weight = 14) { it.a() * 100.0 / 255.0 },
        Pid.of(0x2F, "Fuel tank level", "Fuel level", "%", 0.0, 100.0, Category.FUEL, 1, weight = 80) { it.a() * 100.0 / 255.0 },
        Pid.of(0x30, "Warm-ups since codes cleared", "Warm-ups", "", 0.0, 255.0, Category.EMISSIONS, weight = 3) { it.a().toDouble() },
        Pid.of(0x31, "Distance since codes cleared", "Distance cleared", "km", 0.0, 65535.0, Category.EMISSIONS, 0, 2, weight = 2) { it.a() * 256.0 + it.b() },
        Pid.of(0x32, "EVAP system vapor pressure", "EVAP pressure", "Pa", -8192.0, 8191.75, Category.EMISSIONS, 1, 2, weight = 12) { (it.a() * 256.0 + it.b()) / 4.0 - 8192.0 },
        Pid.of(0x33, "Barometric pressure", "Baro", "kPa", 0.0, 255.0, Category.ENGINE, weight = 32) { it.a().toDouble() },
        Pid.of(0x34, "Wideband O2 B1S1 equivalence ratio (current)", "λ B1S1", "λ", 0.0, 2.0, Category.EMISSIONS, 3, 2, weight = 8) { (it.a() * 256.0 + it.b()) / 32768.0 },
        Pid.of(0x35, "Wideband O2 B1S2 equivalence ratio (current)", "λ B1S2", "λ", 0.0, 2.0, Category.EMISSIONS, 3, 2, weight = 6) { (it.a() * 256.0 + it.b()) / 32768.0 },
        Pid.of(0x3C, "Catalyst temperature B1S1", "Cat B1S1", "°C", -40.0, 6513.5, Category.EMISSIONS, 1, 2, weight = 10) { (it.a() * 256.0 + it.b()) / 10.0 - 40.0 },
        Pid.of(0x3D, "Catalyst temperature B1S2", "Cat B1S2", "°C", -40.0, 6513.5, Category.EMISSIONS, 1, 2, weight = 9) { (it.a() * 256.0 + it.b()) / 10.0 - 40.0 },
        Pid.of(0x3E, "Catalyst temperature B1S1 (bank 1 sensor 1)", "Cat B1S1", "°C", -40.0, 6513.5, Category.EMISSIONS, 1, 2, weight = 10) { (it.a() * 256.0 + it.b()) / 10.0 - 40.0 },
        Pid.of(0x3F, "Catalyst temperature B1S2 (bank 1 sensor 2)", "Cat B1S2", "°C", -40.0, 6513.5, Category.EMISSIONS, 1, 2, weight = 9) { (it.a() * 256.0 + it.b()) / 10.0 - 40.0 },
        Pid.of(0x42, "Control module voltage", "Battery", "V", 0.0, 65.535, Category.ELECTRICAL, 2, 2, weight = 66) { (it.a() * 256.0 + it.b()) / 1000.0 },
        Pid.of(0x43, "Absolute engine load", "Abs load", "%", 0.0, 25700.0, Category.ENGINE, 1, 2, weight = 40) { (it.a() * 256.0 + it.b()) * 100.0 / 255.0 },
        Pid.of(0x44, "Commanded equivalence ratio", "Equiv ratio", "λ", 0.0, 2.0, Category.FUEL, 3, 2, weight = 38) { (it.a() * 256.0 + it.b()) / 32768.0 },
        Pid.of(0x45, "Throttle plate position (relative)", "Rel throttle", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 46) { it.a() * 100.0 / 255.0 },
        Pid.of(0x46, "Ambient air temperature", "Ambient", "°C", -40.0, 215.0, Category.TEMPERATURE, weight = 52) { it.a() - 40.0 },
        Pid.of(0x47, "Throttle plate position B", "Throttle B", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 24) { it.a() * 100.0 / 255.0 },
        Pid.of(0x4D, "Time run with MIL on", "MIL time", "min", 0.0, 65535.0, Category.EMISSIONS, 0, 2, weight = 3) { it.a() * 256.0 + it.b() },
        Pid.of(0x4E, "Time since trouble codes cleared", "Since cleared", "min", 0.0, 65535.0, Category.EMISSIONS, 0, 2, weight = 3) { it.a() * 256.0 + it.b() },
        Pid.of(0x49, "Accelerator pedal D (main)", "Pedal", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 44) { it.a() * 100.0 / 255.0 },
        Pid.of(0x4A, "Accelerator pedal E (secondary)", "Pedal 2", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 22) { it.a() * 100.0 / 255.0 },
        Pid.of(0x4C, "Commanded throttle actuator", "Cmd throttle", "%", 0.0, 100.0, Category.ENGINE, 1, weight = 26) { it.a() * 100.0 / 255.0 },
        Pid.of(0x5C, "Engine oil temperature", "Oil temp", "°C", -40.0, 215.0, Category.TEMPERATURE, weight = 56) { it.a() - 40.0 },
        Pid.of(0x5E, "Engine fuel rate", "Fuel rate", "L/h", 0.0, 3276.75, Category.FUEL, 2, 2, weight = 54) { (it.a() * 256.0 + it.b()) / 20.0 },
        Pid.of(0x61, "Driver's demand engine torque", "Demand torque", "%", -125.0, 130.0, Category.ENGINE, weight = 28) { it.a() - 125.0 },
        Pid.of(0x62, "Actual engine torque", "Actual torque", "%", -125.0, 130.0, Category.ENGINE, weight = 42) { it.a() - 125.0 },
        Pid.of(0x63, "Engine reference torque", "Ref torque", "Nm", 0.0, 65535.0, Category.ENGINE, 0, 2, weight = 20) { it.a() * 256.0 + it.b() },
        Pid.of(0x7A, "DPF differential pressure bank 1", "DPF ΔP B1", "kPa", -327.68, 327.67, Category.EMISSIONS, 2, 7, weight = 3) {
            val raw = (it.getOrElse(1) { 0 } shl 8) or it.getOrElse(2) { 0 }
            val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
            signed / 100.0
        },
        Pid.of(0x7B, "DPF differential pressure bank 2", "DPF ΔP B2", "kPa", -327.68, 327.67, Category.EMISSIONS, 2, 7, weight = 2) {
            val raw = (it.getOrElse(1) { 0 } shl 8) or it.getOrElse(2) { 0 }
            val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
            signed / 100.0
        },
        Pid.of(0x7C, "DPF temperature", "DPF temp", "°C", -40.0, 6513.5, Category.EMISSIONS, 1, 9, weight = 3) {
            val flags = it.getOrElse(0) { 0 }
            val index = (0..3).firstOrNull { bit -> flags and (1 shl bit) != 0 } ?: 0
            val offset = 1 + index * 2
            val raw = (it.getOrElse(offset) { 0 } shl 8) or it.getOrElse(offset + 1) { 0 }
            raw / 10.0 - 40.0
        },
        Pid.of(0xA6, "Odometer", "Odometer", "km", 0.0, 429496729.5, Category.DRIVETRAIN, 1, 4, weight = 48) {
            (it.a() * 16_777_216.0 + it.b() * 65_536.0 + it.c() * 256.0 + it.d()) / 10.0
        },
    )

    private val byId: Map<Int, Pid> = standard.associateBy { it.id }

    fun find(id: Int): Pid? = byId[id]

}
