package com.carstalker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.service.toScanContext
import com.carstalker.app.transport.demo.DemoScenario
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.AvailabilityPill
import com.carstalker.app.ui.components.DemoControls
import com.carstalker.app.ui.components.MetricChip
import com.carstalker.app.ui.components.RadialGauge
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.components.TraceChart
import com.carstalker.app.ui.components.TracePoint
import com.carstalker.app.ui.components.TraceSeries
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.guided.AnalysisSupport
import com.carstalker.core.guided.CapabilityResolver
import com.carstalker.core.guided.CaptureQualitySummary
import com.carstalker.core.guided.DiagnosticAvailability
import com.carstalker.core.guided.DiagnosticDefinition
import com.carstalker.core.guided.DiagnosticStatus
import com.carstalker.core.guided.GuidedFinding
import com.carstalker.core.guided.GuidedResult
import com.carstalker.core.guided.GuidedRunState
import com.carstalker.core.guided.GuidedSnapshot
import com.carstalker.core.guided.PrerequisiteAnswers
import com.carstalker.core.guided.Signals
import com.carstalker.core.guided.StageCapture
import com.carstalker.core.guided.StageKind
import com.carstalker.core.guided.StageSummary
import com.carstalker.core.guided.VehicleCapabilities
import java.util.Locale

