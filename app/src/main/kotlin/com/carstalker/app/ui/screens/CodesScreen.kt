package com.carstalker.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.ActionRow
import com.carstalker.app.ui.components.EmptyState
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcGroup
import com.carstalker.core.dtc.DtcKnowledge
import com.carstalker.core.guided.DiagnosticDefinition
import com.carstalker.core.guided.DiagnosticRegistry
import com.carstalker.core.obd.Ecu
import com.carstalker.core.obd.FreezeFrame
import com.carstalker.core.obd.Mode06Result
import com.carstalker.core.obd.PidCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CodesScreen(
    viewModel: CarStalkerViewModel,
    onOpenConnect: () -> Unit,
    onOpenDiagnose: () -> Unit,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val report by viewModel.dtcReport.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedGroup by rememberSaveable { mutableStateOf(DtcGroup.STORED) }
    var selectedModule by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }

    val connected = connection is ConnectionState.Ready
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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
                Text("DIAGNOSTICS", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Trouble codes",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (report.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = stalker.accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            // Reading is the labelled action in the status card below; a second unlabelled
            // refresh icon here only asked the user to guess which one to press.
            IconButton(onClick = viewModel::exportReport, enabled = connected && report.readAt > 0) {
                Icon(
                    Icons.Rounded.PictureAsPdf,
                    contentDescription = "Preview PDF report",
                    tint = if (connected && report.readAt > 0) stalker.accent else stalker.muted,
                )
            }
        }

        if (report.readAt > 0 && System.currentTimeMillis() - report.readAt > 10 * 60_000) {
            Spacer(Modifier.height(6.dp))
            Text(
                "This scan is over 10 minutes old. Read again before acting on it.",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.warning,
            )
        }
        if (!connected) {
            Spacer(Modifier.height(14.dp))
            StalkerCard(accent = stalker.warning) {
                Column {
                    Text(
                        "Adapter not connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect to read and clear diagnostic trouble codes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                    ) { Text("Connect") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MilCard(
            milOn = report.monitor?.milOn == true,
            stored = report.stored.size,
            pending = report.pending.size,
            permanent = report.permanent.size,
            readAt = report.readAt.takeIf { it > 0 }?.let { timeFormat.format(Date(it)) },
            connected = connected,
            onRead = viewModel::readCodes,
            onClear = { confirmClear = true },
        )

        if (report.modules.size > 1) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("Modules")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModuleChip(
                    label = "All",
                    count = report.total,
                    active = selectedModule == "all",
                    onClick = { selectedModule = "all" },
                )
                report.modules.forEach { module ->
                    ModuleChip(
                        label = module.name,
                        count = module.total,
                        active = selectedModule == Ecu.key(module.source),
                        onClick = { selectedModule = Ecu.key(module.source) },
                    )
                }
            }
        }

        val monitors = report.monitor?.monitors.orEmpty()
        if (monitors.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("Readiness monitors")
            Spacer(Modifier.height(6.dp))
            Text(
                "Not ready is not a fault. Monitors need a drive cycle after a battery disconnect or code clear.",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            StalkerCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    monitors.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { monitor ->
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        monitor.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    when {
                                        !monitor.supported -> StatusPill("N/A", stalker.muted)
                                        monitor.complete -> StatusPill("Ready", stalker.success)
                                        else -> StatusPill("Not ready", stalker.warning)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DtcGroup.entries.forEach { group ->
                val count = report.forGroup(group).size
                val active = selectedGroup == group
                Surface(
                    onClick = { selectedGroup = group },
                    shape = RoundedCornerShape(50),
                    color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (active) stalker.accent.copy(alpha = 0.6f) else Color.Transparent,
                    ),
                ) {
                    Text(
                        "${group.label} · $count",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) stalker.accent else stalker.muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val selected = report.modules.firstOrNull { Ecu.key(it.source) == selectedModule }
        val codes = selected?.forGroup(selectedGroup) ?: report.forGroup(selectedGroup)
        if (codes.isEmpty()) {
            // The selected group being empty does not mean the car is clean; another group may
            // hold codes. Say so instead of implying the module has no faults.
            val elsewhere = DtcGroup.entries
                .filter { it != selectedGroup }
                .mapNotNull { group ->
                    val count = (selected?.forGroup(group) ?: report.forGroup(group)).size
                    if (count > 0) "${group.label.lowercase()} $count" else null
                }
            StalkerCard {
                EmptyState(
                    title = when (selectedGroup) {
                        DtcGroup.STORED -> "No stored codes"
                        DtcGroup.PENDING -> "No pending codes"
                        DtcGroup.PERMANENT -> "No permanent codes"
                    },
                    message = when {
                        !connected -> "Connect an adapter to scan this module."
                        elsewhere.isNotEmpty() ->
                            "Nothing in this group, but this module reports ${elsewhere.joinToString(" and ")}. Switch group above."
                        else -> "This module reports clean. Read again after a drive cycle."
                    },
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                codes.forEach { dtc ->
                    DtcCard(
                        dtc = dtc,
                        guide = DiagnosticRegistry.implemented().firstOrNull { it.matchesCode(dtc.code) },
                        onCopy = {
                            clipboard.setText(AnnotatedString(dtc.code))
                            Toast.makeText(context, "${dtc.code} copied", Toast.LENGTH_SHORT).show()
                        },
                        onOpenGuide = { definition ->
                            viewModel.openGuide(definition)
                            onOpenDiagnose()
                        },
                    )
                }
            }
        }

        val frames = report.freezeFrames.filter {
            selectedModule == "all" || Ecu.key(it.source) == selectedModule
        }
        if (frames.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionHeader("Freeze frames")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                frames.forEach { frame -> FreezeFrameCard(frame) }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("On-board monitor tests")
        Spacer(Modifier.height(10.dp))
        Mode06Card(
            report = report.mode06,
            connected = connected,
            onRead = viewModel::readMode06,
        )

        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear trouble codes?") },
            text = {
                Text(
                    "This turns off the check-engine light, erases stored and pending codes, and resets readiness monitors. " +
                        "Only clear codes after the underlying fault is repaired — an emissions inspection will fail while monitors are incomplete.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearCodes()
                    },
                ) { Text("Clear codes", color = stalker.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MilCard(
    milOn: Boolean,
    stored: Int,
    pending: Int,
    permanent: Int,
    readAt: String?,
    connected: Boolean,
    onRead: () -> Unit,
    onClear: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val total = stored + pending + permanent
    val accent = when {
        milOn -> stalker.danger
        total > 0 -> stalker.warning
        else -> stalker.success
    }
    StalkerCard(accent = accent) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            milOn -> "Check-engine light on"
                            stored > 0 -> "$stored stored code${if (stored == 1) "" else "s"}"
                            pending > 0 -> "$pending pending code${if (pending == 1) "" else "s"}"
                            permanent > 0 -> "$permanent permanent code${if (permanent == 1) "" else "s"}"
                            else -> "No faults reported"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            append("$stored stored · $pending pending · $permanent permanent")
                            if (readAt != null) append(" · read $readAt")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ActionRow(spacing = 10.dp) {
                Button(
                    onClick = onRead,
                    enabled = connected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stalker.accent,
                        contentColor = Color.Black,
                    ),
                ) { Text("Read codes") }
                Button(
                    onClick = onClear,
                    enabled = connected && total > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stalker.danger.copy(alpha = 0.16f),
                        contentColor = stalker.danger,
                    ),
                ) { Text("Clear codes") }
            }
        }
    }
}

@Composable
private fun DtcCard(
    dtc: Dtc,
    guide: DiagnosticDefinition?,
    onCopy: () -> Unit,
    onOpenGuide: (DiagnosticDefinition) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val guidance = remember(dtc.code) { DtcKnowledge.forCode(dtc.code) }
    var expanded by remember { mutableStateOf(false) }
    val accent = when (dtc.category) {
        Dtc.Category.POWERTRAIN -> stalker.danger
        Dtc.Category.CHASSIS -> stalker.warning
        Dtc.Category.BODY -> stalker.accentSecondary
        Dtc.Category.NETWORK -> stalker.success
        Dtc.Category.UNKNOWN -> stalker.muted
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(enabled = guidance != null) { expanded = !expanded },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    Text(
                        dtc.code,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        dtc.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            append(dtc.category.label)
                            append(if (dtc.isManufacturerSpecific) " · manufacturer specific" else " · generic")
                            if (guidance != null) append(if (expanded) " · hide guidance" else " · causes & checks")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (guidance != null) stalker.accent else stalker.muted,
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "Copy ${dtc.code}",
                        tint = stalker.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded && guidance != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Text(
                        guidance.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Likely causes", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                    guidance.causes.forEach { cause ->
                        Text("• $cause", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Next checks", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
                    guidance.nextSteps.forEach { step ->
                        Text("• $step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // A code is a starting point. Offer the guided test that measures it rather
                    // than leaving the user to find the Diagnose tab themselves.
                    if (guide != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onOpenGuide(guide) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Run the guided test for ${dtc.code}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val stalker = LocalStalkerColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) stalker.accent.copy(alpha = 0.6f) else Color.Transparent,
        ),
    ) {
        Text(
            "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) stalker.accent else stalker.muted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun FreezeFrameCard(frame: FreezeFrame) {
    val stalker = LocalStalkerColors.current
    StalkerCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Frame ${frame.frame}" + (frame.dtc?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${Ecu.name(frame.source)} · snapshot recorded when the code was stored",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
                StatusPill("Mode 02", stalker.accentSecondary)
            }
            if (frame.values.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The ECU did not return any snapshot values for this frame.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                val entries = frame.values.entries.toList()
                entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (id, value) ->
                            val pid = PidCatalog.find(id)
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Text(
                                    pid?.shortName ?: "PID ${id.toString(16).uppercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.muted,
                                )
                                Text(
                                    formatMeasurement(value, pid?.decimals ?: 1, pid?.unit.orEmpty()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Mode06Card(
    report: com.carstalker.core.obd.Mode06Report,
    connected: Boolean,
    onRead: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    StalkerCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mode 06 self-tests",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Measured value versus the ECU's own pass/fail limits.",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
                Button(
                    onClick = onRead,
                    enabled = connected && !report.loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stalker.accent.copy(alpha = 0.16f),
                        contentColor = stalker.accent,
                    ),
                ) {
                    if (report.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = stalker.accent,
                        )
                    } else {
                        Text(if (report.results.isEmpty()) "Read" else "Refresh")
                    }
                }
            }
            if (report.results.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when (report.state) {
                        com.carstalker.core.obd.MonitorDataState.NOT_REQUESTED ->
                            "Not checked. Mode 06 exposes catalyst, O2, EVAP and misfire margins that a code scan cannot show. Reading it is optional and can be slow."
                        com.carstalker.core.obd.MonitorDataState.IN_PROGRESS ->
                            "Reading monitor results. This can take a minute on an ELM327."
                        com.carstalker.core.obd.MonitorDataState.UNSUPPORTED ->
                            "This ECU does not advertise any Mode 06 monitor tests."
                        com.carstalker.core.obd.MonitorDataState.FAILED_TO_READ ->
                            "Mode 06 could not be read. That is not the same as zero failures. ${report.error.orEmpty()}"
                        com.carstalker.core.obd.MonitorDataState.STALE ->
                            "The Mode 06 results are from an earlier read. Refresh before relying on them."
                        com.carstalker.core.obd.MonitorDataState.AVAILABLE ->
                            "The ECU reported no completed Mode 06 tests."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.state == com.carstalker.core.obd.MonitorDataState.FAILED_TO_READ) stalker.warning else stalker.muted,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (report.countsAvailable) {
                        StatusPill("${report.passes.size} pass", stalker.success)
                        StatusPill("${report.failures.size} fail", if (report.failures.isEmpty()) stalker.muted else stalker.danger)
                        StatusPill("${report.inconclusive.size} n/a", stalker.muted)
                    } else {
                        StatusPill("Not checked", stalker.warning)
                    }
                }
                if (!report.countsAvailable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "These rows are raw monitor data, not a completed pass/fail summary. Read Mode 06 to refresh them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.warning,
                    )
                }
                Spacer(Modifier.height(10.dp))
                report.results.take(40).forEach { result ->
                    Mode06Row(result)
                }
                if (report.results.size > 40) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "+ ${report.results.size - 40} more tests",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun Mode06Row(result: Mode06Result) {
    val stalker = LocalStalkerColors.current
    val color = when (result.passed) {
        true -> stalker.success
        false -> stalker.danger
        null -> stalker.muted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(result.label)
                    result.source?.let { append(" · ECU 0x${it.toString(16).uppercase(Locale.US)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                when {
                    !result.mappingTrusted -> "UNVERIFIED"
                    result.passed == true -> "PASS"
                    result.passed == false -> "FAIL"
                    else -> "N/A"
                },
                if (!result.mappingTrusted) stalker.warning else color,
            )
        }
        Text(
            buildString {
                append(formatMeasurement(result.value, 2, result.unit))
                val limits = listOfNotNull(
                    result.min?.let { "min ${formatMeasurement(it, 2, result.unit)}" },
                    result.max?.let { "max ${formatMeasurement(it, 2, result.unit)}" },
                )
                if (limits.isNotEmpty()) append("  ·  ${limits.joinToString("  ")}")
                result.marginPercent?.let { append("  ·  ${String.format(Locale.US, "%.0f", it)}% margin") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
        if (!result.mappingTrusted) {
            Text(
                "The monitor identity and reported unit do not agree. This row is excluded from pass/fail counts. Raw: ${result.raw}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.warning,
            )
        }
    }
}

private fun formatMeasurement(value: Double, decimals: Int, unit: String): String {
    val text = if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.${decimals.coerceIn(0, 3)}f", value)
    return if (unit.isBlank()) text else "$text $unit"
}
