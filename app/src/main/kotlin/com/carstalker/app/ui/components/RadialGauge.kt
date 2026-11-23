package com.carstalker.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.NumericStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Instrument-cluster gauge: 270° sweep, major/minor ticks, redline band and an animated needle.
 */
@Composable
fun RadialGauge(
    value: Double,
    min: Double,
    max: Double,
    label: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    redline: Float = 0.86f,
    compact: Boolean = false,
) {
    val stalker = LocalStalkerColors.current
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val fraction = ((value - min) / range).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "gauge",
    )

    val startAngle = 135f
    val sweep = 270f

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().padding(if (compact) 6.dp else 10.dp)) {
            val diameter = min(size.width, size.height)
            val stroke = diameter * 0.045f
            val radius = (diameter - stroke) / 2f
            val topLeft = Offset((size.width - diameter) / 2f + stroke / 2f, (size.height - diameter) / 2f + stroke / 2f)
            val arcSize = Size(diameter - stroke, diameter - stroke)
            val center = Offset(size.width / 2f, size.height / 2f)

            // Track
            drawArc(
                color = stalker.grid,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Redline band
            drawArc(
                color = stalker.danger.copy(alpha = 0.55f),
                startAngle = startAngle + sweep * redline,
                sweepAngle = sweep * (1f - redline),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke * 0.55f, cap = StrokeCap.Round),
            )

            // Glow behind progress
            if (animated > 0.01f) {
                drawArc(
                    color = accent.copy(alpha = 0.18f),
                    startAngle = startAngle,
                    sweepAngle = sweep * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to accent.copy(alpha = 0.35f),
                        0.5f to accent,
                        1f to accent,
                        center = center,
                    ),
                    startAngle = startAngle,
                    sweepAngle = sweep * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // Ticks
            val tickCount = if (compact) 20 else 40
            for (i in 0..tickCount) {
                val angle = Math.toRadians((startAngle + sweep * i / tickCount).toDouble())
                val major = i % (tickCount / 8) == 0
                val outer = radius - stroke * 0.9f
                val inner = outer - if (major) stroke * 1.1f else stroke * 0.55f
                drawLine(
                    color = if (major) stalker.muted.copy(alpha = 0.85f) else stalker.muted.copy(alpha = 0.35f),
                    start = Offset(
                        center.x + (cos(angle) * outer).toFloat(),
                        center.y + (sin(angle) * outer).toFloat(),
                    ),
                    end = Offset(
                        center.x + (cos(angle) * inner).toFloat(),
                        center.y + (sin(angle) * inner).toFloat(),
                    ),
                    strokeWidth = if (major) stroke * 0.28f else stroke * 0.16f,
                    cap = StrokeCap.Round,
                )
            }

            // Needle
            val needleAngle = Math.toRadians((startAngle + sweep * animated).toDouble())
            val needleStart = radius * 0.22f
            val needleEnd = radius * 0.78f
            drawLine(
                color = accent,
                start = Offset(
                    center.x + (cos(needleAngle) * needleStart).toFloat(),
                    center.y + (sin(needleAngle) * needleStart).toFloat(),
                ),
                end = Offset(
                    center.x + (cos(needleAngle) * needleEnd).toFloat(),
                    center.y + (sin(needleAngle) * needleEnd).toFloat(),
                ),
                strokeWidth = stroke * 0.42f,
                cap = StrokeCap.Round,
            )
            drawCircle(color = accent, radius = stroke * 0.55f, center = center)
            drawCircle(color = stalker.grid, radius = stroke * 0.22f, center = center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatValue(value, decimals),
                style = NumericStyle.copy(
                    fontSize = if (compact) 26.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            if (!compact) {
                Text(
                    text = label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

fun formatValue(value: Double, decimals: Int): String {
    val safe = if (value.isFinite()) value else 0.0
    return if (decimals <= 0) {
        Math.round(safe).toString()
    } else {
        String.format(Locale.US, "%.${decimals}f", safe)
    }
}
