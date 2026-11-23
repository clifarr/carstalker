package com.carstalker.app.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.guided.DiagnosticAvailability
import java.util.Locale

data class TracePoint(val x: Float, val y: Float)

data class TraceSeries(
    val label: String,
    val color: Color,
    val points: List<TracePoint>,
)

/** Lightweight multi-series chart for guided captures and live stage traces. */
@Composable
fun TraceChart(
    series: List<TraceSeries>,
    modifier: Modifier = Modifier,
    unit: String = "",
    decimals: Int = 1,
    durationLabel: String? = null,
) {
    val stalker = LocalStalkerColors.current
    val all = series.flatMap { it.points }
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        if (all.isEmpty()) {
            Text(
                "No data yet",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val minY = all.minOf { it.y }
        val maxY = all.maxOf { it.y }
        val spanY = (maxY - minY).takeIf { it > 0.0001f } ?: 1f
        val gridColor = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.fillMaxSize()) {
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val y = size.height * fraction
                drawLine(
                    color = gridColor.copy(alpha = if (fraction == 0f || fraction == 1f) 0.18f else 0.08f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            fun drawSeries(item: TraceSeries) {
                if (item.points.size < 2) return
                val path = Path()
                item.points.forEachIndexed { index, point ->
                    val x = point.x.coerceIn(0f, 1f) * size.width
                    val y = size.height - ((point.y - minY) / spanY).coerceIn(0f, 1f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, item.color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
            series.forEach(::drawSeries)
        }
        Row(
            Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            series.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(item.color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = item.color)
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Text(
                "${formatChart(maxY, decimals)}${if (unit.isBlank()) "" else " $unit"}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            Text(
                "${formatChart(minY, decimals)}${if (unit.isBlank()) "" else " $unit"}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 s", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
            Text(
                durationLabel ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
fun AvailabilityPill(availability: DiagnosticAvailability) {
    val stalker = LocalStalkerColors.current
    val color = when (availability) {
        DiagnosticAvailability.READY -> stalker.success
        DiagnosticAvailability.LIMITED -> stalker.warning
        DiagnosticAvailability.NEEDS_COLD_ENGINE -> stalker.accentSecondary
        DiagnosticAvailability.NEEDS_WARM_ENGINE -> stalker.accent
        DiagnosticAvailability.NEEDS_SCAN -> stalker.accentSecondary
        DiagnosticAvailability.VEHICLE_UNSUPPORTED -> stalker.danger
        DiagnosticAvailability.REQUIRES_ENHANCED_PID -> stalker.warning
        DiagnosticAvailability.PLANNED -> stalker.muted
    }
    StatusPill(availability.label, color)
}

@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    val stalker = LocalStalkerColors.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun DemoControls(
    selected: com.carstalker.app.transport.demo.DemoScenario?,
    onSelect: (com.carstalker.app.transport.demo.DemoScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stalker = LocalStalkerColors.current
    Column(modifier.fillMaxWidth()) {
        Text("Demo controls", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
        Text(
            "Steer the simulated car so the guided stage can settle.",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.carstalker.app.transport.demo.DemoScenario.entries.take(4).forEach { scenario ->
                DemoChip(scenario, selected == scenario, onSelect, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.carstalker.app.transport.demo.DemoScenario.entries.drop(4).forEach { scenario ->
                DemoChip(scenario, selected == scenario, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DemoChip(
    scenario: com.carstalker.app.transport.demo.DemoScenario,
    active: Boolean,
    onSelect: (com.carstalker.app.transport.demo.DemoScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stalker = LocalStalkerColors.current
    Surface(
        onClick = { onSelect(scenario) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (active) stalker.accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            scenario.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) stalker.accent else stalker.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

private fun formatChart(value: Float, decimals: Int): String =
    if (decimals <= 0) Math.round(value).toString()
    else String.format(Locale.US, "%.${decimals}f", value)
