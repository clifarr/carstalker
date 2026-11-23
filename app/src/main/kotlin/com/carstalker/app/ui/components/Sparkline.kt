package com.carstalker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Lightweight live sparkline with a soft gradient fill. */
@Composable
fun Sparkline(
    samples: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (samples.isEmpty()) return@Canvas
        val maxValue = samples.maxOrNull() ?: 0f
        val minValue = samples.minOrNull() ?: 0f
        val span = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        val stepX = if (samples.size > 1) size.width / (samples.size - 1) else size.width
        val padding = size.height * 0.12f
        val usableHeight = size.height - padding * 2

        fun point(index: Int, value: Float): Offset {
            val x = if (samples.size == 1) size.width / 2f else stepX * index
            val normalized = (value - minValue) / span
            val y = size.height - padding - normalized * usableHeight
            return Offset(x, y)
        }

        val linePath = Path()
        samples.forEachIndexed { index, value ->
            val p = point(index, value)
            if (index == 0) linePath.moveTo(p.x, p.y) else linePath.lineTo(p.x, p.y)
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.0f)),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawPath(
            path = linePath,
            color = accent,
            style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val last = point(samples.lastIndex, samples.last())
        drawCircle(color = accent, radius = 3.2f, center = last)
        drawCircle(color = accent.copy(alpha = 0.25f), radius = 7f, center = last)
    }
}
