package com.carstalker.core.dtc

/** Human-readable descriptions for the most common generic (SAE J1979) trouble codes. */
object DtcCatalog {

    private val descriptions: Map<String, String> = buildMap {
        fun add(code: String, text: String) { put(code, text) }

        add("P0010", "Camshaft position actuator circuit, bank 1")
        add("P0011", "Camshaft timing over-advanced, bank 1")
        add("P0012", "Camshaft timing over-retarded, bank 1")
        add("P0016", "Crankshaft/camshaft position correlation, bank 1 sensor A")
        add("P0030", "O2 sensor heater control circuit, B1S1")
        add("P0031", "O2 sensor heater circuit low, B1S1")
        add("P0032", "O2 sensor heater circuit high, B1S1")
        add("P0036", "O2 sensor heater control circuit, B1S2")
        add("P0037", "O2 sensor heater circuit low, B1S2")
        add("P0038", "O2 sensor heater circuit high, B1S2")
        add("P0100", "Mass air flow circuit malfunction")
        add("P0101", "Mass air flow range/performance")
        add("P0102", "Mass air flow circuit low input")
        add("P0103", "Mass air flow circuit high input")
        add("P0106", "Manifold pressure/barometric range/performance")
        add("P0107", "Manifold pressure circuit low input")
        add("P0108", "Manifold pressure circuit high input")
        add("P0110", "Intake air temperature circuit malfunction")
        add("P0111", "Intake air temperature range/performance")
        add("P0112", "Intake air temperature circuit low input")
        add("P0113", "Intake air temperature circuit high input")
        add("P0115", "Engine coolant temperature circuit malfunction")
        add("P0116", "Engine coolant temperature range/performance")
        add("P0117", "Engine coolant temperature circuit low input")
        add("P0118", "Engine coolant temperature circuit high input")
        add("P0120", "Throttle position sensor A circuit malfunction")
        add("P0121", "Throttle position sensor A range/performance")
        add("P0122", "Throttle position sensor A circuit low input")
        add("P0123", "Throttle position sensor A circuit high input")
        add("P0125", "Insufficient coolant temperature for closed loop")
        add("P0128", "Coolant thermostat below regulating temperature")
        add("P0130", "O2 sensor circuit malfunction, B1S1")
        add("P0131", "O2 sensor circuit low voltage, B1S1")
        add("P0132", "O2 sensor circuit high voltage, B1S1")
        add("P0133", "O2 sensor circuit slow response, B1S1")
        add("P0134", "O2 sensor circuit no activity, B1S1")
        add("P0135", "O2 sensor heater circuit, B1S1")
        add("P0136", "O2 sensor circuit malfunction, B1S2")
        add("P0137", "O2 sensor circuit low voltage, B1S2")
        add("P0138", "O2 sensor circuit high voltage, B1S2")
        add("P0139", "O2 sensor circuit slow response, B1S2")
        add("P0140", "O2 sensor circuit no activity, B1S2")
        add("P0141", "O2 sensor heater circuit, B1S2")
        add("P0150", "O2 sensor circuit malfunction, B2S1")
        add("P0151", "O2 sensor circuit low voltage, B2S1")
        add("P0152", "O2 sensor circuit high voltage, B2S1")
        add("P0153", "O2 sensor circuit slow response, B2S1")
        add("P0154", "O2 sensor circuit no activity, B2S1")
        add("P0155", "O2 sensor heater circuit, B2S1")
        add("P0156", "O2 sensor circuit malfunction, B2S2")
        add("P0170", "Fuel trim malfunction, bank 1")
        add("P0171", "System too lean, bank 1")
        add("P0172", "System too rich, bank 1")
        add("P0174", "System too lean, bank 2")
        add("P0175", "System too rich, bank 2")
        add("P0200", "Injector circuit malfunction")
        add("P0201", "Injector circuit, cylinder 1")
        add("P0202", "Injector circuit, cylinder 2")
        add("P0203", "Injector circuit, cylinder 3")
        add("P0204", "Injector circuit, cylinder 4")
        add("P0230", "Fuel pump primary circuit malfunction")
        add("P0231", "Fuel pump secondary circuit low")
        add("P0232", "Fuel pump secondary circuit high")
        add("P0261", "Cylinder 1 injector circuit low")
        add("P0262", "Cylinder 1 injector circuit high")
        add("P0300", "Random/multiple cylinder misfire detected")
        add("P0301", "Cylinder 1 misfire detected")
        add("P0302", "Cylinder 2 misfire detected")
        add("P0303", "Cylinder 3 misfire detected")
        add("P0304", "Cylinder 4 misfire detected")
        add("P0305", "Cylinder 5 misfire detected")
        add("P0306", "Cylinder 6 misfire detected")
        add("P0307", "Cylinder 7 misfire detected")
        add("P0308", "Cylinder 8 misfire detected")
        add("P0325", "Knock sensor 1 circuit malfunction")
        add("P0330", "Knock sensor 2 circuit malfunction")
        add("P0335", "Crankshaft position sensor A circuit malfunction")
        add("P0336", "Crankshaft position sensor A range/performance")
        add("P0340", "Camshaft position sensor A circuit, bank 1")
        add("P0341", "Camshaft position sensor A range/performance")
        add("P0350", "Ignition coil primary/secondary circuit malfunction")
        add("P0351", "Ignition coil A primary/secondary circuit")
        add("P0352", "Ignition coil B primary/secondary circuit")
        add("P0353", "Ignition coil C primary/secondary circuit")
        add("P0354", "Ignition coil D primary/secondary circuit")
        add("P0400", "Exhaust gas recirculation flow malfunction")
        add("P0401", "EGR flow insufficient")
        add("P0402", "EGR flow excessive")
        add("P0403", "EGR control circuit malfunction")
        add("P0404", "EGR range/performance")
        add("P0405", "EGR sensor A circuit low")
        add("P0406", "EGR sensor A circuit high")
        add("P0410", "Secondary air injection system malfunction")
        add("P0411", "Secondary air injection incorrect flow")
        add("P0420", "Catalyst efficiency below threshold, bank 1")
        add("P0421", "Warm-up catalyst efficiency below threshold, bank 1")
        add("P0430", "Catalyst efficiency below threshold, bank 2")
        add("P0440", "Evaporative emission system malfunction")
        add("P0441", "Evaporative emission incorrect purge flow")
        add("P0442", "Evaporative emission small leak detected")
        add("P0443", "Evaporative emission purge control valve circuit")
        add("P0446", "Evaporative emission vent control circuit")
        add("P0455", "Evaporative emission large leak detected")
        add("P0456", "Evaporative emission very small leak detected")
        add("P0460", "Fuel level sensor circuit malfunction")
        add("P0461", "Fuel level sensor range/performance")
        add("P0462", "Fuel level sensor circuit low")
        add("P0463", "Fuel level sensor circuit high")
        add("P0480", "Cooling fan 1 control circuit")
        add("P0481", "Cooling fan 2 control circuit")
        add("P0500", "Vehicle speed sensor malfunction")
        add("P0501", "Vehicle speed sensor range/performance")
        add("P0505", "Idle control system malfunction")
        add("P0506", "Idle speed lower than expected")
        add("P0507", "Idle speed higher than expected")
        add("P0520", "Engine oil pressure sensor circuit")
        add("P0521", "Engine oil pressure sensor range/performance")
        add("P0522", "Engine oil pressure sensor low")
        add("P0523", "Engine oil pressure sensor high")
        add("P0560", "System voltage malfunction")
        add("P0562", "System voltage low")
        add("P0563", "System voltage high")
        add("P0600", "Serial communication link malfunction")
        add("P0601", "Internal control module memory checksum error")
        add("P0602", "Control module programming error")
        add("P0603", "Internal control module keep-alive memory error")
        add("P0604", "Internal control module RAM error")
        add("P0605", "Internal control module ROM error")
        add("P0606", "ECM/PCM processor fault")
        add("P0607", "Control module performance")
        add("P0620", "Generator control circuit")
        add("P0621", "Generator lamp L circuit")
        add("P0622", "Generator field F circuit")
        add("P0641", "Sensor reference voltage A circuit open")
        add("P0650", "Malfunction indicator lamp control circuit")
        add("P0651", "Sensor reference voltage B circuit open")
        add("P0700", "Transmission control system malfunction (MIL request)")
        add("P0705", "Transmission range sensor circuit")
        add("P0706", "Transmission range sensor range/performance")
        add("P0710", "Transmission fluid temperature sensor circuit")
        add("P0711", "Transmission fluid temperature range/performance")
        add("P0712", "Transmission fluid temperature circuit low")
        add("P0713", "Transmission fluid temperature circuit high")
        add("P0715", "Input/turbine speed sensor circuit")
        add("P0720", "Output speed sensor circuit")
        add("P0725", "Engine speed input circuit")
        add("P0730", "Incorrect gear ratio")
        add("P0731", "Gear 1 incorrect ratio")
        add("P0732", "Gear 2 incorrect ratio")
        add("P0733", "Gear 3 incorrect ratio")
        add("P0734", "Gear 4 incorrect ratio")
        add("P0740", "Torque converter clutch circuit")
        add("P0741", "Torque converter clutch stuck off")
        add("P0742", "Torque converter clutch stuck on")
        add("P0745", "Pressure control solenoid A")
        add("P0750", "Shift solenoid A")
        add("P0755", "Shift solenoid B")
        add("P0760", "Shift solenoid C")
        add("P0765", "Shift solenoid D")
        add("P0770", "Shift solenoid E")
        add("P0780", "Shift malfunction")
        add("P0A80", "Replace hybrid battery pack")
        add("P0AFA", "Hybrid battery voltage low")
        add("C0035", "Left front wheel speed sensor circuit")
        add("C0040", "Right front wheel speed sensor circuit")
        add("C0045", "Left rear wheel speed sensor circuit")
        add("C0050", "Right rear wheel speed sensor circuit")
        add("C0110", "ABS pump motor circuit")
        add("U0100", "Lost communication with ECM/PCM")
        add("U0101", "Lost communication with transmission control module")
        add("U0121", "Lost communication with ABS control module")
        add("U0140", "Lost communication with body control module")
        add("U0155", "Lost communication with instrument panel cluster")
    }

