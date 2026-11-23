package com.carstalker.app.service

import com.carstalker.app.BuildConfig
import com.carstalker.app.data.AdapterVoltageParam
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.LogColumnMeta
import com.carstalker.app.data.LogRepository
import com.carstalker.app.data.LogWriter
import com.carstalker.app.data.SettingsRepository
import com.carstalker.app.data.recommendedDashboardPids
import com.carstalker.app.data.toPid
import com.carstalker.app.transport.demo.DemoControllable
import com.carstalker.app.transport.demo.DemoScenario
import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcGroup
import com.carstalker.core.dtc.EcuDtcReport
import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.diagnose.CaptureProgress
import com.carstalker.core.diagnose.CaptureSpec
import com.carstalker.core.elm.AdapterBenchmark
import com.carstalker.core.elm.AdapterInfo
import com.carstalker.core.elm.Elm327Client
import com.carstalker.core.elm.ObdClient
import com.carstalker.core.guided.AnalysisInput
import com.carstalker.core.guided.BaselineSnapshot
import com.carstalker.core.guided.AnalysisSupport
import com.carstalker.core.guided.DiagnosticDefinition
import com.carstalker.core.guided.GuidedAnalysis
import com.carstalker.core.guided.GuidedRunOutcome
import com.carstalker.core.guided.GuidedSnapshot
import com.carstalker.core.guided.GuidedTestRunner
import com.carstalker.core.guided.PrerequisiteAnswers
import com.carstalker.core.guided.ScanContext
import com.carstalker.core.guided.Signals
import com.carstalker.core.guided.StageCapture
import com.carstalker.core.guided.VehicleCapabilities
import com.carstalker.core.guided.VehicleProfile
import com.carstalker.core.guided.terminal
import com.carstalker.core.obd.FreezeFrame
import com.carstalker.core.obd.Mode06Report
import com.carstalker.core.obd.MonitorStatus
import com.carstalker.core.obd.ObdMode
import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.obd.PollScheduler
import com.carstalker.core.transport.Transport
import com.carstalker.core.vehicle.VehicleIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Initializing(val stage: String) : ConnectionState
    data class Ready(val adapter: AdapterInfo, val transportName: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class LiveSnapshot(
    val values: Map<Int, Double> = emptyMap(),
    /** Measured by the adapter itself (`ATRV`), refreshed with every poll cycle. */
    val adapterVoltage: Double? = null,
)

data class DtcReport(
    val modules: List<EcuDtcReport> = emptyList(),
    val monitor: MonitorStatus? = null,
    val freezeFrames: List<FreezeFrame> = emptyList(),
    val mode06: Mode06Report = Mode06Report(),
    val readAt: Long = 0L,
    val loading: Boolean = false,
) {
    val stored: List<Dtc> get() = modules.flatMap { it.stored }
    val pending: List<Dtc> get() = modules.flatMap { it.pending }
    val permanent: List<Dtc> get() = modules.flatMap { it.permanent }
    val total: Int get() = modules.sumOf { it.total }

    fun forGroup(group: DtcGroup): List<Dtc> = modules.flatMap { it.forGroup(group) }

    fun forGroup(group: DtcGroup, source: Int?): List<Dtc> =
        modules.firstOrNull { it.source == source }?.forGroup(group).orEmpty()

    fun moduleFor(source: Int?): EcuDtcReport? = modules.firstOrNull { it.source == source }
}

/** Converts the last scan into the passive context used by readiness and inspection guides. */
fun DtcReport.toScanContext(): ScanContext = ScanContext(
    monitor = monitor,
    storedCodes = stored.map { it.code },
    pendingCodes = pending.map { it.code },
    permanentCodes = permanent.map { it.code },
    freezeFrameCount = freezeFrames.size,
    mode06Tests = mode06.results.size,
    mode06Failures = mode06.failures.size,
)

/**
 * Owns the adapter connection and the polling loop. One instance lives for the whole app,
 * so switching screens never interrupts live data.
 */
class ObdSession(
    private val settings: SettingsRepository,
    private val logs: LogRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settingsFlow: StateFlow<AppSettings> = settings.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private var transport: Transport? = null
    private var obd: ObdClient? = null
    private var pollJob: Job? = null
    private var writer: LogWriter? = null
    private val scheduler = PollScheduler()
    private val captureMutex = Mutex()
    private val lastValues = mutableMapOf<Int, Double>()

    @Volatile
    private var guidedCancelRequested = false

    /** Invalidates an older code read when a clear or a newer read starts. */
    private var dtcReadGeneration = 0L

    @Volatile
    private var guidedSkipRequested = false

    @Volatile
    private var guidedFinishRequested = false

    @Volatile
    private var guidedPauseRequested = false

    @Volatile
    private var guidedResumeRequested = false

    @Volatile
    private var guidedSymptomRequested = false

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveSnapshot())
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    /** Emitted once per completed poll cycle; charts use this so samples stay evenly spaced. */
    private val _samples = MutableStateFlow(LiveSnapshot())
    val samples: StateFlow<LiveSnapshot> = _samples.asStateFlow()

    private val _identity = MutableStateFlow(VehicleIdentity())
    val identity: StateFlow<VehicleIdentity> = _identity.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val _dtcReport = MutableStateFlow(DtcReport())
    val dtcReport: StateFlow<DtcReport> = _dtcReport.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    suspend fun connect(transportFactory: () -> Transport) {
        if (_state.value is ConnectionState.Ready || _state.value is ConnectionState.Connecting) return
        Elm327Client.DEBUG = BuildConfig.DEBUG
        Elm327Client.trace = { android.util.Log.d("Elm327", it) }
        _state.value = ConnectionState.Connecting
        try {
            val newTransport = withContext(Dispatchers.IO) {
                transportFactory().also { it.connect() }
            }
            transport = newTransport
            scheduler.reset()
            lastValues.clear()
            val newElm = Elm327Client(newTransport)
            val newObd = ObdClient(newElm)
            obd = newObd

            _state.value = ConnectionState.Initializing("Waking up the adapter")
            val info = withContext(Dispatchers.IO) { newObd.initialize() }

            _state.value = ConnectionState.Initializing("Reading supported parameters")
            val supported = runCatching { newObd.readSupportedPids() }.getOrDefault(emptySet())
            _supportedPids.value = supported

            _state.value = ConnectionState.Initializing("Reading vehicle identity")
            val vin = runCatching { newObd.readVin() }.getOrNull()
            val calibration = runCatching { newObd.readCalibrationId() }.getOrNull()
            val cvn = runCatching { newObd.readCvn() }.getOrDefault(emptyList())
            val ecuName = runCatching { newObd.readEcuName() }.getOrNull()
            _identity.value = VehicleIdentity(vin, calibration, cvn, ecuName)

            settings.update {
                it.copy(lastVin = vin ?: it.lastVin)
            }

            _state.value = ConnectionState.Ready(info, newTransport.displayName)
            startPolling()
            scope.launch { readCodesInternal() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            closeTransport()
            _state.value = ConnectionState.Error(friendlyMessage(t))
        }
    }

    private fun friendlyMessage(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            t is java.net.ConnectException || raw.contains("ECONNREFUSED", true) ||
                raw.contains("Connection refused", true) ->
                "Connection refused. Check the host, port and that you joined the adapter's Wi-Fi network."
            t is java.net.UnknownHostException ->
                "Host not found. Check the Wi-Fi address."
            t is java.net.SocketTimeoutException || raw.contains("timed out", true) ->
                "The adapter did not respond in time. Check the address and try again."
            raw.contains("No USB serial driver", true) ->
                "No USB serial driver found. Supported chipsets: FTDI, CH340, CP210x, PL2303, CDC-ACM."
            raw.contains("USB permission", true) ->
                "USB permission was denied. Reconnect the cable and allow access."
            raw.contains("Bluetooth", true) || t is SecurityException ->
                "Bluetooth is unavailable or permission was denied."
            raw.isNotBlank() -> raw
            else -> "Could not connect"
        }
    }

    suspend fun disconnect() {
        dtcReadGeneration++
        pollJob?.cancel()
        pollJob = null
        stopRecording()
        closeTransport()
        _state.value = ConnectionState.Disconnected
        _live.value = LiveSnapshot()
        _samples.value = LiveSnapshot()
        _identity.value = VehicleIdentity()
        _supportedPids.value = emptySet()
        _dtcReport.value = DtcReport()
    }

    suspend fun readCodes() = readCodesInternal()

    private suspend fun readCodesInternal() {
        val obd = obd ?: return
        val generation = ++dtcReadGeneration
        _dtcReport.value = _dtcReport.value.copy(loading = true)
        try {
            val modules = runCatching { obd.readDtcScan() }.getOrDefault(emptyList())
            val monitor = runCatching { obd.readMonitorStatus() }.getOrNull()
            val freezeFrames = if (modules.any { it.stored.isNotEmpty() }) {
                withTimeoutOrNull(20_000) {
                    runCatching {
                        obd.readFreezeFrames(modules.filter { it.stored.isNotEmpty() }.map { it.source })
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            } else {
                emptyList()
            }
            if (generation != dtcReadGeneration) return
            _dtcReport.value = DtcReport(
                modules = modules,
                monitor = monitor ?: _dtcReport.value.monitor,
                freezeFrames = freezeFrames,
                readAt = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            if (generation != dtcReadGeneration) return
            _dtcReport.value = _dtcReport.value.copy(loading = false)
            _notice.value = t.message ?: "Could not read codes"
        }
    }

    /** Reads Mode 06 on demand: it is slow on an ELM327 and not needed for a quick code check. */
    suspend fun readMode06() {
        val obd = obd ?: return
        _dtcReport.value = _dtcReport.value.copy(mode06 = _dtcReport.value.mode06.copy(loading = true))
        try {
            val report = withContext(Dispatchers.IO) { obd.readMode06() }
            _dtcReport.value = _dtcReport.value.copy(mode06 = report)
        } catch (t: Throwable) {
            _dtcReport.value = _dtcReport.value.copy(
                mode06 = _dtcReport.value.mode06.copy(loading = false),
            )
            _notice.value = t.message ?: "Could not read Mode 06"
        }
    }

    suspend fun benchmarkAdapter(): AdapterBenchmark? {
        val obd = obd ?: return null
        return try {
            withContext(Dispatchers.IO) { obd.benchmark() }
        } catch (t: Throwable) {
            _notice.value = t.message ?: "Adapter benchmark failed"
            null
        }
    }

    /**
     * Stops normal polling and records only [spec]'s PIDs at the adapter's maximum rate.
     * The poll loop is restarted afterwards, so live gauges resume where they left off.
     */
    suspend fun capture(
        spec: CaptureSpec,
        onProgress: (CaptureProgress) -> Unit = {},
    ): CaptureData = captureMutex.withLock {
        val obd = obd ?: return@withLock CaptureData.EMPTY
        val job = pollJob
        pollJob = null
        job?.cancelAndJoin()

        val started = System.currentTimeMillis()
        val points = mutableListOf<CapturePoint>()
        try {
            while (currentCoroutineContext().isActive) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= spec.durationMs) break
                for (pidId in spec.pids) {
                    if (!currentCoroutineContext().isActive) break
                    if (points.size >= MAX_CAPTURE_POINTS) break
                    val pid = PidCatalog.find(pidId) ?: continue
                    val value = runCatching { obd.readPid(pid) }.getOrNull() ?: continue
                    points += CapturePoint(pidId, System.currentTimeMillis(), value)
                    onProgress(CaptureProgress(elapsed, spec.durationMs, points.size, pidId))
                    delay(15)
                }
            }
        } finally {
            if (_state.value is ConnectionState.Ready) startPolling()
        }
        CaptureData(
            points = points,
            requestedPids = spec.pids,
            startedAt = started,
            endedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Runs one guided diagnostic while normal polling is paused.
     *
     * Only the active stage's PIDs are requested, samples keep their own timestamps, and the
     * pure [GuidedTestRunner] decides when to settle, capture, pause or stop. The result is
     * produced from whatever valid stages completed; a disconnect cannot fabricate a conclusion.
     */
    suspend fun runGuidedTest(
        definition: DiagnosticDefinition,
        profile: VehicleProfile,
        prerequisites: PrerequisiteAnswers,
        activeCodes: List<String>,
        baseline: BaselineSnapshot? = null,
        vehicleKey: String = "",
        onSnapshot: (GuidedSnapshot) -> Unit,
    ): GuidedRunOutcome = captureMutex.withLock {
        guidedCancelRequested = false
        guidedSkipRequested = false
        guidedFinishRequested = false
        guidedPauseRequested = false
        guidedResumeRequested = false
        guidedSymptomRequested = false
        val scan = _dtcReport.value.toScanContext()
        val obd = obd ?: run {
            val capabilities = currentCapabilities(profile)
            val runner = GuidedTestRunner(definition, capabilities, prerequisites, activeCodes)
            runner.start(System.currentTimeMillis())
            val result = GuidedAnalysis.analyze(
                AnalysisInput(
                    definition = definition,
                    capabilities = capabilities,
                    prerequisites = prerequisites,
                    stages = emptyList(),
                    activeCodes = activeCodes,
                    scan = scan,
                    baseline = baseline,
                    vehicleKey = vehicleKey,
                ),
            )
            return@withLock GuidedRunOutcome(result, emptyList(), runner.snapshot(System.currentTimeMillis()))
        }
        val job = pollJob
        pollJob = null
        job?.cancelAndJoin()

        val capabilities = currentCapabilities(profile)
        val runner = GuidedTestRunner(definition, capabilities, prerequisites, activeCodes)
        runner.start(System.currentTimeMillis())
        onSnapshot(runner.snapshot(System.currentTimeMillis()))

        var consecutiveFailures = 0
        var connectionLost = false
        try {
            withContext(Dispatchers.IO) {
                while (currentCoroutineContext().isActive && !runner.runState.terminal) {
                    if (guidedCancelRequested) {
                        runner.cancel()
                        break
                    }
                    val pids = runner.pidsToSample()
                    var answered = 0
                    for (pid in pids) {
                        if (!currentCoroutineContext().isActive || guidedCancelRequested) break
                        val value = try {
                            if (pid == Signals.ADAPTER_VOLTAGE) {
                                obd.readVoltage()
                            } else {
                                PidCatalog.find(pid)?.let { obd.readPid(it) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                        if (value != null && value.isFinite()) {
                            answered++
                            runner.sample(pid, value, System.currentTimeMillis())
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (guidedSkipRequested) {
                        guidedSkipRequested = false
                        runner.skipCurrentStage(now)
                    }
                    if (guidedFinishRequested) {
                        guidedFinishRequested = false
                        runner.finishStageEarly(now)
                    }
                    if (guidedPauseRequested) {
                        guidedPauseRequested = false
                        runner.pause()
                    }
                    if (guidedResumeRequested) {
                        guidedResumeRequested = false
                        runner.resume(now)
                    }
                    if (guidedSymptomRequested) {
                        guidedSymptomRequested = false
                        runner.markSymptom(now)
                    }
                    runner.tick(now)
                    onSnapshot(runner.snapshot(now))
                    consecutiveFailures = if (answered > 0) 0 else consecutiveFailures + 1
                    if (consecutiveFailures >= 12) {
                        connectionLost = true
                        break
                    }
                    delay(20)
                }
            }
        } finally {
            if (_state.value is ConnectionState.Ready) startPolling()
        }
        if (connectionLost) {
            _notice.value = "Live data stopped. The guided test ended with the data captured so far."
        }
        val now = System.currentTimeMillis()
        if (runner.runState == com.carstalker.core.guided.GuidedRunState.ANALYSING) {
            runner.tick(now + 1)
        }
        val captures = runner.completedCaptures
        val result = GuidedAnalysis.analyze(
            AnalysisInput(
                definition = definition,
                capabilities = capabilities,
                prerequisites = prerequisites,
                stages = captures,
                activeCodes = activeCodes,
                skippedStages = runner.skippedStages,
                adapterVoltageUsed = captures.any { AnalysisSupport.usesAdapterVoltage(it) },
                safetyStopReason = runner.safetyReason,
                cancelled = runner.cancelled,
                scan = scan,
                baseline = baseline,
                vehicleKey = vehicleKey,
            ),
        )
        GuidedRunOutcome(result, captures, runner.snapshot(now))
    }

    fun markGuidedSymptom() {
        guidedSymptomRequested = true
    }

    fun cancelGuidedTest() {
        guidedCancelRequested = true
    }

    fun skipGuidedStage() {
        guidedSkipRequested = true
    }

    fun finishGuidedStageEarly() {
        guidedFinishRequested = true
    }

    fun pauseGuidedTest() {
        guidedPauseRequested = true
    }

    fun resumeGuidedTest() {
        guidedResumeRequested = true
    }

    fun setDemoScenario(scenario: DemoScenario) {
        (transport as? DemoControllable)?.setScenario(scenario)
    }

    val isDemoTransport: Boolean
        get() = transport is DemoControllable

    /** Saves a guided capture as a normal wide-format recording. */
    suspend fun saveGuidedCapture(
        definition: DiagnosticDefinition,
        captures: List<StageCapture>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (captures.isEmpty()) return@withContext false
        val currentIdentity = _identity.value
        val ready = _state.value as? ConnectionState.Ready
        val pids = (definition.stages.flatMap { it.pids } + Signals.ADAPTER_VOLTAGE).distinct()
        val columns = pids.mapNotNull { pid ->
            if (pid == Signals.ADAPTER_VOLTAGE) {
                LogColumnMeta(Signals.ADAPTER_VOLTAGE, "Adapter voltage", "V")
            } else {
                PidCatalog.find(pid)?.let { LogColumnMeta(it.id, it.name, it.unit) }
            }
        }
        if (columns.isEmpty()) return@withContext false
        val writer = logs.createSession(
            vin = currentIdentity.vin,
            adapter = ready?.adapter?.identifier,
            protocol = ready?.adapter?.protocolName,
            appVersion = BuildConfig.VERSION_NAME,
            columns = columns,
            startedAt = captures.minOf { it.capture.startedAt },
        )
        try {
            for (stage in captures) {
                stage.capture.points
                    .groupBy { it.timestampMs }
                    .toSortedMap()
                    .forEach { (timestamp, points) ->
                        writer.append(timestamp, points.associate { it.pid to it.value })
                    }
            }
        } finally {
            writer.close(endedAt = captures.maxOf { it.capture.endedAt })
        }
        _notice.value = "Guided capture saved to recordings"
        true
    }

    private fun currentCapabilities(profile: VehicleProfile): VehicleCapabilities = VehicleCapabilities(
        supportedPids = _supportedPids.value,
        profile = profile,
        adapterVoltageAvailable = true,
        protocolName = (_state.value as? ConnectionState.Ready)?.adapter?.protocolName,
    )

    suspend fun clearCodes(): Boolean {
        val obd = obd ?: return false
        return try {
            val cleared = withContext(Dispatchers.IO) { obd.clearDtcs() }
            if (cleared) {
                // Any read that was already in flight may have captured the pre-clear codes.
                dtcReadGeneration++
                _dtcReport.value = DtcReport(
                    monitor = _dtcReport.value.monitor?.copy(milOn = false),
                    readAt = System.currentTimeMillis(),
                )
                _notice.value = "Trouble codes cleared"
                scope.launch { delay(1_500); readCodesInternal() }
            } else {
                _notice.value = "The ECU refused to clear codes"
            }
            cleared
        } catch (t: Throwable) {
            _notice.value = t.message ?: "Could not clear codes"
            false
        }
    }

    fun startRecording() {
        if (_recording.value) return
        val currentIdentity = _identity.value
        val ready = _state.value as? ConnectionState.Ready
        writer = logs.createSession(
            vin = currentIdentity.vin,
            adapter = ready?.adapter?.identifier,
            protocol = ready?.adapter?.protocolName,
            appVersion = BuildConfig.VERSION_NAME,
            columns = recordingColumns(settingsFlow.value),
        )
        _recording.value = true
        _notice.value = "Recording started"
    }

    fun stopRecording() {
        if (!_recording.value) return
        writer?.close()
        writer = null
        _recording.value = false
        _notice.value = "Recording saved"
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val obd = obd ?: break
                val current = settingsFlow.value
                val pids = buildPollList(current)
                val started = System.currentTimeMillis()

                // Voltage is read every cycle, even when nothing else is scheduled: it is the
                // one value that proves the adapter is still alive.
                val voltage = try {
                    obd.readVoltage()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _live.value.adapterVoltage
                }
                if (voltage != null) {
                    lastValues[LiveKeys.ADAPTER_VOLTAGE] = voltage
                    _live.value = LiveSnapshot(lastValues.toMap(), voltage)
                }

                if (pids.isEmpty()) {
                    scheduler.advance()
                    val elapsedIdle = System.currentTimeMillis() - started
                    delay((current.pollIntervalMs - elapsedIdle).coerceAtLeast(50))
                    continue
                }
                val values = LinkedHashMap<Int, Double>()
                for (pid in pids) {
                    if (!isActive) break
                    val value = try {
                        obd.readPid(pid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    scheduler.record(pid.id, value != null)
                    if (value != null && value.isFinite()) {
                        values[pid.id] = value
                        lastValues[pid.id] = value
                        // Publish per parameter so a gauge moves the moment its value arrives
                        // instead of waiting for the whole cycle to finish.
                        _live.value = LiveSnapshot(lastValues.toMap(), voltage)
                    }
                }
                val now = System.currentTimeMillis()
                if (values.isNotEmpty() || voltage != null) {
                    consecutiveFailures = 0
                    val cycleValues = values.toMutableMap()
                    voltage?.let { cycleValues[LiveKeys.ADAPTER_VOLTAGE] = it }
                    _samples.value = LiveSnapshot(cycleValues, voltage)
                    val logValues = cycleValues
                    runCatching { writer?.append(now, logValues) }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 6) {
                        _state.value = ConnectionState.Error("Lost communication with the vehicle")
                        closeTransport()
                        break
                    }
                }
                scheduler.advance()
                // Every 30 cycles (~10 s) let retired parameters prove themselves again.
                if (scheduler.cycle % RETRY_SILENT_EVERY == 0L) scheduler.retrySilent()
                val elapsed = System.currentTimeMillis() - started
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "ObdSession",
                        "cycle ${elapsed}ms, ${values.size}/${pids.size} values, voltage=$voltage",
                    )
                }
                // The interval is a floor between cycles; a long cycle simply starts the next one immediately.
                delay((current.pollIntervalMs - elapsed).coerceAtLeast(0))
            }
        }
    }

    private fun buildPollList(current: AppSettings): List<Pid> {
        val custom = current.customPids
            .filter { it.enabled }
            .mapNotNull { it.toPid() }
            .associateBy { it.id }
        val dashboard = if (current.dashboardAuto) {
            recommendedDashboardPids(_supportedPids.value)
        } else {
            current.dashboardPids
        }
        // Synthetic ids such as the adapter voltage have no OBD PID to poll.
        val requested = (ESSENTIAL_PIDS + dashboard + custom.keys).distinct().filter { it >= 0 }
        val scheduled = scheduler.next(requested, _supportedPids.value, exempt = custom.keys)
        return scheduled.mapNotNull { id -> custom[id] ?: PidCatalog.find(id) }
    }

    private fun recordingColumns(current: AppSettings): List<LogColumnMeta> {
        val custom = current.customPids
            .filter { it.enabled }
            .mapNotNull { it.toPid() }
            .associateBy { it.id }
        val dashboard = if (current.dashboardAuto) {
            recommendedDashboardPids(_supportedPids.value)
        } else {
            current.dashboardPids
        }
        val requested = (ESSENTIAL_PIDS + dashboard + custom.keys).distinct()
        val pids = requested.mapNotNull { id -> custom[id] ?: PidCatalog.find(id) }
        return (listOf(AdapterVoltageParam) + pids)
            .distinctBy { it.id }
            .map { LogColumnMeta(it.id, it.name, it.unit) }
    }

    private fun closeTransport() {
        val current = transport
        transport = null
        obd = null
        if (current != null) {
            scope.launch { runCatching { current.close() } }
        }
    }

    private companion object {
        val ESSENTIAL_PIDS = listOf(0x0C, 0x0D, 0x04, 0x05)

        /** How often retired parameters are given another chance. */
        const val RETRY_SILENT_EVERY = 30L
        const val MAX_CAPTURE_POINTS = 20_000
    }
}
