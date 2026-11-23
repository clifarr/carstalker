package com.carstalker.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.BuildConfig
import com.carstalker.app.R
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.ThemeMode
import com.carstalker.app.ui.CarStalkerViewModel
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
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val benchmark by viewModel.benchmark.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    var newCustomPid by remember { mutableStateOf(false) }
    var editingCustomPid by remember { mutableStateOf<CustomPid?>(null) }
    var showTcpDialog by remember { mutableStateOf(false) }
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
            "Tune your rig",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
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
                        if (settings.pollIntervalMs == 0) "as fast as possible" else "${settings.pollIntervalMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.accent,
                    )
                }
                Slider(
                    value = settings.pollIntervalMs.toFloat(),
                    onValueChange = { viewModel.setPollInterval(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = stalker.accent,
                        activeTrackColor = stalker.accent,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Text(
                    "0 polls as fast as the adapter allows. The adapter handles one command at a time, so fast values like RPM are polled more often than slow ones like coolant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            SwitchRow(
                title = "Keep screen on",
                description = "Useful while watching live data on a dash mount.",
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Dashboard parameters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (settings.dashboardAuto) {
                            "Automatic · up to 10 for this car"
                        } else {
                            "${settings.dashboardPids.size} pinned"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
                OutlinedButton(onClick = viewModel::openPidPicker) { Text("Manage") }
            }
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

        SettingsSection("Vehicle profile") {
            Text(
                "Guided tests use the engine type to choose the correct interpretation. It is never inferred from a single live value.",
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
        }

        SettingsSection("Adapter health") {
            val ready = connection as? com.carstalker.app.service.ConnectionState.Ready
            if (ready == null) {
                Text(
                    "Connect an adapter to check its identity and measure round-trip speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            } else {
                Text(
                    ready.adapter.identifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    ready.adapter.protocolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
                if (ready.adapter.isCloneSuspect) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This adapter reports ELM327 v1.5, a version that never existed. It is almost certainly a clone; expect missing commands and dropped frames.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.danger,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::runBenchmark) { Text("Run benchmark") }
                    Spacer(Modifier.width(12.dp))
                    benchmark?.let {
                        Text(
                            String.format(
                                Locale.US,
                                "%.1f req/s · %.0f ms avg",
                                it.requestsPerSecond,
                                it.averageLatencyMs,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = stalker.accent,
                        )
                    }
                }
            }
        }

        SettingsSection("Connection defaults") {
            Text(
                "USB baud rate",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ChoiceRow(
                options = listOf(9600 to "9600", 38400 to "38400", 115200 to "115200"),
                selected = settings.usbBaud,
                onSelect = viewModel::setUsbBaud,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Wi-Fi endpoint",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${settings.tcpHost}:${settings.tcpPort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
                OutlinedButton(onClick = { showTcpDialog = true }) { Text("Edit") }
            }
        }

        SettingsSection("About") {
            AboutRow("Version", BuildConfig.VERSION_NAME)
            AboutRow("Release channel", BuildConfig.RELEASE_CHANNEL.replaceFirstChar { it.uppercase() })
            OutlinedButton(
                onClick = { showPrivacy = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Privacy policy") }
            Text(
                "CarStalker is open source under the MIT license.",
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

    if (showTcpDialog) {
        TcpDialog(
            host = settings.tcpHost,
            port = settings.tcpPort,
            onDismiss = { showTcpDialog = false },
            onSave = { host, port ->
                viewModel.setTcpEndpoint(host, port)
                showTcpDialog = false
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
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Surface(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (active) stalker.accent else Color.Transparent,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) Color.Black else stalker.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
            }
        }
    }
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
    val canSave = name.isNotBlank() && pidValid && formulaValid

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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it },
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
                    isError = pidHex.isNotEmpty() && !pidValid,
                    supportingText = { if (pidHex.isNotEmpty() && !pidValid) Text("Enter 01–FF") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Unit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formula,
                    onValueChange = { formula = it },
                    label = { Text("Formula") },
                    singleLine = true,
                    isError = formula.isNotEmpty() && !formulaValid,
                    supportingText = {
                        Text(
                            if (formula.isNotEmpty() && !formulaValid) {
                                "Use A, B, C, D, numbers and + - * / ( )"
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
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = max,
                        onValueChange = { max = it },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = decimals,
                        onValueChange = { decimals = it.filter(Char::isDigit).take(1) },
                        label = { Text("Dec") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(74.dp),
                    )
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

@Composable
private fun TcpDialog(
    host: String,
    port: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var hostValue by remember { mutableStateOf(host) }
    var portValue by remember { mutableStateOf(port.toString()) }
    val portNumber = portValue.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi endpoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hostValue,
                    onValueChange = { hostValue = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portValue,
                    onValueChange = { portValue = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(hostValue.trim(), portNumber ?: 35000) },
                enabled = hostValue.isNotBlank() && portNumber != null && portNumber in 1..65535,
                colors = ButtonDefaults.buttonColors(
                    containerColor = stalker.accent,
                    contentColor = Color.Black,
                ),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
