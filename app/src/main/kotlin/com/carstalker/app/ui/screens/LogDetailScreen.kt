package com.carstalker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstalker.app.data.LogPlaybackParser
import com.carstalker.app.data.LogSession
import com.carstalker.app.data.PlaybackData
import com.carstalker.app.data.PlaybackPoint
import com.carstalker.app.data.PlaybackSeries
import com.carstalker.app.ui.components.ChartWindow
import com.carstalker.app.ui.components.EmptyState
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.NumericStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var compareAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var window by remember { mutableStateOf(ChartWindow.full(0L, 1L)) }

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
        window = ChartWindow.full(loaded.startMs, loaded.endMs)
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
                if (session.meta.isDemo) append(" · demo data")
                append(
                    if (session.meta.kind == "guided") {
                        " · Guided · ${session.meta.label ?: "capture"}"
                    } else {
                        " · Drive recording"
                    },
                )
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
                val fullWindow = remember(playback.startMs, playback.endMs) {
                    ChartWindow.full(playback.startMs, playback.endMs)
                }
                val zoomed = window.isZoomed(playback.startMs, playback.endMs)

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        PlaybackChart(
                            series = if (compareAll) playback.series else listOf(selectedSeries),
                            window = window,
                            positionMs = position,
                            selectedKey = selectedSeries.key,
                            colors = colors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .semantics { contentDescription = "Playback chart" }
                                .pointerInput(playback.startMs, playback.endMs) {
                                    detectChartTransformGestures(
                                        isZoomed = { window.isZoomed(playback.startMs, playback.endMs) },
                                        onTransform = { centroidFraction, panFraction, zoom ->
                                            window = window.transformed(
                                                fullStartMs = playback.startMs,
                                                fullEndMs = playback.endMs,
                                                centroidFraction = centroidFraction,
                                                panFraction = panFraction,
                                                zoom = zoom,
                                            )
                                        },
                                    )
                                }
                                .pointerInput(playback.startMs, playback.endMs) {
                                    detectTapGestures(
                                        onDoubleTap = { window = fullWindow },
                                    )
                                },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatClock((window.startMs - playback.startMs).coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                            TextButton(
                                onClick = { compareAll = !compareAll },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    if (compareAll) "Single series" else "Compare all",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.accentSecondary,
                                )
                            }
                            Text(
                                formatClock((window.endMs - playback.startMs).coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                        }
                        if (compareAll) {
                            Text(
                                "Each series is scaled to the zoomed window; units differ. Select one series for an exact reading.",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.warning,
                            )
                        }
                        if (zoomed) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Zoomed · ${formatClock(window.durationMs)} visible",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.accentSecondary,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { window = fullWindow }) {
                                    Text("Reset zoom", color = stalker.accent)
                                }
                            }
                        }
                        Slider(
                            value = elapsed.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                val next = (playback.startMs + it.toLong()).coerceIn(playback.startMs, playback.endMs)
                                position = next
                                // Keep the playhead visible when the slider moves outside the window.
                                if (next < window.startMs || next > window.endMs) {
                                    window = window.centeredOn(next, playback.startMs, playback.endMs)
                                }
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = stalker.accent,
                                activeTrackColor = stalker.accent,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                        Text(
                            "Pinch the chart to zoom, drag it to pan, double-tap to reset.",
                            style = MaterialTheme.typography.labelSmall,
                            color = stalker.muted,
                        )
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
            text = { Text("The CSV file and its metadata will be removed from this device. This cannot be undone.") },
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
    window: ChartWindow,
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
        val duration = window.durationMs.toFloat()
        val usableHeight = chartHeight - padding * 2

        for (line in 0..3) {
            val y = padding + usableHeight * line / 3f
            drawLine(
                color = grid,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1f,
            )
        }

        fun xFor(timeMs: Long): Float = ((timeMs - window.startMs) / duration) * chartWidth

        // Draw unselected series first, then the highlighted one.
        series.sortedBy { it.key == selectedKey }.forEach { item ->
            val color = colors[item.key] ?: Color.White
            val selected = item.key == selectedKey
            val points = item.points
            if (points.isEmpty()) return@forEach

            val firstVisible = points.firstAtOrAfter(window.startMs)
            val lastVisible = points.lastAtOrBefore(window.endMs)
            if (firstVisible > lastVisible) return@forEach

            // The vertical scale follows the visible window, so zooming in reveals detail
            // instead of flattening the line against the whole-recording range.
            var low = Double.MAX_VALUE
            var high = -Double.MAX_VALUE
            for (index in firstVisible..lastVisible) {
                val value = points[index].value
                if (value < low) low = value
                if (value > high) high = value
            }
            // A flat line is centred instead of being pinned to the bottom edge.
            val range = high - low
            val base = if (range > 1e-9) low else low - 0.5
            val span = if (range > 1e-9) range else 1.0

            fun yFor(value: Double): Float {
                val normalized = ((value - base) / span).coerceIn(0.0, 1.0)
                return (chartHeight - padding - normalized * usableHeight).toFloat()
            }

            // One neighbour on each side keeps the line continuous at the window edges.
            val drawFirst = (firstVisible - 1).coerceAtLeast(0)
            val drawLast = (lastVisible + 1).coerceAtMost(points.lastIndex)
            clipRect(0f, 0f, chartWidth, chartHeight) {
                if (drawFirst == drawLast) {
                    drawCircle(
                        color = color.copy(alpha = if (selected) 1f else 0.4f),
                        radius = if (selected) 4f else 2.5f,
                        center = Offset(xFor(points[drawFirst].timeMs), yFor(points[drawFirst].value)),
                    )
                } else {
                    val step = ((drawLast - drawFirst + 1) / 600).coerceAtLeast(1)
                    val path = Path()
                    var first = true
                    var index = drawFirst
                    while (index <= drawLast) {
                        val point = points[index]
                        val x = xFor(point.timeMs)
                        val y = yFor(point.value)
                        if (first) {
                            path.moveTo(x, y)
                            first = false
                        } else {
                            path.lineTo(x, y)
                        }
                        index += step
                    }
                    val last = points[drawLast]
                    path.lineTo(xFor(last.timeMs), yFor(last.value))

                    drawPath(
                        path = path,
                        color = color.copy(alpha = if (selected) 1f else 0.25f),
                        style = Stroke(
                            width = if (selected) 3f else 1.6f,
                            cap = StrokeCap.Round,
                        ),
                    )
                }

                if (selected && positionMs in window.startMs..window.endMs) {
                    val value = item.valueAt(positionMs) ?: return@clipRect
                    val x = xFor(positionMs)
                    val y = yFor(value)
                    drawCircle(color.copy(alpha = 0.25f), radius = 8f, center = Offset(x, y))
                    drawCircle(color, radius = 4f, center = Offset(x, y))
                }
            }
        }

        if (positionMs in window.startMs..window.endMs) {
            val playheadX = xFor(positionMs)
            drawLine(
                color = playhead,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, chartHeight),
                strokeWidth = 1.5f,
            )
        }
    }
}

