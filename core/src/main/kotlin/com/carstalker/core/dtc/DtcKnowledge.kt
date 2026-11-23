package com.carstalker.core.dtc

/**
 * Clean-room diagnostic guidance for common generic codes.
 *
 * This is deliberately short, cause-oriented text written for CarStalker — not a copy of any
 * service publication. It is a starting point, not a repair procedure: always verify with the
 * vehicle's service information and a physical inspection.
 */
data class DtcGuidance(
    val summary: String,
    val causes: List<String>,
    val nextSteps: List<String>,
)

object DtcKnowledge {

    private val guidance: Map<String, DtcGuidance> = mapOf(
        "P0011" to DtcGuidance(
            summary = "Camshaft timing is more advanced than the ECU commanded on bank 1.",
            causes = listOf(
                "Dirty or low engine oil, or the wrong viscosity",
                "Sticking oil control valve / VVT solenoid",
                "Camshaft phaser wear or a restricted oil passage",
                "Timing chain or belt installed a tooth off",
            ),
            nextSteps = listOf(
                "Check oil level, condition and viscosity first.",
                "Command the VVT solenoid and watch cam timing change.",
                "Compare commanded and actual cam angle at idle and under load.",
                "Inspect the phaser and oil passages before replacing parts.",
            ),
        ),
        "P0016" to DtcGuidance(
            summary = "Crankshaft and camshaft position disagree on bank 1.",
            causes = listOf(
                "Stretched timing chain or worn tensioner",
                "Timing belt/chain off by a tooth",
                "Failed camshaft or crankshaft sensor",
                "Wiring or connector fault on either sensor",
            ),
            nextSteps = listOf(
                "Check for other timing codes and freeze-frame conditions.",
                "Verify base timing mechanically before replacing sensors.",
                "Scope both sensors and compare the signal edges.",
                "Inspect the chain/belt and tensioner on high-mileage engines.",
            ),
        ),
        "P0101" to DtcGuidance(
            summary = "Mass air flow reading is outside the expected range for the operating point.",
            causes = listOf(
                "Dirty MAF sensing element",
                "Unmetered air leak after the MAF",
                "Restricted intake or clogged air filter",
                "MAF sensor or wiring fault",
            ),
            nextSteps = listOf(
                "Compare MAF g/s at idle and at a steady 2,500 rpm with the expected value.",
                "Check fuel trims: positive trims suggest unmetered air.",
                "Inspect the intake boot and PCV hoses for leaks.",
                "Clean the MAF with MAF-safe cleaner before replacing it.",
            ),
        ),
        "P0113" to DtcGuidance(
            summary = "Intake air temperature sensor circuit reports a high voltage (cold/Open).",
            causes = listOf(
                "Unplugged or damaged IAT sensor",
                "Open circuit in the signal or ground wire",
                "Corroded connector pins",
                "Failed sensor",
            ),
            nextSteps = listOf(
                "Read IAT with the key on, engine cold; compare with ambient temperature.",
                "Wiggle the harness and watch for the value to jump.",
                "Check resistance across the sensor and continuity to the ECU.",
            ),
        ),
        "P0128" to DtcGuidance(
            summary = "Coolant temperature did not reach the thermostat regulating temperature in time.",
            causes = listOf(
                "Thermostat stuck open",
                "Low coolant level or air pocket",
                "Coolant temperature sensor bias",
                "Very cold ambient conditions with a long idle",
            ),
            nextSteps = listOf(
                "Verify coolant level and bleed the system.",
                "Log coolant temperature from a cold start; it should climb steadily to ~90 °C.",
                "Compare coolant and ambient temperatures after a cold soak.",
                "Replace the thermostat if it plateaus low.",
            ),
        ),
        "P0133" to DtcGuidance(
            summary = "Upstream oxygen sensor response is slower than the monitor expects.",
            causes = listOf(
                "Aging or contaminated O2 sensor",
                "Exhaust leak before the sensor",
                "Lean/rich mixture from a fuel or air fault",
                "Sensor heater circuit degradation",
            ),
            nextSteps = listOf(
                "Check fuel trims and freeze frame first.",
                "Look for exhaust leaks upstream of the sensor.",
                "Graph the O2 signal; a lazy sensor switches slowly.",
                "Verify the heater circuit before replacing the sensor.",
            ),
        ),
        "P0135" to DtcGuidance(
            summary = "Upstream O2 sensor heater circuit is not drawing the expected current.",
            causes = listOf(
                "Failed O2 sensor heater",
                "Blown fuse or open heater supply",
                "Open or shorted heater wiring",
                "ECU driver fault (rare)",
            ),
            nextSteps = listOf(
                "Check the O2 heater fuse first.",
                "Measure heater resistance at the sensor connector.",
                "Check supply voltage and ground with the key on.",
            ),
        ),
        "P0171" to DtcGuidance(
            summary = "Bank 1 fuel trim is too lean: the ECU is adding more fuel than expected.",
            causes = listOf(
                "Unmetered air: intake boot, PCV, brake booster, intake gasket",
                "Weak fuel pump or restricted filter",
                "Dirty/failed MAF",
                "Leaking or sticking injector (less common)",
                "Exhaust leak before the upstream O2 sensor",
            ),
            nextSteps = listOf(
                "Read STFT and LTFT at idle and at 2,500 rpm.",
                "If trims improve with RPM, look for a vacuum leak.",
                "Spray intake-safe cleaner around suspect gaskets and watch trims.",
                "Check fuel pressure and MAF g/s before replacing parts.",
            ),
        ),
        "P0172" to DtcGuidance(
            summary = "Bank 1 fuel trim is too rich: the ECU is removing fuel.",
            causes = listOf(
                "Leaking injector or high fuel pressure",
                "Restricted intake or dirty MAF over-reporting air",
                "EVAP purge valve stuck open",
                "O2 sensor biased rich",
            ),
            nextSteps = listOf(
                "Check fuel pressure and injector balance if available.",
                "Command the EVAP purge valve closed and watch trims.",
                "Compare MAF and calculated load at idle.",
                "Look for a fuel smell in the oil or EVAP system.",
            ),
        ),
        "P0300" to DtcGuidance(
            summary = "Random or multiple-cylinder misfire detected.",
            causes = listOf(
                "Worn spark plugs or ignition coils",
                "Vacuum leak or intake gasket",
                "Low fuel pressure or dirty injectors",
                "Mechanical fault: compression, valve train, head gasket",
            ),
            nextSteps = listOf(
                "Use Mode 06 misfire counters to find affected cylinders.",
                "Check freeze frame for load, RPM and coolant temperature.",
                "Swap coils/plugs and see whether the misfire follows.",
                "Run a compression or cylinder-balance test if ignition and fuel check out.",
            ),
        ),
        "P0301" to DtcGuidance(
            summary = "Cylinder 1 misfire detected.",
            causes = listOf(
                "Spark plug or coil on cylinder 1",
                "Injector or injector circuit on cylinder 1",
                "Vacuum leak near cylinder 1 intake port",
                "Compression or valve-train fault on cylinder 1",
            ),
            nextSteps = listOf(
                "Swap the coil and plug with a known-good cylinder and recheck.",
                "Compare Mode 06 misfire counts across cylinders.",
                "Check injector pulse and resistance.",
                "Run a compression test if the misfire stays on cylinder 1.",
            ),
        ),
        "P0302" to DtcGuidance(
            summary = "Cylinder 2 misfire detected.",
            causes = listOf("Spark plug or coil on cylinder 2", "Injector or circuit on cylinder 2", "Intake leak near cylinder 2", "Compression or valve fault on cylinder 2"),
            nextSteps = listOf("Swap ignition parts with another cylinder.", "Compare Mode 06 misfire counts.", "Check injector pulse and compression."),
        ),
        "P0303" to DtcGuidance(
            summary = "Cylinder 3 misfire detected.",
            causes = listOf("Spark plug or coil on cylinder 3", "Injector or circuit on cylinder 3", "Intake leak near cylinder 3", "Compression or valve fault on cylinder 3"),
            nextSteps = listOf("Swap ignition parts with another cylinder.", "Compare Mode 06 misfire counts.", "Check injector pulse and compression."),
        ),
        "P0304" to DtcGuidance(
            summary = "Cylinder 4 misfire detected.",
            causes = listOf("Spark plug or coil on cylinder 4", "Injector or circuit on cylinder 4", "Intake leak near cylinder 4", "Compression or valve fault on cylinder 4"),
            nextSteps = listOf("Swap ignition parts with another cylinder.", "Compare Mode 06 misfire counts.", "Check injector pulse and compression."),
        ),
        "P0401" to DtcGuidance(
            summary = "Exhaust gas recirculation flow is lower than expected.",
            causes = listOf(
                "Carbon-blocked EGR passages or valve",
                "Failed EGR solenoid or vacuum supply",
                "EGR differential pressure sensor fault",
                "Wiring or connector fault",
            ),
            nextSteps = listOf(
                "Command the EGR valve and watch EGR flow/position.",
                "Inspect passages for carbon blockage.",
                "Check vacuum supply and solenoid operation on vacuum EGR systems.",
            ),
        ),
        "P0420" to DtcGuidance(
            summary = "Catalyst efficiency on bank 1 is below the monitor threshold.",
            causes = listOf(
                "Aged or damaged catalytic converter",
                "Exhaust leak before or after the catalyst",
                "Rich/lean mixture or misfire damaging the catalyst",
                "Downstream O2 sensor fault",
            ),
            nextSteps = listOf(
                "Rule out misfire, fuel-trim and exhaust-leak codes first.",
                "Graph upstream and downstream O2 sensors; the downstream should be stable.",
                "Check catalyst temperature and freeze frame.",
                "Replace the catalyst only after the root cause is fixed.",
            ),
        ),
        "P0430" to DtcGuidance(
            summary = "Catalyst efficiency on bank 2 is below the monitor threshold.",
            causes = listOf("Aged or damaged catalytic converter on bank 2", "Exhaust leak", "Rich/lean mixture or misfire", "Downstream O2 sensor fault"),
            nextSteps = listOf("Rule out misfire and fuel-trim faults.", "Compare bank 1 and bank 2 O2 signals.", "Check for exhaust leaks and catalyst temperature."),
        ),
        "P0442" to DtcGuidance(
            summary = "EVAP system has a small leak.",
            causes = listOf(
                "Loose or damaged fuel cap",
                "Cracked EVAP hose or purge line",
                "Leaking purge or vent valve",
                "Fuel tank or filler neck leak",
            ),
            nextSteps = listOf(
                "Check the fuel cap seal and tighten it.",
                "Inspect EVAP hoses for cracks.",
                "Run a smoke test if the leak is not obvious.",
                "Verify the purge and vent valves hold vacuum.",
            ),
        ),
        "P0455" to DtcGuidance(
            summary = "EVAP system has a large leak or is open.",
            causes = listOf("Missing or loose fuel cap", "Disconnected EVAP hose", "Failed purge/vent valve", "Filler neck or tank damage"),
            nextSteps = listOf("Check the fuel cap first.", "Inspect the EVAP lines under the hood and at the tank.", "Smoke-test the system."),
        ),
        "P0505" to DtcGuidance(
            summary = "Idle air control system is not maintaining the commanded idle speed.",
            causes = listOf("Carboned throttle body or IAC passage", "Failed IAC valve or stepper motor", "Vacuum leak", "Throttle body adaptation lost"),
            nextSteps = listOf("Clean the throttle body and IAC passage.", "Perform the idle/throttle relearn procedure.", "Check for vacuum leaks and verify the IAC commands."),
        ),
        "P0700" to DtcGuidance(
            summary = "The transmission control module has stored a fault and requested the engine MIL.",
            causes = listOf("Any TCM fault: sensor, solenoid, wiring or internal", "Low or degraded transmission fluid", "CAN communication fault"),
            nextSteps = listOf("Read TCM codes from the transmission module.", "Check fluid level and condition.", "Do not replace the transmission until the TCM code is diagnosed."),
        ),
        "U0100" to DtcGuidance(
            summary = "A module has lost CAN communication with the engine control module.",
            causes = listOf("Blown fuse or poor power/ground to the ECM", "CAN bus wiring fault or short", "Failed ECM", "Low battery voltage"),
            nextSteps = listOf("Check battery voltage and ECM fuses.", "Measure CAN resistance and check for shorts.", "Look for other U-codes that identify the affected bus."),
        ),
        "U0121" to DtcGuidance(
            summary = "A module has lost CAN communication with the ABS control module.",
            causes = listOf("ABS module power/ground fault", "CAN bus wiring fault", "Failed ABS module", "Low battery voltage"),
            nextSteps = listOf("Check ABS fuses and grounds.", "Verify CAN continuity to the ABS module.", "Check for other modules dropping at the same time."),
        ),
        "C0035" to DtcGuidance(
            summary = "Left front wheel speed sensor circuit fault.",
            causes = listOf("Damaged sensor or tone ring", "Wiring/connector fault at the wheel", "Metal debris on the sensor", "Bearing play"),
            nextSteps = listOf("Inspect the sensor and tone ring for debris or damage.", "Compare wheel speeds on a test drive.", "Check continuity and connector condition."),
        ),
        "P0A80" to DtcGuidance(
            summary = "Hybrid battery pack requires replacement according to the battery monitor.",
            causes = listOf("One or more weak modules", "Cooling fan or duct fault", "Age-related capacity loss", "Battery current sensor fault"),
            nextSteps = listOf(
                "High-voltage system: do not touch orange cables or the pack without the manufacturer's training and PPE.",
                "Read block voltages and temperatures with a hybrid-capable tool.",
                "Check battery cooling and fan operation.",
                "Confirm the pack is actually failing before replacement.",
            ),
        ),
    )

    fun forCode(code: String): DtcGuidance? = guidance[code.trim().uppercase()]
}
