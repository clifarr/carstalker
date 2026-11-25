package com.carstalker.core.guided

import com.carstalker.core.diagnose.CatalystWizard

/**
 * Declarative catalogue of guided diagnostics.
 *
 * All Phase 1–4 guides are implemented with generic OBD data where it exists. Guides whose data
 * source is inherently limited (DPF, manual symptom capture, baseline comparison) say so in their
 * definitions and results instead of pretending to be more certain than the data allows.
 */
object DiagnosticRegistry {

    val all: List<DiagnosticDefinition> get() = listOf(
        catalyst,
        leanMixture,
        thermostat,
        charging,
        mapPlausibility,
        richMixture,
        oxygenResponse,
        misfire,
        mafPlausibility,
        crankNoStart,
        boostControl,
        dpfHealth,
        fuelPressure,
        egr,
        throttlePedal,
        evapPurge,
        fuelConsumption,
        intermittentPowerLoss,
        readiness,
        usedCarInspection,
        healthBaseline,
    )

    private val byId: Map<String, DiagnosticDefinition> by lazy { all.associateBy { it.id } }

    fun find(id: String): DiagnosticDefinition? = byId[id]

    fun implemented(): List<DiagnosticDefinition> = all.filter { it.implemented }

    fun planned(): List<DiagnosticDefinition> = all.filterNot { it.implemented }

    fun suggestedForCodes(codes: List<String>): List<DiagnosticDefinition> {
        val normalized = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return all.filter { definition -> normalized.any { definition.matchesCode(it) } }
    }

    // ---- Phase 1 ---------------------------------------------------------

