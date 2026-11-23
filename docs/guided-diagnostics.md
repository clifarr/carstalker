# Guided diagnostics engine

CarStalker's guided tests are a reusable, offline platform, not a set of hard-coded screens. This
document describes the architecture and how to add a definition and its interpreter.

## Implementation map

The existing app already had a pure Kotlin OBD engine (`core/obd`, `core/elm`, `core/diagnose`), a
single connection/polling owner (`ObdSession`), a wide-format recording repository, a shared
`DiagnosticReport` model and a Compose Diagnose screen with a catalyst wizard. The guided platform
reuses those pieces instead of replacing them:

- `CapturePoint`/`CaptureData` remain the timestamped sample container.
- `SignalAnalysis` remains the low-level switching/correlation helper.
- `LogRepository` remains the recording store; guided captures are saved as normal wide CSVs.
- `ReportBuilder`/`DiagnosticReport` remain the PDF/plain-text report path.
- `ObdSession` gained a guided sampling loop that pauses normal polling.
- The existing catalyst flow is registered as `catalyst_o2` and keeps its dedicated screen.

New code lives in `core/guided` (definitions, resolver, runner, analyzers) and the app's
`GuidedTestScreen`/`GuidedComponents`/`GuidedResultExport`.

## Layers

```
DiagnosticRegistry            declarative definitions: DTCs, signals, stages, safety
CapabilityResolver            supported PIDs + engine profile -> Ready/Limited/Unsupported
GuidedTestRunner              pure state machine: waiting -> settling -> capturing
ObdSession.runGuidedTest      sampling service: only the active stage's PIDs, per-sample timestamps
GuidedAnalysis                dispatch to the diagnostic-specific interpreter
*Analyzer                     observations, findings, alternatives, quality, next check
GuidedResult                  status, confidence category, evidence, limitations, next steps
```

The core package is `core/src/main/kotlin/com/carstalker/core/guided/`. It has no Android
dependencies, so every rule is unit-testable with synthetic traces.

## State machine

`GuidedTestRunner` owns no threads and reads no clock. Callers feed `sample(pid, value, time)` and
`tick(time)`, which makes the transitions deterministic:

- `NOT_STARTED`
- `WAITING_FOR_CONDITIONS` — live data is fresh, but RPM/coolant/speed/voltage is outside the stage target.
- `SETTLING` — conditions are met and the settling window is running.
- `CAPTURING` — only samples that arrive while the condition is valid are recorded.
- `PAUSED` — conditions were lost or the user paused. The affected stage discards its mixed samples
  and restarts settling.
- `STAGE_COMPLETE` → next stage, or `ANALYSING` → `COMPLETE`.
- `SAFETY_STOPPED`, `UNABLE_TO_RUN`, `CANCELLED`.

Staleness is explicit: if the primary signal stops arriving for 2.5 seconds, the condition is no
longer considered valid. A disconnected adapter therefore pauses the stage instead of repeating the
last value forever.

Safety rules live in `SafetyRules` on each definition: flashing MIL, misfire codes, coolant and RPM
limits, and voltage limits. A safety stop is a result, not an exception.

## Adding a definition

Add a `DiagnosticDefinition` to `DiagnosticRegistry` (or a new registry object for a vehicle pack).
The minimum useful definition is:

```kotlin
val myGuide = DiagnosticDefinition(
    id = "my_guide",
    version = "1.0.0",
    title = "My guided test",
    subtitle = "P0xxx · what it checks",
    description = "What the user will learn and what the test cannot prove.",
    phase = DiagnosticPhase.PHASE_1,
    estimatedMinutes = 10,
    dtcs = listOf("P0xxx"),
    dtcMatch = listOf("P0xxx"),
    requiredSignals = listOf(
        SignalRequirement(Signals.RPM),
        SignalRequirement(Signals.COOLANT),
    ),
    optionalSignals = listOf(
        SignalRequirement(Signals.MAF, required = false),
    ),
    stages = listOf(
        StageDefinition(
            id = "warm_idle",
            title = "Warm idle",
            instruction = "Let the engine idle without touching the throttle.",
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
            pids = listOf(Signals.RPM, Signals.COOLANT, Signals.MAF),
        ),
    ),
    whatYouNeed = listOf("A warm engine and a safe place to idle."),
    safetyNotes = listOf("Do not use the phone while driving."),
    limitations = listOf("This test cannot prove a specific component failed."),
    engineRequirement = EngineRequirement.PETROL,
    requiresWarmEngine = true,
)
```

