package com.carstalker.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.data.TransportKind
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.BrandMark
import com.carstalker.app.ui.components.ChoiceRow
import com.carstalker.app.ui.components.SignalBars
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.theme.Carbon
import com.carstalker.app.data.lastVehicleLabel
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.transport.classic.ClassicDevice
import com.carstalker.app.transport.ble.DiscoveredDevice
import com.carstalker.core.elm.AdapterBenchmark

@Composable
fun ConnectScreen(
    viewModel: CarStalkerViewModel,
    onOpenDashboard: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val benchmark by viewModel.benchmark.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Ready
    val lastVehicle = settings.lastVehicleLabel()
    var renameLastVehicle by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val devices by viewModel.bleDevices.collectAsStateWithLifecycle()
    val scanning by viewModel.bleScanning.collectAsStateWithLifecycle()
    val classicDevices by viewModel.classicDevices.collectAsStateWithLifecycle()
    val classicDiscovering by viewModel.classicDiscovering.collectAsStateWithLifecycle()
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val stalker = LocalStalkerColors.current

    var transportOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val transport = transportOverride
        ?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() }
        ?: settings.lastTransport
    var permissionGranted by remember { mutableStateOf(hasBlePermissions(context)) }
    var permissionRequested by remember { mutableStateOf(false) }
    var hostOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var portOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val tcpHost = hostOverride ?: settings.tcpHost
    val tcpPort = portOverride ?: settings.tcpPort.toString()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionRequested = true
        permissionGranted = result.values.all { it }
    }

    val permanentlyDenied = permissionRequested && !permissionGranted &&
        blePermissions().any { permission ->
            activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

    LaunchedEffect(transport) {
        if (transport == TransportKind.BLE) {
            viewModel.refreshClassicDevices()
        } else {
            viewModel.stopBleScan()
            viewModel.stopClassicDiscovery()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopBleScan()
            viewModel.stopClassicDiscovery()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshUsbDevices()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to stalker.accent.copy(alpha = 0.10f),
                    0.35f to Color.Transparent,
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "CarStalker",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Your car, decoded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = stalker.muted,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                if (connected) "Adapter connected" else "Connect an OBD-II adapter",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (connected) {
                    "Live data is streaming. Open the dashboard, or disconnect to choose another adapter."
                } else {
                    "Live data, diagnostics and drive recordings."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = stalker.muted,
            )

            Spacer(Modifier.height(22.dp))
            if (connected) {
                ConnectedCard(
                    ready = connection as ConnectionState.Ready,
                    benchmark = benchmark,
                    onRunBenchmark = viewModel::runBenchmark,
                    onOpenDashboard = onOpenDashboard,
                    onDisconnect = viewModel::disconnect,
                )
                Spacer(Modifier.height(24.dp))
            } else {
            TransportSelector(transport) { selected ->
                transportOverride = selected.name
                viewModel.setTransport(selected)
            }

            Spacer(Modifier.height(20.dp))

            when (transport) {
                TransportKind.BLE -> BluetoothPane(
                    bleDevices = devices,
                    bleScanning = scanning,
                    classicDevices = classicDevices,
                    classicDiscovering = classicDiscovering,
                    permissionGranted = permissionGranted,
                    permanentlyDenied = permanentlyDenied,
                    onRequestPermission = { permissionLauncher.launch(blePermissions()) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                    onScanBle = {
                        if (permissionGranted) viewModel.startBleScan()
                        else permissionLauncher.launch(blePermissions())
                    },
                    onStopBleScan = viewModel::stopBleScan,
                    onConnectBle = viewModel::connectBle,
                    onScanClassic = {
                        if (permissionGranted) viewModel.startClassicDiscovery()
                        else permissionLauncher.launch(blePermissions())
                    },
                    onStopClassicScan = viewModel::stopClassicDiscovery,
                    onConnectClassic = viewModel::connectClassic,
                )

                TransportKind.USB -> UsbPane(
                    devices = usbDevices,
                    baud = settings.usbBaud,
                    onBaudChange = viewModel::setUsbBaud,
                    onRefresh = viewModel::refreshUsbDevices,
                    onConnect = { device -> viewModel.connectUsb(device, settings.usbBaud) },
                )

                TransportKind.TCP -> TcpPane(
                    host = tcpHost,
                    port = tcpPort,
                    onHostChange = { hostOverride = it },
                    onPortChange = { portOverride = it.filter(Char::isDigit) },
                    onConnect = {
                        val port = tcpPort.toIntOrNull() ?: settings.tcpPort
                        viewModel.connectTcp(tcpHost.trim(), port)
                    },
                )

                TransportKind.DEMO -> DemoPane(onStart = viewModel::connectDemo)
            }

            val error = connection as? ConnectionState.Error
            if (error != null) {
                Spacer(Modifier.height(18.dp))
                StalkerCard(accent = stalker.danger) {
                    Column {
                        Text(
                            "Could not connect",
                            style = MaterialTheme.typography.titleMedium,
                            color = stalker.danger,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            connectionRemedy(transport),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        var showDetail by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { showDetail = !showDetail },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text(
                                if (showDetail) "Hide technical detail" else "Technical detail",
                                style = MaterialTheme.typography.labelMedium,
                                color = stalker.muted,
                            )
                        }
                        if (showDetail) {
                            Text(
                                error.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    when (transport) {
                                        TransportKind.DEMO -> viewModel.connectDemo()
                                        TransportKind.TCP -> {
                                            val port = tcpPort.toIntOrNull() ?: settings.tcpPort
                                            viewModel.connectTcp(tcpHost.trim(), port)
                                        }
                                        TransportKind.USB -> viewModel.refreshUsbDevices()
                                        TransportKind.BLE -> {
                                            if (permissionGranted) viewModel.startBleScan()
                                            else permissionLauncher.launch(blePermissions())
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Carbon),
                            ) { Text("Retry") }
                            OutlinedButton(onClick = { transportOverride = null }) { Text("Change adapter") }
                        }
                    }
                }
            }

            if (lastVehicle != null && connection !is ConnectionState.Ready) {
                Spacer(Modifier.height(18.dp))
                StalkerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Last vehicle",
                                style = MaterialTheme.typography.labelSmall,
                                color = stalker.muted,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                lastVehicle.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            lastVehicle.detail?.let { detail ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.muted,
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                renameText = lastVehicle.name
                                renameLastVehicle = true
                            },
                        ) { Text("Rename", color = stalker.accentSecondary) }
                    }
                }
            }

                Spacer(Modifier.height(24.dp))
            }
            }
        }

        val busy = connection is ConnectionState.Connecting || connection is ConnectionState.Initializing
        AnimatedVisibility(visible = busy, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Carbon.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        Modifier.padding(horizontal = 34.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = stalker.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(46.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            when (val state = connection) {
                                is ConnectionState.Initializing -> state.stage
                                else -> "Connecting to adapter"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Keep the ignition on",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                    }
                }
            }
        }
    }

    if (renameLastVehicle) {
        AlertDialog(
            onDismissRequest = { renameLastVehicle = false },
            title = { Text("Name this car") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(32) },
                    label = { Text("Vehicle name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setVehicleNickname(renameText)
                        renameLastVehicle = false
                    },
                ) { Text("Save", color = stalker.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameLastVehicle = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectedCard(
    ready: ConnectionState.Ready,
    benchmark: AdapterBenchmark?,
    onRunBenchmark: () -> Unit,
    onOpenDashboard: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    StalkerCard(accent = stalker.success) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("Connected", stalker.success)
                Spacer(Modifier.width(10.dp))
                Text(
                    ready.adapter.identifier,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${ready.adapter.protocolName} · ${ready.transportName}",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            ready.adapter.voltage?.let {
                Text(
                    "Adapter voltage ${String.format(java.util.Locale.US, "%.1f", it)} V",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            if (ready.adapter.isCloneSuspect) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This adapter reports ELM327 v1.5, a version that never existed. It is almost certainly a clone; expect missing commands and dropped frames.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.danger,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Adapter health",
                style = MaterialTheme.typography.labelMedium,
                color = stalker.accent,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRunBenchmark) { Text("Run benchmark") }
                Spacer(Modifier.width(12.dp))
                benchmark?.let {
                    Text(
                        String.format(
                            java.util.Locale.US,
                            "%.1f req/s · %.0f ms avg",
                            it.requestsPerSecond,
                            it.averageLatencyMs,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = stalker.accent,
                    )
                }
            }
            if (benchmark == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Measures round-trip speed over the current adapter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenDashboard,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Carbon),
            ) { Text("Open dashboard") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Disconnect to choose a different adapter.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
        }
    }
}

@Composable
private fun TransportSelector(selected: TransportKind, onSelect: (TransportKind) -> Unit) {
    val stalker = LocalStalkerColors.current
    val items = listOf(
        Triple(TransportKind.BLE, "Bluetooth", Icons.Rounded.Bluetooth),
        Triple(TransportKind.USB, "USB", Icons.Rounded.Usb),
        Triple(TransportKind.TCP, "Wi-Fi", Icons.Rounded.Wifi),
        Triple(TransportKind.DEMO, "Demo", null),
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Narrow phones get two rows so long labels like "Bluetooth" always have room.
        val useTwoRows = maxWidth < 480.dp
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (useTwoRows) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowItems.forEach { (kind, label, icon) ->
                            TransportOption(
                                label = label,
                                icon = icon,
                                selected = kind == selected,
                                accent = stalker.accent,
                                muted = stalker.muted,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(kind) },
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items.forEach { (kind, label, icon) ->
                        TransportOption(
                            label = label,
                            icon = icon,
                            selected = kind == selected,
                            accent = stalker.accent,
                            muted = stalker.muted,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(kind) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportOption(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    accent: Color,
    muted: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .selectable(
                selected = selected,
                role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) accent else Color.Transparent,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Carbon else muted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (selected) "✓ $label" else label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Carbon else muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BluetoothPane(
    bleDevices: List<DiscoveredDevice>,
    bleScanning: Boolean,
    classicDevices: List<ClassicDevice>,
    classicDiscovering: Boolean,
    permissionGranted: Boolean,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanBle: () -> Unit,
    onStopBleScan: () -> Unit,
    onConnectBle: (DiscoveredDevice) -> Unit,
    onScanClassic: () -> Unit,
    onStopClassicScan: () -> Unit,
    onConnectClassic: (ClassicDevice) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    if (!permissionGranted) {
        val locationRequired = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
        StalkerCard(accent = stalker.warning) {
            Column {
                Text(
                    if (locationRequired) {
                        "Bluetooth and location permission needed"
                    } else {
                        "Bluetooth permission needed"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (locationRequired) {
                        "Android requires location permission to scan for Bluetooth devices on this version. " +
                            "CarStalker uses it only to find your OBD-II adapter and never reads or stores your location."
                    } else {
                        "CarStalker uses Bluetooth only to talk to your OBD-II adapter."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Carbon),
                ) {
                    Text(if (locationRequired) "Allow Bluetooth and location" else "Allow Bluetooth")
                }
                if (permanentlyDenied) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenSettings) { Text("Open app settings") }
                }
            }
        }
        return
    }

    var mode by rememberSaveable { mutableStateOf(BluetoothMode.CLASSIC) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BluetoothMode.entries.forEach { option ->
                val active = option == mode
                FilterChip(
                    selected = active,
                    onClick = { mode = option },
                    label = {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = stalker.accent.copy(alpha = 0.18f),
                        selectedLabelColor = stalker.accent,
                        labelColor = stalker.muted,
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when (mode) {
            BluetoothMode.CLASSIC -> ClassicPane(
                devices = classicDevices,
                discovering = classicDiscovering,
                onScan = onScanClassic,
                onStopScan = onStopClassicScan,
                onConnect = onConnectClassic,
            )
            BluetoothMode.LE -> LePane(
                devices = bleDevices,
                scanning = bleScanning,
                onScan = onScanBle,
                onStopScan = onStopBleScan,
                onConnect = onConnectBle,
            )
        }
    }
}

private enum class BluetoothMode(val label: String) {
    CLASSIC("Bluetooth Classic"),
    LE("Bluetooth LE"),
}

@Composable
private fun ClassicPane(
    devices: List<ClassicDevice>,
    discovering: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ClassicDevice) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var pendingDevice by remember { mutableStateOf<ClassicDevice?>(null) }
    val paired = devices.filter { it.bonded }
    val others = devices.filterNot { it.bonded }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(
                text = when {
                    discovering -> "Discovering devices"
                    paired.isEmpty() -> "No paired adapters"
                    else -> "${paired.size} paired"
                },
                color = stalker.accentSecondary,
                pulsing = discovering,
            )
            TextButton(onClick = if (discovering) onStopScan else onScan) {
                Text(if (discovering) "Stop" else "Scan", color = stalker.accent)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (paired.isEmpty() && others.isEmpty()) {
            StalkerCard {
                Text(
                    if (discovering) {
                        "Looking for compatible adapters. Keep the dongle powered and in range."
                    } else {
                        "1. Plug the adapter into the OBD-II port. 2. Turn the ignition on. 3. Pair it in Android Bluetooth settings, then tap Scan. Most inexpensive ELM327 adapters use Bluetooth Classic."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (paired.isNotEmpty()) {
                    Text(
                        "PAIRED",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                    paired.forEach { device ->
                        BluetoothDeviceRow(
                            name = device.name,
                            address = device.address,
                            badge = "OBD",
                            onClick = { pendingDevice = device },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "DISCOVERED",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                    others.forEach { device ->
                        BluetoothDeviceRow(
                            name = device.name,
                            address = device.address,
                            badge = "OBD",
                            onClick = { pendingDevice = device },
                        )
                    }
                }
            }
        }
    }

    pendingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDevice = null },
            title = { Text("Connect to ${device.name}?") },
            text = {
                Text(
                    "This will connect to ${device.address}. Make sure this is your OBD-II adapter and not another device in range.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDevice = null
                        onConnect(device)
                    },
                ) { Text("Connect", color = stalker.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDevice = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LePane(
    devices: List<DiscoveredDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var pendingDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    // The scanner only reports compatible adapters, so every entry is safe to show.
    val visibleDevices = devices

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(
                text = when {
                    scanning -> "Scanning for adapters"
                    visibleDevices.isEmpty() -> "No adapters yet"
                    else -> "${visibleDevices.size} adapter${if (visibleDevices.size == 1) "" else "s"}"
                },
                color = stalker.accentSecondary,
                pulsing = scanning,
            )
            TextButton(onClick = if (scanning) onStopScan else onScan) {
                Text(if (scanning) "Stop" else "Scan", color = stalker.accent)
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            scanning && devices.isEmpty() -> {
                StalkerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = stalker.accentSecondary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Looking for nearby LE adapters. Make sure the adapter is powered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                    }
                }
            }
            !scanning && devices.isEmpty() -> {
                StalkerCard {
                    Text(
                        "Tap Scan to look for Bluetooth LE adapters. If your dongle is paired but not listed here, switch to Bluetooth Classic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
            }
            visibleDevices.isEmpty() -> {
                StalkerCard(accent = stalker.warning) {
                    Column {
                        Text(
                            "No LE adapters found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Only compatible OBD-II adapters are listed. If your dongle is paired in Android settings, switch to Bluetooth Classic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                    }
                }
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visibleDevices.forEach { device ->
                        BluetoothDeviceRow(
                            name = device.name,
                            address = device.address,
                            badge = "OBD",
                            rssi = device.rssi,
                            onClick = { pendingDevice = device },
                        )
                    }
                }
            }
        }
    }

    pendingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDevice = null },
            title = { Text("Connect to ${device.name}?") },
            text = {
                Text(
                    "This will connect to ${device.address}. Make sure this is your OBD-II adapter and not another device in range.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDevice = null
                        onConnect(device)
                    },
                ) { Text("Connect", color = stalker.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDevice = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BluetoothDeviceRow(
    name: String,
    address: String,
    badge: String?,
    onClick: () -> Unit,
    rssi: Int? = null,
) {
    val stalker = LocalStalkerColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(stalker.accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = stalker.accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = stalker.accent,
                            modifier = Modifier
                                .background(stalker.accent.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            if (rssi != null) {
                SignalBars(rssi)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = stalker.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UsbPane(
    devices: List<android.hardware.usb.UsbDevice>,
    baud: Int,
    onBaudChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onConnect: (android.hardware.usb.UsbDevice) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Column {
        StalkerCard {
            Column {
                Text(
                    "Serial speed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Use 38400 unless your adapter's manual says otherwise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    options = listOf(9600 to "9600", 38400 to "38400", 115200 to "115200"),
                    selected = baud,
                    onSelect = onBaudChange,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (devices.isEmpty()) "No USB adapters detected" else "${devices.size} USB adapters",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onRefresh) { Text("Refresh", color = stalker.accent) }
        }
        Spacer(Modifier.height(10.dp))
        if (devices.isEmpty()) {
            StalkerCard {
                Text(
                    "Plug an ELM327 USB cable into the phone with an OTG adapter. Supported chipsets: FTDI, CH340, CP210x, PL2303 and CDC-ACM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                devices.forEach { device ->
                    Surface(
                        onClick = { onConnect(device) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(stalker.accent.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Usb,
                                    contentDescription = null,
                                    tint = stalker.accent,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    device.productName ?: "USB serial device",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "VID ${device.vendorId.toString(16)} · PID ${device.productId.toString(16)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = stalker.muted,
                                )
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = stalker.muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TcpPane(
    host: String,
    port: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    StalkerCard {
        Column {
            Text(
                "Wi-Fi adapter",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Join the adapter's Wi-Fi network first, then connect. Default port is 35000.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(16.dp))
            val hostValid = host.isNotBlank() && !host.any { it.isWhitespace() }
            val portValue = port.toIntOrNull()
            val portValid = portValue != null && portValue in 1..65535
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host") },
                singleLine = true,
                isError = host.isNotBlank() && !hostValid,
                supportingText = { if (host.isNotBlank() && !hostValid) Text("Enter a hostname or IP address without spaces") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                singleLine = true,
                isError = port.isNotBlank() && !portValid,
                supportingText = { if (port.isNotBlank() && !portValid) Text("Enter a port from 1 to 65535") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                enabled = hostValid && portValid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Carbon),
            ) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun DemoPane(onStart: () -> Unit) {
    val stalker = LocalStalkerColors.current
    StalkerCard(accent = stalker.accentSecondary) {
        Column {
            StatusPill("No hardware needed", stalker.accentSecondary)
            Spacer(Modifier.height(14.dp))
            Text(
                "Explore CarStalker with a simulated 2013 BRZ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Live engine data, readiness monitors and two stored trouble codes. Clearing codes works too.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accentSecondary, contentColor = Carbon),
            ) {
                Text("Start demo")
            }
        }
    }
}

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBlePermissions(context: android.content.Context): Boolean =
    blePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun connectionRemedy(transport: TransportKind): String = when (transport) {
    TransportKind.BLE ->
        "Check that the adapter is paired, the ignition is on, and that no other phone is connected to it. Most inexpensive ELM327 adapters use Bluetooth Classic."
    TransportKind.USB ->
        "Check the OTG cable and that the adapter is powered. Supported serial chipsets are FTDI, CH340, CP210x, PL2303 and CDC-ACM."
    TransportKind.TCP ->
        "Join the adapter's Wi-Fi network first, then check the host and port. The phone must be on the adapter's network, not your home Wi-Fi."
    TransportKind.DEMO ->
        "The simulator could not start. Try again; if it keeps failing, restart the app."
}
