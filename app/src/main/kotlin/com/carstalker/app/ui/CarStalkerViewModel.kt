package com.carstalker.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.carstalker.app.BuildConfig
import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.GuidedResultExport
import com.carstalker.app.data.LogSession
import com.carstalker.app.data.PdfPreview
import com.carstalker.app.data.PdfPrinter
import com.carstalker.app.data.ReportBuilder
import com.carstalker.app.data.ReportPdfWriter
import com.carstalker.app.data.ThemeMode
import com.carstalker.app.data.TransportKind
import com.carstalker.app.data.VehicleProfileEntry
import com.carstalker.app.data.blankDashboard
import com.carstalker.app.data.MAX_DASHBOARD_PIDS
import com.carstalker.app.data.movedDashboardPids
import com.carstalker.app.data.recommendedDashboardPids
import com.carstalker.app.data.replacedDashboardPids
import com.carstalker.app.data.toggledDashboardPids
import com.carstalker.app.di.AppGraph
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.transport.ble.BleTransport
import com.carstalker.app.transport.ble.DiscoveredDevice
import com.carstalker.app.transport.classic.ClassicDevice
import com.carstalker.app.transport.classic.ClassicTransport
import com.carstalker.app.transport.demo.DemoScenario
import com.carstalker.app.transport.demo.DemoTransport
import com.carstalker.app.transport.tcp.TcpTransport
import com.carstalker.app.transport.usb.UsbTransport
import androidx.core.content.FileProvider
import com.carstalker.app.data.BaselineStore
import com.carstalker.core.guided.BaselineSnapshot
import com.carstalker.core.guided.BaselineExtractor
import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CaptureProgress
import com.carstalker.core.diagnose.CatalystAnswers
import com.carstalker.core.diagnose.CatalystInput
import com.carstalker.core.diagnose.CatalystWizard
import com.carstalker.core.diagnose.WizardResult
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.DiagnosticDefinition
import com.carstalker.core.guided.FuelType
import com.carstalker.core.guided.GuidedResult
import com.carstalker.core.guided.GuidedSnapshot
import com.carstalker.core.guided.PrerequisiteAnswers
import com.carstalker.core.guided.StageCapture
import com.carstalker.core.guided.VehicleCapabilities
import com.carstalker.core.guided.VehicleProfile
import com.carstalker.core.elm.AdapterBenchmark
import com.carstalker.core.obd.UnitSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

class CarStalkerViewModel(private val graph: AppGraph) : ViewModel() {

    @SuppressLint("StaticFieldLeak")
    private val context = graph.appContext

    val settings: StateFlow<AppSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val connection = graph.session.state
    val live = graph.session.live
    val samples = graph.session.samples
    val identity = graph.session.identity
    val supportedPids = graph.session.supportedPids
    val dtcReport = graph.session.dtcReport
    val recording = graph.session.recording
    val bleDevices: StateFlow<List<DiscoveredDevice>> = graph.bleScanner.devices
    val bleScanning: StateFlow<Boolean> = graph.bleScanner.scanning
    val classicDevices: StateFlow<List<ClassicDevice>> = graph.classicScanner.devices
    val classicDiscovering: StateFlow<Boolean> = graph.classicScanner.discovering

    private val _history = MutableStateFlow<Map<Int, List<Float>>>(emptyMap())
    val history: StateFlow<Map<Int, List<Float>>> = _history.asStateFlow()

    /**
     * Wall-clock time of the newest accepted sample per parameter. The dashboard uses it to
     * distinguish a static value the car keeps reporting from a value that stopped updating.
     */
    private val _lastSampleAt = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val lastSampleAt: StateFlow<Map<Int, Long>> = _lastSampleAt.asStateFlow()

    private val _logs = MutableStateFlow<List<LogSession>>(emptyList())
    val logs: StateFlow<List<LogSession>> = _logs.asStateFlow()

