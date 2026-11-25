package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.obd.MonitorDataState
import com.carstalker.core.obd.MonitorStatus
import java.util.Locale

/** Generic OBD-II PIDs used by the guided tests. */
object Signals {
    /** Synthetic signal: voltage measured by the adapter itself (`ATRV`), not a PID. */
    const val ADAPTER_VOLTAGE = -1

    const val FUEL_SYSTEM_STATUS = 0x03
    const val LOAD = 0x04
    const val COOLANT = 0x05
    const val STFT_B1 = 0x06
    const val LTFT_B1 = 0x07
    const val STFT_B2 = 0x08
    const val LTFT_B2 = 0x09
    const val FUEL_PRESSURE = 0x0A
    const val MAP = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val TIMING_ADVANCE = 0x0E
    const val IAT = 0x0F
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val O2_B1S1 = 0x14
    const val O2_B1S2 = 0x15
    const val RUN_TIME = 0x1F
    const val RAIL_PRESSURE = 0x22
    const val RAIL_GAUGE_PRESSURE = 0x23
    const val WIDEBAND_B1S1 = 0x24
    const val WIDEBAND_B1S2 = 0x25
    const val EGR_COMMANDED = 0x2C
    const val EGR_ERROR = 0x2D
    const val EVAP_PURGE = 0x2E
    const val FUEL_LEVEL = 0x2F
    const val WARMUPS_SINCE_CLEARED = 0x30
    const val DISTANCE_SINCE_CLEARED = 0x31
    const val BARO = 0x33
    const val WIDEBAND_CURRENT_B1S1 = 0x34
    const val WIDEBAND_CURRENT_B1S2 = 0x35
    const val CATALYST_B1S1 = 0x3E
    const val CATALYST_B1S2 = 0x3F
    const val CONTROL_MODULE_VOLTAGE = 0x42
    const val RELATIVE_THROTTLE = 0x45
    const val AMBIENT = 0x46
    const val THROTTLE_B = 0x47
    const val PEDAL_D = 0x49
    const val PEDAL_E = 0x4A
    const val COMMANDED_THROTTLE = 0x4C
    const val ENGINE_RUN_TIME_MIL = 0x4D
    const val TIME_SINCE_CLEARED = 0x4E
    const val OIL_TEMP = 0x5C
    const val FUEL_RATE = 0x5E
    const val DPF_PRESSURE_B1 = 0x7A
    const val DPF_PRESSURE_B2 = 0x7B
    const val DPF_TEMPERATURE = 0x7C
    const val ODOMETER = 0xA6

    fun label(pid: Int): String = when (pid) {
        ADAPTER_VOLTAGE -> "Adapter voltage"
        else -> com.carstalker.core.obd.PidCatalog.find(pid)?.shortName
            ?: "PID 0x${pid.toString(16).uppercase(Locale.US)}"
    }

    fun unit(pid: Int): String = when (pid) {
        ADAPTER_VOLTAGE -> "V"
        else -> com.carstalker.core.obd.PidCatalog.find(pid)?.unit.orEmpty()
    }
}

enum class FuelType(val label: String) {
    UNKNOWN("Not set"),
    PETROL("Petrol / gasoline"),
    DIESEL("Diesel"),
    HYBRID("Hybrid"),
    LPG("LPG / CNG"),
}

enum class Aspiration(val label: String) {
    UNKNOWN("Not set"),
    NATURALLY_ASPIRATED("Naturally aspirated"),
    FORCED_INDUCTION("Turbo / supercharged"),
}

/** What the app knows about the engine. It is never inferred from a single live value. */
data class VehicleProfile(
    val fuelType: FuelType = FuelType.UNKNOWN,
    val aspiration: Aspiration = Aspiration.UNKNOWN,
    val bankCount: Int = 1,
) {
    val petrolLike: Boolean
        get() = fuelType == FuelType.PETROL || fuelType == FuelType.HYBRID || fuelType == FuelType.LPG
    val diesel: Boolean get() = fuelType == FuelType.DIESEL
    val naturallyAspirated: Boolean get() = aspiration == Aspiration.NATURALLY_ASPIRATED
    val forcedInduction: Boolean get() = aspiration == Aspiration.FORCED_INDUCTION

    fun label(): String {
        val fuel = if (fuelType == FuelType.UNKNOWN) "Engine type not set" else fuelType.label
        val aspirationLabel = if (aspiration == Aspiration.UNKNOWN) "" else " · ${aspiration.label}"
        return fuel + aspirationLabel
    }
}

