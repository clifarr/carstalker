package com.carstalker.app.service

import android.content.Context
import com.carstalker.app.BuildConfig
import com.carstalker.app.data.AdapterVoltageParam
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.applyPedalCalibration
import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.LogColumnMeta
import com.carstalker.app.data.LogRepository
import com.carstalker.app.data.LogWriter
import com.carstalker.app.data.RecordingPlan
import com.carstalker.app.data.RecordingStall
import com.carstalker.app.data.SettingsRepository
import com.carstalker.app.data.buildRecordingPlan
import com.carstalker.app.data.withDemoProfile
import com.carstalker.app.data.mergedPollPids
import com.carstalker.app.data.samplingGapMs
import com.carstalker.app.data.toPid
import com.carstalker.app.data.visiblePollPids
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
import com.carstalker.core.obd.LiveDataFreshness
import com.carstalker.core.obd.Mode06Report
import com.carstalker.core.obd.MonitorDataState
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

/** Lifecycle of a drive recording. "Saved" is only shown after [RecordingState.COMPLETED]. */
enum class RecordingState {
    IDLE,
    RECORDING,
    STOPPING,
    COMPLETED,
    FAILED,
}

/** Samples written to the open recording, and when the newest one landed. */
data class RecordingProgress(val rows: Long, val lastSampleAt: Long)

data class LiveSnapshot(
    val values: Map<Int, Double> = emptyMap(),
    /** Measured by the adapter itself (`ATRV`), refreshed with every poll cycle. */
    val adapterVoltage: Double? = null,
    /** Wall-clock time of the most recent accepted sample, including adapter voltage. */
    val lastUpdatedAt: Long = 0L,
    /** Wall-clock time of the most recent ECU (non-adapter) sample; 0 until one arrives. */
    val lastEcuAt: Long = 0L,
    /** The adapter is still connected but the ECU stopped answering. */
    val vehicleOffline: Boolean = false,
    /**
     * The active dashboard polls at least one ECU parameter. When false (an adapter-only
     * dashboard), [lastEcuAt] is meaningless and [lastUpdatedAt] is the freshness source.
     */
    val ecuExpected: Boolean = false,
)

/**
 * Wall-clock time that proves the active dashboard is still live.
 *
 * An adapter-only dashboard never receives an ECU sample, so a leftover [LiveSnapshot.lastEcuAt]
 * from an earlier dashboard must not be used: it would keep the status on "Delayed" while
 * adapter voltage streams.
 */
fun LiveSnapshot.freshnessAt(): Long =
    if (ecuExpected) lastEcuAt.takeIf { it > 0 } ?: lastUpdatedAt else lastUpdatedAt

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
    mode06State = mode06.state,
    mode06ReadAt = mode06.readAt,
    readAt = readAt,
)

/**
 * Owns the adapter connection and the polling loop. One instance lives for the whole app,
 * so switching screens never interrupts live data.
 */