    private val _selectedLog = MutableStateFlow<LogSession?>(null)
    val selectedLog: StateFlow<LogSession?> = _selectedLog.asStateFlow()

    private val _pdfPreview = MutableStateFlow<PdfPreview?>(null)
    val pdfPreview: StateFlow<PdfPreview?> = _pdfPreview.asStateFlow()

    private val _usbDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val usbDevices: StateFlow<List<UsbDevice>> = _usbDevices.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _showPidPicker = MutableStateFlow(false)
    val showPidPicker: StateFlow<Boolean> = _showPidPicker.asStateFlow()

    private val _benchmark = MutableStateFlow<AdapterBenchmark?>(null)
    val benchmark: StateFlow<AdapterBenchmark?> = _benchmark.asStateFlow()

    private val _captureData = MutableStateFlow<CaptureData?>(null)
    val captureData: StateFlow<CaptureData?> = _captureData.asStateFlow()

    private val _captureProgress = MutableStateFlow<CaptureProgress?>(null)
    val captureProgress: StateFlow<CaptureProgress?> = _captureProgress.asStateFlow()

    private val _wizardResult = MutableStateFlow<WizardResult?>(null)
    val wizardResult: StateFlow<WizardResult?> = _wizardResult.asStateFlow()

    private val _activeGuide = MutableStateFlow<DiagnosticDefinition?>(null)
    val activeGuide: StateFlow<DiagnosticDefinition?> = _activeGuide.asStateFlow()

    private val _guidedSnapshot = MutableStateFlow<GuidedSnapshot?>(null)
    val guidedSnapshot: StateFlow<GuidedSnapshot?> = _guidedSnapshot.asStateFlow()

    private val _guidedResult = MutableStateFlow<GuidedResult?>(null)
    val guidedResult: StateFlow<GuidedResult?> = _guidedResult.asStateFlow()

    private val _guidedCaptures = MutableStateFlow<List<StageCapture>>(emptyList())
    val guidedCaptures: StateFlow<List<StageCapture>> = _guidedCaptures.asStateFlow()

    private val _guidedTrace = MutableStateFlow<Map<Int, List<Float>>>(emptyMap())
    val guidedTrace: StateFlow<Map<Int, List<Float>>> = _guidedTrace.asStateFlow()