    fun describe(code: String): String {
        val normalized = code.trim().uppercase()
        genericDescriptions[normalized]?.let { return it }
        descriptions[normalized]?.let { return it }
        val prefix = normalized.firstOrNull() ?: return "Unknown trouble code"
        val category = when (prefix) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> "Unknown"
        }
        return if (isGeneric(normalized)) {
            "Generic $category code — no built-in description"
        } else {
            "Manufacturer-specific $category code — check service data for this vehicle"
        }
    }

    /** True when the code has a generic SAE J2012 description in the bundled database. */
    fun isGeneric(code: String): Boolean {
        val normalized = code.trim().uppercase()
        return genericDescriptions.containsKey(normalized) || descriptions.containsKey(normalized)
    }

    /** Number of generic descriptions available; useful for coverage reporting and tests. */
    val genericCount: Int get() = genericDescriptions.size

    /**
     * Generic SAE J2012 descriptions from the CC0 OBDex dataset
     * (https://github.com/foerbsnavi/obdex, LICENSE-DATA: CC0 1.0).
     *
     * The file is a Java resource so it works in both the pure-Kotlin tests and the Android app.
     * Descriptions are facts; the CC0 dedication removes any redistribution concern.
     */
    private val genericDescriptions: Map<String, String> by lazy { loadGenericDescriptions() }

    private fun loadGenericDescriptions(): Map<String, String> {
        val stream = DtcCatalog::class.java.classLoader
            ?.getResourceAsStream("dtc-generic.csv")
            ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val fields = parseCsvLine(line)
                if (fields.size < 2) return@mapNotNull null
                val code = fields[0].trim().uppercase()
                val description = fields[1].trim()
                if (code.isEmpty() || description.isEmpty()) null else code to description
            }.toMap()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