/** Discovered vehicle capabilities. Empty [supportedPids] means "not known yet". */
data class VehicleCapabilities(
    val supportedPids: Set<Int> = emptySet(),
    val profile: VehicleProfile = VehicleProfile(),
    val adapterVoltageAvailable: Boolean = false,
    val protocolName: String? = null,
) {
    fun supports(pid: Int): Boolean = when (pid) {
        Signals.ADAPTER_VOLTAGE -> adapterVoltageAvailable
        else -> supportedPids.isEmpty() || pid in supportedPids
    }

    fun advertises(pid: Int): Boolean = pid in supportedPids

    fun supportsAll(pids: Iterable<Int>): Boolean = pids.all { supports(it) }
    fun supportsAny(pids: Iterable<Int>): Boolean = pids.any { supports(it) }

    /** A wideband/equivalence-ratio upstream sensor is present instead of a conventional 0–1 V PID. */
    val widebandUpstream: Boolean
        get() = !advertises(Signals.O2_B1S1) &&
            (advertises(Signals.WIDEBAND_B1S1) || advertises(Signals.WIDEBAND_CURRENT_B1S1))

    val hasCatalystTemperatureHints: Boolean
        get() = advertises(Signals.CATALYST_B1S1) || advertises(Signals.CATALYST_B1S2)
}

data class SignalRequirement(
    val pid: Int,
    val label: String = Signals.label(pid),
    val required: Boolean = true,
    /** Acceptable substitutes, for example adapter voltage for control-module voltage. */
    val alternatives: List<Int> = emptyList(),
    val unit: String = Signals.unit(pid),
) {
    val candidates: List<Int> get() = listOf(pid) + alternatives

    fun resolvedBy(capabilities: VehicleCapabilities): Boolean = candidates.any { capabilities.supports(it) }
}

enum class DiagnosticPhase(val label: String) {
    PHASE_1("Phase 1"),
    PHASE_2("Phase 2"),
    PHASE_3("Phase 3"),
    PHASE_4("Phase 4"),
}

/**
 * User-facing grouping. [DiagnosticPhase] stays as implementation history; the catalogue is
 * organised by the problem the driver is trying to solve.
 */
enum class DiagnosticCategory(val label: String) {
    ENGINE_FUEL("Engine and fuel"),
    EMISSIONS("Emissions"),
    ELECTRICAL_STARTING("Electrical and starting"),
    AIRFLOW_SENSORS("Airflow, boost and sensors"),
    VEHICLE_HEALTH("Vehicle health"),
    DRIVING_SYMPTOMS("Driving symptoms"),
}

enum class EngineRequirement(val label: String) {
    ANY("Any engine"),
    PETROL("Petrol engines"),
    PETROL_NATURALLY_ASPIRATED("Naturally aspirated petrol engines"),
    DIESEL("Diesel engines"),
    FORCED_INDUCTION("Turbo / supercharged engines"),
}

enum class DiagnosticAvailability(val label: String, val startable: Boolean) {
    READY("Ready", true),
    LIMITED("Limited test", true),
    NEEDS_COLD_ENGINE("Needs cold engine", true),
    NEEDS_WARM_ENGINE("Needs warm engine", true),
    NEEDS_SCAN("Read codes first", false),
    VEHICLE_UNSUPPORTED("Vehicle unsupported", false),
    REQUIRES_ENHANCED_PID("Requires enhanced PID", false),
    PLANNED("Planned", false),
}

data class AvailabilityReport(
    val availability: DiagnosticAvailability,
    val reasons: List<String> = emptyList(),
    val missingSignals: List<String> = emptyList(),
    val engineNote: String? = null,
)

enum class StageKind {
    KEY_ON_ENGINE_OFF,
    WARM_IDLE,
    STEADY_RPM,
    COLD_START_WARMUP,
    ELECTRICAL_LOAD,
    FREE_REV,
    STEADY_CRUISE,
    CRANKING,
    PASSIVE_CAPTURE,
}