    val vehicleCapabilities: StateFlow<VehicleCapabilities> =
        combine(graph.session.supportedPids, settings, connection) { pids, current, conn ->
            val bankCount = if (pids.any { it in setOf(0x08, 0x09, 0x13, 0x1B) }) 2 else 1
            VehicleCapabilities(
                supportedPids = pids,
                profile = VehicleProfile(current.fuelType, current.aspiration, bankCount),
                adapterVoltageAvailable = true,
                protocolName = (conn as? ConnectionState.Ready)?.adapter?.protocolName,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VehicleCapabilities())

    private var captureJob: kotlinx.coroutines.Job? = null
    private var guidedJob: kotlinx.coroutines.Job? = null
    private var guidedTraceStage = -1

    private var scanning = false

    init {
        viewModelScope.launch {
            graph.session.samples.collect { snapshot ->
                val points = snapshot.values
                if (points.isEmpty()) return@collect
                val sampledAt = System.currentTimeMillis()
                _lastSampleAt.update { old ->
                    val updated = old.toMutableMap()
                    for (pid in points.keys) updated[pid] = sampledAt
                    updated
                }
                _history.update { old ->
                    val updated = old.toMutableMap()
                    for ((pid, value) in points) {
                        val series = updated[pid].orEmpty()
                        updated[pid] = (series + value.toFloat()).takeLast(HISTORY_LENGTH)
                    }
                    updated
                }
            }
        }
        viewModelScope.launch {
            graph.session.identity.collect { identity ->
                val key = identity.vin?.takeIf { it.isNotBlank() }
                    ?: identity.calibrationId?.takeIf { it.isNotBlank() }
                    ?: "unknown"
                applyVehicleProfile(key)
            }
        }
        viewModelScope.launch {
            graph.session.notice.filterNotNull().collect {
                _messages.emit(it)
                graph.session.clearNotice()
            }
        }
        viewModelScope.launch {
            graph.session.recording.collect { active ->
                if (!active) refreshLogs()
            }
        }
        viewModelScope.launch {
            graph.session.state.collect { state ->
                if (state is ConnectionState.Connecting) {
                    resetCatalystWizard()
                    resetGuidedTest()
                    _activeGuide.value = null
                    _benchmark.value = null
                    _lastSampleAt.value = emptyMap()
                }
            }
        }
        refreshLogs()
        refreshUsbDevices()
    }

    // ---- Connection -------------------------------------------------------

    fun startBleScan() {
        scanning = true
        graph.bleScanner.start()
    }

    fun stopBleScan() {
        scanning = false
        graph.bleScanner.stop()
    }

    fun connectBle(device: DiscoveredDevice) {
        graph.bleScanner.stop()
        viewModelScope.launch {
            graph.session.connect { BleTransport(context, device.device) }
        }
    }

    fun refreshClassicDevices() {
        graph.classicScanner.refreshBonded()
    }

    fun startClassicDiscovery() {
        graph.classicScanner.startDiscovery()
    }

    fun stopClassicDiscovery() {
        graph.classicScanner.stop()
    }

    /** Debug/QA hook: connect to a bonded Classic device by MAC address. */
    fun connectClassicAddress(address: String) {
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val adapter = manager?.adapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        graph.classicScanner.stop()
        viewModelScope.launch {
            graph.session.connect { ClassicTransport(context, device) }
        }
    }

    fun connectClassic(device: ClassicDevice) {
        graph.classicScanner.stop()
        viewModelScope.launch {
            graph.session.connect { ClassicTransport(context, device.device) }
        }
    }

    fun connectUsb(device: UsbDevice, baud: Int) {
        if (UsbTransport.hasPermission(context, device)) {
            connectUsbInternal(device, baud)
        } else {
            UsbTransport.requestPermission(context, device) { granted ->
                if (granted) connectUsbInternal(device, baud)
                else viewModelScope.launch { _messages.emit("USB permission denied") }
            }
        }
    }

    private fun connectUsbInternal(device: UsbDevice, baud: Int) {
        viewModelScope.launch {
            graph.settings.update { it.copy(usbBaud = baud) }
            graph.session.connect { UsbTransport(context, device, baud) }
        }
    }

    fun connectTcp(host: String, port: Int) {
        viewModelScope.launch {
            graph.settings.update { it.copy(tcpHost = host, tcpPort = port) }
            graph.session.connect { TcpTransport(host, port) }
        }
    }

    fun connectDemo() {
        viewModelScope.launch {
            graph.session.connect { DemoTransport() }
        }
    }

    fun disconnect() {
        viewModelScope.launch { graph.session.disconnect() }
    }

    fun refreshUsbDevices() {
        _usbDevices.value = UsbTransport.availableDevices(context)
    }

    // ---- Diagnostics ------------------------------------------------------

    fun readCodes() {
        viewModelScope.launch { graph.session.readCodes() }
    }

    fun clearCodes() {
        viewModelScope.launch { graph.session.clearCodes() }
    }

    fun readMode06() {
        viewModelScope.launch { graph.session.readMode06() }
    }

    fun runBenchmark() {
        viewModelScope.launch { _benchmark.value = graph.session.benchmarkAdapter() }
    }

    // ---- Guided diagnostics ----------------------------------------------

    fun startCatalystCapture() {
        if (_captureProgress.value != null) return
        captureJob = viewModelScope.launch {
            _captureData.value = null
            _wizardResult.value = null
            _captureProgress.value = CaptureProgress(
                elapsedMs = 0,
                durationMs = CatalystWizard.captureSpec.durationMs,
                points = 0,
                currentPid = null,
            )
            try {
                val data = graph.session.capture(CatalystWizard.captureSpec) { progress ->
                    _captureProgress.value = progress
                }
                _captureData.value = data
                if (data.points.isEmpty()) {
                    _messages.emit("No O2 data captured. Check that the car reports the upstream and downstream O2 PIDs.")
                }
            } catch (t: Throwable) {
                _messages.emit(t.message ?: "O2 capture failed")
            } finally {
                _captureProgress.value = null
            }
        }
    }

    fun analyzeCatalyst(answers: CatalystAnswers) {
        val report = dtcReport.value
        val codes = report.modules.flatMap { module ->
            listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
        }
        _wizardResult.value = CatalystWizard.analyze(
            CatalystInput(
                dtcs = codes,
                mode06 = report.mode06,
                capture = _captureData.value,
                answers = answers,
            ),
        )
    }

    fun resetCatalystWizard() {
        captureJob?.cancel()
        captureJob = null
        _captureData.value = null
        _captureProgress.value = null
        _wizardResult.value = null
    }

    // ---- Shared guided diagnostics --------------------------------------

    fun openGuide(definition: DiagnosticDefinition) {
        if (!definition.implemented) {
            viewModelScope.launch { _messages.emit("${definition.title} is not implemented yet.") }
            return
        }
        resetGuidedTest()
        resetCatalystWizard()
        _activeGuide.value = definition
    }

    fun closeGuide() {
        graph.session.cancelGuidedTest()
        resetGuidedTest()
        resetCatalystWizard()
        _activeGuide.value = null
    }

    fun startGuidedTest(prerequisites: PrerequisiteAnswers) {
        val definition = _activeGuide.value ?: return
        if (definition.legacyWizardId != null || definition.stages.isEmpty()) return
        if (guidedJob?.isActive == true) return
        _guidedResult.value = null
        _guidedCaptures.value = emptyList()
        _guidedTrace.value = emptyMap()
        guidedTraceStage = -1
        val codes = allCodes()
        val capabilities = vehicleCapabilities.value
        val profile = VehicleProfile(
            fuelType = settings.value.fuelType,
            aspiration = settings.value.aspiration,
            bankCount = capabilities.profile.bankCount,
        )
        val baseline = BaselineStore.decode(settings.value.baselineJson)
        val vehicleKey = identity.value.vin.orEmpty()
        guidedJob = viewModelScope.launch {
            try {
                val outcome = graph.session.runGuidedTest(
                    definition = definition,
                    profile = profile,
                    prerequisites = prerequisites,
                    activeCodes = codes,
                    baseline = baseline,
                    vehicleKey = vehicleKey,
                ) { snapshot ->
                    _guidedSnapshot.value = snapshot
                    if (snapshot.stageIndex != guidedTraceStage) {
                        guidedTraceStage = snapshot.stageIndex
                        _guidedTrace.value = emptyMap()
                    }
                    if (snapshot.state == com.carstalker.core.guided.GuidedRunState.CAPTURING ||
                        snapshot.state == com.carstalker.core.guided.GuidedRunState.PAUSED
                    ) {
                        val pids = snapshot.stage?.pids.orEmpty()
                        _guidedTrace.update { old ->
                            old.toMutableMap().apply {
                                for (pid in pids) {
                                    val value = snapshot.liveValues[pid] ?: continue
                                    this[pid] = (this[pid].orEmpty() + value.toFloat()).takeLast(300)
                                }
                            }
                        }
                    }
                }
                _guidedCaptures.value = outcome.captures
                _guidedResult.value = outcome.result
                _guidedSnapshot.value = outcome.snapshot
            } catch (t: Throwable) {
                _messages.emit(t.message ?: "Guided test failed")
                _guidedSnapshot.value = null
            }
        }
    }

    fun markGuidedSymptom() {
        graph.session.markGuidedSymptom()
    }

    fun saveGuidedBaseline() {
        val captures = _guidedCaptures.value
        if (captures.isEmpty()) {
            viewModelScope.launch { _messages.emit("No captured stages to save as a baseline.") }
            return
        }
        val profile = VehicleProfile(
            fuelType = settings.value.fuelType,
            aspiration = settings.value.aspiration,
            bankCount = vehicleCapabilities.value.profile.bankCount,
        )
        val snapshot = BaselineExtractor.fromCaptures(
            captures = captures,
            profile = profile,
            vehicleKey = identity.value.vin.orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
        if (snapshot == null) {
            viewModelScope.launch { _messages.emit("The capture does not contain a warm-idle stage for a baseline.") }
            return
        }
        viewModelScope.launch {
            graph.settings.update { it.copy(baselineJson = BaselineStore.encode(snapshot)) }
            _messages.emit("Baseline saved. Repeat under the same conditions to compare.")
        }
    }

    fun cancelGuidedTest() {
        graph.session.cancelGuidedTest()
    }

    fun skipGuidedStage() {
        graph.session.skipGuidedStage()
    }

    fun finishGuidedStageEarly() {
        graph.session.finishGuidedStageEarly()
    }

    fun pauseGuidedTest() {
        graph.session.pauseGuidedTest()
    }

    /** Called when the app leaves the foreground: never keep recording an unattended stage. */
    fun pauseGuidedTestForBackground() {
        if (_guidedResult.value == null && _guidedSnapshot.value?.isActive == true) {
            graph.session.pauseGuidedTest()
        }
    }

    fun resumeGuidedTest() {
        graph.session.resumeGuidedTest()
    }

    fun resetGuidedTest() {
        guidedJob?.cancel()
        guidedJob = null
        _guidedSnapshot.value = null
        _guidedResult.value = null
        _guidedCaptures.value = emptyList()
        _guidedTrace.value = emptyMap()
        guidedTraceStage = -1
    }

    fun setDemoScenario(scenario: DemoScenario) {
        graph.session.setDemoScenario(scenario)
    }

    fun saveGuidedCapture() {
        val definition = _activeGuide.value ?: return
        val captures = _guidedCaptures.value
        if (captures.isEmpty()) {
            viewModelScope.launch { _messages.emit("There is no guided capture to save yet.") }
            return
        }
        viewModelScope.launch {
            if (graph.session.saveGuidedCapture(definition, captures)) {
                refreshLogs()
            } else {
                _messages.emit("Could not save the guided capture.")
            }
        }
    }

    private fun allCodes(): List<String> = dtcReport.value.modules.flatMap { module ->
        listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
    }.distinct()

    /**
     * Builds the same report model used by the PDF renderer and opens it in the in-app preview.
     * The file is written to app-private storage and exposed through the existing FileProvider so
     * the preview can hand the same document to sharing or printing.
     */
    fun exportReport() {
        viewModelScope.launch {
            runCatching {
                val ready = connection.value as? ConnectionState.Ready
                val report = ReportBuilder.build(
                    appVersion = BuildConfig.VERSION_NAME,
                    shopName = settings.value.shopName,
                    identity = identity.value,
                    adapter = ready?.adapter,
                    report = dtcReport.value,
                    wizardResult = _wizardResult.value,
                    guidedResults = _guidedResult.value?.let { listOf(it) }.orEmpty(),
                    maskVin = !settings.value.includeVinInReports,
                    isDemo = ready?.transportName?.contains("Demo") == true,
                )
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, "reports").apply { mkdirs() }
                    File(directory, "carstalker-report-${System.currentTimeMillis()}.pdf").also {
                        ReportPdfWriter.write(report, it, context)
                    }
                }
                _pdfPreview.value = PdfPreview(
                    file = file,
                    uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                    title = "Diagnostic report",
                    shareSubject = "CarStalker diagnostic report",
                    printJobName = "CarStalker diagnostic report",
                )
            }.onFailure {
                _messages.emit("Could not create report: ${it.message ?: "unknown error"}")
            }
        }
    }

