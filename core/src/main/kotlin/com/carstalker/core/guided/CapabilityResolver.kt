package com.carstalker.core.guided

/**
 * Resolves whether a diagnostic can run on the connected vehicle.
 *
 * The resolver is deliberately conservative: it never assumes an optional signal exists, it
 * distinguishes "not advertised" from "not read yet", and it downgrades a test to limited rather
 * than hiding the reason. A Start action is only offered for startable states.
 */
object CapabilityResolver {

    fun resolve(
        definition: DiagnosticDefinition,
        capabilities: VehicleCapabilities,
        scan: ScanContext = ScanContext(),
    ): AvailabilityReport {
        if (!definition.implemented) {
            return AvailabilityReport(
                availability = DiagnosticAvailability.PLANNED,
                reasons = listOf(definition.catalogueNote ?: "This guide is planned but not implemented yet."),
            )
        }

        val reasons = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val known = capabilities.supportedPids.isNotEmpty()

        if (!known) {
            reasons += "Supported PIDs have not been read from this vehicle yet; the test may be limited."
        }

        for (signal in definition.requiredSignals) {
            if (!signal.resolvedBy(capabilities)) missing += signal.label
        }
        if (missing.isNotEmpty()) {
            return AvailabilityReport(
                availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                reasons = listOf("The vehicle does not report: ${missing.joinToString()}."),
                missingSignals = missing,
            )
        }

        val engineReport = engineAvailability(definition, capabilities.profile)
        if (engineReport.availability == DiagnosticAvailability.VEHICLE_UNSUPPORTED) return engineReport

        var availability = if (known) {
            engineReport.availability
        } else {
            downgrade(engineReport.availability, DiagnosticAvailability.LIMITED)
        }
        reasons += engineReport.reasons

        val optionalMissing = definition.optionalSignals
            .filterNot { it.resolvedBy(capabilities) }
            .map { it.label }

        when (definition.id) {
            DiagnosticRegistry.catalyst.id -> {
                val narrowband = capabilities.supports(Signals.O2_B1S1) &&
                    capabilities.supports(Signals.O2_B1S2)
                when {
                    narrowband -> Unit
                    capabilities.widebandUpstream -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "Wideband upstream O2 detected: the live narrowband comparison is unavailable, so this test relies on Mode 06 and the manual observations."
                    }
                    !known -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "O2 support is not known yet; Mode 06 and the manual observations still work."
                    }
                    else -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "The car does not advertise both upstream and downstream conventional O2 PIDs; only the Mode 06 and manual path is available."
                    }
                }
            }

            DiagnosticRegistry.charging.id -> {
                val moduleVoltage = capabilities.supports(Signals.CONTROL_MODULE_VOLTAGE)
                if (!moduleVoltage) {
                    if (capabilities.adapterVoltageAvailable) {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "Control-module voltage (PID 42) is unavailable; adapter voltage is supplementary and can read lower."
                    } else {
                        return AvailabilityReport(
                            availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                            reasons = listOf("No control-module or adapter voltage reading is available."),
                            missingSignals = listOf("Control module voltage"),
                        )
                    }
                }
            }

            DiagnosticRegistry.mapPlausibility.id -> {
                if (capabilities.profile.diesel || capabilities.profile.forcedInduction) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Idle-vacuum rules apply to naturally aspirated petrol engines. Only the key-on pressure plausibility part will be interpreted."
                }
                if (capabilities.profile.fuelType == FuelType.UNKNOWN) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Set the engine type in Settings to get the full petrol-engine interpretation."
                }
            }

            DiagnosticRegistry.thermostat.id -> {
                availability = if (definition.requiresColdEngine) {
                    DiagnosticAvailability.NEEDS_COLD_ENGINE
                } else {
                    availability
                }
                reasons += "Requires an engine that has been off for several hours for the cold-soak comparison."
            }

            DiagnosticRegistry.oxygenResponse.id -> {
                val narrowband = capabilities.supports(Signals.O2_B1S1)
                val wideband = capabilities.advertises(Signals.WIDEBAND_B1S1) ||
                    capabilities.advertises(Signals.WIDEBAND_CURRENT_B1S1)
                when {
                    narrowband -> Unit
                    wideband -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "Only a wideband/equivalence-ratio upstream channel is reported; narrowband switching rules are not applied."
                    }
                    !known -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "Upstream O2 support is not known yet; the test may be limited."
                    }
                }
                if (!capabilities.supports(Signals.O2_B1S2)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "No downstream O2 channel is reported; catalyst damping cannot be assessed."
                }
            }

            DiagnosticRegistry.misfire.id -> {
                availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                reasons += "Generic OBD does not provide verified cylinder-specific misfire counts on every vehicle; the test uses codes, RPM stability and trims."
            }

            DiagnosticRegistry.mafPlausibility.id -> {
                if (!capabilities.supports(Signals.MAP)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "MAP is not reported; the airflow consistency check is reduced to MAF versus RPM."
                }
                if (capabilities.profile.fuelType == FuelType.UNKNOWN) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Engine type is not set; the airflow interpretation is generic."
                }
            }

            DiagnosticRegistry.crankNoStart.id -> {
                if (!capabilities.supports(Signals.CONTROL_MODULE_VOLTAGE)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Control-module voltage is unavailable; adapter voltage is supplementary and may read lower."
                }
                if (!capabilities.supports(Signals.MAP)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "MAP is not reported; the cranking capture will contain RPM and voltage only."
                }
            }

            DiagnosticRegistry.boostControl.id -> {
                availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                reasons += "Requested boost is not a generic OBD-II PID; this is a measured-boost trend, not an under/overboost verdict."
                if (!capabilities.supports(Signals.BARO)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Barometric pressure is not reported; a nominal reference is used, so altitude reduces confidence."
                }
            }

            DiagnosticRegistry.fuelPressure.id -> {
                val lowPressure = capabilities.supports(Signals.FUEL_PRESSURE)
                val relativeRail = capabilities.supports(Signals.RAIL_PRESSURE)
                val gaugeRail = capabilities.supports(Signals.RAIL_GAUGE_PRESSURE)
                when {
                    lowPressure -> Unit
                    relativeRail || gaugeRail -> {
                        availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                        reasons += "Only a rail-pressure channel is reported; low-pressure fuel supply and requested-versus-actual comparison are unavailable."
                    }
                }
            }

            DiagnosticRegistry.egr.id -> {
                if (!capabilities.supports(Signals.EGR_COMMANDED)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Only EGR error is reported; the test cannot see how much EGR the ECU requested."
                }
                if (!capabilities.supports(Signals.MAP) && !capabilities.supports(Signals.MAF)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Neither MAP nor MAF is reported; the airflow response to EGR cannot be checked."
                }
            }

            DiagnosticRegistry.throttlePedal.id -> {
                if (!capabilities.supports(Signals.PEDAL_D) && !capabilities.supports(Signals.PEDAL_E)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "No accelerator-pedal channel is reported; only throttle/commanded-throttle correlation is possible."
                }
                if (!capabilities.supports(Signals.THROTTLE_B) && !capabilities.supports(Signals.RELATIVE_THROTTLE)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "No redundant or relative throttle channel is reported; redundant-sensor correlation is limited."
                }
            }

            DiagnosticRegistry.dpfHealth.id -> {
                if (!capabilities.supports(Signals.DPF_TEMPERATURE)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "DPF temperature (PID 0x7C) is not reported; pressure response can be checked but regeneration temperature cannot."
                }
                if (!capabilities.supports(Signals.MAF) && !capabilities.supports(Signals.MAP)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Neither MAF nor MAP is reported; the exhaust-flow context is weaker."
                }
            }

            DiagnosticRegistry.fuelConsumption.id -> {
                availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                reasons += "No stored same-condition baseline is available; this run produces a fuelling snapshot, not a consumption verdict."
            }

            DiagnosticRegistry.intermittentPowerLoss.id -> {
                availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                reasons += "The symptom marker is manual; a passenger must operate the app while the vehicle is driven."
                if (!capabilities.supports(Signals.MAP) && !capabilities.supports(Signals.MAF)) {
                    availability = downgrade(availability, DiagnosticAvailability.LIMITED)
                    reasons += "Neither MAP nor MAF is reported; the boost/airflow context is weaker."
                }
            }

            DiagnosticRegistry.readiness.id -> {
                if (scan.monitor == null) {
                    return AvailabilityReport(
                        availability = DiagnosticAvailability.NEEDS_SCAN,
                        reasons = listOf("Read codes first so the ECU's readiness monitor states are available."),
                        missingSignals = listOf("Readiness monitor data"),
                    )
                }
            }

            DiagnosticRegistry.usedCarInspection.id -> {
                if (!scan.hasScan) {
                    return AvailabilityReport(
                        availability = DiagnosticAvailability.NEEDS_SCAN,
                        reasons = listOf("Read codes first so the code history and readiness data are available."),
                        missingSignals = listOf("Code scan"),
                    )
                }
            }
        }

        if (optionalMissing.isNotEmpty()) {
            reasons += "Optional signals not reported: ${optionalMissing.joinToString()}."
        }

        return AvailabilityReport(
            availability = availability,
            reasons = reasons.distinct(),
            missingSignals = optionalMissing,
            engineNote = engineReport.engineNote,
        )
    }

    private fun engineAvailability(
        definition: DiagnosticDefinition,
        profile: VehicleProfile,
    ): AvailabilityReport = when (definition.engineRequirement) {
        EngineRequirement.ANY -> AvailabilityReport(DiagnosticAvailability.READY)
        EngineRequirement.PETROL -> when (profile.fuelType) {
            FuelType.PETROL, FuelType.HYBRID, FuelType.LPG -> AvailabilityReport(DiagnosticAvailability.READY)
            FuelType.DIESEL -> AvailabilityReport(
                availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                reasons = listOf("This guide uses petrol fuel-trim behaviour and does not apply to a diesel engine."),
                engineNote = profile.label(),
            )
            FuelType.UNKNOWN -> AvailabilityReport(
                availability = DiagnosticAvailability.LIMITED,
                reasons = listOf("Engine fuel type is not set. Set it in Settings so the interpretation matches the car."),
                engineNote = profile.label(),
            )
        }
        EngineRequirement.PETROL_NATURALLY_ASPIRATED -> when {
            profile.diesel -> AvailabilityReport(
                availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                reasons = listOf("MAP plausibility for a diesel needs different expected values; this guide is for naturally aspirated petrol engines."),
                engineNote = profile.label(),
            )
            profile.forcedInduction -> AvailabilityReport(
                availability = DiagnosticAvailability.LIMITED,
                reasons = listOf("Forced induction changes manifold pressure; only key-on plausibility is interpreted."),
                engineNote = profile.label(),
            )
            profile.petrolLike && profile.naturallyAspirated -> AvailabilityReport(DiagnosticAvailability.READY)
            else -> AvailabilityReport(
                availability = DiagnosticAvailability.LIMITED,
                reasons = listOf("Engine type is not set; idle-vacuum interpretation is disabled until it is."),
                engineNote = profile.label(),
            )
        }
        EngineRequirement.DIESEL -> when (profile.fuelType) {
            FuelType.DIESEL -> AvailabilityReport(DiagnosticAvailability.READY)
            FuelType.UNKNOWN -> AvailabilityReport(
                availability = DiagnosticAvailability.LIMITED,
                reasons = listOf("Diesel engine type is not set."),
                engineNote = profile.label(),
            )
            else -> AvailabilityReport(
                availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                reasons = listOf("This guide applies to diesel engines."),
                engineNote = profile.label(),
            )
        }
        EngineRequirement.FORCED_INDUCTION -> when {
            profile.forcedInduction -> AvailabilityReport(DiagnosticAvailability.READY)
            profile.naturallyAspirated -> AvailabilityReport(
                availability = DiagnosticAvailability.VEHICLE_UNSUPPORTED,
                reasons = listOf("Boost-control interpretation does not apply to a naturally aspirated engine."),
                engineNote = profile.label(),
            )
            else -> AvailabilityReport(
                availability = DiagnosticAvailability.LIMITED,
                reasons = listOf("Aspiration is not set; boost-control interpretation needs a turbo/supercharged profile."),
                engineNote = profile.label(),
            )
        }
    }

    /** Never upgrade: the more limited state wins. */
    private fun downgrade(
        current: DiagnosticAvailability,
        candidate: DiagnosticAvailability,
    ): DiagnosticAvailability = if (current.ordinal >= candidate.ordinal) current else candidate
}
