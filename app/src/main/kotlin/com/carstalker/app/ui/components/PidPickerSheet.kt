package com.carstalker.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.carstalker.app.data.AdapterVoltageParam
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.DEFAULT_DASHBOARD_SIZE
import com.carstalker.app.data.DashboardParams
import com.carstalker.app.data.toPid
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.util.Hex
import java.util.Locale

/**
 * Full-height parameter picker.
 *
 * Uses the same ModalBottomSheet inset handling as the Replace list, so the last row stays
 * above the system navigation bar in portrait and landscape without a fixed empty gap.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PidPickerSheet(
    pinned: List<Int>,
    supported: Set<Int>,
    otherSupported: List<Int>,
    automatic: Boolean,
    dashboardName: String,
    maxPids: Int,
    customPids: List<CustomPid>,
    calibratedPids: Set<Int> = emptySet(),
    onToggle: (Int) -> Unit,
    onUseRecommended: () -> Unit,
    onFindPedalChannels: () -> Unit,
    onAddCustomPid: (CustomPid) -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var addingPid by remember { mutableStateOf<Int?>(null) }
    val atLimit = pinned.size >= maxPids
    val groups = remember {
        val byCategory = DashboardParams.groupBy { it.category }
        CategoryOrder.mapNotNull { category -> byCategory[category]?.let { category to it } }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // Let the sheet surface fill the navigation-bar area with the app theme colour;
        // the content is inset separately so it stays above the system bar.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
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
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = onFindPedalChannels,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Find my throttle & pedal channels", color = stalker.accent)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Samples the car while you press the pedal and recommends the values that actually move.",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                            if (calibratedPids.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Calibrated for this car: " + calibratedPids
                                        .mapNotNull { PidCatalog.find(it)?.shortName }
                                        .joinToString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.accentSecondary,
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
                        contentPadding = PaddingValues(bottom = 24.dp),
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
                                    badge = if (pid.id in calibratedPids) "CALIBRATED" else null,
                                    enabled = !atLimit || pid.id in pinned,
                                    onToggle = { onToggle(pid.id) },
                                )
                            }
                            item(key = "space-${category.name}") {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (otherSupported.isNotEmpty()) {
                            item(key = "other-supported") {
                                OtherSupportedPids(
                                    ids = otherSupported,
                                    onPickPid = { addingPid = it },
                                )
                            }
                        }
                    }
                }
        }

    addingPid?.let { pid ->
        CustomPidDialog(
            initial = null,
            presetPidHex = Hex.toHex(pid),
            onDismiss = { addingPid = null },
            onSave = { custom ->
                onAddCustomPid(custom)
                addingPid = null
            },
        )
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

/**
 * Standard Mode 01 parameters the car advertises but the app has no definition for. Tapping one
 * opens the custom-PID dialog with the code fixed, so the value can still reach a dashboard once
 * the user supplies its meaning and formula.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherSupportedPids(ids: List<Int>, onPickPid: (Int) -> Unit) {
    val stalker = LocalStalkerColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OTHER SUPPORTED PIDS",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${ids.size}",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (ids.size == 1) {
                "Your car also reports this standard OBD-II parameter. CarStalker has no definition for it yet: tap it to add your own."
            } else {
                "Your car also reports these standard OBD-II parameters. CarStalker has no definitions for them yet: tap one to add your own."
            },
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ids.forEach { id ->
                Surface(
                    onClick = { onPickPid(id) },
                    shape = RoundedCornerShape(6.dp),
                    color = stalker.accent.copy(alpha = 0.12f),
                ) {
                    Text(
                        "0x${Hex.toHex(id)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.accent,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "A custom PID needs a formula from you: the app cannot guess what the value means.",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
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