    fun closePdfPreview() {
        _pdfPreview.value = null
    }

    /** Shares the document currently shown in the preview. Returns false if no share target exists. */
    fun sharePdfPreview(): Boolean {
        val preview = _pdfPreview.value ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, preview.uri)
            putExtra(Intent.EXTRA_SUBJECT, preview.shareSubject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /**
     * Opens the system print dialog for the document currently shown in the preview. Returns false
     * when the device has no print service.
     */
    fun printPdfPreview(activity: Activity): Boolean {
        val preview = _pdfPreview.value ?: return false
        return PdfPrinter.print(activity, preview.file, preview.printJobName)
    }

    fun exportGuidedJson() {
        val result = _guidedResult.value ?: return
        viewModelScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, "reports").apply { mkdirs() }
                    File(directory, "carstalker-guided-${result.diagnosticId}-${System.currentTimeMillis()}.json").also {
                        it.writeText(GuidedResultExport.encode(result))
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CarStalker guided result")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, "Share guided result")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                _messages.emit("Could not export result: ${it.message ?: "unknown error"}")
            }
        }
    }

    fun toggleRecording() {
        if (recording.value) graph.session.stopRecording() else graph.session.startRecording()
    }

    // ---- Settings ---------------------------------------------------------

    fun setUnitSystem(system: UnitSystem) = updateSettings { it.copy(unitSystem = system) }

    fun setTheme(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }

    fun setPollInterval(ms: Int) = updateSettings { it.copy(pollIntervalMs = ms.coerceIn(0, 1_000)) }

    fun setKeepScreenOn(enabled: Boolean) = updateSettings { it.copy(keepScreenOn = enabled) }

    fun setTransport(kind: TransportKind) = updateSettings { it.copy(lastTransport = kind) }

    fun setUsbBaud(baud: Int) = updateSettings { it.copy(usbBaud = baud) }

    fun setShopName(name: String) = updateSettings { it.copy(shopName = name.take(80)) }

    fun setFuelType(fuelType: FuelType) = updateSettings { current ->
        val key = current.profileVehicleKey
        current.copy(
            fuelType = fuelType,
            vehicleProfiles = if (key.isBlank()) {
                current.vehicleProfiles
            } else {
                current.vehicleProfiles + (key to VehicleProfileEntry(fuelType, current.aspiration))
            },
        )
    }

    fun setAspiration(aspiration: Aspiration) = updateSettings { current ->
        val key = current.profileVehicleKey
        current.copy(
            aspiration = aspiration,
            vehicleProfiles = if (key.isBlank()) {
                current.vehicleProfiles
            } else {
                current.vehicleProfiles + (key to VehicleProfileEntry(current.fuelType, aspiration))
            },
        )
    }

    /**
     * Tells the polling loop which parameter's detail view is open. While it is set, that
     * parameter is requested on every cycle instead of waiting for its scheduled turn.
     */
    fun setDetailPid(pidId: Int?) {
        graph.session.setFocusPid(pidId)
    }

    fun toggleDashboardPid(pidId: Int) {
        val supported = supportedPids.value
        val active = settings.value.activeDashboard
        val base = if (active.automatic) recommendedDashboardPids(supported) else active.pids
        if (toggledDashboardPids(base, pidId) == null) {
            viewModelScope.launch {
                _messages.emit("A dashboard can show up to $MAX_DASHBOARD_PIDS values. Remove one first.")
            }
            return
        }
        updateSettings { latest ->
            val dashboard = latest.activeDashboard
            // The first manual toggle freezes the automatic selection, then edits that list.
            val current = if (dashboard.automatic) {
                recommendedDashboardPids(supported)
            } else {
                dashboard.pids
            }
            val pids = toggledDashboardPids(current, pidId) ?: return@updateSettings latest
            latest.copy(
                dashboards = latest.dashboards.map {
                    if (it.id == dashboard.id) it.copy(pids = pids, automatic = false) else it
                },
            )
        }
    }

    /**
     * Moves a dashboard value to the target value's position. Dropping onto a card places the
     * dragged value just before it, which is how a two-column grid reads.
     */
    fun moveDashboardPid(fromId: Int, toId: Int) {
        if (fromId == toId) return
        val supported = supportedPids.value
        updateSettings { current ->
            val active = current.activeDashboard
            val base = if (active.automatic) recommendedDashboardPids(supported) else active.pids
            val reordered = movedDashboardPids(base, fromId, toId) ?: return@updateSettings current
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == active.id) it.copy(pids = reordered, automatic = false) else it
                },
            )
        }
    }

    /** Swaps one dashboard value for another, keeping its position and the dashboard size. */
    fun replaceDashboardPid(oldId: Int, newId: Int) {
        if (oldId == newId) return
        val supported = supportedPids.value
        updateSettings { current ->
            val active = current.activeDashboard
            val base = if (active.automatic) recommendedDashboardPids(supported) else active.pids
            val replaced = replacedDashboardPids(base, oldId, newId) ?: return@updateSettings current
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == active.id) it.copy(pids = replaced, automatic = false) else it
                },
            )
        }
    }

    /** Restore the automatic selection for this car on the active dashboard. */
    fun useRecommendedDashboard() {
        updateSettings { current ->
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == current.activeDashboardId) it.copy(automatic = true) else it
                },
            )
        }
    }

    // ---- Dashboards -------------------------------------------------------

    fun selectDashboard(id: String) {
        updateSettings { current ->
            if (current.dashboards.any { it.id == id }) current.copy(activeDashboardId = id) else current
        }
    }

    fun createBlankDashboard() {
        updateSettings { current ->
            val number = current.dashboards.size + 1
            val dashboard = blankDashboard(
                id = "dashboard-${java.util.UUID.randomUUID()}",
                name = "Dashboard $number",
            )
            current.copy(dashboards = current.dashboards + dashboard, activeDashboardId = dashboard.id)
        }
    }

    fun duplicateDashboard(id: String) {
        updateSettings { current ->
            val source = current.dashboards.firstOrNull { it.id == id } ?: return@updateSettings current
            val copy = source.copy(
                id = "dashboard-${java.util.UUID.randomUUID()}",
                name = "${source.name} copy",
            )
            current.copy(dashboards = current.dashboards + copy, activeDashboardId = copy.id)
        }
    }

    fun renameDashboard(id: String, name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isEmpty()) return
        updateSettings { current ->
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == id) it.copy(name = trimmed) else it
                },
            )
        }
    }

    fun deleteDashboard(id: String) {
        updateSettings { current ->
            if (current.dashboards.size <= 1) return@updateSettings current
            val remaining = current.dashboards.filterNot { it.id == id }
            current.copy(
                dashboards = remaining,
                activeDashboardId = if (current.activeDashboardId == id) remaining.first().id else current.activeDashboardId,
            )
        }
    }

    fun setDashboardGauges(id: String, enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == id) it.copy(showGauges = enabled) else it
                },
            )
        }
    }

    fun setDashboardAutomatic(id: String, enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                dashboards = current.dashboards.map {
                    if (it.id == id) it.copy(automatic = enabled) else it
                },
            )
        }
    }

    fun setIncludeVinInReports(include: Boolean) {
        updateSettings { it.copy(includeVinInReports = include) }
    }

    /**
     * Applies the stored profile for [vehicleKey] when the connected vehicle changes. A profile
     * from another car is never silently reused.
     */
    fun applyVehicleProfile(vehicleKey: String) {
        if (vehicleKey.isBlank()) return
        updateSettings { current ->
            if (current.profileVehicleKey == vehicleKey) return@updateSettings current
            val stored = current.vehicleProfiles[vehicleKey]
            // A profile entered before the first connection belongs to the first car seen.
            val fallback = if (stored == null && current.profileVehicleKey == "unknown") {
                VehicleProfileEntry(current.fuelType, current.aspiration)
            } else {
                null
            }
            val effective = stored ?: fallback
            current.copy(
                profileVehicleKey = vehicleKey,
                fuelType = effective?.fuelType ?: FuelType.UNKNOWN,
                aspiration = effective?.aspiration ?: Aspiration.UNKNOWN,
                vehicleProfiles = if (fallback != null) {
                    current.vehicleProfiles + (vehicleKey to fallback)
                } else {
                    current.vehicleProfiles
                },
            )
        }
    }

    fun openPidPicker() { _showPidPicker.value = true }

    fun closePidPicker() { _showPidPicker.value = false }

    fun addCustomPid(custom: CustomPid) {
        updateSettings { current ->
            val existing = current.customPids.filterNot { it.pidHex.equals(custom.pidHex, ignoreCase = true) }
            current.copy(customPids = existing + custom)
        }
    }

    fun removeCustomPid(pidHex: String) {
        updateSettings { current ->
            current.copy(customPids = current.customPids.filterNot { it.pidHex.equals(pidHex, ignoreCase = true) })
        }
    }

    fun setCustomPidEnabled(pidHex: String, enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                customPids = current.customPids.map {
                    if (it.pidHex.equals(pidHex, ignoreCase = true)) it.copy(enabled = enabled) else it
                },
            )
        }
    }

    private fun updateSettings(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(block) }
    }

    // ---- Logs -------------------------------------------------------------

    fun refreshLogs() {
        viewModelScope.launch {
            _logs.value = withContext(Dispatchers.IO) { graph.logs.listSessions() }
        }
    }

    fun openLog(session: LogSession) {
        _selectedLog.value = session
    }

    fun closeLog() {
        _selectedLog.value = null
    }

    fun deleteLog(session: LogSession) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.logs.delete(session) }
            if (_selectedLog.value?.meta?.id == session.meta.id) _selectedLog.value = null
            refreshLogs()
        }
    }

    fun openExternal(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            viewModelScope.launch { _messages.emit("No app can open that link.") }
        }
    }

    /**
     * Local, opt-in connection diagnostics for a beta bug report. It contains no VIN, location or
     * account data and never leaves the device until the user chooses a share target.
     */
    fun exportConnectionDiagnostics() {
        viewModelScope.launch {
            runCatching {
                val ready = connection.value as? ConnectionState.Ready
                val text = buildString {
                    appendLine("CarStalker connection diagnostics")
                    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Connection: ${connection.value::class.simpleName}")
                    appendLine("Transport: ${ready?.transportName ?: "not connected"}")
                    appendLine("Adapter: ${ready?.adapter?.identifier ?: "not connected"}")
                    appendLine("Protocol: ${ready?.adapter?.protocolName ?: "unknown"}")
                    appendLine("Supported PIDs: ${supportedPids.value.size}")
                    appendLine("Vehicle identity: not included")
                    appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                }
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
                    File(directory, "carstalker-diagnostics-${System.currentTimeMillis()}.txt").also {
                        it.writeText(text)
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CarStalker connection diagnostics")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                _messages.emit("Could not create diagnostics: ${it.message ?: "unknown error"}")
            }
        }
    }

    fun shareLog(session: LogSession) {
        val file = graph.logs.fileFor(session)
        if (!file.exists()) {
            viewModelScope.launch { _messages.emit("Recording file is missing") }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CarStalker recording ${session.meta.id}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCleared() {
        captureJob?.cancel()
        guidedJob?.cancel()
        graph.bleScanner.stop()
        graph.classicScanner.stop()
        super.onCleared()
    }

    companion object {
        private const val HISTORY_LENGTH = 600

        fun factory(graph: AppGraph) = viewModelFactory {
            initializer { CarStalkerViewModel(graph) }
        }
    }
}
