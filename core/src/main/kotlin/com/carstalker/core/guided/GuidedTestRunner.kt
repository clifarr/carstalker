package com.carstalker.core.guided

import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import java.util.Locale
import kotlin.math.abs

/**
 * Pure, deterministic stage runner for guided tests.
 *
 * The runner owns no threads and reads no clock: callers feed timestamped samples and ticks, which
 * makes every transition reproducible in a unit test. It implements the shared state machine
 * `waiting → settling → capturing`, pauses when the condition window is lost, discards mixed
 * samples by restarting the affected stage, and stops for safety conditions.
 */
class GuidedTestRunner(
    private val definition: DiagnosticDefinition,
    private val capabilities: VehicleCapabilities,
    private val prerequisites: PrerequisiteAnswers,
    private val activeCodes: List<String> = emptyList(),
) {

    private data class Sample(val pid: Int, val timestampMs: Long, val value: Double)

    private val stages: List<StageDefinition> = definition.stages
    private val captures = mutableListOf<StageCapture>()
    private val skipped = mutableListOf<String>()
    private val history = ArrayDeque<Sample>()
    private val live = LinkedHashMap<Int, Double>()
    private val lastSampleAt = mutableMapOf<Int, Long>()
    private val currentPoints = mutableListOf<CapturePoint>()
    private val currentMarkers = mutableListOf<SymptomMarker>()
    private val markerHistory = mutableListOf<SymptomMarker>()

    private var state: GuidedRunState = GuidedRunState.NOT_STARTED
    private var stageIndex = 0
    private var stageEnteredAt = 0L
    private var conditionMetAt: Long? = null
    private var captureStartedAt: Long? = null
    private var lastSatisfiedAt: Long? = null
    private var interruptions = 0
    private var stageMessage = ""
    private var safetyStopReason: String? = null
    private var lastSafetyCheck = SafetyCheck()
    private var userPaused = false

    val runState: GuidedRunState get() = state
    val currentStageIndex: Int get() = stageIndex
    val currentStage: StageDefinition? get() = stages.getOrNull(stageIndex)
    val safetyReason: String? get() = safetyStopReason
    val cancelled: Boolean get() = state == GuidedRunState.CANCELLED
    val skippedStages: List<String> get() = skipped.toList()
    val completedCaptures: List<StageCapture> get() = captures.toList()

    /**
     * Safety signals whose limits cannot be checked because the vehicle does not advertise them.
     * Generic OBD cannot prove these are safe, so results must say so instead of implying a pass.
     */
    val unverifiedSafetySignals: List<String>
        get() = buildList {
            if (!capabilities.supports(Signals.RPM)) add(Signals.label(Signals.RPM))
            if (!capabilities.supports(Signals.COOLANT)) add(Signals.label(Signals.COOLANT))
            if (!capabilities.supports(Signals.CONTROL_MODULE_VOLTAGE) && !capabilities.adapterVoltageAvailable) {
                add("Voltage")
            }
        }

    /** Safety signals that must be sampled on every cycle, independent of the stage's analytics. */
    private fun safetySignals(stage: StageDefinition? = currentStage): List<Int> {
        val engineOff = stage?.condition?.requireEngineOff == true
        return buildList {
            // With the engine off there is no RPM to verify; requiring a fresh value would block
            // key-on stages on cars that only report engine speed while running.
            if (!engineOff) add(Signals.RPM)
            add(Signals.COOLANT)
            if (capabilities.supports(Signals.CONTROL_MODULE_VOLTAGE)) {
                add(Signals.CONTROL_MODULE_VOLTAGE)
            } else {
                add(Signals.ADAPTER_VOLTAGE)
            }
        }.filter { it == Signals.ADAPTER_VOLTAGE || capabilities.supports(it) }.distinct()
    }

    fun start(now: Long) {
        if (state != GuidedRunState.NOT_STARTED) return
        val stop = initialSafetyStop()
        if (stop != null) {
            safetyStop(stop)
            return
        }
        if (stages.isEmpty()) {
            state = GuidedRunState.ANALYSING
            stageMessage = "No capture stages; analysing available data."
            return
        }
        state = GuidedRunState.WAITING_FOR_CONDITIONS
        stageEnteredAt = now
        stageMessage = currentStage?.instruction.orEmpty()
    }

    fun sample(pid: Int, value: Double, timestampMs: Long) {
        if (state.terminal) return
        if (!value.isFinite()) return
        live[pid] = value
        lastSampleAt[pid] = timestampMs
        history.addLast(Sample(pid, timestampMs, value))
        prune(timestampMs)
        if (state == GuidedRunState.CAPTURING && pid in currentStagePids()) {
            currentPoints += CapturePoint(pid, timestampMs, value)
        }
    }

    fun tick(now: Long) {
        if (state.terminal) return
        if (state == GuidedRunState.NOT_STARTED) return

        if (state == GuidedRunState.ANALYSING) {
            // Capture is over; stale safety telemetry must not delay the result.
            state = GuidedRunState.COMPLETE
            return
        }

        checkSafety(now).let { safety ->
            lastSafetyCheck = safety
            when (safety.status) {
                SafetyStatus.UNSAFE -> {
                    safetyStop(safety.reason ?: "A safety limit was exceeded.")
                    return
                }
                SafetyStatus.UNAVAILABLE -> {
                    // Missing or stale safety data must never count as a passed check. Capture
                    // cannot start, and an active segment is paused rather than trusted.
                    val reason = safety.reason ?: "Waiting for fresh safety data."
                    if (state == GuidedRunState.CAPTURING || state == GuidedRunState.SETTLING) {
                        pauseForSafetyLoss(reason)
                    } else if (!userPaused) {
                        stageMessage = reason
                    }
                    return
                }
                SafetyStatus.OK -> Unit
            }
        }

        if (userPaused) {
            state = GuidedRunState.PAUSED
            stageMessage = "Paused. Resume when you are ready; the stage will restart its settling window."
            return
        }

        val stage = currentStage ?: run {
            state = GuidedRunState.ANALYSING
            return
        }
        val condition = evaluate(stage, now)

        when (state) {
            GuidedRunState.WAITING_FOR_CONDITIONS -> {
                if (condition.satisfied) {
                    if (stage.condition.settlingMs <= 0) {
                        beginCapture(stage, now)
                    } else {
                        state = GuidedRunState.SETTLING
                        conditionMetAt = now
                        stageMessage = "Conditions met. Hold steady."
                    }
                } else {
                    stageMessage = condition.detail ?: "Waiting for the required conditions."
                }
            }

            GuidedRunState.SETTLING -> {
                if (!condition.satisfied) {
                    conditionMetAt = null
                    state = GuidedRunState.WAITING_FOR_CONDITIONS
                    stageMessage = condition.detail ?: "Conditions changed. Waiting again."
                } else {
                    val since = conditionMetAt ?: now.also { conditionMetAt = it }
                    if (now - since >= stage.condition.settlingMs) {
                        beginCapture(stage, now)
                    } else {
                        stageMessage = "Settling: ${((stage.condition.settlingMs - (now - since)) / 1000).coerceAtLeast(0)} s to go."
                    }
                }
            }

            GuidedRunState.CAPTURING -> {
                if (!condition.satisfied) {
                    val last = lastSatisfiedAt ?: now
                    if (now - last > stage.condition.graceMs) {
                        pauseForLostCondition(stage, condition)
                    } else {
                        stageMessage = "Conditions changing — hold steady."
                    }
                } else {
                    lastSatisfiedAt = now
                    when (completionFor(stage, now)) {
                        Completion.DURATION -> completeStage(stage, now, completed = true)
                        Completion.PLATEAU -> completeStage(stage, now, completed = true, plateau = true)
                        null -> {
                            val elapsed = now - (captureStartedAt ?: now)
                            stageMessage = "Capturing live data: ${(elapsed / 1000).coerceAtLeast(0)} of ${stage.condition.captureMs / 1000} seconds."
                        }
                    }
                }
            }

            GuidedRunState.PAUSED -> {
                if (condition.satisfied && !userPaused) {
                    state = GuidedRunState.SETTLING
                    conditionMetAt = now
                    stageMessage = "Conditions restored. Settling before restarting the capture."
                }
            }

            else -> Unit
        }
    }

    fun skipCurrentStage(now: Long) {
        val stage = currentStage ?: return
        if (!stage.optional && !stage.allowFinishEarly) return
        if (stage.allowFinishEarly && currentPoints.isNotEmpty()) {
            completeStage(stage, now, completed = false, note = "Finished early by the user.")
            return
        }
        skipped += stage.id
        stageMessage = "${stage.title} skipped."
        state = GuidedRunState.STAGE_COMPLETE
        advanceStage(now)
    }

    fun finishStageEarly(now: Long) {
        val stage = currentStage ?: return
        if (!stage.allowFinishEarly) return
        completeStage(stage, now, completed = false, note = "Finished early by the user.")
    }

    /** Records a user-marked symptom for a passive capture; ignored outside an active capture. */
    fun markSymptom(now: Long) {
        if (state != GuidedRunState.CAPTURING) return
        if (currentStage?.kind != StageKind.PASSIVE_CAPTURE) return
        val marker = SymptomMarker(now)
        currentMarkers += marker
        markerHistory += marker
        stageMessage = "Symptom marked (${currentMarkers.size} in this segment). Keep driving; finish the stage when safe."
    }

    fun pause() {
        if (state == GuidedRunState.CAPTURING || state == GuidedRunState.SETTLING || state == GuidedRunState.WAITING_FOR_CONDITIONS) {
            userPaused = true
            invalidateCurrentMarkers("Paused by the user; the partial segment was discarded.")
            state = GuidedRunState.PAUSED
            stageMessage = "Paused. The partial segment was discarded."
        }
    }

    /**
     * Called when the adapter disconnects mid-test. The active segment is invalidated, but any
     * symptom markers are kept as explicitly invalidated evidence instead of disappearing.
     */
    fun connectionLost(reason: String) {
        if (state.terminal || state == GuidedRunState.PAUSED) return
        interruptions++
        invalidateCurrentMarkers(reason)
        currentPoints.clear()
        captureStartedAt = null
        conditionMetAt = null
        lastSatisfiedAt = null
        state = GuidedRunState.PAUSED
        stageMessage = "Paused: $reason Reconnect and resume, or cancel."
    }

    fun resume(now: Long) {
        if (!userPaused && state != GuidedRunState.PAUSED) return
        userPaused = false
        conditionMetAt = null
        lastSatisfiedAt = null
        currentPoints.clear()
        captureStartedAt = null
        state = GuidedRunState.WAITING_FOR_CONDITIONS
        stageEnteredAt = now
        stageMessage = "Restarting this stage. Earlier symptom markers remain recorded as invalidated."
    }

    fun cancel() {
        if (state.terminal) return
        state = GuidedRunState.CANCELLED
        stageMessage = "Test cancelled."
    }

    fun snapshot(now: Long): GuidedSnapshot {
        val stage = currentStage
        val condition = if (stage != null) evaluate(stage, now) else {
            ConditionFeedback(
                satisfied = false,
                headline = if (state == GuidedRunState.ANALYSING || state == GuidedRunState.COMPLETE) {
                    "Capture complete"
                } else {
                    "Not started"
                },
            )
        }
        val captureElapsed = when {
            state == GuidedRunState.CAPTURING -> now - (captureStartedAt ?: now)
            state == GuidedRunState.STAGE_COMPLETE -> (currentPoints.lastOrNull()?.timestampMs ?: now) -
                (currentPoints.firstOrNull()?.timestampMs ?: now)
            else -> 0L
        }
        val captureDuration = stage?.condition?.captureMs ?: 0L
        val progress = when {
            stage == null -> 0f
            state == GuidedRunState.CAPTURING -> (captureElapsed.toFloat() / captureDuration.coerceAtLeast(1L)).coerceIn(0f, 1f)
            else -> 0f
        }
        return GuidedSnapshot(
            state = state,
            stageIndex = stageIndex.coerceAtMost((stages.size - 1).coerceAtLeast(0)),
            stageCount = stages.size,
            stage = stage,
            condition = condition.copy(
                headline = headlineFor(state, condition),
                detail = if (stageMessage.isNotBlank()) stageMessage else condition.detail,
            ),
            liveValues = live.toMap(),
            stageElapsedMs = if (stageEnteredAt > 0) now - stageEnteredAt else 0,
            captureElapsedMs = captureElapsed.coerceAtLeast(0),
            captureDurationMs = captureDuration,
            progress = progress,
            interruptions = interruptions,
            canSkip = stage != null && (stage.optional || stage.allowFinishEarly),
            canFinishEarly = stage?.allowFinishEarly == true && currentPoints.isNotEmpty(),
            pausedByUser = userPaused,
            markerCount = currentMarkers.size,
            invalidatedMarkerCount = markerHistory.count { it.state == SymptomMarkerState.INVALIDATED },
            message = stageMessage,
            safetyStatus = lastSafetyCheck.status,
            safetyMessage = lastSafetyCheck.reason,
            unverifiedSafetySignals = unverifiedSafetySignals,
        )
    }

    /**
     * Everything the poll loop must read this cycle: the stage's analytics channels plus the
     * safety channels, which are never left to the stage definition or the dashboard.
     */
    fun pidsToSample(): List<Int> {
        val stage = currentStage ?: return emptyList()
        val analytics = stage.pids.filter { capabilities.supports(it) }
        return (analytics + safetySignals(stage)).distinct()
    }

    fun captureDataForCurrentStage(now: Long): CaptureData = buildCapture(now)

    // ---- internals -------------------------------------------------------

    private fun currentStagePids(): Set<Int> = currentStage?.pids?.toSet().orEmpty()

    /**
     * Marks the markers in the discarded segment as invalidated instead of deleting them. The
     * result can then say a symptom was marked but its evidence was discarded, never that no
     * symptom was entered.
     */
    private fun invalidateCurrentMarkers(reason: String) {
        if (currentMarkers.isEmpty()) return
        val current = currentMarkers.toSet()
        for (index in markerHistory.indices) {
            if (markerHistory[index] in current) {
                markerHistory[index] = markerHistory[index].copy(
                    state = SymptomMarkerState.INVALIDATED,
                    reason = reason,
                )
            }
        }
        currentMarkers.clear()
    }

    private fun beginCapture(stage: StageDefinition, now: Long) {
        state = GuidedRunState.CAPTURING
        captureStartedAt = now
        lastSatisfiedAt = now
        conditionMetAt = null
        currentPoints.clear()
        currentMarkers.clear()
        stageMessage = "Capturing live data."
    }

    private fun pauseForLostCondition(stage: StageDefinition, condition: ConditionFeedback) {
        interruptions++
        state = GuidedRunState.PAUSED
        invalidateCurrentMarkers("Conditions were lost; the partial segment was discarded.")
        currentPoints.clear()
        captureStartedAt = null
        conditionMetAt = null
        lastSatisfiedAt = null
        stageMessage = buildString {
            append("Paused: conditions were lost")
            condition.detail?.let { append(". $it") }
            append(". The stage will restart its settling window and discard mixed samples.")
        }
    }

    /**
     * A safety signal went missing or stale mid-stage. The segment is discarded because the app
     * can no longer show that the test stayed inside its limits.
     */
    private fun pauseForSafetyLoss(reason: String) {
        interruptions++
        state = GuidedRunState.PAUSED
        invalidateCurrentMarkers("Safety data was unavailable; the partial segment was discarded.")
        currentPoints.clear()
        captureStartedAt = null
        conditionMetAt = null
        lastSatisfiedAt = null
        stageMessage = "Paused: $reason The stage will restart once safety data is fresh again."
    }

    private enum class Completion { DURATION, PLATEAU }

    private fun completionFor(stage: StageDefinition, now: Long): Completion? {
        val started = captureStartedAt ?: return null
        if (now - started >= stage.condition.captureMs) return Completion.DURATION
        if (!stage.condition.completeOnCoolantPlateau) return null
        // A plateau must be observed, not assumed: require a minimum window and sample count.
        if (now - started < MIN_PLATEAU_CAPTURE_MS) return null
        val coolant = seriesWithin(Signals.COOLANT, now - stage.condition.plateauWindowMs)
        if (coolant.size < MIN_PLATEAU_SAMPLES) return null
        val last = coolant.last().value
        if (last < stage.condition.plateauCoolantC) return null
        val spread = coolant.maxOf { it.value } - coolant.minOf { it.value }
        return if (spread <= stage.condition.plateauToleranceC) Completion.PLATEAU else null
    }

    private fun completeStage(
        stage: StageDefinition,
        now: Long,
        completed: Boolean,
        plateau: Boolean = false,
        note: String? = null,
    ) {
        val capture = buildCapture(now)
        val outcome = outcomeFor(stage, capture, completed, plateau)
        val notes = buildList {
            note?.let { add(it) }
            if (interruptions > 0) add("$interruptions interruption(s); mixed samples were discarded.")
            if (!completed) {
                add(
                    "Finished early: ${formatSeconds(capture.durationMs)} of the " +
                        "${formatSeconds(stage.condition.captureMs)} target captured.",
                )
            }
            if (outcome == StageOutcome.INSUFFICIENT_QUALITY) {
                add("Too few samples or too short a capture to treat this stage as evidence.")
            } else if (outcome == StageOutcome.PARTIAL) {
                add("Partial evidence: this stage is not counted as a complete capture.")
            }
        }
        captures += StageCapture(
            stageId = stage.id,
            title = stage.title,
            kind = stage.kind,
            capture = capture,
            interruptions = interruptions,
            completed = completed,
            outcome = outcome,
            notes = notes,
            markerEvents = markerHistory.toList(),
        )
        stageMessage = when (outcome) {
            StageOutcome.VALID -> "${stage.title} complete."
            StageOutcome.PARTIAL -> "${stage.title} finished with partial data."
            else -> "${stage.title} did not capture enough usable data."
        }
        state = GuidedRunState.STAGE_COMPLETE
        advanceStage(now)
    }

    /**
     * Capture validity is deliberately conservative: a short or sparse capture cannot become
     * valid evidence just because it contains one sample.
     */
    private fun outcomeFor(
        stage: StageDefinition,
        capture: CaptureData,
        completed: Boolean,
        plateau: Boolean,
    ): StageOutcome {
        if (capture.points.isEmpty()) return StageOutcome.INSUFFICIENT_QUALITY
        val requiredMs = stage.condition.captureMs
        val duration = capture.durationMs
        val keyPid = primarySignal(stage)
        val samples = keyPid
            ?.let { capture.sampleCount(it) }
            ?.takeIf { it > 0 }
            ?: capture.points.size
        val minSamples = (requiredMs / 1000.0 * MIN_SAMPLES_PER_SECOND).toInt()
            .coerceAtLeast(MIN_STAGE_SAMPLES)
        if (samples < minSamples) return StageOutcome.INSUFFICIENT_QUALITY
        if (plateau) {
            return if (duration >= MIN_PLATEAU_CAPTURE_MS && samples >= MIN_PLATEAU_SAMPLES) {
                StageOutcome.VALID
            } else {
                StageOutcome.PARTIAL
            }
        }
        val enoughDuration = duration >= (requiredMs * MIN_VALID_FRACTION).toLong()
        return when {
            completed && enoughDuration -> StageOutcome.VALID
            duration >= requiredMs / 2 -> StageOutcome.PARTIAL
            else -> StageOutcome.INSUFFICIENT_QUALITY
        }
    }

    private fun formatSeconds(ms: Long): String = "${(ms / 1000).coerceAtLeast(0)} s"

    private fun advanceStage(now: Long) {
        stageIndex++
        interruptions = 0
        currentPoints.clear()
        currentMarkers.clear()
        markerHistory.clear()
        captureStartedAt = null
        conditionMetAt = null
        lastSatisfiedAt = null
        stageEnteredAt = now
        if (stageIndex >= stages.size) {
            state = GuidedRunState.ANALYSING
            stageMessage = "Capture complete. Analysing evidence."
        } else {
            state = GuidedRunState.WAITING_FOR_CONDITIONS
            stageMessage = currentStage?.instruction.orEmpty()
        }
    }

    private fun buildCapture(now: Long): CaptureData {
        val points = currentPoints.toList()
        val started = points.firstOrNull()?.timestampMs ?: captureStartedAt ?: now
        val ended = points.lastOrNull()?.timestampMs ?: now
        val requested = currentStage?.pids?.filter { capabilities.supports(it) }.orEmpty()
        return CaptureData(
            points = points,
            requestedPids = requested,
            startedAt = started,
            endedAt = ended.coerceAtLeast(started),
            markers = currentMarkers.filter { it.state == SymptomMarkerState.VALID }.map { it.timestampMs },
        )
    }

    private fun evaluate(stage: StageDefinition, now: Long): ConditionFeedback {
        val condition = stage.condition
        val primaryPid = primarySignal(stage)
        val lastSeen = lastSampleAt[primaryPid]
        if (lastSeen == null || now - lastSeen > STALE_AFTER_MS) {
            return ConditionFeedback(
                satisfied = false,
                headline = "Waiting for live data",
                detail = "No fresh ${Signals.label(primaryPid)} sample for ${((now - (lastSeen ?: now)) / 1000).coerceAtLeast(0)} s. Check the adapter connection.",
            )
        }
        val rpm = live[Signals.RPM]
        val coolant = live[Signals.COOLANT]
        val speed = live[Signals.SPEED]
        val map = live[Signals.MAP]
        val voltage = live[Signals.CONTROL_MODULE_VOLTAGE] ?: live[Signals.ADAPTER_VOLTAGE]

        if (condition.kind == StageKind.CRANKING) {
            if (rpm == null) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for RPM",
                    detail = "The vehicle has not reported engine speed. Cranking data may not be available on this adapter.",
                )
            }
            if (rpm < 60) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Crank the engine",
                    detail = "Turn the key/start button as you normally would. The app records RPM and voltage while cranking.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                    target = "60–700 rpm while cranking",
                )
            }
            return ConditionFeedback(
                satisfied = true,
                headline = if (rpm > 700) "Engine started" else "Cranking detected",
                detail = if (rpm > 700) {
                    "The engine started. Stop cranking; the stage can be finished early."
                } else {
                    "Cranking at ${format(rpm)} rpm. Keep cranking briefly or finish the stage when you have enough data."
                },
                liveValue = rpm,
                liveUnit = "rpm",
                target = "60–700 rpm while cranking",
            )
        }

        if (condition.kind == StageKind.COLD_START_WARMUP) {
            if (coolant == null) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for coolant data",
                    detail = "The vehicle has not reported coolant temperature yet.",
                )
            }
            if (rpm == null || rpm < 250) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Start the engine",
                    detail = "The engine must be running for the warm-up capture.",
                    liveValue = coolant,
                    liveUnit = "°C",
                )
            }
            return ConditionFeedback(
                satisfied = true,
                headline = "Warm-up capture",
                detail = "Coolant ${format(coolant)} °C. Recording until the temperature plateaus.",
                liveValue = coolant,
                liveUnit = "°C",
                target = "Plateau at ~75 °C or warmer",
            )
        }

        if (condition.requireEngineOff) {
            if (rpm == null) {
                return ConditionFeedback(
                    satisfied = true,
                    headline = "Engine off",
                    detail = "RPM is not reported; confirm the engine is off before continuing.",
                    liveValue = map ?: voltage,
                    liveUnit = if (map != null) "kPa" else "V",
                    target = "Ignition on, engine not running",
                )
            }
            if (rpm > 50) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Switch the engine off",
                    detail = "Turn the ignition on but do not start the engine.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                )
            }
            return ConditionFeedback(
                satisfied = true,
                headline = "Engine off",
                detail = map?.let { "Manifold pressure ${format(it)} kPa." } ?: "Key-on conditions are valid.",
                liveValue = map ?: voltage,
                liveUnit = if (map != null) "kPa" else "V",
                target = "MAP near barometric pressure",
            )
        }

        if (condition.requireEngineRunning) {
            if (rpm == null) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for RPM",
                    detail = "The vehicle has not reported engine speed yet.",
                )
            }
            if (rpm < 250) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Start the engine",
                    detail = "The engine must be running for this stage.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                )
            }
        }

        condition.minCoolantC?.let { minimum ->
            if (coolant == null) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for coolant data",
                    detail = "The vehicle has not reported coolant temperature.",
                )
            }
            if (coolant < minimum) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Warm the engine",
                    detail = "Coolant is ${format(coolant)} °C; wait for at least ${format(minimum)} °C.",
                    liveValue = coolant,
                    liveUnit = "°C",
                    target = "≥ ${format(minimum)} °C",
                )
            }
        }
        condition.maxCoolantC?.let { maximum ->
            if (coolant != null && coolant > maximum) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Let the engine cool",
                    detail = "Coolant is ${format(coolant)} °C; the stage limit is ${format(maximum)} °C.",
                    liveValue = coolant,
                    liveUnit = "°C",
                )
            }
        }

        condition.minSpeedKph?.let { minimum ->
            if (speed == null || speed < minimum) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Increase speed",
                    detail = speed?.let { "Speed is ${format(it)} km/h; hold at least ${format(minimum)} km/h." }
                        ?: "Vehicle speed is not reported; this stage needs a steady cruise.",
                    liveValue = speed,
                    liveUnit = "km/h",
                    target = "≥ ${format(minimum)} km/h",
                )
            }
        }
        condition.maxSpeedKph?.let { maximum ->
            if (speed != null && speed > maximum) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Slow down",
                    detail = "Speed is ${format(speed)} km/h; the stage limit is ${format(maximum)} km/h.",
                    liveValue = speed,
                    liveUnit = "km/h",
                )
            }
        }

        val rpmRange = condition.rpmRange
        if (rpmRange != null) {
            if (rpm == null) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for RPM",
                    detail = "The vehicle has not reported engine speed.",
                )
            }
            if (rpm < rpmRange.first || rpm > rpmRange.last) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = if (rpm < rpmRange.first) "Raise the RPM" else "Lower the RPM",
                    detail = "Hold ${rpmRange.first}–${rpmRange.last} rpm. Live: ${format(rpm)} rpm.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                    target = "${rpmRange.first}–${rpmRange.last} rpm",
                )
            }
        }

        if (condition.kind == StageKind.FREE_REV) {
            return ConditionFeedback(
                satisfied = true,
                headline = "Ready for throttle response",
                detail = "Open the throttle gently once or twice, then let it return to idle.",
                liveValue = map,
                liveUnit = "kPa",
                target = "MAP should move with the throttle",
            )
        }

        if (rpmRange != null && condition.stabilityToleranceRpm > 0) {
            val window = seriesWithin(Signals.RPM, now - condition.stabilityWindowMs)
            if (window.size < 3) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Waiting for a stable signal",
                    detail = "Collecting RPM samples.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                    target = "${rpmRange.first}–${rpmRange.last} rpm",
                )
            }
            val spread = window.maxOf { it.value } - window.minOf { it.value }
            if (spread > condition.stabilityToleranceRpm) {
                return ConditionFeedback(
                    satisfied = false,
                    headline = "Hold steady",
                    detail = "RPM is moving by ${format(spread)} rpm; hold a constant throttle.",
                    liveValue = rpm,
                    liveUnit = "rpm",
                    target = "${rpmRange.first}–${rpmRange.last} rpm",
                    rpmSpread = spread,
                )
            }
            return ConditionFeedback(
                satisfied = true,
                headline = "Steady",
                detail = "RPM variation ${format(spread)} rpm.",
                liveValue = rpm,
                liveUnit = "rpm",
                target = "${rpmRange.first}–${rpmRange.last} rpm",
                rpmSpread = spread,
            )
        }

        return ConditionFeedback(
            satisfied = true,
            headline = "Conditions met",
            detail = stage.instruction,
            liveValue = rpm ?: coolant ?: voltage,
            liveUnit = if (rpm != null) "rpm" else if (coolant != null) "°C" else "V",
        )
    }

    /** The signal that proves a stage's condition is live and usable as its evidence key. */
    private fun primarySignal(stage: StageDefinition): Int = when (stage.condition.kind) {
        StageKind.COLD_START_WARMUP -> Signals.COOLANT
        StageKind.CRANKING, StageKind.PASSIVE_CAPTURE -> Signals.RPM
        StageKind.KEY_ON_ENGINE_OFF -> when {
            lastSampleAt.containsKey(Signals.MAP) -> Signals.MAP
            lastSampleAt.containsKey(Signals.CONTROL_MODULE_VOLTAGE) -> Signals.CONTROL_MODULE_VOLTAGE
            lastSampleAt.containsKey(Signals.ADAPTER_VOLTAGE) -> Signals.ADAPTER_VOLTAGE
            stage.pids.contains(Signals.MAP) -> Signals.MAP
            stage.pids.contains(Signals.CONTROL_MODULE_VOLTAGE) -> Signals.CONTROL_MODULE_VOLTAGE
            else -> Signals.ADAPTER_VOLTAGE
        }
        else -> Signals.RPM
    }

    /** A value that is still recent enough to describe the engine right now, or null. */
    private fun freshSignal(pid: Int, now: Long): Double? {
        val last = lastSampleAt[pid] ?: return null
        if (now - last > SAFETY_STALE_AFTER_MS) return null
        return live[pid]
    }

    private fun checkSafety(now: Long): SafetyCheck {
        val rules = definition.safety
        val required = safetySignals().toSet()
        val coolant = freshSignal(Signals.COOLANT, now)
        val rpm = freshSignal(Signals.RPM, now)
        val voltage = freshSignal(Signals.CONTROL_MODULE_VOLTAGE, now)
            ?: freshSignal(Signals.ADAPTER_VOLTAGE, now)

        // Freshness first: a stale-but-plausible reading must not pass a gate, and a stale
        // out-of-range reading is not proof of danger either.
        if (Signals.COOLANT in required && coolant == null) {
            return SafetyCheck(
                SafetyStatus.UNAVAILABLE,
                "Waiting for a fresh coolant temperature reading.",
                Signals.COOLANT,
            )
        }
        if (Signals.RPM in required && rpm == null) {
            return SafetyCheck(
                SafetyStatus.UNAVAILABLE,
                "Waiting for a fresh engine speed reading.",
                Signals.RPM,
            )
        }
        if (voltage == null) {
            return SafetyCheck(
                SafetyStatus.UNAVAILABLE,
                "Waiting for a fresh voltage reading.",
                Signals.CONTROL_MODULE_VOLTAGE,
            )
        }

        if (coolant != null && coolant > rules.maxCoolantC) {
            return SafetyCheck(
                SafetyStatus.UNSAFE,
                "Coolant reached ${format(coolant)} °C. Stop the test and let the engine cool.",
                Signals.COOLANT,
            )
        }
        if (rpm != null && rpm > rules.maxRpm) {
            return SafetyCheck(
                SafetyStatus.UNSAFE,
                "Engine speed reached ${format(rpm)} rpm, above the ${rules.maxRpm} rpm test limit.",
                Signals.RPM,
            )
        }
        if (voltage < rules.minVoltage) {
            return SafetyCheck(
                SafetyStatus.UNSAFE,
                "Voltage dropped to ${format(voltage)} V. Stop and check the charging system and connections.",
                Signals.CONTROL_MODULE_VOLTAGE,
            )
        }
        if (voltage > rules.maxVoltage) {
            return SafetyCheck(
                SafetyStatus.UNSAFE,
                "Voltage reached ${format(voltage)} V. Stop and have the charging system checked.",
                Signals.CONTROL_MODULE_VOLTAGE,
            )
        }
        return SafetyCheck(SafetyStatus.OK)
    }

    private fun initialSafetyStop(): String? {
        val rules = definition.safety
        if (prerequisites.flashingMil && rules.stopOnFlashingMil) {
            return "The MIL is flashing. Stop driving and fix the misfire before any load test."
        }
        if (prerequisites.activeMisfire && rules.stopOnMisfireCodes) {
            return "An active misfire was reported. Stop driving and fix it before testing."
        }
        val stopCode = activeCodes.firstOrNull { code ->
            rules.stopCodePrefixes.any { prefix -> code.trim().uppercase().startsWith(prefix.uppercase()) }
        }
        if (stopCode != null) {
            return "$stopCode indicates a fault that makes this test unsafe or meaningless. Fix it first."
        }
        return null
    }

    private fun safetyStop(reason: String) {
        safetyStopReason = reason
        state = GuidedRunState.SAFETY_STOPPED
        stageMessage = reason
    }

    private fun seriesWithin(pid: Int, sinceMs: Long): List<Sample> =
        history.filter { it.pid == pid && it.timestampMs >= sinceMs }

    private fun prune(now: Long) {
        val horizon = now - 120_000L
        while (history.isNotEmpty() && history.first().timestampMs < horizon) {
            history.removeFirst()
        }
    }

    private fun headlineFor(state: GuidedRunState, condition: ConditionFeedback): String = when (state) {
        GuidedRunState.NOT_STARTED -> "Not started"
        GuidedRunState.WAITING_FOR_CONDITIONS -> condition.headline
        GuidedRunState.SETTLING -> "Settling"
        GuidedRunState.CAPTURING -> "Capturing live data"
        GuidedRunState.PAUSED -> "Paused"
        GuidedRunState.STAGE_COMPLETE -> "Stage complete"
        GuidedRunState.ANALYSING -> "Analysing evidence"
        GuidedRunState.COMPLETE -> "Complete"
        GuidedRunState.UNABLE_TO_RUN -> "Unable to run"
        GuidedRunState.SAFETY_STOPPED -> "Safety stop"
        GuidedRunState.CANCELLED -> "Cancelled"
        GuidedRunState.PREREQUISITE_CHECK -> "Checking prerequisites"
    }

    private fun format(value: Double): String = when {
        abs(value - value.toLong()) < 0.05 -> value.toLong().toString()
        else -> String.format(Locale.US, "%.1f", value)
    }

    private companion object {
        const val STALE_AFTER_MS = 2_500L
        const val SAFETY_STALE_AFTER_MS = 4_000L
        const val MIN_PLATEAU_CAPTURE_MS = 60_000L
        const val MIN_PLATEAU_SAMPLES = 20
        const val MIN_STAGE_SAMPLES = 5
        const val MIN_SAMPLES_PER_SECOND = 1.0
        const val MIN_VALID_FRACTION = 0.9
    }
}