/**
 * Like [androidx.compose.foundation.gestures.detectTransformGestures], but the page can still
 * scroll vertically until the chart is zoomed: a single finger is only claimed once the chart
 * has something to pan, while two fingers always pinch.
 */
private suspend fun PointerInputScope.detectChartTransformGestures(
    isZoomed: () -> Boolean,
    onTransform: (centroidFraction: Float, panFraction: Float, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pastTouchSlop = false
        var multiTouch = false
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        do {
            val event = awaitPointerEvent()
            multiTouch = multiTouch || event.changes.count { it.pressed } >= 2
            if (!multiTouch && !isZoomed()) continue

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastTouchSlop) {
                accumulatedZoom *= zoomChange
                accumulatedPan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                if (zoomMotion > viewConfiguration.touchSlop ||
                    accumulatedPan.getDistance() > viewConfiguration.touchSlop
                ) {
                    pastTouchSlop = true
                }
            }
            if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (size.width > 0) {
                    onTransform(centroid.x / size.width, panChange.x / size.width, zoomChange)
                }
                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Index of the first point at or after [timeMs], or [size] when there is none. */
private fun List<PlaybackPoint>.firstAtOrAfter(timeMs: Long): Int {
    var low = 0
    var high = lastIndex
    var result = size
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs >= timeMs) {
            result = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return result
}

/** Index of the last point at or before [timeMs], or -1 when there is none. */
private fun List<PlaybackPoint>.lastAtOrBefore(timeMs: Long): Int {
    var low = 0
    var high = lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs <= timeMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
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