class ObdSession(
    private val settings: SettingsRepository,
    private val logs: LogRepository,
    private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settingsFlow: StateFlow<AppSettings> = settings.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private var transport: Transport? = null
    private var obd: ObdClient? = null
    private var pollJob: Job? = null

    /** Recording channels snapshotted at start; dashboards cannot change them mid-file. */
    @Volatile
    private var writer: LogWriter? = null

    @Volatile
    private var recordingPlan: RecordingPlan? = null

    /**
     * Monotonic time of the last completed adapter exchange. The poll loop advances it before
     * every read, so a long silence means the loop itself was not running (Android froze the
     * process) rather than one slow Bluetooth Classic exchange. Uses elapsed realtime so a
     * suspend counts as a gap and a clock change cannot fake one.
     */
    @Volatile
    private var lastPollProgressAt = 0L

    /** Rows written to the open recording and the newest sample time, for the notification. */
    @Volatile
    private var recordedRows = 0L

    @Volatile
    private var recordedLastAt = 0L

    private val scheduler = PollScheduler()
    private val captureMutex = Mutex()
    private val lastValues = mutableMapOf<Int, Double>()
    private val freshness = LiveDataFreshness()

    /** Parameter whose detail view is open; requested on every cycle while it is set. */
    @Volatile
    private var focusPid: Int? = null

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

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    /** Set when a recording failed or could not be protected in the background. */
    private val _recordingIssue = MutableStateFlow<String?>(null)
    val recordingIssue: StateFlow<String?> = _recordingIssue.asStateFlow()

    /** Sampling gaps in the current or most recent recording; null until one is detected. */
    private val _recordingStall = MutableStateFlow<RecordingStall?>(null)
    val recordingStall: StateFlow<RecordingStall?> = _recordingStall.asStateFlow()

    val isRecording: Boolean get() = _recordingState.value == RecordingState.RECORDING

    fun clearRecordingIssue() {
        _recordingIssue.value = null
    }

    fun clearRecordingStall() {
        _recordingStall.value = null
    }

    /** Rows written so far and the newest sample time; the service shows this in the notification. */
    fun recordingProgress(): RecordingProgress = RecordingProgress(recordedRows, recordedLastAt)

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Prioritises one parameter while its detail view is open, so the enlarged gauge refreshes
     * on every poll cycle instead of waiting for its scheduled turn. Synthetic adapter
     * measurements (negative ids) have no OBD request and are ignored.
     */
    fun setFocusPid(pidId: Int?) {
        focusPid = pidId?.takeIf { it >= 0 }
    }

    suspend fun connect(transportFactory: () -> Transport) {
        if (_state.value is ConnectionState.Ready || _state.value is ConnectionState.Connecting) return
        Elm327Client.DEBUG = BuildConfig.DEBUG
        Elm327Client.trace = { android.util.Log.d("Elm327", it) }
        _state.value = ConnectionState.Connecting
        // Never carry the previous session's readings into a new connection attempt.
        _live.value = LiveSnapshot()
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

            val vehicleKey = vin?.takeIf { it.isNotBlank() }
                ?: calibration?.takeIf { it.isNotBlank() }
                ?: "unknown"
            val demo = newTransport is DemoControllable
            settings.update { current ->
                // Repair the simulated car once after the old global fallback could copy a real
                // car's name and fuel type onto it. Later demo edits are preserved.
                val repaired = if (demo) current.withDemoProfile(vehicleKey) else current
                // Never keep a previous car's VIN when this car does not report one.
                repaired.copy(lastVin = vin)
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
        freshness.reset()
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
        _dtcReport.value = _dtcReport.value.copy(
            mode06 = _dtcReport.value.mode06.copy(
                loading = true,
                state = MonitorDataState.IN_PROGRESS,
            ),
        )
        try {
            val report = withContext(Dispatchers.IO) { obd.readMode06() }
            _dtcReport.value = _dtcReport.value.copy(mode06 = report)
        } catch (t: Throwable) {
            _dtcReport.value = _dtcReport.value.copy(
                mode06 = _dtcReport.value.mode06.copy(
                    loading = false,
                    state = MonitorDataState.FAILED_TO_READ,
                    error = t.message ?: "Could not read Mode 06",
                ),
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
                    unverifiedSafetySignals = runner.unverifiedSafetySignals,
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
                        runner.connectionLost("The adapter stopped responding.")
                        onSnapshot(runner.snapshot(now))
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
                unverifiedSafetySignals = runner.unverifiedSafetySignals,
                cancelled = runner.cancelled,
                connectionLost = connectionLost,
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
        val writer = try {
            logs.createSession(
                vin = currentIdentity.vin,
                adapter = ready?.adapter?.identifier,
                protocol = ready?.adapter?.protocolName,
                appVersion = BuildConfig.VERSION_NAME,
                columns = columns,
                startedAt = captures.minOf { it.capture.startedAt },
                kind = "guided",
                label = definition.title,
                isDemo = ready?.transportName?.contains("Demo") == true,
            )
        } catch (t: Throwable) {
            _notice.value = "Guided capture could not be saved: ${t.message ?: "file error"}"
            return@withContext false
        }
        val result = try {
            for (stage in captures) {
                stage.capture.points
                    .groupBy { it.timestampMs }
                    .toSortedMap()
                    .forEach { (timestamp, points) ->
                        writer.append(timestamp, points.associate { it.pid to it.value })
                    }
            }
            writer.close(endedAt = captures.maxOf { it.capture.endedAt })
        } catch (t: Throwable) {
            writer.close()
        }
        if (!result.success) {
            _notice.value = result.message ?: "Guided capture could not be saved"
            return@withContext false
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
        if (_recordingState.value == RecordingState.RECORDING ||
            _recordingState.value == RecordingState.STOPPING
        ) {
            return
        }
        val currentIdentity = _identity.value
        val ready = _state.value as? ConnectionState.Ready
        val plan = try {
            buildRecordingPlan(settingsFlow.value, _supportedPids.value)
        } catch (t: Throwable) {
            val reason = "Recording could not start: ${t.message ?: "the channel list could not be built"}"
            _recordingIssue.value = reason
            setRecordingState(RecordingState.FAILED)
            _notice.value = reason
            return
        }
        val newWriter = try {
            logs.createSession(
                vin = currentIdentity.vin,
                adapter = ready?.adapter?.identifier,
                protocol = ready?.adapter?.protocolName,
                appVersion = BuildConfig.VERSION_NAME,
                columns = plan.columns,
                kind = "drive",
                label = null,
                isDemo = ready?.transportName?.contains("Demo") == true,
            )
        } catch (t: Throwable) {
            val reason = "Recording could not start: ${t.message ?: "could not create the log file"}"
            _recordingIssue.value = reason
            setRecordingState(RecordingState.FAILED)
            _notice.value = reason
            return
        }
        writer = newWriter
        recordingPlan = plan
        recordedRows = 0L
        recordedLastAt = 0L
        _recordingIssue.value = null
        _recordingStall.value = null
        setRecordingState(RecordingState.RECORDING)
        if (!RecordingService.start(context)) {
            val reason = "Recording continues, but the background service could not start. " +
                "Keep the app open, or lock-screen recording may stop."
            _recordingIssue.value = reason
            _notice.value = reason
        } else {
            _notice.value = "Recording started: ${plan.pids.size} channels"
        }
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return
        finishRecording(RecordingState.COMPLETED)
    }

    /**
     * Re-posts the recording notification after POST_NOTIFICATIONS is granted. The service
     * was already running; starting it again updates the existing foreground notification.
     */
    fun refreshRecordingNotification() {
        if (isRecording) RecordingService.start(context)
    }

    /** Called by the foreground service when it cannot protect background capture. */
    fun reportBackgroundFailure(reason: String) {
        if (!isRecording) return
        _recordingIssue.value = reason
        _notice.value = reason
    }

    private fun finishRecording(outcome: RecordingState, message: String? = null) {
        val current = _recordingState.value
        if (current != RecordingState.RECORDING && current != RecordingState.STOPPING) return
        // Keep _recording true until the writer is closed: the UI refreshes the recordings list
        // when it flips, and reading the index before close() persists endedAt made a saved
        // recording look like an interrupted, partial file.
        _recordingState.value = RecordingState.STOPPING
        val active = writer
        writer = null
        recordingPlan = null
        val result = active?.close()
        if (result?.success == true && outcome == RecordingState.COMPLETED) {
            setRecordingState(RecordingState.COMPLETED)
            _notice.value = message ?: "Recording saved"
        } else {
            val reason = result?.message
                ?: message
                ?: "The recording could not be saved. The partial file was kept."
            setRecordingState(RecordingState.FAILED)
            _recordingIssue.value = reason
            _notice.value = reason
        }
        RecordingService.stop(context)
    }

    private fun failRecording(reason: String) {
        if (_recordingState.value != RecordingState.RECORDING) return
        android.util.Log.w("ObdSession", reason)
        finishRecording(RecordingState.FAILED, reason)
    }

    private fun setRecordingState(state: RecordingState) {
        _recordingState.value = state
        _recording.value = state == RecordingState.RECORDING
    }

    /**
     * Writes one cycle into the recording. A write failure ends the recording immediately so
     * the UI can never keep showing a live recording while data is being lost.
     */
    private fun appendRecording(timestampMs: Long, values: Map<Int, Double>) {
        val active = writer ?: return
        try {
            active.append(timestampMs, values)
            recordedRows++
            recordedLastAt = timestampMs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failRecording(
                "Recording stopped: ${t.message ?: "the log file could not be written"}. " +
                    "The partial file was kept.",
            )
        }
    }

    /**
     * Marks the poll loop as alive and reports a gap if the loop was stopped since the previous
     * mark. Called before every adapter read: a slow exchange never counts as a stall, but a
     * frozen process does, and the gap is written into the recording's metadata once it resumes.
     */
    private fun markPollProgress() {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = samplingGapMs(lastPollProgressAt, now)
        if (gap != null && isRecording) noteSamplingStall(gap)
        lastPollProgressAt = now
    }

    private fun noteSamplingStall(gapMs: Long) {
        val previous = _recordingStall.value
        _recordingStall.value = RecordingStall(
            longestMs = maxOf(previous?.longestMs ?: 0L, gapMs),
            totalMs = (previous?.totalMs ?: 0L) + gapMs,
            count = (previous?.count ?: 0) + 1,
            detectedAt = System.currentTimeMillis(),
        )
        writer?.noteStall(gapMs)
        android.util.Log.w(
            "ObdSession",
            "No samples for ${gapMs / 1000}s while recording; the poll loop was not running",
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        lastPollProgressAt = android.os.SystemClock.elapsedRealtime()
        pollJob = scope.launch {
            var adapterFailures = 0
            freshness.start(System.currentTimeMillis())
            while (isActive) {
                val obd = obd ?: break
                markPollProgress()
                val current = settingsFlow.value
                val dashboardRequested = visiblePollPids(current, _supportedPids.value)
                // Recording subscriptions are merged into every cycle, so switching dashboards
                // cannot stop the channels that the open CSV already claims to contain.
                val recordingPids = recordingPlan?.pids.orEmpty()
                val focus = focusPid
                val requested = mergedPollPids(dashboardRequested, recordingPids, focus)
                val ecuExpected = requested.isNotEmpty()
                val pids = buildPollList(current, requested, focus)
                val started = System.currentTimeMillis()

                // Voltage is read every cycle, even when nothing else is scheduled. It proves the
                // adapter is alive, not the ECU: after the ignition is switched off the adapter
                // keeps answering ATRV while the engine values stop. Never fall back to the last
                // voltage on a failed read, or a dead adapter would look alive forever.
                val voltage = try {
                    obd.readVoltage()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                markPollProgress()
                if (voltage != null) {
                    lastValues[LiveKeys.ADAPTER_VOLTAGE] = voltage
                    publishLive(voltage, ecuExpected)
                }

                if (pids.isEmpty()) {
                    // A focus dashboard can legitimately request no OBD PIDs. ATRV is still a
                    // real sample, so publish it to charts and recordings instead of skipping.
                    if (voltage != null) {
                        val now = System.currentTimeMillis()
                        val cycleValues = mapOf(LiveKeys.ADAPTER_VOLTAGE to voltage)
                        _samples.value = LiveSnapshot(cycleValues, voltage, now)
                        appendRecording(now, cycleValues)
                    }
                    publishLive(voltage, ecuExpected, sampled = false)
                    adapterFailures = if (voltage == null) adapterFailures + 1 else 0
                    if (adapterFailures >= ADAPTER_FAILURE_LIMIT) {
                        loseConnection()
                        break
                    }
                    scheduler.advance()
                    retrySilentIfDue(ecuExpected)
                    val elapsedIdle = System.currentTimeMillis() - started
                    delay((current.pollIntervalMs - elapsedIdle).coerceAtLeast(50))
                    continue
                }
                val values = LinkedHashMap<Int, Double>()
                // Recorded values use the interpolation mode snapshotted at recording start, so
                // switching to a dashboard with a different setting cannot change the file.
                val recordValues = LinkedHashMap<Int, Double>()
                val plan = recordingPlan
                for (pid in pids) {
                    if (!isActive) break
                    val raw = try {
                        obd.readPid(pid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    markPollProgress()
                    val rawValue = raw?.takeIf { it.isFinite() }
                    val value = rawValue?.let {
                        applyPedalCalibration(
                            pid.id,
                            it,
                            current,
                            current.activeDashboard.pedalInterpolation,
                        )
                    }
                    val recordValue = rawValue?.let {
                        applyPedalCalibration(pid.id, it, current, plan?.pedalInterpolation == true)
                    }
                    val answered = value != null && value.isFinite()
                    scheduler.record(pid.id, answered)
                    val answeredAt = System.currentTimeMillis()
                    if (answered) {
                        values[pid.id] = value
                        lastValues[pid.id] = value
                        recordValues[pid.id] = recordValue ?: value
                        freshness.sample(answeredAt)
                    } else {
                        freshness.miss(answeredAt)
                    }
                    // Check after every exchange, not once per cycle: the dashboard must stop
                    // looking live within one adapter round-trip of the ECU going quiet.
                    publishLive(voltage, ecuExpected, sampled = answered)
                }
                val now = System.currentTimeMillis()
                if (values.isNotEmpty() || voltage != null) {
                    adapterFailures = 0
                    val cycleValues = values.toMutableMap()
                    voltage?.let { cycleValues[LiveKeys.ADAPTER_VOLTAGE] = it }
                    _samples.value = LiveSnapshot(cycleValues, voltage, now)
                    val logValues = recordValues.toMutableMap()
                    voltage?.let { logValues[LiveKeys.ADAPTER_VOLTAGE] = it }
                    appendRecording(now, logValues)
                } else {
                    adapterFailures++
                    if (adapterFailures >= ADAPTER_FAILURE_LIMIT) {
                        loseConnection()
                        break
                    }
                }
                publishLive(voltage, ecuExpected, sampled = false)
                scheduler.advance()
                retrySilentIfDue(ecuExpected)
                val elapsed = System.currentTimeMillis() - started
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "ObdSession",
                        "cycle ${elapsed}ms, ${values.size}/${pids.size} values, voltage=$voltage, " +
                            "offline=${freshness.isOffline(now, ecuExpected)}",
                    )
                }
                // The interval is a floor between cycles; a long cycle simply starts the next one immediately.
                delay((current.pollIntervalMs - elapsed).coerceAtLeast(0))
            }
        }
    }

    /**
     * Publishes what the dashboard should show. When the ECU has been silent for the freshness
     * window the last engine values are dropped instead of being republished with a new
     * timestamp: the adapter answering ATRV must not keep a switched-off engine looking live.
     */
    private fun publishLive(voltage: Double?, ecuExpected: Boolean, sampled: Boolean = voltage != null) {
        // A cancelled poll loop can finish its blocking read after a disconnect. Never let that
        // late snapshot put values back on a dashboard that is already offline.
        if (_state.value !is ConnectionState.Ready) return
        val now = System.currentTimeMillis()
        val offline = freshness.isOffline(now, ecuExpected)
        val previous = _live.value
        if (offline && !previous.vehicleOffline) {
            // Keep only the adapter measurement. If the ECU answers again, every value must be
            // re-read before it can appear as live.
            lastValues.keys.retainAll { it == LiveKeys.ADAPTER_VOLTAGE }
        }
        val values = if (offline) {
            voltage?.let { mapOf(LiveKeys.ADAPTER_VOLTAGE to it) }.orEmpty()
        } else {
            lastValues.toMap()
        }
        _live.value = LiveSnapshot(
            values = values,
            adapterVoltage = voltage ?: previous.adapterVoltage,
            lastUpdatedAt = if (sampled) now else previous.lastUpdatedAt,
            lastEcuAt = freshness.lastSampleAt,
            vehicleOffline = offline,
            ecuExpected = ecuExpected,
        )
    }

    /**
     * Gives retired parameters another chance. Normally this happens every
     * [RETRY_SILENT_EVERY] cycles, but while the ECU is offline every cycle is a recovery
     * probe: the engine may be started again at any moment, and the dashboard must come back
     * without waiting for a slow user-chosen poll interval to run out its retry count.
     */
    private fun retrySilentIfDue(ecuExpected: Boolean) {
        val offline = freshness.isOffline(System.currentTimeMillis(), ecuExpected)
        val every = if (offline) 1L else RETRY_SILENT_EVERY
        if (scheduler.cycle % every == 0L) scheduler.retrySilent()
    }

    private fun buildPollList(current: AppSettings, requested: List<Int>, priority: Int? = null): List<Pid> {
        val custom = current.customPids
            .filter { it.enabled }
            .mapNotNull { it.toPid() }
            .associateBy { it.id }
        val scheduled = scheduler.next(requested, _supportedPids.value, exempt = custom.keys, priority = priority)
        return scheduled.mapNotNull { id -> custom[id] ?: PidCatalog.find(id) }
    }

    private fun loseConnection() {
        finishRecording(
            RecordingState.FAILED,
            "Connection lost. Recording stopped; the partial file was kept.",
        )
        _state.value = ConnectionState.Error("Lost communication with the vehicle")
        _live.value = LiveSnapshot()
        freshness.reset()
        closeTransport()
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
        /** How often retired parameters are given another chance. */
        const val RETRY_SILENT_EVERY = 30L

        /** Consecutive cycles with no adapter answer and no ECU values before the link is dead. */
        const val ADAPTER_FAILURE_LIMIT = 6

        const val MAX_CAPTURE_POINTS = 20_000
    }
}