data class StageCondition(
    val kind: StageKind,
    val rpmRange: IntRange? = null,
    val minSpeedKph: Double? = null,
    val maxSpeedKph: Double? = null,
    val minCoolantC: Double? = null,
    val maxCoolantC: Double? = null,
    val requireEngineOff: Boolean = false,
    val requireEngineRunning: Boolean = false,
    val stabilityToleranceRpm: Int = 150,
    val stabilityWindowMs: Long = 3_000,
    val settlingMs: Long = 6_000,
    val captureMs: Long = 20_000,
    val graceMs: Long = 2_500,
    /** Finish early once coolant reaches [plateauCoolantC] and stops changing. */
    val completeOnCoolantPlateau: Boolean = false,
    val plateauCoolantC: Double = 75.0,
    val plateauWindowMs: Long = 60_000,
    val plateauToleranceC: Double = 2.0,
)

data class StageDefinition(
    val id: String,
    val title: String,
    val instruction: String,
    val condition: StageCondition,
    val pids: List<Int>,
    val optional: Boolean = false,
    /** The user may finish this stage early, for example once a warm-up has plateaued. */
    val allowFinishEarly: Boolean = false,
    val note: String? = null,
) {
    val kind: StageKind get() = condition.kind
}

data class SafetyRules(
    val stopOnFlashingMil: Boolean = true,
    val stopOnMisfireCodes: Boolean = true,
    val stopCodePrefixes: List<String> = emptyList(),
    val maxCoolantC: Double = 115.0,
    val maxRpm: Int = 5_000,
    val minVoltage: Double = 10.0,
    val maxVoltage: Double = 16.5,
)

data class DiagnosticDefinition(
    val id: String,
    val version: String = "1.0.0",
    val title: String,
    val subtitle: String,
    val description: String,
    val phase: DiagnosticPhase,
    val category: DiagnosticCategory = DiagnosticCategory.ENGINE_FUEL,
    val estimatedMinutes: Int,
    /** Extra search terms such as "won't start", "charging", "smoke test". */
    val keywords: List<String> = emptyList(),
    /** Describes preparation that the estimated capture time does not include. */
    val durationNote: String? = null,
    val dtcs: List<String> = emptyList(),
    /** Exact codes or prefixes used to suggest this guide for a scan result. */
    val dtcMatch: List<String> = emptyList(),
    val requiredSignals: List<SignalRequirement> = emptyList(),
    val optionalSignals: List<SignalRequirement> = emptyList(),
    val stages: List<StageDefinition> = emptyList(),
    val whatYouNeed: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val engineRequirement: EngineRequirement = EngineRequirement.ANY,
    val requiresColdEngine: Boolean = false,
    val requiresWarmEngine: Boolean = false,
    val implemented: Boolean = true,
    val catalogueNote: String? = null,
    /** Existing screen flow that remains the UI for this guide. */
    val legacyWizardId: String? = null,
    val safety: SafetyRules = SafetyRules(),
) {
    fun matchesCode(code: String): Boolean {
        val normalized = code.trim().uppercase(Locale.US)
        return dtcMatch.any { pattern ->
            val clean = pattern.trim().uppercase(Locale.US)
            if (clean.endsWith("*")) normalized.startsWith(clean.dropLast(1)) else normalized == clean
        }
    }
}

data class PrerequisiteAnswers(
    val engineWarmConfirmed: Boolean = false,
    val engineColdConfirmed: Boolean = false,
    val flashingMil: Boolean = false,
    val activeMisfire: Boolean = false,
    val safeLocationConfirmed: Boolean = false,
    val closedLoopConfirmed: Boolean = false,
    val ambientTemperatureC: Double? = null,
    val electricalLoadApplied: Boolean = false,
    val passengerPresent: Boolean = false,
)

enum class GuidedRunState {
    NOT_STARTED,
    PREREQUISITE_CHECK,
    WAITING_FOR_CONDITIONS,
    SETTLING,
    CAPTURING,
    PAUSED,
    STAGE_COMPLETE,
    ANALYSING,
    COMPLETE,
    UNABLE_TO_RUN,
    SAFETY_STOPPED,
    CANCELLED,
}

val GuidedRunState.terminal: Boolean
    get() = this == GuidedRunState.COMPLETE ||
        this == GuidedRunState.UNABLE_TO_RUN ||
        this == GuidedRunState.SAFETY_STOPPED ||
        this == GuidedRunState.CANCELLED

data class ConditionFeedback(
    val satisfied: Boolean,
    val headline: String,
    val detail: String? = null,
    val liveValue: Double? = null,
    val liveUnit: String? = null,
    val target: String? = null,
    val rpmSpread: Double? = null,
)

