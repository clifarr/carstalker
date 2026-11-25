package com.carstalker.app.ui.components

import android.annotation.SuppressLint

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.NumericStyle

/** Live-data tile with a large readout and a sparkline. */
@SuppressLint("ModifierParameter")
@Composable
fun StatTile(
    title: String,
    value: String,
    unit: String,
    samples: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    armed: Boolean = false,
    dropTarget: Boolean = false,
    dragging: Boolean = false,
    dragTranslation: Offset = Offset.Zero,
    shift: Offset = Offset.Zero,
    contentModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val dotColor = if (stale) stalker.warning else accent
    val borderColor = when {
        armed -> stalker.accent
        dropTarget -> stalker.accentSecondary
        else -> MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (armed || dropTarget) 2.dp else 1.dp
    // Cards next to the insertion point glide aside; the held card follows the finger exactly.
    val animatedShift by animateOffsetAsState(
        targetValue = shift,
        animationSpec = tween(durationMillis = 140),
        label = "tileShift",
    )
    val translation = animatedShift + dragTranslation
    Surface(
        onClick = onClick,
        modifier = modifier
            .zIndex(if (dragging) 2f else 0f)
            .graphicsLayer {
                translationX = translation.x
                translationY = translation.y
                scaleX = if (dragging) 1.03f else 1f
                scaleY = if (dragging) 1.03f else 1f
            }
            .semantics {
                contentDescription = buildString {
                    append("$title: $value $unit, ${samples.size} recent samples")
                    if (stale) append(", not updating")
                    if (armed) append(", held for editing")
                }
            },
        shape = RoundedCornerShape(22.dp),
        color = if (armed) stalker.accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
        shadowElevation = if (dragging) 10.dp else 0.dp,
    ) {
        Column(Modifier.then(contentModifier).padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (stale) {
                    Text(
                        text = "stale",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.warning,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = NumericStyle.copy(fontSize = 27.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (stale) 0.55f else 1f),
                    maxLines = 1,
                )
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(
                samples = samples,
                accent = if (stale) stalker.muted else accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            )
        }
    }
}

/** Compact stat used inside the hero cluster card. */
@Composable
fun MiniStat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val stalker = LocalStalkerColors.current
    Column(modifier) {
        Text(
            text = label.uppercase(java.util.Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = NumericStyle.copy(fontSize = 18.sp),
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