    val catalyst = DiagnosticDefinition(
        id = CatalystWizard.ID,
        version = "1.1.0",
        title = CatalystWizard.definition.title,
        subtitle = CatalystWizard.definition.subtitle,
        description = CatalystWizard.definition.description,
        phase = DiagnosticPhase.PHASE_1,
        category = DiagnosticCategory.EMISSIONS,
        keywords = listOf("catalyst", "converter", "p0420", "p0430", "o2", "oxygen sensor", "emissions"),
        estimatedMinutes = CatalystWizard.definition.estimatedMinutes,
        dtcs = listOf("P0420", "P0430"),
        dtcMatch = listOf("P0420", "P0430"),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.COOLANT, "Coolant temperature"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.O2_B1S1, "Upstream O2 voltage", required = false),
            SignalRequirement(Signals.O2_B1S2, "Downstream O2 voltage", required = false),
            SignalRequirement(Signals.WIDEBAND_B1S1, "Upstream wideband O2", required = false),
            SignalRequirement(Signals.CATALYST_B1S1, "Catalyst temperature", required = false),
        ),
        whatYouNeed = listOf(
            "A warm engine and a safe place to hold ~2,500 rpm, or a steady 50–60 km/h cruise.",
            "The OBD adapter connected; a passenger if you are driving.",
            "10–15 minutes, including a short drive to warm the catalyst.",
        ),
        safetyNotes = CatalystWizard.definition.safetyNotes,
        limitations = listOf(
            "Generic OBD-II only; no manufacturer-specific catalyst monitor or service data.",
            "Some cars do not report downstream O2 voltage or Mode 06; the result is then less certain.",
            "Narrowband O2 comparison needs PIDs 0x14/0x15. Wideband cars lean on Mode 06.",
            "Thresholds are a starting point and still need real-car validation.",
        ),
        requiresWarmEngine = true,
        legacyWizardId = CatalystWizard.ID,
        safety = SafetyRules(
            stopCodePrefixes = listOf("P030"),
            stopOnMisfireCodes = true,
            stopOnFlashingMil = true,
        ),
    )

    val leanMixture = DiagnosticDefinition(
        id = "lean_mixture",
        version = "1.0.0",
        title = "Vacuum leak, MAF or fuel delivery?",
        subtitle = "P0171 / P0174 · lean fuel trims",
        description = "Compare combined short- and long-term fuel trims at a warm idle and a steady elevated RPM. The pattern can rank a vacuum leak, airflow measurement and fuel delivery — it cannot prove one of them.",
        phase = DiagnosticPhase.PHASE_1,
        category = DiagnosticCategory.ENGINE_FUEL,
        keywords = listOf("lean", "vacuum leak", "intake leak", "pcv", "fuel trim", "maf", "smoke test"),
        estimatedMinutes = 10,
        dtcs = listOf("P0171", "P0174"),
        dtcMatch = listOf("P0171", "P0174"),
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
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
        ),
        stages = listOf(
            StageDefinition(
                id = "warm_idle",
                title = "Warm idle",
                instruction = "Let the engine idle in park or neutral. Do not touch the throttle.",
                condition = StageCondition(
                    kind = StageKind.WARM_IDLE,
                    rpmRange = 550..1_100,
                    minCoolantC = 70.0,
                    maxSpeedKph = 5.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 120,
                    settlingMs = 8_000,
                    captureMs = 20_000,
                ),
                pids = leanPids(),
            ),
            StageDefinition(
                id = "steady_rpm",
                title = "Steady 2,500 rpm",
                instruction = "Hold a steady ~2,500 rpm in park or neutral only if this is safe and appropriate for your engine. Use a light, constant throttle.",
                condition = StageCondition(
                    kind = StageKind.STEADY_RPM,
                    rpmRange = 2_300..2_700,
                    minCoolantC = 70.0,
                    maxSpeedKph = 5.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 180,
                    settlingMs = 5_000,
                    captureMs = 20_000,
                ),
                pids = leanPids(),
                note = "If your engine or local rules do not allow stationary revving, skip this stage and use a steady cruise with a passenger.",
            ),
            StageDefinition(
                id = "optional_cruise",
                title = "Optional steady cruise",
                instruction = "Set this up before you drive. Hold a steady 50–90 km/h with a passenger operating the phone, or skip.",
                condition = StageCondition(
                    kind = StageKind.STEADY_CRUISE,
                    minSpeedKph = 40.0,
                    maxSpeedKph = 110.0,
                    minCoolantC = 70.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 350,
                    settlingMs = 5_000,
                    captureMs = 30_000,
                ),
                pids = leanPids(),
                optional = true,
                allowFinishEarly = true,
            ),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine in closed-loop fuelling.",
            "A safe place to idle and, if appropriate, hold ~2,500 rpm.",
            "About 10 minutes. Do not interact with the phone while driving.",
        ),
        safetyNotes = listOf(
            "Never read or tap the screen while driving. A passenger or a preconfigured capture is required.",
            "Do not hold a stationary high RPM if your vehicle or local rules do not allow it.",
            "Stop for a flashing MIL, severe misfire, overheating, fuel smell or abnormal noise.",
            "This test does not prove a failed sensor or a leaking gasket; it ranks what to inspect next.",
        ),
        limitations = listOf(
            "A vacuum leak, MAF bias, fuel delivery fault, exhaust leak and sensor error can produce similar trims.",
            "Thresholds are configurable starting points, not universal specifications.",
            "Bank 2 is only analysed when the car reports bank 2 trims.",
            "Diesel engines do not report petrol-style fuel trims; this guide is for petrol engines.",
        ),
        engineRequirement = EngineRequirement.PETROL,
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030")),
    )

    val thermostat = DiagnosticDefinition(
        id = "thermostat_ect",
        version = "1.0.0",
        title = "Thermostat or temperature sensor?",
        subtitle = "P0128 / P0115–P0119 · warm-up",
        description = "Record a cold start and watch the coolant curve. Warm-up time, the final plateau and implausible jumps separate a thermostat that opens early from a sensor or wiring problem.",
        phase = DiagnosticPhase.PHASE_1,
        category = DiagnosticCategory.ENGINE_FUEL,
        keywords = listOf("thermostat", "coolant", "temperature", "warm-up", "p0128", "overheating"),
        estimatedMinutes = 15,
        durationNote = "Needs an overnight cold soak, then about 15 minutes of capture.",
        dtcs = listOf("P0128", "P0115", "P0116", "P0117", "P0118", "P0119"),
        dtcMatch = listOf("P0128", "P0115", "P0116", "P0117", "P0118", "P0119"),
        requiredSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.IAT, "Intake air temperature", required = false),
            SignalRequirement(Signals.AMBIENT, "Ambient temperature", required = false),
            SignalRequirement(Signals.RPM, "Engine speed", required = false),
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
        ),
        stages = listOf(
            StageDefinition(
                id = "cold_warmup",
                title = "Cold start warm-up",
                instruction = "Start the engine and leave it running. The app records until the coolant temperature stops rising or 10 minutes pass. You may drive gently if that is normal for your car.",
                condition = StageCondition(
                    kind = StageKind.COLD_START_WARMUP,
                    requireEngineRunning = true,
                    settlingMs = 0,
                    captureMs = 600_000,
                    completeOnCoolantPlateau = true,
                    plateauCoolantC = 75.0,
                    plateauWindowMs = 60_000,
                    plateauToleranceC = 2.0,
                ),
                pids = listOf(
                    Signals.COOLANT,
                    Signals.IAT,
                    Signals.AMBIENT,
                    Signals.RPM,
                    Signals.SPEED,
                    Signals.LOAD,
                ),
                allowFinishEarly = true,
                note = "A proper cold-soak comparison needs an engine that has been off for several hours.",
            ),
        ),
        whatYouNeed = listOf(
            "An engine that has been off long enough to approach ambient temperature (ideally overnight).",
            "A safe place to start and idle; you may drive gently if that is normal.",
            "About 10–15 minutes, or until the temperature stops rising.",
        ),
        safetyNotes = listOf(
            "Do not open the cooling system while hot.",
            "Stop if the temperature gauge enters the red, coolant is lost, or you smell coolant.",
            "A cold-soak comparison is invalid if the engine was recently run; the app will say so.",
        ),
        limitations = listOf(
            "Thermostat setpoints, ambient temperature, cabin heating and engine design all change the warm-up curve.",
            "A stuck-open thermostat and an inaccurate coolant sensor can coexist.",
            "This test observes; it does not replace a pressure test or service information.",
        ),
        requiresColdEngine = true,
        safety = SafetyRules(maxCoolantC = 110.0),
    )

    val charging = DiagnosticDefinition(
        id = "charging_system",
        version = "1.0.0",
        title = "Charging and electrical health",
        subtitle = "P0560–P0563 / P0620 · voltage",
        description = "Compare voltage with the engine off, at idle, at a moderate engine speed and under electrical load. Smart charging can deliberately lower output, so a low reading is not automatically a failed alternator.",
        phase = DiagnosticPhase.PHASE_1,
        category = DiagnosticCategory.ELECTRICAL_STARTING,
        keywords = listOf("charging", "alternator", "battery", "voltage", "p0560", "p0620", "electrical"),
        estimatedMinutes = 8,
        dtcs = listOf("P0560", "P0561", "P0562", "P0563", "P0620"),
        dtcMatch = listOf("P0560", "P0561", "P0562", "P0563", "P0620"),
        requiredSignals = listOf(
            SignalRequirement(
                Signals.CONTROL_MODULE_VOLTAGE,
                "Control module voltage",
                alternatives = listOf(Signals.ADAPTER_VOLTAGE),
            ),
            SignalRequirement(Signals.RPM, "Engine speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
        ),
        stages = listOf(
            StageDefinition(
                id = "key_on_engine_off",
                title = "Ignition on, engine off",
                instruction = "Turn the ignition on but do not start the engine. Wait for the voltage to settle.",
                condition = StageCondition(
                    kind = StageKind.KEY_ON_ENGINE_OFF,
                    requireEngineOff = true,
                    settlingMs = 4_000,
                    captureMs = 10_000,
                ),
                pids = chargingPids(),
            ),
            StageDefinition(
                id = "warm_idle",
                title = "Warm idle",
                instruction = "Start the engine and let it idle. Switch off unnecessary accessories.",
                condition = StageCondition(
                    kind = StageKind.WARM_IDLE,
                    rpmRange = 550..1_100,
                    minCoolantC = 60.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 120,
                    settlingMs = 6_000,
                    captureMs = 15_000,
                ),
                pids = chargingPids(),
            ),
            StageDefinition(
                id = "elevated_rpm",
                title = "Moderate engine speed",
                instruction = "Hold a steady 1,800–2,500 rpm in park or neutral, if safe for your engine.",
                condition = StageCondition(
                    kind = StageKind.STEADY_RPM,
                    rpmRange = 1_500..2_700,
                    minCoolantC = 60.0,
                    maxSpeedKph = 5.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 200,
                    settlingMs = 4_000,
                    captureMs = 15_000,
                ),
                pids = chargingPids(),
            ),
            StageDefinition(
                id = "electrical_load",
                title = "Idle with electrical load",
                instruction = "At idle, switch on the headlights and rear demister (or another normal load). Keep them on for the capture.",
                condition = StageCondition(
                    kind = StageKind.ELECTRICAL_LOAD,
                    rpmRange = 550..1_100,
                    minCoolantC = 60.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 150,
                    settlingMs = 4_000,
                    captureMs = 15_000,
                ),
                pids = chargingPids(),
                optional = true,
            ),
        ),
        whatYouNeed = listOf(
            "A safe place to sit with the ignition on and the engine idling.",
            "About 8 minutes and the ability to switch headlights or a demister on.",
            "The OBD adapter connected for the whole test.",
        ),
        safetyNotes = listOf(
            "Do not disconnect the battery or touch alternator terminals while the engine runs.",
            "Stop for a burning smell, smoke, unusual noise or a battery warning light.",
            "A low voltage reading alone does not prove the alternator is faulty.",
        ),
        limitations = listOf(
            "Control-module voltage is not a battery-post or alternator B+ measurement.",
            "Smart charging and start-stop systems may intentionally reduce output.",
            "Lithium starter-battery retrofits and failing batteries change the interpretation.",
            "Adapter voltage (`ATRV`) is supplementary and can be lower than ECU voltage.",
        ),
        safety = SafetyRules(minVoltage = 9.5, maxVoltage = 16.5),
    )

    val mapPlausibility = DiagnosticDefinition(
        id = "map_plausibility",
        version = "1.0.0",
        title = "MAP sensor plausibility",
        subtitle = "P0105–P0109 · manifold pressure",
        description = "Compare manifold pressure with barometric pressure with the engine off, then check the idle vacuum and the response to a gentle throttle blip. The expected pattern depends on the engine.",
        phase = DiagnosticPhase.PHASE_1,
        category = DiagnosticCategory.AIRFLOW_SENSORS,
        keywords = listOf("map", "manifold pressure", "vacuum", "baro", "p0105", "boost"),
        estimatedMinutes = 7,
        dtcs = listOf("P0105", "P0106", "P0107", "P0108", "P0109"),
        dtcMatch = listOf("P0105", "P0106", "P0107", "P0108", "P0109"),
        requiredSignals = listOf(
            SignalRequirement(Signals.MAP, "Manifold absolute pressure"),
            SignalRequirement(Signals.RPM, "Engine speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.BARO, "Barometric pressure", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.IAT, "Intake air temperature", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
        ),
        stages = listOf(
            StageDefinition(
                id = "key_on_engine_off",
                title = "Ignition on, engine off",
                instruction = "Turn the ignition on but do not start the engine. MAP should read close to atmospheric pressure.",
                condition = StageCondition(
                    kind = StageKind.KEY_ON_ENGINE_OFF,
                    requireEngineOff = true,
                    settlingMs = 4_000,
                    captureMs = 8_000,
                ),
                pids = mapPids(),
            ),
            StageDefinition(
                id = "warm_idle",
                title = "Warm idle",
                instruction = "With the engine warm, let it idle without touching the throttle.",
                condition = StageCondition(
                    kind = StageKind.WARM_IDLE,
                    rpmRange = 550..1_100,
                    minCoolantC = 70.0,
                    maxSpeedKph = 5.0,
                    requireEngineRunning = true,
                    stabilityToleranceRpm = 120,
                    settlingMs = 6_000,
                    captureMs = 15_000,
                ),
                pids = mapPids(),
            ),
            StageDefinition(
                id = "throttle_response",
                title = "Gentle throttle blip",
                instruction = "With the engine warm and in neutral or park, open the throttle gently once or twice, then let it return to idle.",
                condition = StageCondition(
                    kind = StageKind.FREE_REV,
                    requireEngineRunning = true,
                    maxSpeedKph = 5.0,
                    settlingMs = 2_000,
                    captureMs = 10_000,
                ),
                pids = mapPids(),
                optional = true,
                note = "Skip if stationary revving is not appropriate for your engine or surroundings.",
            ),
        ),
        whatYouNeed = listOf(
            "A safe place to switch the ignition on and idle the engine.",
            "About 7 minutes.",
            "An engine you can identify as naturally aspirated petrol, turbo petrol or diesel.",
        ),
        safetyNotes = listOf(
            "Do not blip the throttle aggressively or hold a high RPM in neutral.",
            "Keep clear of the engine bay while the engine is running.",
            "Stop for a flashing MIL, abnormal noise or overheating.",
        ),
        limitations = listOf(
            "The idle-vacuum rules apply to naturally aspirated petrol engines only.",
            "Altitude, intake restrictions and throttle calibration shift absolute values.",
            "A MAP/BARO mismatch can be a sensor, wiring, reference-voltage or unit problem.",
            "Diesels and forced-induction engines need different expected values.",
        ),
        engineRequirement = EngineRequirement.PETROL_NATURALLY_ASPIRATED,
        requiresWarmEngine = true,
    )

    // ---- Phase 2–4 -------------------------------------------------------

    val richMixture = DiagnosticsPhase2.richMixture
    val oxygenResponse = DiagnosticsPhase2.oxygenResponse
    val misfire = DiagnosticsPhase2.misfire
    val mafPlausibility = DiagnosticsPhase2.mafPlausibility
    val crankNoStart = DiagnosticsPhase2.crankNoStart

    val boostControl = DiagnosticsPhase3.boostControl
    val fuelPressure = DiagnosticsPhase3.fuelPressure
    val egr = DiagnosticsPhase3.egr
    val throttlePedal = DiagnosticsPhase3.throttlePedal
    val evapPurge = DiagnosticsPhase3.evapPurge
    val dpfHealth = DiagnosticsPhase3.dpfHealth

    val fuelConsumption = DiagnosticsPhase4.fuelConsumption
    val intermittentPowerLoss = DiagnosticsPhase4.intermittentPowerLoss
    val readiness = DiagnosticsPhase4.readiness
    val usedCarInspection = DiagnosticsPhase4.usedCarInspection
    val healthBaseline = DiagnosticsPhase4.healthBaseline

    private fun leanPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.FUEL_SYSTEM_STATUS,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.STFT_B2,
        Signals.LTFT_B2,
        Signals.MAF,
        Signals.MAP,
        Signals.THROTTLE,
        Signals.SPEED,
        Signals.LOAD,
    )

    private fun chargingPids(): List<Int> = listOf(
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.ADAPTER_VOLTAGE,
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
    )

    private fun mapPids(): List<Int> = listOf(
        Signals.MAP,
        Signals.RPM,
        Signals.BARO,
        Signals.THROTTLE,
        Signals.IAT,
        Signals.MAF,
        Signals.LOAD,
        Signals.COOLANT,
        Signals.SPEED,
    )

}
