package com.carstalker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.ActionRow
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.diagnose.Answer
import com.carstalker.core.diagnose.CaptureData
import com.carstalker.core.diagnose.CapturePoint
import com.carstalker.core.diagnose.CaptureProgress
import com.carstalker.core.diagnose.CatalystAnswers
import com.carstalker.core.diagnose.CatalystWizard
import com.carstalker.core.diagnose.Confidence
import com.carstalker.core.diagnose.Likelihood
import com.carstalker.core.diagnose.WizardCause
import com.carstalker.core.diagnose.WizardResult
import com.carstalker.core.obd.Mode06Report
import java.util.Locale

private const val STEP_INTRO = 0
private const val STEP_PREPARE = 1
private const val STEP_CAPTURE = 2
private const val STEP_QUESTIONS = 3

@Composable
fun CatalystFlowScreen(
    viewModel: CarStalkerViewModel,
    onBack: () -> Unit,
    onOpenConnect: () -> Unit,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Ready
    val report by viewModel.dtcReport.collectAsStateWithLifecycle()
    val capture by viewModel.captureData.collectAsStateWithLifecycle()
    val progress by viewModel.captureProgress.collectAsStateWithLifecycle()
    val result by viewModel.wizardResult.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    var started by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(STEP_INTRO) }
    var engineHealthy by rememberSaveable { mutableStateOf(false) }
    var fuelLevelOk by rememberSaveable { mutableStateOf(false) }
    var oilCoolantOk by rememberSaveable { mutableStateOf(false) }
    var flashingMil by rememberSaveable { mutableStateOf(false) }
    var exhaustLeak by remember { mutableStateOf(Answer.UNKNOWN) }
    var recentExhaustWork by remember { mutableStateOf(Answer.UNKNOWN) }
    var oilConsumption by remember { mutableStateOf(Answer.UNKNOWN) }
    var coolantLoss by remember { mutableStateOf(Answer.UNKNOWN) }
    var downstreamResponse by remember { mutableStateOf(Answer.UNKNOWN) }
    var inletTemp by remember { mutableStateOf("") }
    var outletTemp by remember { mutableStateOf("") }

    // One predicate drives the gate. A danger condition always blocks the capture, even when a
    // positive attestation was also ticked.
    val liveMisfireCodes = (report.stored + report.pending).filter { it.code.startsWith("P030") }
    val gate = CatalystWizard.safetyGate(
        engineHealthy = engineHealthy,
        fuelLevelOk = fuelLevelOk,
        flashingMil = flashingMil,
        activeMisfireCodes = liveMisfireCodes.map { it.code },
    )
    val canProceed = gate.canProceed
    val liveSafetyStop = !gate.canProceed && (flashingMil || liveMisfireCodes.isNotEmpty())

    LaunchedEffect(Unit) {
        if (viewModel.wizardResult.value == null) {
            started = false
            step = STEP_INTRO
        }
    }
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
            Text("GUIDED DIAGNOSIS", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            CatalystWizard.definition.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            CatalystWizard.definition.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(18.dp))
        when {
            result != null -> WizardResultView(
                result = result!!,
                onExport = viewModel::exportReport,
                onReset = {
                    viewModel.resetCatalystWizard()
                    started = false
                    step = STEP_INTRO
                },
                onBack = onBack,
            )

            !started -> {
                CatalystIntroCard(
                    connected = connected,
                    onStart = {
                        started = true
                        step = STEP_PREPARE
                    },
                    onConnect = onOpenConnect,
                    onTryDemo = viewModel::connectDemo,
                )
            }

            else -> {
                WizardProgress(step)
                Spacer(Modifier.height(14.dp))
                when (step) {
                    STEP_PREPARE -> PrepareStep(
                        report = report,
                        engineHealthy = engineHealthy,
                        fuelLevelOk = fuelLevelOk,
                        oilCoolantOk = oilCoolantOk,
                        flashingMil = flashingMil,
                        onEngineHealthy = {
                            engineHealthy = it
                            if (it) flashingMil = false
                        },
                        onFuelLevelOk = { fuelLevelOk = it },
                        onOilCoolantOk = {
                            oilCoolantOk = it
                            if (it) {
                                oilConsumption = Answer.NO
                                coolantLoss = Answer.NO
                            }
                        },
                        onFlashingMil = {
                            flashingMil = it
                            if (it) engineHealthy = false
                        },
                        canProceed = canProceed && connected,
                        connected = connected,
                        onReadCodes = viewModel::readCodes,
                        onReadMode06 = viewModel::readMode06,
                        onBack = { step = STEP_INTRO },
                        onNext = { if (canProceed) step = STEP_CAPTURE },
                    )

                    STEP_CAPTURE -> CaptureStep(
                        capture = capture,
                        progress = progress,
                        mode06 = report.mode06,
                        safetyStop = !connected || liveSafetyStop,
                        safetyReason = if (!connected) {
                            "The adapter is disconnected. Reconnect before capturing O2 data."
                        } else {
                            gate.blockedReason
                        },
                        onStart = {
                            if (connected && !liveSafetyStop) viewModel.startCatalystCapture()
                        },
                        onBack = { step = STEP_PREPARE },
                        onNext = { if (connected && !liveSafetyStop) step = STEP_QUESTIONS },
                        onSkip = { if (connected && !liveSafetyStop) step = STEP_QUESTIONS },
                    )

                    STEP_QUESTIONS -> QuestionsStep(
                        exhaustLeak = exhaustLeak,
                        recentExhaustWork = recentExhaustWork,
                        oilConsumption = oilConsumption,
                        coolantLoss = coolantLoss,
                        downstreamResponse = downstreamResponse,
                        inletTemp = inletTemp,
                        outletTemp = outletTemp,
                        onExhaustLeak = { exhaustLeak = it },
                        onRecentExhaustWork = { recentExhaustWork = it },
                        onOilConsumption = { oilConsumption = it },
                        onCoolantLoss = { coolantLoss = it },
                        onDownstreamResponse = { downstreamResponse = it },
                        onInletTemp = { inletTemp = it },
                        onOutletTemp = { outletTemp = it },
                        onBack = { step = STEP_CAPTURE },
                        onAnalyze = {
                            viewModel.analyzeCatalyst(
                                CatalystAnswers(
                                    engineHealthyConfirmed = engineHealthy,
                                    flashingMil = flashingMil,
                                    fuelLevelOk = fuelLevelOk,
                                    exhaustLeakSuspected = exhaustLeak,
                                    recentExhaustWork = recentExhaustWork,
                                    oilConsumption = oilConsumption,
                                    coolantLoss = coolantLoss,
                                    downstreamRespondedQuickly = downstreamResponse,
                                    inletTempC = inletTemp.toDoubleOrNull(),
                                    outletTempC = outletTemp.toDoubleOrNull(),
                                ),
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CatalystIntroCard(
    connected: Boolean,
    onStart: () -> Unit,
    onConnect: () -> Unit,
    onTryDemo: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    StalkerCard(accent = stalker.accent) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .background(stalker.accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = stalker.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        CatalystWizard.definition.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        CatalystWizard.definition.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.accent,
                    )
                }
                StatusPill("~${CatalystWizard.definition.estimatedMinutes} min", stalker.accentSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                CatalystWizard.definition.description,
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(12.dp))
            Text("What you need", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            Text("• A warm engine and a safe place to hold ~2,500 rpm, or a steady 50–60 km/h cruise.", style = MaterialTheme.typography.bodySmall)
            Text("• The OBD adapter connected; a passenger if you are driving.", style = MaterialTheme.typography.bodySmall)
            Text("• 10–15 minutes, including a short drive to warm the catalyst.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("Safety", style = MaterialTheme.typography.labelMedium, color = stalker.warning)
            CatalystWizard.definition.safetyNotes.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
            }
            Spacer(Modifier.height(14.dp))
            if (connected) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) { Text("Start guided test") }
            } else {
                ActionRow {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                    ) { Text("Connect adapter") }
                    OutlinedButton(onClick = onTryDemo) { Text("Try demo") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The catalogue stays readable without an adapter; the test needs a connection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
        }
    }
}

@Composable
private fun WizardProgress(step: Int) {
    val stalker = LocalStalkerColors.current
    val labels = listOf("Prepare", "Capture", "Questions")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index + 1 <= step
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            if (active) stalker.accent else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) stalker.accent else stalker.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PrepareStep(
    report: com.carstalker.app.service.DtcReport,
    engineHealthy: Boolean,
    fuelLevelOk: Boolean,
    oilCoolantOk: Boolean,
    flashingMil: Boolean,
    onEngineHealthy: (Boolean) -> Unit,
    onFuelLevelOk: (Boolean) -> Unit,
    onOilCoolantOk: (Boolean) -> Unit,
    onFlashingMil: (Boolean) -> Unit,
    onReadCodes: () -> Unit,
    canProceed: Boolean,
    connected: Boolean,
    onReadMode06: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    SectionHeader("1 · Prepare the car")
    Spacer(Modifier.height(10.dp))
    StalkerCard {
        Column {
            Text(
                "Warm the engine to normal temperature, then read the codes and the ECU's Mode 06 catalyst test.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            val codes = report.stored.map { it.code } + report.pending.map { it.code }
            Text(
                "Codes: ${codes.distinct().joinToString().ifBlank { "none read yet" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (codes.none { it == "P0420" || it == "P0430" }) {
                Text(
                    "No P0420/P0430 is stored. You can still run the test if you suspect the catalyst, but first confirm why you are testing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.warning,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Mode 06 catalyst: ${mode06Label(report.mode06)}",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            ActionRow {
                OutlinedButton(onClick = onReadCodes, enabled = connected) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read codes")
                }
                OutlinedButton(onClick = onReadMode06, enabled = connected) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read Mode 06")
                }
            }
            Spacer(Modifier.height(12.dp))
            CheckRow(
                checked = engineHealthy,
                onCheckedChange = onEngineHealthy,
                text = "The engine is not misfiring and the MIL is not flashing.",
            )
            CheckRow(
                checked = fuelLevelOk,
                onCheckedChange = onFuelLevelOk,
                text = "Fuel level is between 1/4 and 3/4.",
            )
            CheckRow(
                checked = oilCoolantOk,
                onCheckedChange = onOilCoolantOk,
                text = "Oil and coolant levels are OK, with no obvious oil consumption or coolant loss.",
            )
            CheckRow(
                checked = flashingMil,
                onCheckedChange = onFlashingMil,
                text = "The MIL is flashing or the engine is actively misfiring.",
                danger = true,
            )
            if (flashingMil) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Stop here. A flashing MIL or active misfire can destroy the catalyst; fix the misfire first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.danger,
                )
            }
            if (!canProceed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !connected -> "Connect an adapter or start the demo to read codes and run the capture."
                        flashingMil -> "Next is blocked. A flashing MIL or active misfire must be repaired before a catalyst test."
                        else -> "Confirm the engine is not misfiring and the fuel level is in range to enable the capture."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.warning,
                )
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(spacing = 10.dp) {
                TextButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = onNext,
                    enabled = canProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) { Text("Next: capture") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureStep(
    capture: CaptureData?,
    progress: CaptureProgress?,
    mode06: Mode06Report,
    safetyStop: Boolean,
    safetyReason: String?,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    SectionHeader("2 · Capture the O2 signals")
    Spacer(Modifier.height(10.dp))
    StalkerCard {
        Column {
            Text(
                "With the engine warm and in closed loop, hold a steady ~2,500 rpm (or 50–60 km/h) for 30 seconds. The app records only the O2 sensors, RPM and coolant.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Mode 06 catalyst: ${mode06Label(mode06)}",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            if (safetyStop) {
                Text(
                    safetyReason ?: "The capture is blocked by a safety condition.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.danger,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = stalker.accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Capturing… ${(progress.elapsedMs / 1000)} / ${(progress.durationMs / 1000)} s · ${progress.points} samples",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.accent,
                )
            } else {
                Button(
                    onClick = onStart,
                    enabled = !safetyStop,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (capture == null) "Start 30 s capture" else "Capture again")
                }
            }
            Spacer(Modifier.height(12.dp))
            O2SignalChart(capture = capture, modifier = Modifier.fillMaxWidth().height(190.dp))
            if (capture != null) {
                Spacer(Modifier.height(6.dp))
                val up = capture.sampleCount(CatalystWizard.UPSTREAM_PID)
                val down = capture.sampleCount(CatalystWizard.DOWNSTREAM_PID)
                Text(
                    "Upstream $up samples · downstream $down samples · ${String.format(Locale.US, "%.1f", capture.averageHz(CatalystWizard.UPSTREAM_PID))} Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
                if (up == 0 || down == 0) {
                    Text(
                        "One O2 channel was not reported. You can still use Mode 06, but the live comparison is weaker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.warning,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack, enabled = progress == null) { Text("Back") }
                TextButton(onClick = onSkip, enabled = progress == null && !safetyStop) { Text("Skip capture") }
                Button(
                    onClick = onNext,
                    enabled = progress == null && !safetyStop,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) { Text("Next: questions") }
            }
        }
    }
}

@Composable
private fun QuestionsStep(
    exhaustLeak: Answer,
    recentExhaustWork: Answer,
    oilConsumption: Answer,
    coolantLoss: Answer,
    downstreamResponse: Answer,
    inletTemp: String,
    outletTemp: String,
    onExhaustLeak: (Answer) -> Unit,
    onRecentExhaustWork: (Answer) -> Unit,
    onOilConsumption: (Answer) -> Unit,
    onCoolantLoss: (Answer) -> Unit,
    onDownstreamResponse: (Answer) -> Unit,
    onInletTemp: (String) -> Unit,
    onOutletTemp: (String) -> Unit,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    SectionHeader("3 · A few observations")
    Spacer(Modifier.height(10.dp))
    StalkerCard {
        Column {
            Text(
                "The app cannot hear a leak or hold a thermometer. These answers make the result safer.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(12.dp))
            AnswerRow("Any exhaust hiss, soot or recent exhaust work?", exhaustLeak, onExhaustLeak)
            AnswerRow("Recent exhaust repairs?", recentExhaustWork, onRecentExhaustWork)
            AnswerRow("Does the engine use oil?", oilConsumption, onOilConsumption)
            AnswerRow("Does it lose coolant?", coolantLoss, onCoolantLoss)
            AnswerRow("After a throttle snap, did the downstream sensor respond within ~3 s?", downstreamResponse, onDownstreamResponse)
            Spacer(Modifier.height(10.dp))
            Text("Optional: converter inlet/outlet temperature", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            Text(
                "A temperature difference can support a diagnosis, but it varies with load, emissivity and measurement location. Skip this if you do not have an IR thermometer; it is not a standalone converter test.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = inletTemp,
                    onValueChange = onInletTemp,
                    label = { Text("Inlet °C") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = outletTemp,
                    onValueChange = onOutletTemp,
                    label = { Text("Outlet °C") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(spacing = 10.dp) {
                TextButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = onAnalyze,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) { Text("See result") }
            }
        }
    }
}

@Composable
private fun WizardResultView(result: WizardResult, onExport: () -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val stalker = LocalStalkerColors.current
    val color = when (result.confidence) {
        Confidence.HIGH -> stalker.success
        Confidence.MEDIUM -> stalker.warning
        Confidence.LOW -> stalker.danger
    }
    StalkerCard(accent = color) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Result",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill("${result.confidence.label} confidence", color)
            }
            Spacer(Modifier.height(8.dp))
            Text(result.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            Text("Findings", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            result.causes.forEach { cause -> CauseCard(cause) }
            if (result.evidence.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Evidence", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.evidence.forEach { item ->
                    Text("• ${item.label}: ${item.value}", style = MaterialTheme.typography.bodySmall, color = stalker.muted)
                }
            }
            if (result.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Warnings", style = MaterialTheme.typography.labelMedium, color = stalker.danger)
                result.warnings.forEach { warning ->
                    Text("• $warning", style = MaterialTheme.typography.bodySmall, color = stalker.danger)
                }
            }
            if (result.nextSteps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Next steps", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                result.nextSteps.forEach { next ->
                    Text("• $next", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onExport,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Preview report")
                }
                OutlinedButton(onClick = onReset) {
                    Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start over")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("← All guides") }
        }
    }
}

@Composable
private fun CauseCard(cause: WizardCause) {
    val stalker = LocalStalkerColors.current
    val color = when (cause.likelihood) {
        Likelihood.LIKELY -> stalker.danger
        Likelihood.POSSIBLE -> stalker.warning
        Likelihood.UNLIKELY -> stalker.muted
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cause.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(cause.likelihood.label, color)
        }
        Text(
            "${cause.confidence.label} confidence · ${cause.explanation}",
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        cause.evidence.forEach { item ->
            Text("• $item", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        }
    }
}

@Composable
private fun AnswerRow(label: String, answer: Answer, onAnswer: (Answer) -> Unit) {
    val stalker = LocalStalkerColors.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Answer.YES to "Yes", Answer.NO to "No", Answer.UNKNOWN to "Not sure").forEach { (value, text) ->
                val active = answer == value
                Surface(
                    onClick = { onAnswer(value) },
                    shape = RoundedCornerShape(50),
                    color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (active) stalker.accent.copy(alpha = 0.6f) else Color.Transparent),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) stalker.accent else stalker.muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    danger: Boolean = false,
) {
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
            colors = CheckboxDefaults.colors(
                checkedColor = if (danger) stalker.danger else stalker.accent,
                checkmarkColor = Color.Black,
            ),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) stalker.danger else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun O2SignalChart(capture: CaptureData?, modifier: Modifier = Modifier) {
    val stalker = LocalStalkerColors.current
    val upstream = capture?.series(CatalystWizard.UPSTREAM_PID).orEmpty()
    val downstream = capture?.series(CatalystWizard.DOWNSTREAM_PID).orEmpty()
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))) {
        if (upstream.isEmpty() && downstream.isEmpty()) {
            Text(
                "No O2 capture yet",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val all = upstream + downstream
                val start = all.minOf { it.timestampMs }
                val end = all.maxOf { it.timestampMs }
                val span = (end - start).coerceAtLeast(1L)
                fun x(point: CapturePoint) = ((point.timestampMs - start).toFloat() / span) * size.width
                fun y(value: Double) = size.height - (value.coerceIn(0.0, 1.0).toFloat() * size.height)
                for (fraction in listOf(0.1f, 0.45f, 0.9f)) {
                    val lineY = size.height - fraction * size.height
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = Offset(0f, lineY),
                        end = Offset(size.width, lineY),
                        strokeWidth = 1f,
                    )
                }
                fun drawSeries(points: List<CapturePoint>, color: Color) {
                    if (points.size < 2) return
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(x(point), y(point.value)) else path.lineTo(x(point), y(point.value))
                    }
                    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
                drawSeries(upstream, stalker.accent)
                drawSeries(downstream, stalker.accentSecondary)
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Upstream", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                Text("Downstream", style = MaterialTheme.typography.labelSmall, color = stalker.accentSecondary)
            }
        }
    }
}

private fun mode06Label(report: Mode06Report): String {
    if (!report.countsAvailable) {
        return when (report.state) {
            com.carstalker.core.obd.MonitorDataState.NOT_REQUESTED -> "not checked"
            com.carstalker.core.obd.MonitorDataState.UNSUPPORTED -> "not supported"
            com.carstalker.core.obd.MonitorDataState.FAILED_TO_READ -> "could not be read"
            com.carstalker.core.obd.MonitorDataState.IN_PROGRESS -> "reading"
            com.carstalker.core.obd.MonitorDataState.STALE -> "outdated"
            com.carstalker.core.obd.MonitorDataState.AVAILABLE -> "not completed"
        }
    }
    val results = report.trustedResults.filter { it.mid == 0x21 || it.mid == 0x22 }
    return when {
        results.any { it.passed == false } -> "FAIL"
        results.any { it.passed == true } -> "PASS"
        else -> "not completed"
    }
}
