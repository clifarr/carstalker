package com.carstalker.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.data.toPid
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.MiniStat
import com.carstalker.app.ui.components.RadialGauge
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.Sparkline
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatTile
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.dashboardParam
import com.carstalker.app.data.recommendedDashboardPids
import com.carstalker.app.ui.components.formatValue
import com.carstalker.app.ui.theme.Amber
import com.carstalker.app.ui.theme.Ember
import com.carstalker.app.ui.theme.Ice
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.Mint
import com.carstalker.app.ui.theme.Volt
import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.obd.UnitSystem
import com.carstalker.core.obd.Units
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: CarStalkerViewModel,
    onOpenConnect: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val supported by viewModel.supportedPids.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    var detailPid by remember { mutableStateOf<Pid?>(null) }

    val customPids = remember(settings.customPids) {
        settings.customPids.filter { it.enabled }.mapNotNull { it.toPid() }
    }
    val dashboardPids = remember(settings.dashboardPids, settings.dashboardAuto, supported, customPids) {
        val ids = if (settings.dashboardAuto) {
            recommendedDashboardPids(supported)
        } else {
            settings.dashboardPids
        }
        ids.mapNotNull { id ->
            customPids.firstOrNull { it.id == id } ?: dashboardParam(id)
        }
    }

    val ready = connection as? ConnectionState.Ready
    val rpmPid = PidCatalog.find(0x0C)!!
    val speedPid = PidCatalog.find(0x0D)!!
    val loadPid = PidCatalog.find(0x04)!!
    val coolantPid = PidCatalog.find(0x05)!!
    val batteryPid = dashboardParam(LiveKeys.ADAPTER_VOLTAGE)!!
    val rpm = live.values[0x0C] ?: 0.0
    val speedDisplay = Units.display(speedPid, live.values[0x0D] ?: 0.0, settings.unitSystem)
    val maxSpeed = if (settings.unitSystem == UnitSystem.IMPERIAL) 150.0 else 240.0

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "LIVE TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.accent,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (identity.isKnown) identity.displayName else "Your garage",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (identity.vin == null && identity.calibrationId != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "ECU calibration ${identity.calibrationId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
            }
            StatusPill(
                text = when (connection) {
                    is ConnectionState.Ready -> "Live"
                    is ConnectionState.Connecting, is ConnectionState.Initializing -> "Connecting"
                    is ConnectionState.Error -> "Offline"
                    else -> "Offline"
                },
                color = when (connection) {
                    is ConnectionState.Ready -> stalker.success
                    is ConnectionState.Error -> stalker.danger
                    else -> stalker.muted
                },
                pulsing = connection is ConnectionState.Ready,
                onClick = onOpenConnect,
            )
            Spacer(Modifier.width(10.dp))
            RecordButton(
                recording = recording,
                enabled = connection is ConnectionState.Ready || recording,
                onClick = viewModel::toggleRecording,
            )
        }

        if (recording) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("REC", stalker.danger, pulsing = true)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recording every sample to CSV",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (connection) {
            is ConnectionState.Ready, is ConnectionState.Connecting, is ConnectionState.Initializing -> {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(
                        Modifier.background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(stalker.accent.copy(alpha = 0.08f), Color.Transparent),
                            ),
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                RadialGauge(
                                    value = rpm,
                                    min = 0.0,
                                    max = 8000.0,
                                    label = "Engine speed",
                                    unit = "rpm",
                                    accent = Volt,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { detailPid = rpmPid },
                                    redline = 0.86f,
                                )
                                RadialGauge(
                                    value = speedDisplay.value,
                                    min = 0.0,
                                    max = maxSpeed,
                                    label = "Vehicle speed",
                                    unit = speedDisplay.unit,
                                    accent = Ice,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { detailPid = speedPid },
                                    redline = 0.94f,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val coolant = live.values[0x05]
                                val load = live.values[0x04]
                                val volts = live.values[LiveKeys.ADAPTER_VOLTAGE] ?: live.adapterVoltage
                                val coolantDisplay = coolant?.let { Units.display(PidCatalog.find(0x05)!!, it, settings.unitSystem) }
                                MiniStat(
                                    "Coolant",
                                    coolantDisplay?.let { formatValue(it.value, 0) } ?: "—",
                                    coolantDisplay?.unit ?: "",
                                    accent = Ember,
                                    modifier = Modifier.clickable { detailPid = coolantPid },
                                )
                                MiniStat(
                                    "Load",
                                    load?.let { formatValue(it, 0) } ?: "—",
                                    "%",
                                    accent = Volt,
                                    modifier = Modifier.clickable { detailPid = loadPid },
                                )
                                MiniStat(
                                    "Battery",
                                    volts?.let { formatValue(it, 1) } ?: "—",
                                    "V",
                                    accent = Ice,
                                    modifier = Modifier.clickable { detailPid = batteryPid },
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                StalkerCard(accent = stalker.accent) {
                    Column {
                        Text(
                            "No adapter connected",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Connect a Bluetooth, USB or Wi-Fi ELM327 adapter to stream live engine data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onOpenConnect,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = stalker.accent,
                                    contentColor = Color.Black,
                                ),
                            ) { Text("Connect adapter") }
                            OutlinedButton(onClick = viewModel::connectDemo) {
                                Text("Try demo")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader(
            title = "Live data",
            trailing = {
                TextButton(onClick = viewModel::openPidPicker) {
                    Text("Edit", color = stalker.accent)
                }
            },
        )
        Spacer(Modifier.height(10.dp))

        if (dashboardPids.isEmpty()) {
            StalkerCard {
                Text(
                    "No parameters pinned. Tap Edit to choose what you want to watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        } else {
            dashboardPids.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { pid ->
                        val rawValue = live.values[pid.id]
                        val display = rawValue?.let { Units.display(pid, it, settings.unitSystem) }
                        StatTile(
                            title = pid.shortName,
                            value = display?.let {
                                formatValue(it.value, if (pid.unit == "km/h" || pid.unit == "mph") 0 else pid.decimals)
                            } ?: "—",
                            unit = display?.unit ?: pid.unit,
                            samples = history[pid.id].orEmpty(),
                            accent = accentFor(pid),
                            modifier = Modifier.weight(1f),
                            onClick = { detailPid = pid },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("Vehicle")
        Spacer(Modifier.height(10.dp))
        VehicleCard(
            vin = identity.vin,
            calibrationId = identity.calibrationId,
            cvn = identity.cvn,
            ecuName = identity.ecuName,
            protocol = ready?.adapter?.protocolName,
            adapter = ready?.adapter?.identifier,
            voltage = live.adapterVoltage ?: ready?.adapter?.voltage,
            supportedCount = supported.size,
        )
        Spacer(Modifier.height(24.dp))
    }

    detailPid?.let { pid ->
        PidDetailSheet(
            pid = pid,
            samples = history[pid.id].orEmpty(),
            unitSystem = settings.unitSystem,
            isPinned = dashboardPids.any { it.id == pid.id },
            onTogglePin = { viewModel.toggleDashboardPid(pid.id) },
            onDismiss = { detailPid = null },
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stalker = LocalStalkerColors.current
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "recPulse",
    )
    Surface(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        color = if (recording) stalker.danger.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (recording) stalker.danger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.size(44.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                contentDescription = if (recording) "Stop recording" else "Start recording",
                tint = when {
                    recording -> stalker.danger.copy(alpha = pulse)
                    enabled -> stalker.muted
                    else -> stalker.muted.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VehicleCard(
    vin: String?,
    calibrationId: String?,
    cvn: List<String>,
    ecuName: String?,
    protocol: String?,
    adapter: String?,
    voltage: Double?,
    supportedCount: Int,
) {
    val stalker = LocalStalkerColors.current
    val clipboard = LocalClipboardManager.current
    StalkerCard {
        Column {
            if (vin == null && calibrationId == null) {
                Text(
                    "Vehicle identity is read when the adapter connects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            } else {
                vin?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VIN", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                            Text(
                                it,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Surface(
                            onClick = { clipboard.setText(AnnotatedString(it)) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy VIN",
                                    tint = stalker.muted,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                calibrationId?.let {
                    InfoLine("Calibration ID", it)
                }
                if (cvn.isNotEmpty()) InfoLine("CVN", cvn.joinToString(", "))
                ecuName?.let { InfoLine("ECU", it) }
            }
            if (protocol != null) {
                HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                )
                InfoLine("Protocol", protocol)
                adapter?.let { InfoLine("Adapter", it) }
                voltage?.let { InfoLine("Adapter voltage", String.format(Locale.US, "%.1f V", it)) }
                InfoLine("Supported PIDs", supportedCount.toString())
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PidDetailSheet(
    pid: Pid,
    samples: List<Float>,
    unitSystem: UnitSystem,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = samples.lastOrNull()?.toDouble()
    val display = current?.let { Units.display(pid, it, unitSystem) }
    val min = samples.minOrNull()
    val max = samples.maxOrNull()
    val avg = if (samples.isEmpty()) null else samples.average()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(pid.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "PID ${pid.hex} · ${pid.category.name.lowercase(Locale.US)}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadialGauge(
                    value = display?.value ?: 0.0,
                    min = pid.min,
                    max = pid.max,
                    label = pid.shortName,
                    unit = display?.unit ?: pid.unit,
                    accent = accentFor(pid),
                    modifier = Modifier.size(210.dp),
                    decimals = pid.decimals,
                    redline = 0.92f,
                )
            }
            Spacer(Modifier.height(10.dp))
            Sparkline(
                samples = samples,
                accent = accentFor(pid),
                modifier = Modifier.fillMaxWidth().height(86.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailStat("Min", min?.let { formatValue(it.toDouble(), pid.decimals) } ?: "—", pid.unit)
                DetailStat("Average", avg?.let { formatValue(it, pid.decimals) } ?: "—", pid.unit)
                DetailStat("Max", max?.let { formatValue(it.toDouble(), pid.decimals) } ?: "—", pid.unit)
            }
            Spacer(Modifier.height(20.dp))
            if (!pid.isCustom) {
                OutlinedButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isPinned) "Remove from dashboard" else "Pin to dashboard")
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    "Custom PID — manage it in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
            ) { Text("Close") }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, unit: String) {
    val stalker = LocalStalkerColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(Locale.US), style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
    }
}

private fun accentFor(pid: Pid): Color = when (pid.category) {
    Pid.Category.ENGINE -> Volt
    Pid.Category.DRIVETRAIN -> Mint
    Pid.Category.FUEL -> Amber
    Pid.Category.TEMPERATURE -> Ember
    Pid.Category.ELECTRICAL -> Ice
    Pid.Category.EMISSIONS -> Mint
    Pid.Category.OTHER -> Ice
}