data class GuidedSnapshot(
    val state: GuidedRunState,
    val stageIndex: Int,
    val stageCount: Int,
    val stage: StageDefinition?,
    val condition: ConditionFeedback,
    val liveValues: Map<Int, Double> = emptyMap(),
    val stageElapsedMs: Long = 0,
    val captureElapsedMs: Long = 0,
    val captureDurationMs: Long = 0,
    val progress: Float = 0f,
    val interruptions: Int = 0,
    val canSkip: Boolean = false,
    val canFinishEarly: Boolean = false,
    val pausedByUser: Boolean = false,
    val markerCount: Int = 0,
    val invalidatedMarkerCount: Int = 0,
    val message: String = "",
) {
    val stageNumber: Int get() = (stageIndex + 1).coerceAtMost(stageCount.coerceAtLeast(1))
    val isActive: Boolean
        get() = state == GuidedRunState.WAITING_FOR_CONDITIONS ||
            state == GuidedRunState.SETTLING ||
            state == GuidedRunState.CAPTURING ||
            state == GuidedRunState.PAUSED
}

/** How much a stage actually exercised its intended condition. */
enum class StageOutcome(val label: String) {
    VALID("Valid"),
    NOT_EXERCISED("Not exercised"),
    INSUFFICIENT_QUALITY("Insufficient quality"),
    SKIPPED("Skipped"),
    INTERRUPTED("Interrupted"),
}

enum class SymptomMarkerState { VALID, INVALIDATED }

/** A timestamped, first-class symptom annotation. It survives a stage restart. */
data class SymptomMarker(
    val timestampMs: Long,
    val state: SymptomMarkerState = SymptomMarkerState.VALID,
    val reason: String? = null,
)

/** One completed (or interrupted) stage capture. */
data class StageCapture(
    val stageId: String,
    val title: String,
    val kind: StageKind,
    val capture: CaptureData,
    val interruptions: Int = 0,
    val completed: Boolean = true,
    val outcome: StageOutcome = if (completed) StageOutcome.VALID else StageOutcome.INTERRUPTED,
    val notes: List<String> = emptyList(),
    val markerEvents: List<SymptomMarker> = emptyList(),
) {
    fun series(pid: Int): List<CapturePoint> = capture.series(pid)
    fun sampleCount(pid: Int): Int = capture.sampleCount(pid)
    fun averageHz(pid: Int): Double = capture.averageHz(pid)
    val durationMs: Long get() = capture.durationMs

    /** Kept for compatibility: the timestamps of valid markers. */
    val markers: List<Long>
        get() = (markerEvents.filter { it.state == SymptomMarkerState.VALID }.map { it.timestampMs } + capture.markers).distinct()

    val invalidatedMarkers: List<SymptomMarker>
        get() = markerEvents.filter { it.state == SymptomMarkerState.INVALIDATED }
}

enum class DiagnosticStatus(val label: String) {
    NO_ANOMALY_DETECTED("No anomaly detected"),
    SUSPICIOUS("Suspicious"),
    INCONCLUSIVE("Inconclusive"),
    UNABLE_TO_RUN("Unable to run"),
    SAFETY_STOPPED("Safety stopped"),
    CANCELLED("Cancelled"),
}

enum class EvidenceLevel(val label: String) {
    STRONG("Strong evidence"),
    MODERATE("Moderate evidence"),
    SUPPORTIVE("Supportive, not conclusive"),
    WEAK("Weak evidence"),
}

enum class ResultConfidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
}

data class GuidedObservation(
    val label: String,
    val value: String,
    val source: String = "Live capture",
)

data class GuidedFinding(
    val id: String,
    val title: String,
    val evidenceLevel: EvidenceLevel,
    val confidence: ResultConfidence,
    val explanation: String,
    val supporting: List<String> = emptyList(),
    val against: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val nextCheck: String,
)

data class StageSummary(
    val stageId: String,
    val title: String,
    val valid: Boolean,
    val completed: Boolean,
    val sampleCount: Int,
    val durationSeconds: Double,
    val effectiveHz: Double,
    val gaps: Int,
    val outcome: StageOutcome = if (valid) StageOutcome.VALID else StageOutcome.INSUFFICIENT_QUALITY,
    val stimulusObserved: Boolean? = null,
    val metrics: Map<String, Double> = emptyMap(),
    val notes: List<String> = emptyList(),
)

