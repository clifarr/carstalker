package com.carstalker.core.guided

/**
 * Phase 2 definitions: richer mixture, oxygen-sensor response, misfire context, MAF plausibility
 * and crank/no-start data. Every rule is generic OBD-II and offline; enhanced Mode 06 mapping is
 * never assumed.
 */
object DiagnosticsPhase2 {

    val all: List<DiagnosticDefinition> get() = listOf(
        richMixture,
        oxygenResponse,
        misfire,
        mafPlausibility,
        crankNoStart,
    )

    private fun mixturePids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.FUEL_SYSTEM_STATUS,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.STFT_B2,
        Signals.LTFT_B2,
        Signals.MAF,
        Signals.MAP,
        Signals.THROTTLE,
        Signals.LOAD,
    )

    private fun oxygenPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.O2_B1S1,
        Signals.O2_B1S2,
        Signals.WIDEBAND_B1S1,
        Signals.WIDEBAND_CURRENT_B1S1,
        Signals.THROTTLE,
        Signals.FUEL_SYSTEM_STATUS,
    )

    private fun misfirePids(): List<Int> = listOf(
        Signals.RPM,
        Signals.LOAD,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.MAF,
        Signals.MAP,
        Signals.CONTROL_MODULE_VOLTAGE,
    )

    private fun mafPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.MAF,
        Signals.MAP,
        Signals.IAT,
        Signals.LOAD,
        Signals.THROTTLE,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.STFT_B1,
        Signals.LTFT_B1,
    )

    private fun crankKeyOnPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.ADAPTER_VOLTAGE,
        Signals.MAP,
        Signals.COOLANT,
    )

    private fun crankPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.ADAPTER_VOLTAGE,
        Signals.MAP,
        Signals.COOLANT,
        Signals.FUEL_SYSTEM_STATUS,
    )

    val richMixture = DiagnosticDefinition(
        id = "rich_mixture",
        version = "1.0.0",
        title = "Rich-running mixture",
        subtitle = "P0172 / P0175 · negative fuel trims",
        description = "Capture negative combined trims at a warm idle and a steady elevated RPM, check O2/AF feedback and compare airflow plausibility. The result ranks plausible causes instead of naming a failed part.",
        phase = DiagnosticPhase.PHASE_2,
        estimatedMinutes = 10,
        dtcs = listOf("P0172", "P0175"),
        dtcMatch = listOf("P0172", "P0175"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.COOLANT, "Coolant temperature"),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1"),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.FUEL_SYSTEM_STATUS, "Fuel system status", required = false),
            SignalRequirement(Signals.STFT_B2, "Short-term fuel trim, bank 2", required = false),
            SignalRequirement(Signals.LTFT_B2, "Long-term fuel trim, bank 2", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.O2_B1S1, "Upstream O2 voltage", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(mixturePids()),
            DefinitionSupport.steadyRpm(
                mixturePids(),
                note = "If stationary revving is not appropriate for your engine, use a steady cruise with a passenger instead.",
            ),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine in closed-loop fuelling.",
            "A safe place to idle and, if appropriate, hold ~2,500 rpm.",
            "About 10 minutes. Do not interact with the phone while driving.",
        ),
        safetyNotes = listOf(
            "Never read or tap the screen while driving.",
            "Do not hold a stationary high RPM if your vehicle or local rules do not allow it.",
            "Stop for a flashing MIL, severe misfire, overheating, fuel smell or abnormal noise.",
            "This test does not prove a leaking injector, purge valve or sensor fault; it ranks what to inspect next.",
        ),
        limitations = listOf(
            "Negative trims can come from airflow over-reporting, fuel pressure, injectors, EVAP purge, an intake restriction or sensor bias.",
            "Thresholds are configurable starting points, not universal specifications.",
            "Bank 2 is only analysed when the car reports bank 2 trims.",
            "Diesel engines do not report petrol-style fuel trims; this guide is for petrol engines.",
        ),
        engineRequirement = EngineRequirement.PETROL,
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030")),
    )

    val oxygenResponse = DiagnosticDefinition(
        id = "oxygen_response",
        version = "1.0.0",
        title = "Oxygen sensor responsiveness",
        subtitle = "P0130–P0167 · sensor response",
        description = "Identify the sensor technology, measure response to a real throttle change and check heater/circuit codes. Downstream sensors are damped by a healthy catalyst and are not expected to switch like the upstream sensor.",
        phase = DiagnosticPhase.PHASE_2,
        estimatedMinutes = 9,
        dtcs = listOf(
            "P0130", "P0131", "P0132", "P0133", "P0134", "P0135", "P0136",
            "P0137", "P0138", "P0139", "P0140", "P0141",
        ),
        dtcMatch = listOf("P013*", "P014*", "P015*", "P016*"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.COOLANT, "Coolant temperature"),
            SignalRequirement(
                Signals.O2_B1S1,
                "Upstream O2 voltage",
                alternatives = listOf(Signals.WIDEBAND_B1S1, Signals.WIDEBAND_CURRENT_B1S1),
            ),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.O2_B1S2, "Downstream O2 voltage", required = false),
            SignalRequirement(Signals.WIDEBAND_B1S2, "Downstream wideband O2", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.FUEL_SYSTEM_STATUS, "Fuel system status", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(oxygenPids(), captureMs = 15_000),
            DefinitionSupport.freeRev(
                oxygenPids(),
                captureMs = 20_000,
                instruction = "Warm idle first, then open the throttle gently once or twice and let it return to idle. Watch for a response, not a specific voltage.",
            ),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine in closed-loop fuelling.",
            "A safe place to idle and blip the throttle gently.",
            "About 9 minutes. The test is about response, not a single voltage.",
        ),
        safetyNotes = listOf(
            "Do not hold the engine at high RPM; brief, gentle throttle openings are enough.",
            "Stop for a flashing MIL, severe misfire, overheating or abnormal noise.",
            "A heater or circuit code must be fixed before the sensor response can be trusted.",
        ),
        limitations = listOf(
            "Narrowband voltage rules do not apply to wideband/equivalence-ratio or pump-current sensors.",
            "A downstream sensor is intentionally damped by a healthy catalyst; flat is not automatically bad.",
            "Heater performance and circuit faults are not measured directly by this live-data test.",
            "Low adapter sample rates can make a healthy sensor look slow; the result says so explicitly.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_500),
    )

    val misfire = DiagnosticDefinition(
        id = "misfire",
        version = "1.0.0",
        title = "Misfire investigation",
        subtitle = "P0300–P0308 · cylinder evidence",
        description = "Correlate stored misfire codes with RPM stability, load, trims and voltage. Cylinder-specific Mode 06 counts are only shown when their identifiers are verified, so this generic test does not invent them.",
        phase = DiagnosticPhase.PHASE_2,
        estimatedMinutes = 10,
        dtcs = listOf("P0300", "P0301", "P0302", "P0303", "P0304", "P0305", "P0306", "P0307", "P0308"),
        dtcMatch = listOf("P030*"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.LOAD, "Calculated load"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(misfirePids()),
            DefinitionSupport.steadyRpm(misfirePids(), rpmRange = 1_800..2_200),
        ),
        whatYouNeed = listOf(
            "A warm engine and a safe place to idle and hold a moderate RPM.",
            "The stored and pending codes read before starting.",
            "About 10 minutes. Stop if the MIL starts flashing.",
        ),
        safetyNotes = listOf(
            "A flashing MIL or severe misfire is a stop condition. Do not continue load tests.",
            "Do not intentionally create a misfire or disconnect ignition components while running.",
            "Any coil or plug swap is done with the engine off and appropriate precautions.",
            "Diesel engines may not have replaceable spark-ignition components.",
        ),
        limitations = listOf(
            "Generic OBD does not provide a verified cylinder-specific misfire count on every vehicle.",
            "Mode 06 misfire identifiers, scaling and units are not assumed here.",
            "A misfire that is not present during the capture can still be stored in the ECU.",
            "Compression, valve, injector and ignition causes cannot be separated from live data alone.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(
            stopOnMisfireCodes = false,
            stopCodePrefixes = emptyList(),
            maxRpm = 3_000,
        ),
    )

    val mafPlausibility = DiagnosticDefinition(
        id = "maf_plausibility",
        version = "1.0.0",
        title = "MAF plausibility",
        subtitle = "P0100–P0104 · airflow",
        description = "Compare MAF response with RPM, MAP, load and temperature. The test flags a static or inconsistent airflow signal without applying universal grams-per-second thresholds.",
        phase = DiagnosticPhase.PHASE_2,
        estimatedMinutes = 10,
        dtcs = listOf("P0100", "P0101", "P0102", "P0103", "P0104"),
        dtcMatch = listOf("P010*"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.MAF, "Mass air flow"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.IAT, "Intake air temperature", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(mafPids()),
            DefinitionSupport.steadyRpm(mafPids()),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine in closed-loop fuelling.",
            "A safe place to idle and hold a steady elevated RPM.",
            "About 10 minutes. Engine size is not guessed from airflow.",
        ),
        safetyNotes = listOf(
            "Do not hold a stationary high RPM if your vehicle or local rules do not allow it.",
            "Stop for a flashing MIL, severe misfire, overheating or abnormal noise.",
            "An air-filter or intake restriction can mimic a MAF fault; inspect before replacing parts.",
        ),
        limitations = listOf(
            "Generic OBD does not know the engine displacement, volumetric efficiency or expected airflow map.",
            "The test checks consistency and response, not a manufacturer's grams-per-second specification.",
            "A dirty MAF, intake leak, exhaust restriction or wiring fault can all shift the signal.",
            "Diesels and forced-induction engines need different expected airflow.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030")),
    )

    val crankNoStart = DiagnosticDefinition(
        id = "crank_no_start",
        version = "1.0.0",
        title = "Cranks but does not start",
        subtitle = "Various · cranking data",
        description = "Collect RPM during cranking, ECU voltage, coolant and manifold pressure. The result reports insufficient data explicitly instead of blaming a crank sensor when sampling or reconnection is too slow.",
        phase = DiagnosticPhase.PHASE_2,
        estimatedMinutes = 8,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(
                Signals.CONTROL_MODULE_VOLTAGE,
                "Control-module voltage",
                alternatives = listOf(Signals.ADAPTER_VOLTAGE),
            ),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.FUEL_SYSTEM_STATUS, "Fuel system status", required = false),
        ),
        stages = listOf(
            DefinitionSupport.keyOn(crankKeyOnPids(), captureMs = 5_000),
            DefinitionSupport.cranking(crankPids()),
        ),
        whatYouNeed = listOf(
            "A safe place to crank the engine.",
            "The adapter connected and supported PIDs read before starting.",
            "About 8 minutes. Do not crank for long periods; respect the starter's duty cycle.",
        ),
        safetyNotes = listOf(
            "Stop cranking if the engine starts, if anything sounds abnormal, or if there is a fuel smell.",
            "Do not crank repeatedly for long periods; starter and battery damage is possible.",
            "Do not open or test a pressurised fuel system while cranking.",
            "A no-start can be a safety issue; use professional help if fuel, spark or timing is uncertain.",
        ),
        limitations = listOf(
            "ELM327 adapters can miss cranking samples or reconnect too slowly to see RPM.",
            "RPM = 0 may mean no crank signal, a non-reporting ECU, or a sampling limitation.",
            "Generic OBD does not measure fuel pressure, spark or compression directly.",
            "The result is a data-quality assessment, not a crank-sensor verdict.",
        ),
        safety = SafetyRules(
            stopOnMisfireCodes = false,
            stopCodePrefixes = emptyList(),
            maxRpm = 1_500,
            minVoltage = 7.5,
        ),
    )
}
