package com.carstalker.app.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.NumericStyle

/** Live-data tile with a large readout and a sparkline. */
@Composable
fun StatTile(
    title: String,
    value: String,
    unit: String,
    samples: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = NumericStyle.copy(fontSize = 27.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
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
                accent = accent,
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
