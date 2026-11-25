package com.carstalker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.BuildConfig
import com.carstalker.app.R
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.ThemeMode
import com.carstalker.app.data.dashboardParam
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.ChoiceRow
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.FuelType
import com.carstalker.core.obd.FormulaParser
import com.carstalker.core.obd.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: CarStalkerViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    var newCustomPid by remember { mutableStateOf(false) }
    var editingCustomPid by remember { mutableStateOf<CustomPid?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("SETTINGS", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
        Spacer(Modifier.height(3.dp))
        Text(
            "App preferences",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Preferences for the app and your car. Adapter and network setup live on the Connect screen.",
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )
        Spacer(Modifier.height(18.dp))

        SettingsSection("Units") {
            ChoiceRow(
                options = listOf(UnitSystem.METRIC to "Metric", UnitSystem.IMPERIAL to "Imperial"),
                selected = settings.unitSystem,
                onSelect = viewModel::setUnitSystem,
            )
        }

        SettingsSection("Appearance") {
            ChoiceRow(
                options = listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.DARK to "Dark",
                    ThemeMode.LIGHT to "Light",
                ),
                selected = settings.themeMode,
                onSelect = viewModel::setTheme,
            )
        }

        SettingsSection("Live data") {
            Column {
                var advancedPolling by remember { mutableStateOf(settings.pollIntervalMs > 0) }
                SwitchRow(
                    title = "Automatic polling",
                    description = "The adapter decides the fastest safe rate. Recommended.",
                    checked = settings.pollIntervalMs == 0,
                    onCheckedChange = { automatic ->
                        advancedPolling = !automatic
                        viewModel.setPollInterval(if (automatic) 0 else 100)
                    },
                )
                if (!advancedPolling && settings.pollIntervalMs == 0) {
                    Text(
                        "Fast values such as RPM are requested more often than slow values such as coolant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Minimum gap between polls",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${settings.pollIntervalMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = stalker.accent,
                        )
                    }
                    // 0 means automatic, which the switch above already owns. Keeping it out of
                    // the manual range stops the switch saying "off" while the slider says 0.
                    Slider(
                        value = settings.pollIntervalMs.coerceIn(50, 1000).toFloat(),
                        onValueChange = { viewModel.setPollInterval(it.roundToInt()) },
                        valueRange = 50f..1000f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = stalker.accent,
                            activeTrackColor = stalker.accent,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                    Text(
                        "The adapter handles one command at a time, so fast values like RPM are polled more often than slow ones like coolant. Turn Automatic polling back on to let the adapter pick the rate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
            }
            SwitchRow(
                title = "Keep screen on",
                description = "Useful while watching live data on a dash mount.",
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
            )
        }

        SettingsSection("Dashboards") {
            Text(
                "Create a dashboard with only the values you need. Only the values on the active dashboard are polled, so a single-value dashboard updates much faster than a full screen.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            settings.dashboards.forEach { dashboard ->
                val active = dashboard.id == settings.activeDashboardId
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = active,
                        onClick = { viewModel.selectDashboard(dashboard.id) },
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(
                            selectedColor = stalker.accent,
                            unselectedColor = stalker.muted,
                        ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            dashboard.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            buildString {
                                append(
                                    when {
                                        dashboard.automatic -> "Automatic selection"
                                        dashboard.pids.isEmpty() -> "Blank"
                                        dashboard.pids.size == 1 -> "1 value"
                                        else -> "${dashboard.pids.size} values"
                                    },
                                )
                                append(if (dashboard.showGauges) " · gauges" else " · no gauges")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = stalker.muted,
                        )
                    }
                    if (active) {
                        OutlinedButton(onClick = viewModel::openPidPicker) { Text("Edit") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::createBlankDashboard, modifier = Modifier.fillMaxWidth()) {
                Text("New blank dashboard")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { viewModel.duplicateDashboard(settings.activeDashboardId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Duplicate active dashboard")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Advanced: custom PIDs can be added to any dashboard from the parameter picker.",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
        }

        SettingsSection("Custom PIDs") {
            if (settings.customPids.isEmpty()) {
                Text(
                    "Add manufacturer-specific parameters with your own formula. A, B, C and D are the response data bytes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            } else {
                settings.customPids.forEach { custom ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).padding(vertical = 2.dp),
                        ) {
                            Text(
                                custom.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "PID ${custom.pidHex.uppercase(Locale.US)} · ${custom.unit.ifBlank { "no unit" }} · ${custom.formula}",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                                maxLines = 1,
                            )
                        }
                        Switch(
                            checked = custom.enabled,
                            onCheckedChange = { viewModel.setCustomPidEnabled(custom.pidHex, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = stalker.accent,
                            ),
                        )
                        IconButton(onClick = { editingCustomPid = custom }) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Edit ${custom.name}",
                                tint = stalker.muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { viewModel.removeCustomPid(custom.pidHex) }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete ${custom.name}",
                                tint = stalker.muted,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { newCustomPid = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add custom PID")
            }
        }

        SettingsSection("Your car") {
            val profile = settings.vehicleProfiles[settings.profileVehicleKey]
            // Keyed on the car only: re-keying on the saved nickname would trim spaces while typing.
            var vehicleName by remember(settings.profileVehicleKey) {
                mutableStateOf(profile?.nickname.orEmpty())
            }
            OutlinedTextField(
                value = vehicleName,
                onValueChange = {
                    vehicleName = it.take(32)
                    viewModel.setVehicleNickname(it)
                },
                label = { Text("Vehicle name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Shown on the dashboard when the car does not report a VIN. Stored against this car's ECU fingerprint, not another vehicle.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Fuel and aspiration describe the car you connect to. Guided tests use them to choose the correct interpretation; they are never inferred from a single live value.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Text("Fuel", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            ChoiceRow(
                options = listOf(
                    FuelType.UNKNOWN to "Not set",
                    FuelType.PETROL to "Petrol",
                    FuelType.DIESEL to "Diesel",
                ),
                selected = settings.fuelType,
                onSelect = viewModel::setFuelType,
            )
            Text("Aspiration", style = MaterialTheme.typography.labelMedium, color = stalker.accent)
            ChoiceRow(
                options = listOf(
                    Aspiration.UNKNOWN to "Not set",
                    Aspiration.NATURALLY_ASPIRATED to "Naturally aspirated",
                    Aspiration.FORCED_INDUCTION to "Turbo / supercharged",
                ),
                selected = settings.aspiration,
                onSelect = viewModel::setAspiration,
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = "Interpolate pedal readings",
                description = "Rescale the channel chosen by the pedal finder so a released pedal reads 0 % and a fully pressed pedal reads 100 %.",
                checked = settings.pedalCalibrationEnabled,
                onCheckedChange = viewModel::setPedalCalibrationEnabled,
            )
            val calibrated = settings.pedalCalibrations
                .filter { it.vehicleKey == settings.profileVehicleKey }
            if (calibrated.isNotEmpty()) {
                Text(
                    "Calibrated channels: " + calibrated.joinToString { calibration ->
                        val name = dashboardParam(calibration.pidId)?.shortName
                            ?: calibration.pidId.toString()
                        "$name (${calibration.releasedValue.toInt()}–${calibration.pressedValue.toInt()} %)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
        }

        SettingsSection("Reports") {
            OutlinedTextField(
                value = settings.shopName,
                onValueChange = viewModel::setShopName,
                label = { Text("Shop name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Printed at the top of exported PDF reports. Leave blank for a personal report.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            SwitchRow(
                title = "Include full VIN in reports",
                description = "Off by default. When off, exported PDF reports mask the VIN except its last four characters.",
                checked = settings.includeVinInReports,
                onCheckedChange = viewModel::setIncludeVinInReports,
            )
        }

        SettingsSection("About") {
            AboutRow("Version", BuildConfig.VERSION_NAME)
            AboutRow("Release channel", BuildConfig.RELEASE_CHANNEL.replaceFirstChar { it.uppercase() })
            OutlinedButton(
                onClick = { showPrivacy = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Privacy policy") }
            OutlinedButton(
                onClick = viewModel::exportConnectionDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export connection diagnostics") }
            OutlinedButton(
                onClick = { viewModel.openExternal("https://github.com/clifarr/carstalker") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Source code") }
            OutlinedButton(
                onClick = { viewModel.openExternal("https://github.com/clifarr/carstalker/issues/new") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Report an issue") }
            OutlinedButton(
                onClick = { viewModel.openExternal("https://github.com/clifarr/carstalker/releases") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Releases") }
            Text(
                "Glossary: OBD adapter = the ELM327 dongle; DTC = diagnostic trouble code; PID = parameter identifier; Mode 06 = the ECU's own monitor self-tests; readiness = whether those monitors have completed.",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            Text(
                "CarStalker is open source under the MIT license. Diagnostics export is local and contains no VIN or location.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
        }

        Spacer(Modifier.height(26.dp))
    }

    if (newCustomPid || editingCustomPid != null) {
        CustomPidDialog(
            initial = editingCustomPid,
            onDismiss = {
                newCustomPid = false
                editingCustomPid = null
            },
            onSave = { custom ->
                viewModel.addCustomPid(custom)
                newCustomPid = false
                editingCustomPid = null
            },
        )
    }

    if (showPrivacy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacy = false })
    }
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val policy = remember(context) {
        context.resources.openRawResource(R.raw.privacy_policy)
            .bufferedReader()
            .use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy policy") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    policy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    Spacer(Modifier.height(8.dp))
    StalkerCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = stalker.accent,
            ),
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val stalker = LocalStalkerColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = stalker.muted)
    }
}

@Composable
private fun CustomPidDialog(
    initial: CustomPid?,
    onDismiss: () -> Unit,
    onSave: (CustomPid) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var shortName by remember { mutableStateOf(initial?.shortName.orEmpty()) }
    var pidHex by remember { mutableStateOf(initial?.pidHex?.uppercase(Locale.US).orEmpty()) }
    var unit by remember { mutableStateOf(initial?.unit.orEmpty()) }
    var min by remember { mutableStateOf(initial?.min?.toString() ?: "0") }
    var max by remember { mutableStateOf(initial?.max?.toString() ?: "100") }
    var decimals by remember { mutableStateOf(initial?.decimals?.toString() ?: "1") }
    var formula by remember { mutableStateOf(initial?.formula.orEmpty()) }

    val pidValue = pidHex.toIntOrNull(16)
    val pidValid = pidValue != null && pidValue in 1..255
    val formulaValid = formula.isNotBlank() && FormulaParser.isValid(formula)
    val minValue = min.toDoubleOrNull()
    val maxValue = max.toDoubleOrNull()
    val rangeValid = minValue != null && maxValue != null && maxValue > minValue
    val decimalsValid = (decimals.toIntOrNull() ?: -1) in 0..3
    val unitValid = unit.length <= 12
    val collision = pidValue != null && pidValue in 1..255 &&
        com.carstalker.core.obd.PidCatalog.find(pidValue) != null &&
        initial?.pidHex?.toIntOrNull(16) != pidValue
    val canSave = name.isNotBlank() && pidValid && formulaValid && rangeValid && decimalsValid && unitValid && !collision

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New custom PID" else "Edit custom PID") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Mode 01 custom PID only. A, B, C and D are the first four response bytes. Extended or header-specific requests are not supported.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(12) },
                    label = { Text("Short name (tile)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pidHex,
                    onValueChange = { input ->
                        pidHex = input.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                    },
                    label = { Text("PID (hex)") },
                    singleLine = true,
                    isError = (pidHex.isNotEmpty() && !pidValid) || collision,
                    supportingText = {
                        when {
                            pidHex.isNotEmpty() && !pidValid -> Text("Enter 01–FF")
                            collision -> Text("This PID is already defined by a standard parameter")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it.take(12) },
                    label = { Text("Unit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formula,
                    onValueChange = { formula = it.take(80) },
                    label = { Text("Formula") },
                    singleLine = true,
                    isError = formula.isNotEmpty() && !formulaValid,
                    supportingText = {
                        Text(
                            if (formula.isNotEmpty() && !formulaValid) {
                                "Use A, B, C, D, numbers, + - * / % and parentheses. Division by zero or a non-finite result is rejected."
                            } else {
                                "Example: (A*256+B)/4"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = min,
                        onValueChange = { min = it },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = min.isNotBlank() && minValue == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = max,
                        onValueChange = { max = it },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = max.isNotBlank() && (maxValue == null || !rangeValid),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = decimals,
                        onValueChange = { decimals = it.filter(Char::isDigit).take(1) },
                        label = { Text("Dec") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = decimals.isNotBlank() && !decimalsValid,
                        modifier = Modifier.width(74.dp),
                    )
                }
                if (!rangeValid && min.isNotBlank() && max.isNotBlank()) {
                    Text("Max must be greater than min.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
                if (!decimalsValid && decimals.isNotBlank()) {
                    Text("Decimals must be 0–3.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
                if (!unitValid) {
                    Text("Unit must be 12 characters or fewer.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val id = pidValue ?: return@Button
                    onSave(
                        CustomPid(
                            pidHex = pidHex.uppercase(Locale.US).padStart(2, '0'),
                            name = name.trim(),
                            shortName = shortName.trim().ifBlank { name.trim().take(10) },
                            unit = unit.trim(),
                            min = min.toDoubleOrNull() ?: 0.0,
                            max = max.toDoubleOrNull() ?: 100.0,
                            decimals = decimals.toIntOrNull()?.coerceIn(0, 3) ?: 1,
                            formula = formula.trim(),
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = stalker.accent,
                    contentColor = Color.Black,
                ),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

