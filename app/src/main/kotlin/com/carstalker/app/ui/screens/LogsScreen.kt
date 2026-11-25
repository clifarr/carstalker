package com.carstalker.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.InsertChartOutlined
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.data.LogSession
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.EmptyState
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.theme.LocalStalkerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(viewModel: CarStalkerViewModel) {
    val sessions by viewModel.logs.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val selected by viewModel.selectedLog.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current
    var deleteTarget by remember { mutableStateOf<LogSession?>(null) }
    val dateFormat = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }

    val selectedSession = selected
    if (selectedSession != null) {
        LogDetailScreen(
            session = selectedSession,
            onBack = viewModel::closeLog,
            onShare = { viewModel.shareLog(selectedSession) },
            onDelete = { viewModel.deleteLog(selectedSession) },
        )
        return
    }

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
                Text("RECORDINGS", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Your recordings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            TextButton(onClick = viewModel::refreshLogs) {
                Text("Refresh", color = stalker.accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a recording to play it back. No adapter needed.",
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(14.dp))

        if (recording) {
            StalkerCard(accent = stalker.danger) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("REC", stalker.danger, pulsing = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Recording now",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Samples are written to CSV as they arrive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                    }
                    Button(
                        onClick = viewModel::toggleRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = stalker.danger.copy(alpha = 0.16f),
                            contentColor = stalker.danger,
                        ),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (sessions.isEmpty()) {
            StalkerCard {
                Column {
                EmptyState(
                    title = "No recordings yet",
                    message = "Tap the record button on the dashboard to capture a drive. Recordings appear here and can be played back without an adapter.",
                    icon = {
                        Box(
                            Modifier
                                .size(54.dp)
                                .then(
                                    Modifier.background(
                                        stalker.accent.copy(alpha = 0.12f),
                                        CircleShape,
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.InsertChartOutlined,
                                contentDescription = null,
                                tint = stalker.accent,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                )
                if (connection !is com.carstalker.app.service.ConnectionState.Ready) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = viewModel::connectDemo,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try demo") }
                }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sessions.forEach { session ->
                    Surface(
                        onClick = { viewModel.openLog(session) },
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(stalker.accent.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Play recording",
                                        tint = stalker.accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        dateFormat.format(Date(session.meta.startedAt)),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "${formatDuration(session.durationMs)} · ${session.meta.rows} samples · ${formatSize(session.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = stalker.muted,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StatusPill(
                                            if (session.meta.kind == "guided") {
                                                "Guided · ${session.meta.label ?: "capture"}"
                                            } else {
                                                "Drive"
                                            },
                                            if (session.meta.kind == "guided") stalker.accentSecondary else stalker.accent,
                                        )
                                        if (session.meta.isDemo) StatusPill("Demo", stalker.muted)
                                    }
                                }
                                IconButton(onClick = { viewModel.shareLog(session) }) {
                                    Icon(
                                        Icons.Rounded.Share,
                                        contentDescription = "Share recording",
                                        tint = stalker.accent,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                IconButton(onClick = { deleteTarget = session }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete recording",
                                        tint = stalker.muted,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (session.meta.vin != null || session.meta.pids.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    session.meta.vin?.let {
                                        Text(
                                            "VIN ${it.takeLast(6)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = stalker.muted,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    if (session.meta.pids.isNotEmpty()) {
                                        Text(
                                            "${session.meta.pids.size} parameters",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = stalker.muted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this recording?") },
            text = { Text("The CSV file and its metadata will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLog(target)
                        deleteTarget = null
                    },
                ) { Text("Delete", color = stalker.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

