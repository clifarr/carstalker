package com.carstalker.core.guided

/**
 * Phase 4 definitions: no-code health checks. Fuel consumption, readiness and used-car inspection
 * use data the app already has. Triggered intermittent capture and longitudinal baselines still
 * need infrastructure that is not present in this build.
 */
object DiagnosticsPhase4 {

    val all: List<DiagnosticDefinition> get() = listOf(
        fuelConsumption,
        intermittentPowerLoss,
        readiness,
        usedCarInspection,
        healthBaseline,
    )

    private fun consumptionPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.LOAD,
        Signals.MAF,
        Signals.MAP,
        Signals.FUEL_RATE,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.FUEL_LEVEL,
    )

    private fun readinessPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.COOLANT,
        Signals.WARMUPS_SINCE_CLEARED,
        Signals.DISTANCE_SINCE_CLEARED,
        Signals.ENGINE_RUN_TIME_MIL,
        Signals.TIME_SINCE_CLEARED,
    )

    private fun inspectionPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.MAP,
        Signals.MAF,
    )

    val fuelConsumption = DiagnosticDefinition(
        id = "fuel_consumption",
        version = "1.0.0",
        title = "Fuel consumption health check",
        subtitle = "No DTC required · fuelling context",
        description = "Capture fuel rate, airflow and trims at warm idle and a steady elevated RPM. Without a stored same-condition baseline the result is a snapshot, not a verdict on consumption.",
        phase = DiagnosticPhase.PHASE_4,
        estimatedMinutes = 10,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.LOAD, "Calculated load"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.FUEL_RATE, "Engine fuel rate", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.FUEL_LEVEL, "Fuel tank level", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(consumptionPids()),
            DefinitionSupport.steadyRpm(consumptionPids()),
        ),
        whatYouNeed = listOf(
            "A fully warmed engine and a safe place to idle and hold a steady RPM.",
            "A baseline capture from the same vehicle and conditions for a comparison.",
            "About 10 minutes. Do not drive while using the phone.",
        ),
        safetyNotes = listOf(
            "Do not hold a stationary high RPM if your vehicle or local rules do not allow it.",
            "Stop for a flashing MIL, severe misfire, overheating or a fuel smell.",
            "Fuel consumption is affected by driving style, load, temperature and fuel quality.",
        ),
        limitations = listOf(
            "Without a stored baseline this test can only document the current fuelling context.",
            "Fuel rate may be calculated by the ECU rather than measured directly.",
            "MAF-based estimates are approximations and are labelled as such.",
            "Short captures cannot represent long-term consumption.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_000),
    )

    val readiness = DiagnosticDefinition(
        id = "readiness_pre_vrt",
        version = "1.0.0",
        title = "Emissions readiness check",
        subtitle = "No DTC required · pre-test check",
        description = "Summarise readiness monitors, stored/pending/permanent codes and the counters the ECU exposes. Incomplete readiness is not proof of deliberate clearing, and a clean scan is not a compliance guarantee.",
        phase = DiagnosticPhase.PHASE_4,
        estimatedMinutes = 5,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.WARMUPS_SINCE_CLEARED, "Warm-ups since codes cleared", required = false),
            SignalRequirement(Signals.DISTANCE_SINCE_CLEARED, "Distance since codes cleared", required = false),
            SignalRequirement(Signals.ENGINE_RUN_TIME_MIL, "Time with MIL on", required = false),
            SignalRequirement(Signals.TIME_SINCE_CLEARED, "Time since codes cleared", required = false),
        ),
        stages = listOf(
            DefinitionSupport.keyOn(
                readinessPids(),
                captureMs = 6_000,
                instruction = "Turn the ignition on but do not start the engine. The app records the readiness counters.",
            ),
        ),
        whatYouNeed = listOf(
            "The ignition on, engine off, and the adapter connected.",
            "A recent code scan so monitor readiness is available.",
            "About 5 minutes. This is a passive summary, not a repair test.",
        ),
        safetyNotes = listOf(
            "Do not use a clean readiness result as a guarantee of emissions compliance.",
            "Incomplete readiness is normal after a battery disconnect or code clear; it is not proof of tampering.",
            "Address stored or pending faults before presenting the vehicle for a test.",
        ),
        limitations = listOf(
            "Readiness monitor names and support vary by vehicle and protocol.",
            "The app cannot prove why a monitor is incomplete.",
            "Permanent codes may remain after a repair until the ECU clears them.",
            "A clean scan does not replace a state emissions test.",
        ),
        safety = SafetyRules(
            stopOnMisfireCodes = false,
            stopCodePrefixes = emptyList(),
            maxRpm = 1_500,
        ),
    )

    val usedCarInspection = DiagnosticDefinition(
        id = "used_car_inspection",
        version = "1.0.0",
        title = "Used-car inspection",
        subtitle = "No DTC required · health summary",
        description = "A passive summary of codes, readiness, counters, trims, warm-up and charging readings for a pre-purchase check. It documents evidence and limitations; it is not a substitute for a mechanical inspection.",
        phase = DiagnosticPhase.PHASE_4,
        estimatedMinutes = 12,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.COOLANT, "Coolant temperature", required = false),
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.WARMUPS_SINCE_CLEARED, "Warm-ups since codes cleared", required = false),
            SignalRequirement(Signals.DISTANCE_SINCE_CLEARED, "Distance since codes cleared", required = false),
            SignalRequirement(Signals.TIME_SINCE_CLEARED, "Time since codes cleared", required = false),
        ),
        stages = listOf(
            DefinitionSupport.keyOn(readinessPids(), captureMs = 5_000),
            DefinitionSupport.warmIdle(inspectionPids(), captureMs = 20_000),
        ),
        whatYouNeed = listOf(
            "A cold or warm car, depending on what you want to check, and a safe place to idle.",
            "A recent code scan so readiness and permanent codes are available.",
            "About 12 minutes. A mechanical inspection is still required.",
        ),
        safetyNotes = listOf(
            "Do not use this summary as a guarantee of roadworthiness or emissions compliance.",
            "Do not test a car in an unsafe location or one with a known fuel leak or overheating problem.",
            "A clean scan can hide recent clearing, pending faults or mechanical problems.",
        ),
        limitations = listOf(
            "This is a data summary, not a full pre-purchase inspection.",
            "The app cannot verify mileage, service history, body condition or safety systems.",
            "Readiness and counter data depend on what the ECU exposes.",
            "A short idle capture cannot represent all driving conditions.",
        ),
        safety = SafetyRules(
            stopOnMisfireCodes = false,
            stopCodePrefixes = emptyList(),
            maxRpm = 2_500,
        ),
    )

    val intermittentPowerLoss = DiagnosticDefinition(
        id = "intermittent_power_loss",
        version = "1.0.0",
        title = "Intermittent power loss",
        subtitle = "Various · triggered capture",
        description = "Record a passive drive capture and mark the moment the symptom occurs. The result compares the marked event with the seconds before it and ranks ignition, fuel, boost, electrical and mechanical possibilities.",
        phase = DiagnosticPhase.PHASE_4,
        estimatedMinutes = 12,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.LOAD, "Calculated load"),
            SignalRequirement(Signals.SPEED, "Vehicle speed"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.THROTTLE, "Throttle position", required = false),
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.FUEL_PRESSURE, "Fuel pressure", required = false),
        ),
        stages = listOf(
            DefinitionSupport.passiveCapture(
                pids = powerLossPids(),
                captureMs = 90_000,
            ),
        ),
        whatYouNeed = listOf(
            "A passenger to operate the app while you drive, or a preconfigured unattended capture.",
            "A safe road and normal driving conditions; do not use the phone while driving.",
            "About 12 minutes, including a short drive.",
        ),
        safetyNotes = listOf(
            "Never read or tap the screen while driving. The passenger marks the symptom.",
            "Stop for a flashing MIL, severe misfire, overheating, smoke, a fuel smell or abnormal noise.",
            "Do not attempt to reproduce a power loss in traffic or at high speed.",
            "A marked event is evidence for a follow-up test, not a diagnosis.",
        ),
        limitations = listOf(
            "The symptom marker is manual; event timing depends on the passenger and adapter sample rate.",
            "A short capture may not reproduce an intermittent fault.",
            "The test cannot distinguish ignition, fuel, boost, electrical, transmission or traction-control causes.",
            "Driving conditions and driver input can change the signals around the marker.",
        ),
        engineRequirement = EngineRequirement.ANY,
        safety = SafetyRules(
            stopCodePrefixes = listOf("P030"),
            maxRpm = 5_000,
            minVoltage = 8.0,
        ),
    )

    private fun powerLossPids(): List<Int> = listOf(
        Signals.RPM,
        Signals.LOAD,
        Signals.SPEED,
        Signals.MAP,
        Signals.MAF,
        Signals.THROTTLE,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.FUEL_PRESSURE,
    )

    val healthBaseline = DiagnosticDefinition(
        id = "health_baseline",
        version = "1.0.0",
        title = "Longitudinal health baseline",
        subtitle = "No DTC required · same-condition trend",
        description = "Save a same-vehicle, same-condition baseline and compare later captures. The result checks RPM, coolant, load and ambient match before it says anything about a trend.",
        phase = DiagnosticPhase.PHASE_4,
        estimatedMinutes = 12,
        dtcs = emptyList(),
        dtcMatch = emptyList(),
        requiredSignals = listOf(
            SignalRequirement(Signals.RPM, "Engine speed"),
            SignalRequirement(Signals.COOLANT, "Coolant temperature"),
        ),
        optionalSignals = listOf(
            SignalRequirement(Signals.MAF, "Mass air flow", required = false),
            SignalRequirement(Signals.MAP, "Manifold pressure", required = false),
            SignalRequirement(Signals.LOAD, "Calculated load", required = false),
            SignalRequirement(Signals.STFT_B1, "Short-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.LTFT_B1, "Long-term fuel trim, bank 1", required = false),
            SignalRequirement(Signals.SPEED, "Vehicle speed", required = false),
            SignalRequirement(Signals.CONTROL_MODULE_VOLTAGE, "Control-module voltage", required = false),
            SignalRequirement(Signals.IAT, "Intake air temperature", required = false),
            SignalRequirement(Signals.AMBIENT, "Ambient temperature", required = false),
            SignalRequirement(Signals.FUEL_RATE, "Engine fuel rate", required = false),
        ),
        stages = listOf(
            DefinitionSupport.warmIdle(baselinePids()),
            DefinitionSupport.steadyRpm(baselinePids()),
        ),
        whatYouNeed = listOf(
            "The same vehicle, engine and conditions as the saved baseline.",
            "A fully warmed engine and a safe place to idle and hold a steady RPM.",
            "About 12 minutes. Save the first run as the baseline, then repeat later.",
        ),
        safetyNotes = listOf(
            "Do not hold a stationary high RPM if your vehicle or local rules do not allow it.",
            "Stop for a flashing MIL, severe misfire, overheating or abnormal noise.",
            "A baseline is only comparable when the engine state, RPM, load and ambient conditions match.",
        ),
        limitations = listOf(
            "The first run has no baseline to compare; save it and repeat under the same conditions.",
            "A trend is not a diagnosis; components can drift without setting a code.",
            "Mismatched conditions, ambient changes and capture quality can produce false differences.",
        ),
        requiresWarmEngine = true,
        safety = SafetyRules(stopCodePrefixes = listOf("P030"), maxRpm = 3_000),
    )

    private fun baselinePids(): List<Int> = listOf(
        Signals.RPM,
        Signals.COOLANT,
        Signals.SPEED,
        Signals.LOAD,
        Signals.MAF,
        Signals.MAP,
        Signals.STFT_B1,
        Signals.LTFT_B1,
        Signals.CONTROL_MODULE_VOLTAGE,
        Signals.IAT,
        Signals.AMBIENT,
        Signals.FUEL_RATE,
    )
}