Then add it to `DiagnosticRegistry.all`. If it is not implemented yet, set `implemented = false`
and provide a `catalogueNote`. The UI will show it as `Planned` and will not offer Start.

### Stage condition fields

- `kind`: `KEY_ON_ENGINE_OFF`, `WARM_IDLE`, `STEADY_RPM`, `COLD_START_WARMUP`, `ELECTRICAL_LOAD`,
  `FREE_REV`, `STEADY_CRUISE`, `CRANKING`, `PASSIVE_CAPTURE`.
- `rpmRange`, `minSpeedKph`, `maxSpeedKph`, `minCoolantC`, `maxCoolantC`.
- `requireEngineOff` / `requireEngineRunning`.
- `stabilityToleranceRpm` and `stabilityWindowMs`: the rolling RPM spread must stay inside the
  tolerance before settling starts.
- `settlingMs`: stable-condition window before capture.
- `captureMs`: intended capture length.
- `graceMs`: how long a condition may blip before the stage pauses.
- `completeOnCoolantPlateau`: finish a warm-up early once coolant reaches the plateau target and
  stops changing.

`optional = true` lets the user skip a stage. `allowFinishEarly = true` lets the user finish a long
observation (for example a warm-up) without waiting for the full timeout.

## Adding an analyzer

1. Create `MyGuideAnalyzer.kt` in `core/.../guided/`.
2. Accept `AnalysisInput` and return `GuidedResult`.
3. Use `AnalysisSupport` for robust statistics, combined trims, gaps, quality and formatting.
4. Add a branch to `GuidedAnalysis.analyze`.
5. Add synthetic-trace tests under `core/src/test/.../guided/` (for example
   `GuidedDiagnosticsTest.kt` or `GuidedPhase2Test.kt`).

The result must contain:

- `status`: `NO_ANOMALY_DETECTED`, `SUSPICIOUS`, `INCONCLUSIVE`, `UNABLE_TO_RUN`,
  `SAFETY_STOPPED` or `CANCELLED`.
- `confidence`: a categorical `HIGH`/`MEDIUM`/`LOW`; never a fabricated probability.
- `observations`: the measured values, with units and the source.
- `findings`: one or more candidate causes, each with evidence for, evidence against, competing
  explanations and one next check.
- `stages`: per-stage validity, sample count, effective rate and gaps.
- `quality`: required signals present, sampling adequate, missing signals and notes.
- `limitations`, `warnings` and `nextSteps`.

Do not assert that a component failed. Use language such as “consistent with”, “supports
investigation” and “not enough evidence”. If a required signal is missing, return
`UNABLE_TO_RUN` or a documented limited branch.

## Capability resolution

`CapabilityResolver` checks required signals (with alternatives such as adapter voltage for control
module voltage), the engine profile and definition-specific conditions. It returns an
`AvailabilityReport` with reasons and missing signals. The UI shows those reasons and only offers a
Start action for startable states.

The engine profile (`FuelType`, `Aspiration`) is stored in Settings and shown on the Diagnose tab.
It is never inferred from one live value. Diesel and forced-induction engines get limited MAP and
mixture interpretations instead of petrol naturally-aspirated rules.

## Current guides

