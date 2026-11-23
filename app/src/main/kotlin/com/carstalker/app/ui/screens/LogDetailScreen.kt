package com.carstalker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstalker.app.data.LogPlaybackParser
import com.carstalker.app.data.LogSession
import com.carstalker.app.data.PlaybackData
import com.carstalker.app.data.PlaybackSeries
import com.carstalker.app.ui.components.EmptyState
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.NumericStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val PLAYBACK_SPEEDS = listOf(0.5f, 1f, 2f, 4f)

@Composable
fun LogDetailScreen(
    session: LogSession,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val dateFormat = remember { SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault()) }

    var data by remember(session.meta.id) { mutableStateOf<PlaybackData?>(null) }
    var position by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(session.meta.id) {
        data = withContext(Dispatchers.IO) {
            runCatching { LogPlaybackParser.parse(session.csvFile) }
                .getOrElse { PlaybackData(emptyList(), 0L, 0L) }
        }
    }

    LaunchedEffect(data) {
        val loaded = data ?: return@LaunchedEffect
        position = loaded.startMs
        selectedKey = loaded.series.firstOrNull()?.key
        playing = false
    }

    LaunchedEffect(playing, speed, data) {
        val loaded = data ?: return@LaunchedEffect
        while (playing) {
            delay(50)
            val next = position + (50 * speed).toLong()
            position = next.coerceAtMost(loaded.endMs)
            if (position >= loaded.endMs) playing = false
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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("RECORDING", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                Text(
                    dateFormat.format(Date(session.meta.startedAt)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = "Share recording", tint = stalker.accent)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete recording", tint = stalker.muted)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(formatDuration(session.durationMs))
                append(" · ")
                append(session.meta.rows)
                append(" samples")
                session.meta.vin?.let { append(" · VIN ${it.takeLast(6)}") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(16.dp))

        val playback = data
        when {
            playback == null -> {
                StalkerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = stalker.accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Loading recording…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = stalker.muted,
                        )
                    }
                }
            }

            playback.isEmpty -> {
                StalkerCard {
                    EmptyState(
                        title = "Nothing to play",
                        message = "This recording has no readable samples. It may have been created before any data arrived.",
                    )
                }
            }

            else -> {
                val palette = listOf(
                    stalker.accent,
                    stalker.accentSecondary,
                    stalker.warning,
                    stalker.danger,
                    stalker.success,
                    Color(0xFFB388FF),
                    Color(0xFFFF80AB),
                    Color(0xFF82B1FF),
                    Color(0xFFFFD180),
                    Color(0xFF80CBC4),
                )
                val colors = playback.series.mapIndexed { index, series ->
                    series.key to palette[index % palette.size]
                }.toMap()
                val selectedSeries = playback.series.firstOrNull { it.key == selectedKey }
                    ?: playback.series.first()
                val elapsed = (position - playback.startMs).coerceAtLeast(0L)
                val duration = playback.durationMs

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        PlaybackChart(
                            series = playback.series,
                            startMs = playback.startMs,
                            endMs = playback.endMs,
                            positionMs = position,
                            selectedKey = selectedSeries.key,
                            colors = colors,
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatClock(elapsed), style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                            Text(formatClock(duration), style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                        }
                        Slider(
                            value = elapsed.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                playing = false
                                position = (playback.startMs + it.toLong()).coerceIn(playback.startMs, playback.endMs)
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = stalker.accent,
                                activeTrackColor = stalker.accent,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { playing = false; position = playback.startMs }) {
                                Icon(Icons.Rounded.Replay, contentDescription = "Back to start", tint = stalker.muted)
                            }
                            Spacer(Modifier.weight(1f))
                            FilledIconButton(
                                onClick = {
                                    if (!playing && position >= playback.endMs) position = playback.startMs
                                    playing = !playing
                                },
                                modifier = Modifier.size(58.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = stalker.accent,
                                    contentColor = Color.Black,
                                ),
                            ) {
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    val index = PLAYBACK_SPEEDS.indexOf(speed).coerceAtLeast(0)
                                    speed = PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size]
                                },
                            ) {
                                Text(
                                    "${formatSpeed(speed)}×",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = stalker.accent,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playback.series.forEach { series ->
                        val active = series.key == selectedSeries.key
                        val color = colors[series.key] ?: stalker.accent
                        Surface(
                            onClick = { selectedKey = series.key },
                            shape = RoundedCornerShape(50),
                            color = if (active) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (active) color.copy(alpha = 0.5f) else Color.Transparent),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(color, CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    series.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) color else stalker.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                StalkerCard(accent = colors[selectedSeries.key]) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(colors[selectedSeries.key] ?: stalker.accent, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                selectedSeries.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                selectedSeries.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            PlaybackStat("Min", selectedSeries.min, selectedSeries.unit)
                            PlaybackStat("Average", selectedSeries.average, selectedSeries.unit)
                            PlaybackStat("Max", selectedSeries.max, selectedSeries.unit)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionHeader("Values at ${formatClock(elapsed)}")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playback.series.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { series ->
                                PlaybackValueTile(
                                    series = series,
                                    value = series.valueAt(position),
                                    color = colors[series.key] ?: stalker.accent,
                                    selected = series.key == selectedSeries.key,
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedKey = series.key },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Recordings store metric values. Playback uses the units saved in the CSV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            text = { Text("The CSV file and its metadata will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Delete", color = stalker.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaybackChart(
    series: List<PlaybackSeries>,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    selectedKey: String?,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val playhead = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Canvas(modifier) {
        val padding = 8f
        val chartWidth = size.width
        val chartHeight = size.height
        val duration = (endMs - startMs).coerceAtLeast(1L).toFloat()

        for (line in 0..3) {
            val y = padding + (chartHeight - padding * 2) * line / 3f
            drawLine(
                color = grid,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1f,
            )
        }

        fun xFor(timeMs: Long): Float = ((timeMs - startMs) / duration) * chartWidth

        fun yFor(value: Double, series: PlaybackSeries): Float {
            val normalized = ((value - series.rangeMin) / (series.rangeMax - series.rangeMin)).coerceIn(0.0, 1.0)
            return (chartHeight - padding - normalized * (chartHeight - padding * 2)).toFloat()
        }

        // Draw unselected series first, then the highlighted one.
        series.sortedBy { it.key == selectedKey }.forEach { item ->
            val color = colors[item.key] ?: Color.White
            val selected = item.key == selectedKey
            val points = item.points
            if (points.isEmpty()) return@forEach

            if (points.size == 1) {
                drawCircle(
                    color = color.copy(alpha = if (selected) 1f else 0.4f),
                    radius = if (selected) 4f else 2.5f,
                    center = Offset(xFor(points[0].timeMs), yFor(points[0].value, item)),
                )
                return@forEach
            }

            val step = (points.size / 600).coerceAtLeast(1)
            val path = Path()
            var first = true
            var index = 0
            while (index < points.size) {
                val point = points[index]
                val x = xFor(point.timeMs)
                val y = yFor(point.value, item)
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                index += step
            }
            val last = points.last()
            path.lineTo(xFor(last.timeMs), yFor(last.value, item))

            drawPath(
                path = path,
                color = color.copy(alpha = if (selected) 1f else 0.25f),
                style = Stroke(
                    width = if (selected) 3f else 1.6f,
                    cap = StrokeCap.Round,
                ),
            )

            if (selected) {
                val value = item.valueAt(positionMs) ?: return@forEach
                val x = xFor(positionMs.coerceIn(startMs, endMs))
                val y = yFor(value, item)
                drawCircle(color.copy(alpha = 0.25f), radius = 8f, center = Offset(x, y))
                drawCircle(color, radius = 4f, center = Offset(x, y))
            }
        }

        val playheadX = xFor(positionMs.coerceIn(startMs, endMs))
        drawLine(
            color = playhead,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, chartHeight),
            strokeWidth = 1.5f,
        )
    }
}

@Composable
private fun PlaybackStat(label: String, value: Double, unit: String) {
    val stalker = LocalStalkerColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(Locale.US), style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        Spacer(Modifier.height(4.dp))
        Text(
            formatNumber(value),
            style = NumericStyle.copy(fontSize = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
    }
}

@Composable
private fun PlaybackValueTile(
    series: PlaybackSeries,
    value: Double?,
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) color.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    series.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value?.let(::formatNumber) ?: "—",
                    style = NumericStyle.copy(fontSize = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (series.unit.isNotBlank()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        series.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

private fun formatNumber(value: Double): String {
    if (!value.isFinite()) return "—"
    val magnitude = abs(value)
    return when {
        magnitude >= 1000 -> String.format(Locale.US, "%.0f", value)
        magnitude >= 100 -> String.format(Locale.US, "%.1f", value)
        magnitude == magnitude.toLong().toDouble() -> value.toLong().toString()
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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

