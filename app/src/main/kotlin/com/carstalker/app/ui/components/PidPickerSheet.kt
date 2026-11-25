package com.carstalker.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carstalker.app.data.AdapterVoltageParam
import com.carstalker.app.data.DEFAULT_DASHBOARD_SIZE
import com.carstalker.app.data.DashboardParams
import com.carstalker.app.data.toPid
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.obd.Pid
import java.util.Locale

/**
 * Full-height parameter picker.
 *
 * The list scrolls freely; only the small drag handle at the top dismisses the sheet,
 * so scrolling can never collapse it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PidPickerSheet(
    pinned: List<Int>,
    supported: Set<Int>,
    automatic: Boolean,
    dashboardName: String,
    maxPids: Int,
    customPids: List<com.carstalker.app.data.CustomPid>,
    onToggle: (Int) -> Unit,
    onUseRecommended: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val atLimit = pinned.size >= maxPids
    val groups = remember {
        val byCategory = DashboardParams.groupBy { it.category }
        CategoryOrder.mapNotNull { category -> byCategory[category]?.let { category to it } }
    }

    // Live drag offset: the sheet follows the finger and springs back if released early.
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetHeightPx = with(density) { maxHeight.toPx() * 0.92f }
            val dismissThresholdPx = with(density) { 72.dp.toPx() }
            val progress = (dragOffset / sheetHeightPx).coerceIn(0f, 1f)

            fun settle(target: Float, then: (() -> Unit)? = null) {
                scope.launch {
                    animate(
                        initialValue = dragOffset,
                        targetValue = target,
                        animationSpec = if (target == 0f) {
                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                        } else {
                            tween(durationMillis = 170)
                        },
                    ) { value, _ -> dragOffset = value }
                    then?.invoke()
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f * (1f - progress)))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .offset { IntOffset(0, dragOffset.roundToInt()) },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.statusBarsPadding()) {
                    // Only this handle drags the sheet, so the list below scrolls freely.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                },
                                onDragStopped = { velocity ->
                                    val flung = velocity > 1_200f
                                    if (dragOffset > dismissThresholdPx || flung) {
                                        settle(sheetHeightPx, onDismiss)
                                    } else {
                                        settle(0f)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "DASHBOARD PARAMETERS",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.accent,
                            )
                            Text(
                                dashboardName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "Pick the live values this dashboard shows.",
                                style = MaterialTheme.typography.bodySmall,
                                color = stalker.muted,
                            )
                            if (automatic) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "Automatic: up to $DEFAULT_DASHBOARD_SIZE values, chosen for this car.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.accent,
                                )
                            } else {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "${pinned.size} of $maxPids values selected.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (atLimit) stalker.warning else stalker.muted,
                                )
                                TextButton(
                                    onClick = onUseRecommended,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(
                                        "Use recommended selection",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = stalker.accent,
                                    )
                                }
                            }
                            if (atLimit) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "Maximum $maxPids values. Remove one before adding another.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.warning,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = stalker.muted,
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (customPids.isNotEmpty()) {
                            stickyHeader(key = "category-custom") {
                                CustomHeader(
                                    selected = customPids.count { it.enabled && it.id in pinned },
                                    total = customPids.size,
                                )
                            }
                            items(customPids, key = { "custom-${it.pidHex}" }) { custom ->
                                val pid = custom.toPid()
                                if (pid == null) {
                                    PidPickRow(
                                        pid = AdapterVoltageParam.copy(
                                            id = -2,
                                            name = custom.name,
                                            shortName = custom.shortName,
                                            unit = custom.unit,
                                        ),
                                        checked = false,
                                        supported = false,
                                        badge = "INVALID",
                                        enabled = false,
                                        onToggle = {},
                                    )
                                } else {
                                    PidPickRow(
                                        pid = pid,
                                        checked = pid.id in pinned,
                                        // Custom PIDs have no support bitmap; they are always selectable.
                                        supported = true,
                                        badge = "CUSTOM",
                                        enabled = !atLimit || pid.id in pinned,
                                        onToggle = { onToggle(pid.id) },
                                    )
                                }
                            }
                        }
                        groups.forEach { (category, pids) ->
                            stickyHeader(key = "category-${category.name}") {
                                CategoryHeader(
                                    category = category,
                                    selected = pids.count { it.id in pinned },
                                    total = pids.size,
                                )
                            }
                            items(pids, key = { it.id }) { pid ->
                                PidPickRow(
                                    pid = pid,
                                    checked = pid.id in pinned,
                                    // Adapter measurements have no PID to check against.
                                    supported = pid.id < 0 || supported.isEmpty() || pid.id in supported,
                                    badge = null,
                                    enabled = !atLimit || pid.id in pinned,
                                    onToggle = { onToggle(pid.id) },
                                )
                            }
                            item(key = "space-${category.name}") {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: Pid.Category, selected: Int, total: Int) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = stalker.accent,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$selected of $total",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
    }
}

@Composable
private fun PidPickRow(
    pid: Pid,
    checked: Boolean,
    supported: Boolean,
    badge: String?,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .background(
                if (checked) stalker.accent.copy(alpha = 0.06f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = stalker.accent,
                checkmarkColor = Color.Black,
                uncheckedColor = stalker.muted,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                pid.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pid.id < 0) "Adapter measurement" else "PID ${pid.hex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                    fontFamily = FontFamily.Monospace,
                )
                if (pid.unit.isNotBlank()) {
                    Text(
                        " · ${pid.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
            }
        }
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = stalker.accentSecondary,
                modifier = Modifier
                    .background(stalker.accentSecondary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (!supported) {
            Text(
                "N/A",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.warning,
                modifier = Modifier
                    .background(stalker.warning.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun CustomHeader(selected: Int, total: Int) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CUSTOM PIDS",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.accentSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$selected of $total",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
    }
}

/** Display order of the picker's category sections. */
private val CategoryOrder = listOf(
    Pid.Category.ENGINE,
    Pid.Category.DRIVETRAIN,
    Pid.Category.TEMPERATURE,
    Pid.Category.FUEL,
    Pid.Category.ELECTRICAL,
    Pid.Category.EMISSIONS,
    Pid.Category.OTHER,
)