| Phase | Guide | Status | Notes |
|---|---|---|---|
| 1 | Catalyst vs oxygen sensor | Existing dedicated flow | Mode 06 + narrowband O2 capture + manual observations. Registered as `catalyst_o2`. |
| 1 | Lean mixture: vacuum leak vs MAF vs fuel delivery | Implemented | Warm idle + steady RPM (+ optional cruise), combined STFT/LTFT, bank separation. |
| 1 | Thermostat vs ECT sensor | Implemented | Cold-start warm-up, plateau, time-to-temperature, implausible-step check. |
| 1 | Charging and electrical health | Implemented | Key-on, idle, moderate RPM, optional electrical load. |
| 1 | MAP plausibility | Implemented | Key-on MAP/BARO, idle vacuum (NA petrol), throttle response. |
| 2 | Rich-running mixture | Implemented | Negative combined trims at idle/elevated RPM, bank separation, O2/airflow context. |
| 2 | Oxygen sensor responsiveness | Implemented | Narrowband/wideband-aware response, heater-code gate, explicit low-sampling branch. |
| 2 | Misfire investigation | Implemented (limited) | Codes + RPM stability + load/trims/voltage; no unverified cylinder Mode 06 counts. |
| 2 | MAF plausibility | Implemented | MAF/RPM scaling and MAF/(RPM·MAP) consistency; no universal g/s thresholds. |
| 2 | Cranks but does not start | Implemented (limited) | Key-on + cranking capture, missing-RPM branch, no crank-sensor verdict. |
| 3 | Turbo under/overboost | Implemented (limited) | Key-on MAP/BARO plausibility and measured-boost trend; no requested-boost target. |
| 3 | Fuel pressure | Implemented (limited) | Measured pressure stability/response; requested-vs-actual only with a target PID. |
| 3 | EGR flow and control | Implemented | Command/error vs MAP/MAF response, inconclusive when EGR is not enabled. |
| 3 | Throttle and pedal | Implemented | Channel correlation during a stationary response; professional-assessment advice. |
| 3 | EVAP purge | Implemented (limited) | Purge command vs trim response; no leak-location claim. |
| 3 | DPF health | Implemented (limited) | Standard 0x7A/0x7B differential pressure + 0x7C temperature; passive only, no soot/regen guess. |
| 4 | Fuel consumption | Implemented (snapshot) | Fuel-rate/MAF snapshot; no verdict without a same-condition baseline. |
| 4 | Intermittent power loss | Implemented (limited) | Passenger-marked passive drive capture; event-window comparison, no component verdict. |
| 4 | Emissions readiness | Implemented | Monitor readiness, code states, counters and Mode 06 failures. |
| 4 | Used-car inspection | Implemented | Passive codes/readiness/counters/trims/charging summary with explicit limits. |
| 4 | Longitudinal health baseline | Implemented | Persistent same-vehicle baseline; compares only when RPM/coolant/load/ambient match. |

All registered workflows are implemented. DPF, intermittent power loss and the baseline remain
deliberately limited and state their data/trigger assumptions in the result.

Readiness and used-car summaries use `ScanContext` (the last `DtcReport`) in addition to the short
key-on capture. The capability resolver receives the same context so it can say “Read codes first”
instead of pretending readiness is available. The health-baseline guide stores its reference in
the app settings and refuses to compare when the vehicle profile or conditions differ.

## Testing

```bash
./gradlew :core:test
```

`GuidedDiagnosticsTest` covers the Phase 1 guides, the runner and capability resolution.
`GuidedPhase2Test` adds synthetic traces for:

- idle-only and persistent rich trims, plus normal trims;
- static, switching and under-sampled narrowband O2;
- cylinder-specific misfire codes;
- static and proportional MAF;
- missing and present cranking RPM;
- MAP/BARO boost-sensor mismatch;
- pressure drop with demand;
- large EGR error;
- correlated throttle/pedal channels;
- purge command with trim response;
- fuel-consumption snapshot without a baseline;
- incomplete readiness and used-car code summaries;
- forced-induction/NA and missing-purge capability states;
- cranking-stage runner completion without requiring a running engine;
- DPF pressure zero/response/static states;
- marked power-loss events, driver lift and passive marker storage;
- baseline creation, matched comparison, airflow shift and condition mismatch.

The demo adapter can be steered from the capture screen (warm idle, 2,500 rpm, engine off,
electrical load, cold start, cranking, driving cycle) so the whole flow can be exercised without a car. It
simulates per-command adapter latency and jitter, so cycle times and effective sample rates are
realistic rather than instant.

## Data and privacy

Guided results are local. The PDF report uses the same `DiagnosticReport` model as the existing
report path. “Save capture” writes the stage samples to the existing wide-format recording store,
so a capture can be reopened without the adapter. “Export JSON” writes a structured result with
the observations, findings, evidence, quality, limitations and next steps; raw samples stay in the
CSV unless you explicitly save the capture. Nothing is uploaded.

## Limitations

- Generic OBD-II only. Manufacturer-specific monitors and service data are not bundled.
- ELM327 sample rate limits temporal resolution; the quality section says when it is inadequate.
- A single capture cannot prove a component failed.
- Thresholds are configurable starting points and still need real-car validation.
