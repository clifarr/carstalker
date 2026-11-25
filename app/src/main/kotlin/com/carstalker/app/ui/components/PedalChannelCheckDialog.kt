package com.carstalker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.obd.PedalChannelCheck
import com.carstalker.core.obd.PedalChannelReading
import com.carstalker.core.obd.PidCatalog
import java.util.Locale

/**
 * Interactive finder for the throttle/pedal channels that actually work on this car.
 *
 * A short live capture is more useful than a per-model guess: generic PIDs are interpreted
 * differently by different ECUs, and many cars do not report a VIN at all.
 */
@Composable
fun PedalChannelCheckDialog(
    viewModel: CarStalkerViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.pedalCheck.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    fun close() {
        viewModel.dismissPedalChannelCheck()
        onDismiss()
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::close,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 640.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "THROTTLE & PEDAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.accent,
                            )
                            Text(
                                "Find the channel that follows your pedal",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = ::close) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = stalker.muted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "There is no reliable free per-model PID database. This samples the car for " +
                            "10 seconds while you press the pedal, so the recommendation comes from " +
                            "your car rather than a model guess.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                    Spacer(Modifier.height(16.dp))

                    val current = state
                    when {
                        current == null || (!current.running && current.result == null && current.error == null) -> {
                            Text(
                                "Release the pedal for the first two seconds, then press it fully and " +
                                    "hold until the check ends.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = viewModel::startPedalChannelCheck,
                                enabled = connection is ConnectionState.Ready,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = stalker.accent,
                                    contentColor = Color.Black,
                                ),
                            ) { Text("Start 10-second check") }
                            if (connection !is ConnectionState.Ready) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Connect the adapter to run this check.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.warning,
                                )
                            }
                        }

                        current.error != null -> {
                            Text(
                                current.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = stalker.warning,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = viewModel::startPedalChannelCheck,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = stalker.accent,
                                    contentColor = Color.Black,
                                ),
                            ) { Text("Try again") }
                        }

                        current.running -> {
                            Text(
                                if (current.progress < PedalChannelCheck.RELEASE_FRACTION.toFloat()) {
                                    "Release the pedal"
                                } else {
                                    "Press the pedal fully and hold"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = stalker.accent,
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { current.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = stalker.accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${(current.progress * 100).toInt()}% · keep the pedal steady",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                        }

                        current.result != null -> {
                            val result = current.result
                            Text(
                                result.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (result.readings.any { it.needsInterpolation }) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "The released and fully pressed readings are used to rescale the " +
                                        "channel to 0–100 %. You can turn this off in Settings → Your car.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = stalker.muted,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            result.readings
                                .sortedByDescending { it.range }
                                .forEach { reading ->
                                    PedalReadingRow(
                                        reading = reading,
                                        recommended = reading.pidId in result.recommended,
                                    )
                                }
                            if (result.recommended.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                val best = result.recommended.first()
                                val bestName = PidCatalog.find(best)?.shortName ?: "Pedal"
                                Button(
                                    onClick = {
                                        viewModel.usePedalChannel(best)
                                        close()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = stalker.accent,
                                        contentColor = Color.Black,
                                    ),
                                ) { Text("Use $bestName on this dashboard") }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = viewModel::startPedalChannelCheck,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Run again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PedalReadingRow(reading: PedalChannelReading, recommended: Boolean) {
    val stalker = LocalStalkerColors.current
    val isPedal = reading.pidId in PedalChannelCheck.ACCELERATOR_PIDS
    val color = when {
        isPedal && reading.moved -> stalker.accentSecondary
        reading.moved -> stalker.accent
        else -> stalker.muted
    }
    val label = when {
        isPedal && reading.moved -> "PEDAL"
        reading.moved -> "THROTTLE"
        else -> "NO MOVEMENT"
    }
    val hex = PidCatalog.find(reading.pidId)?.hex ?: String.format(Locale.US, "%02X", reading.pidId)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (recommended) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (recommended) color.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    reading.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "PID $hex · ${reading.min.toInt()}–${reading.max.toInt()} % " +
                        "(range ${reading.range.toInt()} %)",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
                if (reading.needsInterpolation) {
                    Text(
                        "Released ${reading.releasedValue.toInt()} % · pressed " +
                            "${reading.pressedValue.toInt()} % → 0–100 %",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { (reading.range / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(text = label, color = color)
        }
    }
}