data class CaptureQualitySummary(
    val requiredSignalsPresent: Boolean,
    val samplingAdequate: Boolean,
    val missingSignals: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val interruptions: Int = 0,
    /** Null when the guide does not require a stimulus. */
    val stimulusObserved: Boolean? = null,
    /** Short, plain-English description of the evidence quality. */
    val evidenceQuality: String = "",
)

data class GuidedResult(
    val diagnosticId: String,
    val definitionVersion: String,
    val title: String,
    val status: DiagnosticStatus,
    val confidence: ResultConfidence,
    val summary: String,
    val observations: List<GuidedObservation> = emptyList(),
    val findings: List<GuidedFinding> = emptyList(),
    val stages: List<StageSummary> = emptyList(),
    val quality: CaptureQualitySummary = CaptureQualitySummary(true, true),
    val limitations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val generatedAt: Long = 0L,
    val profile: VehicleProfile = VehicleProfile(),
    val stagesSkipped: List<String> = emptyList(),
    val vehicleKey: String = "",
    val adapterName: String? = null,
    val protocolName: String? = null,
    val isDemo: Boolean = false,
    val scanReadAt: Long = 0L,
    val dataAgeMs: Long = 0L,
) {
    val primaryFinding: GuidedFinding? get() = findings.firstOrNull()

    /** The single most useful next action, or a fallback when the result is clean. */
    val nextCheck: String
        get() = primaryFinding?.nextCheck
            ?: nextSteps.firstOrNull()
            ?: "No further check is needed for this test. Re-run it if the symptom returns."

    /** Plain-English scope, so a clean result cannot read as a whole-car verdict. */
    val scope: String
        get() {
            val clean = title.trimEnd('.', '?', '!')
            return "Scope: $clean. A single OBD capture cannot prove a component is healthy or failed."
        }
}

/** Snapshot of the last DTC/monitor scan, used by the passive readiness and inspection guides. */
data class ScanContext(
    val monitor: MonitorStatus? = null,
    val storedCodes: List<String> = emptyList(),
    val pendingCodes: List<String> = emptyList(),
    val permanentCodes: List<String> = emptyList(),
    val freezeFrameCount: Int = 0,
    val mode06Tests: Int = 0,
    val mode06Failures: Int = 0,
    val mode06State: MonitorDataState = MonitorDataState.NOT_REQUESTED,
    val mode06ReadAt: Long = 0L,
    val readAt: Long = 0L,
) {
    val allCodes: List<String> get() = (storedCodes + pendingCodes + permanentCodes).distinct()
    val hasScan: Boolean get() = monitor != null || allCodes.isNotEmpty()

    /** Counts are only meaningful when the monitor data was actually read. */
    val mode06CountsAvailable: Boolean
        get() = mode06State == MonitorDataState.AVAILABLE
}

/** A stored same-vehicle, same-condition reference capture for the health-baseline guide. */
data class BaselineSnapshot(
    val vehicleKey: String = "",
    val profile: VehicleProfile = VehicleProfile(),
    val createdAt: Long = 0L,
    val definitionVersion: String = "1.0.0",
    val conditions: Map<String, Double> = emptyMap(),
    val metrics: Map<String, Double> = emptyMap(),
    val notes: List<String> = emptyList(),
)

data class AnalysisInput(
    val definition: DiagnosticDefinition,
    val capabilities: VehicleCapabilities,
    val prerequisites: PrerequisiteAnswers,
    val stages: List<StageCapture>,
    val activeCodes: List<String> = emptyList(),
    val skippedStages: List<String> = emptyList(),
    val adapterVoltageUsed: Boolean = false,
    val safetyStopReason: String? = null,
    val cancelled: Boolean = false,
    /** The adapter stopped answering during the test; the captured stages are still usable. */
    val connectionLost: Boolean = false,
    val scan: ScanContext = ScanContext(),
    val baseline: BaselineSnapshot? = null,
    val vehicleKey: String = "",
    val adapterName: String? = null,
    val protocolName: String? = null,
    val isDemo: Boolean = false,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    fun stage(id: String): StageCapture? = stages.firstOrNull { it.stageId == id }
}

data class GuidedRunOutcome(
    val result: GuidedResult,
    val captures: List<StageCapture>,
    val snapshot: GuidedSnapshot,
)
