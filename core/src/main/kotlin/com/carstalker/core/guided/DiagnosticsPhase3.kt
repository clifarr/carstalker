package com.carstalker.core.guided

/**
 * Phase 3 definitions. Generic OBD rarely exposes requested boost, rail-pressure targets or
 * verified DPF data, so these guides either run a clearly labelled limited test or remain
 * catalogue entries until enhanced data can be decoded.
 */
object DiagnosticsPhase3 {

    val all: List<DiagnosticDefinition> get() = listOf(
        boostControl,
        fuelPressure,
        egr,
        throttlePedal,
        evapPurge,
        dpfHealth,
    )

    private fun boostKeyOnPids(): List<Int> = listOf(
        Signals.MAP,
        Signals.BARO,
        Signals.RPM,
        Signals.CONTROL_MODULE_VOLTAGE,
    )

    private fun boostPids(): List<Int> = listOf(
        Signals.MAP,
        Signals.BARO,
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.THROTTLE,
        Signals.LOAD,
    )

    private fun fuelPressurePids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.FUEL_PRESSURE,
        Signals.RAIL_PRESSURE,
        Signals.RAIL_GAUGE_PRESSURE,
        Signals.LOAD,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.FUEL_SYSTEM_STATUS,
    )

    private fun egrPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.EGR_COMMANDED,
        Signals.EGR_ERROR,
        Signals.MAP,
        Signals.MAF,
        Signals.THROTTLE,
        Signals.LOAD,
    )

    private fun throttlePids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.THROTTLE,
        Signals.RELATIVE_THROTTLE,
        Signals.THROTTLE_B,
        Signals.PEDAL_D,
        Signals.PEDAL_E,
        Signals.COMMANDED_THROTTLE,
        Signals.LOAD,
    )

    private fun evapPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.EVAP_PURGE,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.FUEL_SYSTEM_STATUS,
        Signals.MAP,
    )

    val boostControl = DiagnosticDefinition(
        id = "boost_control",
        version = "1.0.0",
        title = "Turbo under/overboost",
        subtitle = "P0299 / P0234 / P2263 · boost tracking",
        description = "Compare manifold pressure with barometric pressure across key-on, idle and a gentle throttle response. Generic OBD does not expose a requested-boost target on most cars, so this is a limited measured-boost trend, not an under/overboost verdict.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.AIRFLOW_SENSORS,
        keywords = listOf("turbo", "boost", "underboost", "overboost", "p0299", "p0234", "wastegate"),
        estimatedMinutes = 10,
        dtcs = listOf("P0299", "P0234", "P2263"),
        dtcMatch = listOf("P0299", "P0234", "P2263"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.MAP, "Manifold pressure"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.BARO, "Barometric pressure", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
        ),
        stages = listOf(
            DefinitionSupport.keyOn(boostKeyOnPids()),
            DefinitionSupport.warmIdle(boostPids()),
            DefinitionSupport.freeRev(
                boostPids(),
                instruction = "Open the throttle gently once or twice and let it return to idle. A stationary free rev may not build full boost.",
            ),
        ),
        whatYouNeed = listOf(
            "A turbocharged or supercharged engine and a safe place to idle and blip the throttle.",
            "The adapter connected; requested boost is only available on some vehicles.",
            "About 10 minutes. A road test needs a passenger and is not required for this limited test.",
        ),
        safetyNotes = listOf(
            "Do not attempt to build full boost in neutral or in a confined space.",
            "Stop for a flashing MIL, severe misfire, overheating, smoke or abnormal noise.",
            "A boost leak or stuck actuator can be dangerous under load; have it assessed professionally.",
        ),
        limitations = listOf(
            "Requested boost is not a generic OBD-II PID; this test cannot prove underboost versus target.",
            "A stationary free rev may not produce enough exhaust flow to spool the turbocharger.",
            "MAP sensor bias, barometric assumptions and altitude affect the result.",
            "Wastegate, diverter, intercooler, exhaust and sensor faults can produce similar readings.",
        ),
        engineRequirement = EngineRequirement.FORCED_INDUCTION,
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 4_000),
    )

    val fuelPressure = DiagnosticDefinition(
        id = "fuel_pressure",
        version = "1.0.0",
        title = "Fuel pressure",
        subtitle = "P0087 / P0088 / P0191 · measured pressure",
        description = "Compare measured fuel or rail pressure at warm idle and a steady elevated RPM. Requested-versus-actual comparison is only possible when the vehicle exposes both values in the same circuit and units.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.ENGINE_FUEL,
        keywords = listOf("fuel pressure", "rail", "p0087", "p0191", "fuel pump"),
        estimatedMinutes = 10,
        dtcs = listOf("P0087", "P0088", "P0191"),
        dtcMatch = listOf("P0087", "P0088", "P0191"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(
                Signals.FUEL_PRESSURE,
                "Fuel pressure",
                alternatives = listOf(Signals.RAIL_PRESSURE, Signals.RAIL_GAUGE_PRESSURE),
            ),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.FUEL_SYSTEM_STATUS, "Fuel system status", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(fuelPressurePids()),
            DefinitionSupport.steadyRpm(fuelPressurePids()),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine and a safe place to idle and hold a steady RPM.",
            "A vehicle that reports fuel or rail pressure through generic OBD.",
            "About 10 minutes. Do not open the fuel system.",
        ),
        safetyNotes = listOf(
            "Do not open or loosen any fuel-system component while the engine is running or the system is pressurised.",
            "Stop for a fuel smell, leak, flashing MIL, severe misfire or abnormal noise.",
            "High-pressure fuel systems can injure; leave physical testing to a qualified technician.",
        ),
        limitations = listOf(
            "Generic OBD may expose low-pressure fuel pressure, relative rail pressure or gauge rail pressure, not all three.",
            "Without a requested-pressure PID the test can only check stability and response.",
            "Sensor location, units and reference frames differ between low- and high-pressure circuits.",
            "A stable reading does not prove fuel volume or injector delivery under load.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_000),
    )

    val egr = DiagnosticDefinition(
        id = "egr",
        version = "1.0.0",
        title = "EGR flow and control",
        subtitle = "P0400–P0409 · EGR response",
        description = "Correlate commanded EGR and EGR error with manifold-pressure and airflow changes. Inconsistency supports a flow or control investigation; it does not automatically condemn the valve.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.EMISSIONS,
        keywords = listOf("egr", "exhaust gas recirculation", "p0401", "emissions"),
        estimatedMinutes = 10,
        dtcs = listOf("P0400", "P0401", "P0402", "P0403", "P0404", "P0405", "P0406", "P0407", "P0408", "P0409"),
        dtcMatch = listOf("P040*"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(
                Signals.EGR_COMMANDED,
                "Commanded EGR",
                alternatives = listOf(Signals.EGR_ERROR),
            ),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.EGR_ERROR, "EGR error", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(egrPids()),
            DefinitionSupport.steadyRpm(egrPids(), rpmRange = 2_000..2_500),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine and a safe place to idle and hold a moderate RPM.",
            "A vehicle that reports commanded EGR or EGR error.",
            "About 10 minutes. The ECU may only command EGR under specific conditions.",
        ),
        safetyNotes = listOf(
            "Do not disconnect or block EGR components to force a result.",
            "Stop for a flashing MIL, severe misfire, overheating or abnormal noise.",
            "EGR faults can affect emissions and driveability; use service information for repair decisions.",
        ),
        limitations = listOf(
            "The ECU may not command EGR during a stationary test, which makes the result inconclusive.",
            "Commanded EGR and EGR error are not the same as measured valve position or flow.",
            "Carbon buildup, a stuck valve, a sensor fault and a wiring fault can look similar.",
            "Diesels and petrol engines use different EGR strategies and expected responses.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_000),
    )

    val throttlePedal = DiagnosticDefinition(
        id = "throttle_pedal",
        version = "1.0.0",
        title = "Throttle and pedal plausibility",
        subtitle = "P0120–P0124 / P2135 · redundant sensors",
        description = "Compare throttle, relative throttle and accelerator-pedal channels during a gentle throttle response. A disagreement supports a sensor, wiring or adaptation investigation; drive-by-wire faults deserve professional assessment.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.AIRFLOW_SENSORS,
        keywords = listOf("throttle", "pedal", "drive by wire", "p0120", "p2135", "limp"),
        estimatedMinutes = 9,
        dtcs = listOf("P0120", "P0121", "P0122", "P0123", "P0124", "P2135"),
        dtcMatch = listOf("P012*", "P2135"),
        requiredSignals = listOf(
            SignalRequirement(Signals.THROTTLE, "Throttle position"),
            SignalRequirement(Signals.RPM, "Engine speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.RELATIVE_THROTTLE, "Relative throttle position", required = false),
            SignalRequirement(Signals.THROTTLE_B, "Throttle position B", required = false),
            SignalRequirement(Signals.PEDAL_D, "Accelerator pedal D", required = false),
            SignalRequirement(Signals.PEDAL_E, "Accelerator pedal E", required = false),
            SignalRequirement(Signals.COMMANDED_THROTTLE, "Commanded throttle actuator", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(throttlePids(), captureMs = 12_000),
            DefinitionSupport.freeRev(
                throttlePids(),
                captureMs = 20_000,
                instruction = "With the engine warm and the car stationary, press the accelerator gently and release it smoothly. Watch the pedal and throttle channels move together.",
            ),
        ),
        whatYouNeed = listOf(
            "A warm engine, transmission in park or neutral, and a safe stationary location.",
            "A vehicle that reports throttle or pedal position through generic OBD.",
            "About 9 minutes. Do not drive to perform this test.",
        ),
        safetyNotes = listOf(
            "A drive-by-wire fault can affect power delivery. Have the vehicle assessed professionally and avoid normal driving until it is resolved.",
            "Do not perform this test while the vehicle is moving.",
            "Stop immediately for an unexpected engine-speed increase, a stuck pedal or a flashing MIL.",
            "Do not attempt to repair or adjust throttle/pedal components without service information.",
        ),
        limitations = listOf(
            "PID 0x11 is not guaranteed to represent both redundant throttle channels.",
            "Pedal and throttle channels may be intentionally scaled or offset differently.",
            "A correlation check cannot prove a sensor is good; it only shows whether the channels track.",
            "Some vehicles do not expose pedal position through generic OBD.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(
            stopOnMisfireCodes = false,
            stopCodePrefixes = emptyList(),
            maxRpm = 3_500,
        ),
    )

    val evapPurge = DiagnosticDefinition(
        id = "evap_purge",
        version = "1.0.0",
        title = "EVAP purge investigation",
        subtitle = "P0441 / P0496 · purge response",
        description = "Correlate commanded EVAP purge with fuel-trim and idle response. Generic OBD cannot locate an EVAP leak or prove which valve failed, so the result stays deliberately limited.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.EMISSIONS,
        keywords = listOf("evap", "purge", "p0441", "p0496", "fuel vapour", "canister"),
        estimatedMinutes = 10,
        dtcs = listOf("P0441", "P0496"),
        dtcMatch = listOf("P0441", "P0496"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1"),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1"),
            SignalRequirement(Signals.EVAP_PURGE, "Commanded EVAP purge"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.FUEL_SYSTEM_STATUS, "Fuel system status", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(evapPids()),
            DefinitionSupport.steadyRpm(evapPids(), rpmRange = 1_800..2_200),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine, ideally after a refuel or with a normally seated fuel cap.",
            "A safe place to idle and hold a moderate RPM.",
            "About 10 minutes. Do not open the fuel or vapour system.",
        ),
        safetyNotes = listOf(
            "Do not open, disconnect or bypass EVAP components; fuel vapour is flammable.",
            "Stop for a fuel smell, leak, flashing MIL, severe misfire or abnormal noise.",
            "A P0455/P0457 leak code needs a proper smoke or leak test, not a parts guess.",
        ),
        limitations = listOf(
            "Generic OBD exposes purge command, not purge-valve position or actual flow.",
            "A canister that is not saturated may produce little or no trim response.",
            "The test cannot locate a leak or prove which valve or hose failed.",
            "Some vehicles only command purge under driving conditions, so a stationary test may be inconclusive.",
        ),
        engineRequirement = EngineRequirement.PETROL,
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_000),
    )

    val dpfHealth = DiagnosticDefinition(
        id = "dpf_health",
        version = "1.0.0",
        title = "DPF health and regeneration",
        subtitle = "P2002 / P2463 / P2452 · diesel particulate filter",
        description = "Read standard DPF differential pressure and temperature at key-on, warm idle and a steady elevated RPM. Passive observation only; it never forces a regeneration, guesses a soot load or prescribes a universal pressure limit.",
        phase = DiagnosticPhase.PHASE_3,
        category = DiagnosticCategory.EMISSIONS,
        keywords = listOf("dpf", "diesel", "particulate", "regeneration", "p2002", "p2463"),
        estimatedMinutes = 12,
        dtcs = listOf("P2002", "P2463", "P2452"),
        dtcMatch = listOf("P2002", "P2463", "P2452"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(
                Signals.DPF_PRESSURE_B1,
                "DPF differential pressure",
                alternatives = listOf(Signals.DPF_PRESSURE_B2),
            ),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.DPF_TEMPERATURE, "DPF temperature", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
        ),
        stages = listOf(
            DefinitionSupport.keyOn(dpfKeyOnPids()),
            DefinitionSupport.warmIdle(dpfPids()),
            DefinitionSupport.steadyRpm(dpfPids(), rpmRange = 2_000..2_500),
        ),
        whatYouNeed = listOf(
            "A diesel vehicle with a DPF and a safe place to idle and hold a moderate RPM.",
            "A vehicle that reports standard DPF pressure PID 0x7A or 0x7B; temperature 0x7C is optional.",
            "About 12 minutes. This is passive observation; do not force a regeneration.",
        ),
        safetyNotes = listOf(
            "Never force a DPF regeneration from this app; use the manufacturer's procedure.",
            "Do not disconnect, bypass or chemically clean DPF components based on this test.",
            "Stop for a flashing MIL, overheating, abnormal noise, smoke or a fuel smell.",
            "A high DPF temperature can burn; keep hands and cables away from the exhaust.",
        ),
        limitations = listOf(
            "Standard DPF pressure/temperature PIDs are not supported by every diesel.",
            "Generic OBD does not expose soot load, ash load or regeneration status on every vehicle.",
            "Relative pressure changes are used; no universal kPa limit is assumed.",
            "A high post-regeneration value can be ash, restriction, sensor/plumbing error or unfavourable conditions.",
        ),
        engineRequirement = EngineRequirement.DIESEL,
        requiresWarmEngine = true,
        safety = SafetyRules(
            stopCodePrefixes = listOf("P030"),
            maxCoolantC = 110.0,
            maxRpm = 3_000,
        ),
    )

    private fun dpfKeyOnPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.DPF_PRESSURE_B1,
        Signals.DPF_PRESSURE_B2,
        Signals.DPF_TEMPERATURE,
        Signals.MAP,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.COOLANT,
    )

    private fun dpfPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.DPF_PRESSURE_B1,
        Signals.DPF_PRESSURE_B2,
        Signals.DPF_TEMPERATURE,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.MAF,
        Signals.MAP,
        Signals.LOAD,
    )
}