@Composable
fun GuidedTestScreen(
    definition: DiagnosticDefinition,
    viewModel: CarStalkerViewModel,
    onBack: () -> Unit,
    onOpenConnect: () -> Unit,
) {
    val capabilities by viewModel.vehicleCapabilities.collectAsStateWithLifecycle()
    val snapshot by viewModel.guidedSnapshot.collectAsStateWithLifecycle()
    val result by viewModel.guidedResult.collectAsStateWithLifecycle()
    val captures by viewModel.guidedCaptures.collectAsStateWithLifecycle()
    val trace by viewModel.guidedTrace.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val report by viewModel.dtcReport.collectAsStateWithLifecycle()
    val scan = remember(report) { report.toScanContext() }
    val availability = remember(definition, capabilities, scan) {
        CapabilityResolver.resolve(definition, capabilities, scan)
    }
    val stalker = LocalStalkerColors.current
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← All guides") }
            Spacer(Modifier.weight(1f))
            Text("GUIDED TEST", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            definition.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(definition.subtitle, style = MaterialTheme.typography.bodySmall, color = stalker.accent)
        Spacer(Modifier.height(14.dp))

        when {
            result != null -> GuidedResultView(
                definition = definition,
                result = result!!,
                captures = captures,
                onSave = viewModel::saveGuidedCapture,
                onExport = viewModel::exportReport,
                onExportJson = viewModel::exportGuidedJson,
                onSaveBaseline = viewModel::saveGuidedBaseline,
                onRestart = { viewModel.resetGuidedTest() },
                onBack = onBack,
            )

            snapshot != null -> GuidedCaptureView(
                definition = definition,
                snapshot = snapshot!!,
                trace = trace,
                connection = connection,
                onCancel = viewModel::cancelGuidedTest,
                onSkip = viewModel::skipGuidedStage,
                onFinishEarly = viewModel::finishGuidedStageEarly,
                onPause = viewModel::pauseGuidedTest,
                onResume = viewModel::resumeGuidedTest,
                onMarkSymptom = viewModel::markGuidedSymptom,
                onDemoScenario = viewModel::setDemoScenario,
            )

            else -> GuidedIntroView(
                definition = definition,
                availability = availability,
                capabilities = capabilities,
                connection = connection,
                onStart = viewModel::startGuidedTest,
                onConnect = onOpenConnect,
                onTryDemo = viewModel::connectDemo,
                onDemoScenario = viewModel::setDemoScenario,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun GuidedIntroView(
    definition: DiagnosticDefinition,
    availability: com.carstalker.core.guided.AvailabilityReport,
    capabilities: VehicleCapabilities,
    connection: ConnectionState,
    onStart: (PrerequisiteAnswers) -> Unit,
    onConnect: () -> Unit,
    onTryDemo: () -> Unit,
    onDemoScenario: (DemoScenario) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val connected = connection is ConnectionState.Ready
    var demoScenario by remember { mutableStateOf<DemoScenario?>(null) }
    var engineWarm by rememberSaveable(definition.id) { mutableStateOf(false) }
    var engineCold by rememberSaveable(definition.id) { mutableStateOf(false) }
    var noFault by rememberSaveable(definition.id) { mutableStateOf(false) }
    var safePlace by rememberSaveable(definition.id) { mutableStateOf(false) }
    var closedLoop by rememberSaveable(definition.id) { mutableStateOf(false) }
    var passenger by rememberSaveable(definition.id) { mutableStateOf(false) }
    var ambient by rememberSaveable(definition.id) { mutableStateOf("") }

    val needsCold = definition.requiresColdEngine
    val needsWarm = definition.requiresWarmEngine
    val needsClosedLoop = definition.id == "lean_mixture" || definition.id == "rich_mixture"
    val needsPassenger = definition.id == "intermittent_power_loss"
    val prerequisitesReady = noFault && safePlace &&
        (!needsCold || engineCold) &&
        (!needsWarm || engineWarm) &&
        (!needsClosedLoop || closedLoop) &&
        (!needsPassenger || passenger)

    StalkerCard(accent = if (availability.availability.startable) stalker.accent else stalker.warning) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    AvailabilityPill(availability.availability)
                } else {
                    StatusPill("Connect to check", stalker.muted)
                }
                Spacer(Modifier.width(8.dp))
                StatusPill("~${definition.estimatedMinutes} min", stalker.accentSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Text(definition.description, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
            definition.durationNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = stalker.warning)
            }
            Spacer(Modifier.height(12.dp))
            Text("What you need", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            definition.whatYouNeed.take(3).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Text("Safety", style = MaterialTheme.typography.labelMedium, color = stalker.warning)
            definition.safetyNotes.take(2).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
            }
            if (availability.reasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Why this state", style = MaterialTheme.typography.labelMedium, color = stalker.warning)
                availability.reasons.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.warning)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Before you start", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            CheckRow(
                checked = noFault,
                onCheckedChange = { noFault = it },
                text = "The MIL is not flashing and there is no active misfire.",
            )
            CheckRow(
                checked = safePlace,
                onCheckedChange = { safePlace = it },
                text = "I am in a safe place and will not use the phone while driving.",
            )
            if (needsWarm) {
                CheckRow(
                    checked = engineWarm,
                    onCheckedChange = { engineWarm = it },
                    text = "The engine is fully warmed to normal operating temperature.",
                )
            }
            if (needsCold) {
                CheckRow(
                    checked = engineCold,
                    onCheckedChange = { engineCold = it },
                    text = "The engine has been off long enough to be cold-soaked (ideally overnight).",
                )
            }
            if (needsClosedLoop) {
                CheckRow(
                    checked = closedLoop,
                    onCheckedChange = { closedLoop = it },
                    text = "The engine is in closed-loop fuelling (not still warming up).",
                )
            }
            if (needsPassenger) {
                CheckRow(
                    checked = passenger,
                    onCheckedChange = { passenger = it },
                    text = "A passenger will operate the app while I drive; I will not touch the phone.",
                )
            }
            if (definition.id == "thermostat_ect") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ambient,
                    onValueChange = { ambient = it },
                    label = { Text("Ambient temperature °C (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (definition.limitations.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                var showLimits by rememberSaveable(definition.id) { mutableStateOf(false) }
                TextButton(
                    onClick = { showLimits = !showLimits },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        if (showLimits) "Hide technical limits" else "Technical limits (${definition.limitations.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.muted,
                    )
                }
                if (showLimits) {
                    definition.limitations.forEach {
                        Text("• $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                    }
                }
            }
            val demo = (connection as? ConnectionState.Ready)?.transportName?.contains("Demo") == true
            if (demo) {
                Spacer(Modifier.height(12.dp))
                DemoControls(selected = demoScenario, onSelect = {
                    demoScenario = it
                    onDemoScenario(it)
                })
            }
            Spacer(Modifier.height(14.dp))
            if (!connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                    ) { Text("Connect adapter") }
                    OutlinedButton(onClick = onTryDemo) { Text("Try demo") }
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    onStart(
                        PrerequisiteAnswers(
                            engineWarmConfirmed = engineWarm,
                            engineColdConfirmed = engineCold,
                            flashingMil = false,
                            activeMisfire = false,
                            safeLocationConfirmed = safePlace,
                            closedLoopConfirmed = closedLoop,
                            passengerPresent = passenger,
                            ambientTemperatureC = ambient.toDoubleOrNull(),
                        ),
                    )
                },
                enabled = connected && availability.availability.startable && prerequisitesReady,
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start guided test")
            }
            if (!connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Connect an adapter or start the demo to run this guide.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            } else if (!prerequisitesReady) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Complete the checks above to enable Start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
            if (capabilities.profile.fuelType == com.carstalker.core.guided.FuelType.UNKNOWN) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Engine type is not set; the result will be marked limited. Set it in Settings or on the Diagnose tab.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.warning,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedCaptureView(
    definition: DiagnosticDefinition,
    snapshot: GuidedSnapshot,
    trace: Map<Int, List<Float>>,
    connection: ConnectionState,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onFinishEarly: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMarkSymptom: () -> Unit,
    onDemoScenario: (DemoScenario) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val stage = snapshot.stage
    val stateColor = when (snapshot.state) {
        GuidedRunState.CAPTURING -> stalker.success
        GuidedRunState.SETTLING -> stalker.accent
        GuidedRunState.WAITING_FOR_CONDITIONS -> stalker.accentSecondary
        GuidedRunState.PAUSED -> stalker.warning
        GuidedRunState.SAFETY_STOPPED -> stalker.danger
        GuidedRunState.CANCELLED -> stalker.muted
        else -> stalker.muted
    }
    var demoScenario by remember { mutableStateOf<DemoScenario?>(null) }

    StalkerCard(accent = stateColor) {
        Column {
            Text(
                "GUIDED TEST · STEP ${snapshot.stageNumber} OF ${snapshot.stageCount}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stage?.title ?: "Preparing",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stage?.instruction.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(stateLabel(snapshot.state), stateColor, pulsing = snapshot.state == GuidedRunState.CAPTURING)
                Spacer(Modifier.width(8.dp))
                snapshot.condition.target?.let { StatusPill(it, stalker.accentSecondary) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                snapshot.condition.headline,
                style = MaterialTheme.typography.titleMedium,
                color = stateColor,
            )
            snapshot.condition.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
            }

            val preferredPid = primaryPid(definition, stage?.kind)
            val pid = if (preferredPid == Signals.CONTROL_MODULE_VOLTAGE &&
                snapshot.liveValues[preferredPid] == null &&
                snapshot.liveValues[Signals.ADAPTER_VOLTAGE] != null
            ) {
                Signals.ADAPTER_VOLTAGE
            } else {
                preferredPid
            }
            val value = snapshot.liveValues[pid]
            if (value != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val range = gaugeRange(pid)
                    RadialGauge(
                        value = value,
                        min = range.first,
                        max = range.second,
                        label = Signals.label(pid),
                        unit = Signals.unit(pid),
                        accent = stateColor,
                        modifier = Modifier.size(220.dp),
                        decimals = range.third,
                        redline = 0.9f,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { snapshot.progress },
                modifier = Modifier.fillMaxWidth(),
                color = stateColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when (snapshot.state) {
                    GuidedRunState.CAPTURING ->
                        "Capturing live data: ${(snapshot.captureElapsedMs / 1000)} of ${(snapshot.captureDurationMs / 1000)} seconds"
                    GuidedRunState.SETTLING -> "Settling before capture"
                    GuidedRunState.PAUSED -> "Paused — restore the conditions to restart"
                    GuidedRunState.WAITING_FOR_CONDITIONS -> "Waiting for the required conditions"
                    else -> snapshot.message
                },
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
            )
            if (snapshot.interruptions > 0) {
                Text(
                    "${snapshot.interruptions} interruption(s): mixed samples were discarded and the stage restarted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.warning,
                )
            }
            snapshot.condition.rpmSpread?.let {
                Text(
                    "RPM spread ${AnalysisSupport.format(it, 0)} rpm",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
            if (stage?.kind == StageKind.PASSIVE_CAPTURE) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onMarkSymptom,
                    enabled = snapshot.state == GuidedRunState.CAPTURING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stalker.warning,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mark symptom now")
                }
                Text(
                    when {
                        snapshot.markerCount == 0 && snapshot.invalidatedMarkerCount > 0 ->
                            "${snapshot.invalidatedMarkerCount} marker(s) were recorded but invalidated by an interruption. Tap again during a valid segment."
                        snapshot.markerCount == 0 -> "Passenger: tap when the power loss occurs."
                        snapshot.invalidatedMarkerCount > 0 ->
                            "${snapshot.markerCount} valid marker(s); ${snapshot.invalidatedMarkerCount} earlier marker(s) invalidated."
                        else -> "${snapshot.markerCount} symptom marker(s) recorded."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot.invalidatedMarkerCount > 0) stalker.warning else stalker.muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            val live = liveSeries(
                definition,
                stage,
                trace,
                listOf(stalker.accent, stalker.accentSecondary, stalker.warning),
            )
            var showTrace by rememberSaveable("trace") { mutableStateOf(false) }
            if (live.first.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { showTrace = !showTrace },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        if (showTrace) "Hide live trace" else "Show live trace",
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.accentSecondary,
                    )
                }
                if (showTrace) {
                    TraceChart(
                        series = live.first,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        unit = live.second,
                        durationLabel = "${(snapshot.stageElapsedMs / 1000)} s",
                    )
                }
            }

            val demo = (connection as? ConnectionState.Ready)?.transportName?.contains("Demo") == true
            if (demo) {
                var showDemo by rememberSaveable("demo") { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDemo = !showDemo },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        if (showDemo) "Hide simulation controls" else "Simulation controls",
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.accentSecondary,
                    )
                }
                if (showDemo) {
                    DemoControls(selected = demoScenario, onSelect = {
                        demoScenario = it
                        onDemoScenario(it)
                    })
                }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
                if (snapshot.state == GuidedRunState.PAUSED) {
                    OutlinedButton(onClick = onResume) { Text("Resume") }
                } else if (snapshot.isActive) {
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                }
                if (snapshot.canSkip && !snapshot.canFinishEarly) {
                    OutlinedButton(onClick = onSkip) { Text("Skip stage") }
                }
                if (snapshot.canFinishEarly) {
                    Button(
                        onClick = onFinishEarly,
                        colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                    ) { Text("Finish stage") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedResultView(
    definition: DiagnosticDefinition,
    result: GuidedResult,
    captures: List<StageCapture>,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onExportJson: () -> Unit,
    onSaveBaseline: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val statusColor = when (result.status) {
        DiagnosticStatus.NO_ANOMALY_DETECTED -> stalker.success
        DiagnosticStatus.SUSPICIOUS -> stalker.warning
        DiagnosticStatus.INCONCLUSIVE -> stalker.muted
        DiagnosticStatus.UNABLE_TO_RUN -> stalker.danger
        DiagnosticStatus.SAFETY_STOPPED -> stalker.danger
        DiagnosticStatus.CANCELLED -> stalker.muted
    }
    val primary = result.primaryFinding
    val patternMatch = when (primary?.evidenceLevel) {
        com.carstalker.core.guided.EvidenceLevel.STRONG -> "Strong"
        com.carstalker.core.guided.EvidenceLevel.MODERATE -> "Moderate"
        com.carstalker.core.guided.EvidenceLevel.SUPPORTIVE -> "Supportive"
        com.carstalker.core.guided.EvidenceLevel.WEAK -> "Weak"
        null -> "No pattern"
    }
    val measurementQuality = when {
        !result.quality.requiredSignalsPresent -> "Incomplete"
        result.quality.stimulusObserved == false -> "Not exercised"
        !result.quality.samplingAdequate -> "Limited"
        result.quality.interruptions > 0 -> "Limited"
        else -> "Adequate"
    }
    val dataBullets = buildList {
        if (primary != null) {
            addAll(primary.supporting.take(3))
        } else {
            addAll(result.observations.take(3).map { "${it.label}: ${it.value}" })
        }
    }
    val nextCheck = result.nextCheck

    Column {
        StalkerCard(accent = statusColor) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Result",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(result.status.label, statusColor)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill("Pattern: $patternMatch", if (patternMatch == "No pattern") stalker.muted else stalker.accentSecondary)
                    StatusPill("Data: $measurementQuality", when (measurementQuality) {
                        "Adequate" -> stalker.success
                        "Incomplete", "Not exercised" -> stalker.warning
                        else -> stalker.accentSecondary
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.confidence.label} evidence confidence · ${result.profile.label()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
                Spacer(Modifier.height(8.dp))
                Text(result.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

                if (result.status == DiagnosticStatus.INCONCLUSIVE ||
                    result.status == DiagnosticStatus.UNABLE_TO_RUN ||
                    result.status == DiagnosticStatus.SAFETY_STOPPED
                ) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "What was missing",
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.warning,
                    )
                    val missing = result.quality.missingSignals
                    if (missing.isNotEmpty()) {
                        Text("• Missing: ${missing.joinToString()}", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                    }
                    result.quality.notes.take(3).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                    }
                    if (missing.isEmpty() && result.quality.notes.isEmpty()) {
                        Text("• The capture did not contain enough valid data for a conclusion.", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                    }
                }

                if (dataBullets.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("What the data shows", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                    dataBullets.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = stalker.accent.copy(alpha = 0.10f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, stalker.accent.copy(alpha = 0.45f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("NEXT SAFE CHECK", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            nextCheck,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    result.scope,
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )

                if (result.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    result.warnings.take(2).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.danger)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (definition.id == "health_baseline") {
            OutlinedButton(onClick = onSaveBaseline, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Save this run as the baseline")
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onSave,
            enabled = captures.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Save result")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Share PDF")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExportJson, modifier = Modifier.fillMaxWidth()) {
            Text("Export JSON")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Run again")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("← All diagnostics")
        }

        Spacer(Modifier.height(14.dp))
        ExpandableSection(
            title = "Evidence and graphs",
            subtitle = "${result.observations.size} observation(s) · ${captures.size} captured stage(s)",
        ) {
            if (result.observations.isNotEmpty()) {
                Text("Observed", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.observations.forEach { observation ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            observation.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            observation.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            captures.forEach { stage ->
                val chart = capturedSeries(
                    definition,
                    stage,
                    listOf(stalker.accent, stalker.accentSecondary, stalker.warning),
                )
                if (chart.first.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stage.title, style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                    TraceChart(
                        series = chart.first,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        unit = chart.second,
                        durationLabel = "${(stage.durationMs / 1000)} s",
                    )
                }
            }
            if (result.stages.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Stage quality", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.stages.forEach { stage -> StageSummaryRow(stage) }
            }
        }

        Spacer(Modifier.height(10.dp))
        ExpandableSection(
            title = "Other possible causes",
            subtitle = if (primary == null) "No candidate cause ranked" else "${primary.alternatives.size} alternative(s)",
        ) {
            if (primary != null) {
                Text(primary.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(primary.explanation, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                primary.against.forEach {
                    Text("• Against: $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                }
                if (primary.alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Also consider", style = MaterialTheme.typography.labelMedium, color = stalker.warning)
                    primary.alternatives.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                    }
                }
            }
            result.findings.drop(1).forEach { finding ->
                Spacer(Modifier.height(8.dp))
                FindingCard(finding)
            }
        }

        Spacer(Modifier.height(10.dp))
        ExpandableSection(
            title = "Data quality and limitations",
            subtitle = result.quality.evidenceQuality.ifBlank { measurementQuality },
        ) {
            if (result.quality.notes.isNotEmpty()) {
                Text("Data quality", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.quality.notes.forEach {
                    Text("• $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                }
            }
            if (result.warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Warnings", style = MaterialTheme.typography.labelMedium, color = stalker.danger)
                result.warnings.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.danger)
                }
            }
            if (result.limitations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Limitations", style = MaterialTheme.typography.labelMedium, color = stalker.muted)
                result.limitations.forEach {
                    Text("• $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                }
            }
            if (result.stagesSkipped.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Skipped stages: ${result.stagesSkipped.joinToString()}", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
            }
            if (result.nextSteps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Next steps", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.nextSteps.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    StalkerCard {
        Column {
            Surface(
                onClick = { expanded = !expanded },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                    }
                    Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = stalker.accent)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun FindingCard(finding: GuidedFinding) {
    val stalker = LocalStalkerColors.current
    val color = when (finding.evidenceLevel) {
        com.carstalker.core.guided.EvidenceLevel.STRONG -> stalker.danger
        com.carstalker.core.guided.EvidenceLevel.MODERATE -> stalker.warning
        com.carstalker.core.guided.EvidenceLevel.SUPPORTIVE -> stalker.accentSecondary
        com.carstalker.core.guided.EvidenceLevel.WEAK -> stalker.muted
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                finding.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(finding.evidenceLevel.label, color)
        }
        Text(
            "${finding.confidence.label} confidence · ${finding.explanation}",
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        finding.supporting.forEach {
            Text("• $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        }
        if (finding.alternatives.isNotEmpty()) {
            Text(
                "Also consider: ${finding.alternatives.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.warning,
            )
        }
        Text(
            "Next check: ${finding.nextCheck}",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.accent,
        )
    }
}

@Composable
private fun StageSummaryRow(stage: StageSummary) {
    val stalker = LocalStalkerColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                stage.outcome.label,
                when (stage.outcome) {
                    com.carstalker.core.guided.StageOutcome.VALID -> stalker.success
                    com.carstalker.core.guided.StageOutcome.SKIPPED -> stalker.muted
                    else -> stalker.warning
                },
            )
        }
        Text(
            "${stage.sampleCount} samples · ${AnalysisSupport.format(stage.effectiveHz, 1)} Hz · ${AnalysisSupport.format(stage.durationSeconds, 0)} s" +
                if (stage.gaps > 0) " · ${stage.gaps} gap(s)" else "",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
        stage.metrics.forEach { (key, value) ->
            Text(
                "${metricLabel(key)}: ${metricValue(key, value)}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
        }
        stage.notes.forEach {
            Text("• $it", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = stalker.accent, checkmarkColor = Color.Black),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun primaryPid(definition: DiagnosticDefinition, kind: StageKind?): Int = when {
    definition.id == "charging_system" -> Signals.CONTROL_MODULE_VOLTAGE
    definition.id == "thermostat_ect" -> Signals.COOLANT
    definition.id == "map_plausibility" -> Signals.MAP
    definition.id == "oxygen_response" -> Signals.O2_B1S1
    definition.id == "maf_plausibility" -> Signals.MAF
    definition.id == "boost_control" -> Signals.MAP
    definition.id == "fuel_pressure" -> Signals.FUEL_PRESSURE
    definition.id == "egr" -> Signals.EGR_COMMANDED
    definition.id == "throttle_pedal" -> Signals.THROTTLE
    definition.id == "evap_purge" -> Signals.EVAP_PURGE
    definition.id == "fuel_consumption" -> Signals.FUEL_RATE
    definition.id == "dpf_health" -> Signals.DPF_PRESSURE_B1
    definition.id == "readiness_pre_vrt" || definition.id == "used_car_inspection" -> Signals.CONTROL_MODULE_VOLTAGE
    kind == StageKind.COLD_START_WARMUP -> Signals.COOLANT
    kind == StageKind.CRANKING -> Signals.RPM
    kind == StageKind.KEY_ON_ENGINE_OFF -> Signals.MAP
    else -> Signals.RPM
}

private fun gaugeRange(pid: Int): Triple<Double, Double, Int> = when (pid) {
    Signals.RPM -> Triple(0.0, 4_000.0, 0)
    Signals.COOLANT, Signals.IAT, Signals.AMBIENT -> Triple(0.0, 120.0, 0)
    Signals.MAP, Signals.BARO -> Triple(0.0, 120.0, 0)
    Signals.CONTROL_MODULE_VOLTAGE, Signals.ADAPTER_VOLTAGE -> Triple(8.0, 16.0, 1)
    Signals.SPEED -> Triple(0.0, 140.0, 0)
    Signals.STFT_B1, Signals.LTFT_B1, Signals.STFT_B2, Signals.LTFT_B2 -> Triple(-30.0, 30.0, 1)
    Signals.O2_B1S1, Signals.O2_B1S2 -> Triple(0.0, 1.2, 2)
    Signals.WIDEBAND_B1S1, Signals.WIDEBAND_B1S2, Signals.WIDEBAND_CURRENT_B1S1, Signals.WIDEBAND_CURRENT_B1S2 -> Triple(0.7, 1.3, 2)
    Signals.MAF -> Triple(0.0, 150.0, 1)
    Signals.FUEL_PRESSURE, Signals.RAIL_PRESSURE, Signals.RAIL_GAUGE_PRESSURE -> Triple(0.0, 1_000.0, 0)
    Signals.FUEL_RATE -> Triple(0.0, 30.0, 1)
    Signals.THROTTLE, Signals.RELATIVE_THROTTLE, Signals.THROTTLE_B, Signals.PEDAL_D, Signals.PEDAL_E,
    Signals.COMMANDED_THROTTLE, Signals.EGR_COMMANDED, Signals.EVAP_PURGE, Signals.LOAD -> Triple(0.0, 100.0, 1)
    Signals.EGR_ERROR -> Triple(-50.0, 50.0, 1)
    Signals.DPF_PRESSURE_B1, Signals.DPF_PRESSURE_B2 -> Triple(-50.0, 50.0, 2)
    Signals.DPF_TEMPERATURE -> Triple(0.0, 800.0, 1)
    else -> Triple(0.0, 100.0, 1)
}

private fun liveSeries(
    definition: DiagnosticDefinition,
    stage: com.carstalker.core.guided.StageDefinition?,
    trace: Map<Int, List<Float>>,
    stalkerColors: List<Color>,
): Pair<List<TraceSeries>, String> {
    if (stage == null) return emptyList<TraceSeries>() to ""
    val pids = when (definition.id) {
        "lean_mixture", "rich_mixture" -> listOf(Signals.STFT_B1, Signals.LTFT_B1)
        "thermostat_ect" -> listOf(Signals.COOLANT, Signals.IAT)
        "charging_system" -> listOf(Signals.CONTROL_MODULE_VOLTAGE, Signals.ADAPTER_VOLTAGE)
        "map_plausibility", "boost_control" -> listOf(Signals.MAP, Signals.BARO)
        "oxygen_response" -> listOf(Signals.O2_B1S1, Signals.O2_B1S2)
        "misfire" -> listOf(Signals.RPM, Signals.LOAD)
        "maf_plausibility" -> listOf(Signals.MAF, Signals.RPM)
        "crank_no_start" -> listOf(Signals.RPM, Signals.CONTROL_MODULE_VOLTAGE)
        "fuel_pressure" -> listOf(Signals.FUEL_PRESSURE, Signals.RAIL_PRESSURE, Signals.RAIL_GAUGE_PRESSURE)
        "egr" -> listOf(Signals.EGR_COMMANDED, Signals.MAP)
        "throttle_pedal" -> listOf(Signals.THROTTLE, Signals.PEDAL_D)
        "evap_purge" -> listOf(Signals.EVAP_PURGE, Signals.STFT_B1)
        "fuel_consumption" -> listOf(Signals.FUEL_RATE, Signals.MAF)
        "dpf_health" -> listOf(Signals.DPF_PRESSURE_B1, Signals.DPF_TEMPERATURE)
        "intermittent_power_loss" -> listOf(Signals.RPM, Signals.LOAD)
        else -> stage.pids.take(2)
    }
    val series = pids.mapIndexedNotNull { index, pid ->
        val values = trace[pid].orEmpty()
        if (values.size < 2) return@mapIndexedNotNull null
        TraceSeries(
            label = Signals.label(pid),
            color = stalkerColors[index % stalkerColors.size],
            points = values.mapIndexed { i, value ->
                TracePoint(i.toFloat() / (values.size - 1), value)
            },
        )
    }
    val unit = pids.firstOrNull()?.let { Signals.unit(it) }.orEmpty()
    return series to unit
}

private fun capturedSeries(
    definition: DiagnosticDefinition,
    stage: StageCapture,
    stalkerColors: List<Color>,
): Pair<List<TraceSeries>, String> {
    if (definition.id == "lean_mixture" || definition.id == "rich_mixture") {
        val combined = AnalysisSupport.combinedTrim(
            stage.series(Signals.STFT_B1),
            stage.series(Signals.LTFT_B1),
        )
        if (combined.isNotEmpty()) {
            return listOf(
                TraceSeries(
                    label = "STFT + LTFT B1",
                    color = stalkerColors.first(),
                    points = normalized(combined),
                ),
            ) to "%"
        }
    }
    val pids = when (definition.id) {
        "thermostat_ect" -> listOf(Signals.COOLANT, Signals.IAT)
        "charging_system" -> listOf(Signals.CONTROL_MODULE_VOLTAGE, Signals.ADAPTER_VOLTAGE)
        "map_plausibility", "boost_control" -> listOf(Signals.MAP, Signals.BARO)
        "oxygen_response" -> listOf(Signals.O2_B1S1, Signals.O2_B1S2)
        "misfire" -> listOf(Signals.RPM, Signals.LOAD)
        "maf_plausibility" -> listOf(Signals.MAF, Signals.RPM)
        "crank_no_start" -> listOf(Signals.RPM, Signals.CONTROL_MODULE_VOLTAGE)
        "fuel_pressure" -> listOf(Signals.FUEL_PRESSURE, Signals.RAIL_PRESSURE, Signals.RAIL_GAUGE_PRESSURE)
        "egr" -> listOf(Signals.EGR_COMMANDED, Signals.MAP)
        "throttle_pedal" -> listOf(Signals.THROTTLE, Signals.PEDAL_D)
        "evap_purge" -> listOf(Signals.EVAP_PURGE, Signals.STFT_B1)
        "fuel_consumption" -> listOf(Signals.FUEL_RATE, Signals.MAF)
        "dpf_health" -> listOf(Signals.DPF_PRESSURE_B1, Signals.DPF_TEMPERATURE)
        "intermittent_power_loss" -> listOf(Signals.RPM, Signals.LOAD)
        else -> stage.capture.requestedPids.take(2)
    }
    val series = pids.mapIndexedNotNull { index, pid ->
        val points = stage.series(pid)
        if (points.size < 2) return@mapIndexedNotNull null
        TraceSeries(
            label = Signals.label(pid),
            color = stalkerColors[index % stalkerColors.size],
            points = normalized(points),
        )
    }
    val unit = pids.firstOrNull()?.let { Signals.unit(it) }.orEmpty()
    return series to unit
}

private fun normalized(points: List<CapturePoint>): List<TracePoint> {
    val sorted = points.sortedBy { it.timestampMs }
    val start = sorted.first().timestampMs
    val span = (sorted.last().timestampMs - start).coerceAtLeast(1L)
    return sorted.map { TracePoint((it.timestampMs - start).toFloat() / span, it.value.toFloat()) }
}

private fun stateLabel(state: GuidedRunState): String = when (state) {
    GuidedRunState.WAITING_FOR_CONDITIONS -> "Waiting"
    GuidedRunState.SETTLING -> "Settling"
    GuidedRunState.CAPTURING -> "Recording"
    GuidedRunState.PAUSED -> "Paused"
    GuidedRunState.STAGE_COMPLETE -> "Stage complete"
    GuidedRunState.SAFETY_STOPPED -> "Safety stop"
    GuidedRunState.CANCELLED -> "Cancelled"
    GuidedRunState.ANALYSING -> "Analysing"
    else -> state.name.lowercase(Locale.US).replace('_', ' ')
}

private fun metricLabel(key: String): String = when (key) {
    "rpm", "rpm_median" -> "RPM"
    "rpm_stddev" -> "RPM variation"
    "coolantC" -> "Coolant"
    "combinedTrimB1", "combined_trim_b1" -> "Combined trim B1"
    "combinedTrimB2", "combined_trim_b2" -> "Combined trim B2"
    "closedLoopFraction" -> "Closed loop"
    "voltageV", "voltage_min" -> "Voltage"
    "mapKpa", "map_kpa" -> "MAP"
    "baroKpa", "baro_kpa" -> "BARO"
    "throttlePercent", "throttle_pct" -> "Throttle"
    "pedal_d_pct" -> "Pedal D"
    "maf_gps" -> "MAF"
    "pressure_kpa" -> "Pressure"
    "egr_command_pct" -> "EGR command"
    "egr_error_pct" -> "EGR error"
    "purge_pct" -> "Purge command"
    "fuel_lph" -> "Fuel flow"
    "o2_switching_hz" -> "O2 switching"
    "o2_amplitude_v" -> "O2 amplitude"
    "lambda_range" -> "λ range"
    "dpf_pressure_kpa" -> "DPF pressure"
    "dpf_temperature_c" -> "DPF temperature"
    "markers" -> "Symptom markers"
    "initialC" -> "Initial coolant"
    "plateauC" -> "Plateau"
    "maxC" -> "Maximum coolant"
    "timeTo70s" -> "Time to 70 °C"
    else -> key
}

private fun metricValue(key: String, value: Double): String = when (key) {
    "rpm", "rpm_median", "rpm_stddev" -> "${AnalysisSupport.format(value, 0)} rpm"
    "coolantC", "initialC", "plateauC", "maxC" -> "${AnalysisSupport.format(value, 1)} °C"
    "combinedTrimB1", "combinedTrimB2", "combined_trim_b1", "combined_trim_b2" ->
        "${AnalysisSupport.formatSigned(value, 1)} %"
    "closedLoopFraction" -> "${AnalysisSupport.format(value * 100, 0)} %"
    "voltageV", "voltage_min" -> "${AnalysisSupport.format(value, 2)} V"
    "mapKpa", "map_kpa", "baroKpa", "baro_kpa", "pressure_kpa" ->
        "${AnalysisSupport.format(value, 1)} kPa"
    "throttlePercent", "throttle_pct", "pedal_d_pct", "purge_pct" ->
        "${AnalysisSupport.format(value, 1)} %"
    "egr_command_pct" -> "${AnalysisSupport.format(value, 1)} %"
    "egr_error_pct" -> "${AnalysisSupport.formatSigned(value, 1)} %"
    "maf_gps" -> "${AnalysisSupport.format(value, 1)} g/s"
    "fuel_lph" -> "${AnalysisSupport.format(value, 2)} L/h"
    "o2_switching_hz" -> "${AnalysisSupport.format(value, 2)} Hz"
    "o2_amplitude_v" -> "${AnalysisSupport.format(value, 3)} V"
    "lambda_range" -> AnalysisSupport.format(value, 3)
    "dpf_pressure_kpa" -> "${AnalysisSupport.format(value, 2)} kPa"
    "dpf_temperature_c" -> "${AnalysisSupport.format(value, 1)} °C"
    "markers" -> AnalysisSupport.format(value, 0)
    "timeTo70s" -> "${AnalysisSupport.format(value, 0)} s"
    else -> AnalysisSupport.format(value, 1)
}
