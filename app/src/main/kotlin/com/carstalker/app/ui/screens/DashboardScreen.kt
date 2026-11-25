package com.carstalker.app.ui.screens

import android.annotation.SuppressLint

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.CustomPid
import com.carstalker.app.data.DashboardParams
import com.carstalker.app.data.toPid
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.service.freshnessAt
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.MiniStat
import com.carstalker.app.ui.components.RadialGauge
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.Sparkline
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatTile
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.dashboardParam
import com.carstalker.app.data.isSampleStale
import com.carstalker.app.ui.components.ActionRow
import com.carstalker.app.ui.components.formatValue
import com.carstalker.app.ui.theme.Amber
import com.carstalker.app.ui.theme.Ember
import com.carstalker.app.ui.theme.Ice
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.app.ui.theme.Mint
import com.carstalker.app.ui.theme.Volt
import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.obd.UnitSystem
import com.carstalker.core.obd.Units
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: CarStalkerViewModel,
    onOpenConnect: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val lastSampleAt by viewModel.lastSampleAt.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val supported by viewModel.supportedPids.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current

    var detailPid by remember { mutableStateOf<Pid?>(null) }
    var showDashboards by remember { mutableStateOf(false) }
    val dashboard = settings.activeDashboard
    val vehicleName = settings.vehicleProfiles[settings.profileVehicleKey]
        ?.nickname
        ?.takeIf { it.isNotBlank() }
        ?: identity.displayName

    val customPids = remember(settings.customPids) {
        settings.customPids.filter { it.enabled }.mapNotNull { it.toPid() }
    }
    val dashboardPids = remember(settings.dashboards, settings.activeDashboardId, supported, customPids) {
        dashboard.resolvePids(supported).mapNotNull { id ->
            customPids.firstOrNull { it.id == id } ?: dashboardParam(id)
        }
    }
    // A one-value dashboard without gauges already shows that value at full size; repeating it
    // as a small tile below is noise.
    val singleFocus = dashboardPids.size == 1 && !dashboard.showGauges

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(connection) {
        while (connection is ConnectionState.Ready) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    val vehicleOffline = connection is ConnectionState.Ready && live.vehicleOffline
    // An adapter-only dashboard has no ECU timestamp, so its freshness comes from the adapter
    // sample. Preferring a leftover ECU timestamp would leave such a dashboard stuck on
    // "Delayed" even while voltage keeps streaming.
    val lastVehicleAt = live.freshnessAt()
    val ageMs = if (lastVehicleAt > 0) (now - lastVehicleAt).coerceAtLeast(0) else Long.MAX_VALUE
    val stale = connection is ConnectionState.Ready && !vehicleOffline && ageMs > 3_000
    val liveLabel = when {
        connection is ConnectionState.Connecting || connection is ConnectionState.Initializing -> "Connecting"
        connection !is ConnectionState.Ready -> "Offline"
        vehicleOffline -> "Vehicle offline"
        live.lastUpdatedAt == 0L -> "Waiting"
        stale -> "Delayed"
        else -> "Live"
    }
    val liveColor = when {
        connection !is ConnectionState.Ready -> stalker.muted
        vehicleOffline -> stalker.warning
        stale -> stalker.warning
        else -> stalker.success
    }
    val dataLive = connection is ConnectionState.Ready && !vehicleOffline && !stale

    // A value can be static because the car reports it that way, or because no new sample
    // arrived. Only the second case is marked stale, so a Honda throttle that the ECU keeps
    // reporting at one position is not confused with a poll that stopped working.
    fun pidIsStale(pidId: Int): Boolean = isSampleStale(lastSampleAt[pidId] ?: 0L, now)

    // Long-press editing: an armed card can be dropped on another card to move, or released
    // in place to open the replace list. Bounds are kept in root coordinates for hit testing.
    var armedPid by remember { mutableStateOf<Int?>(null) }
    var dropTargetPid by remember { mutableStateOf<Int?>(null) }
    var replacePid by remember { mutableStateOf<Pid?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var dragStartRoot by remember { mutableStateOf(Offset.Zero) }
    val tileBounds = remember { mutableMapOf<Int, Rect>() }
    // Frozen at arm time so the preview offsets cannot feed back into hit testing.
    val dragBounds = remember { mutableMapOf<Int, Rect>() }
    var gridBounds by remember { mutableStateOf<Rect?>(null) }

    // The detail sheet owns the polling priority: while it is open, that PID is read every cycle.
    DisposableEffect(detailPid) {
        viewModel.setDetailPid(detailPid?.id)
        onDispose { viewModel.setDetailPid(null) }
    }

    val scrollState = rememberScrollState()
    var topAnchorPx by remember { mutableStateOf<Float?>(null) }
    // The gauge top is a magnetic point: the scroll resists leaving it and settles on it.
    val stickyBandPx = with(LocalDensity.current) { 96.dp.toPx() }
    val dragSlopPx = LocalViewConfiguration.current.touchSlop.toFloat()
    val anchorModifier = Modifier.onGloballyPositioned { coords ->
        topAnchorPx = coords.positionInParent().y
    }
    val stickyConnection = remember(scrollState, topAnchorPx, stickyBandPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val anchor = topAnchorPx ?: return Offset.Zero
                val distance = abs(scrollState.value - anchor)
                if (distance >= stickyBandPx) return Offset.Zero
                // Full resistance at the anchor, easing out to none at the band edge.
                val strength = 1f - distance / stickyBandPx
                return Offset(0f, available.y * strength * 0.7f)
            }
        }
    }

    // A dashboard opens at the top, then the first data block slides under the header so the
    // gauges (or the single large card) use the whole screen. A manual scroll cancels this.
    // A dashboard opens at the top and stays there. It used to scroll itself down to the gauges
    // a second after opening, which hid the vehicle name and the record button without the user
    // asking; scrolling is now entirely theirs.
    LaunchedEffect(dashboard.id) {
        scrollState.scrollTo(0)
    }

    // The data block's top edge is a gentle snap point: stopping within a thumb's width of it
    // settles exactly on it instead of leaving the gauges half hidden.
    LaunchedEffect(scrollState, topAnchorPx) {
        val anchor = topAnchorPx?.toInt() ?: return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress }
            .filter { !it }
            .collect {
                val distance = scrollState.value - anchor
                if (distance != 0 && abs(distance) <= stickyBandPx) {
                    scrollState.animateScrollTo(anchor, tween(durationMillis = 220))
                }
            }
    }

    val ready = connection as? ConnectionState.Ready
    // The cluster gauges are square, so a landscape phone (where half the width is wider than
    // the whole viewport is tall) would draw a dial taller than the screen. Cap the dial to a
    // share of the viewport height so the whole sweep stays visible in both orientations.
    val gaugeMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val rpmPid = PidCatalog.find(0x0C)!!
    val speedPid = PidCatalog.find(0x0D)!!
    val loadPid = PidCatalog.find(0x04)!!
    val coolantPid = PidCatalog.find(0x05)!!
    val batteryPid = dashboardParam(LiveKeys.ADAPTER_VOLTAGE)!!
    val rpm = live.values[0x0C] ?: 0.0
    val speedDisplay = Units.display(speedPid, live.values[0x0D] ?: 0.0, settings.unitSystem)
    val maxSpeed = if (settings.unitSystem == UnitSystem.IMPERIAL) 150.0 else 240.0

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .nestedScroll(stickyConnection)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "LIVE TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.accent,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = vehicleName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (identity.vin == null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (ready != null) {
                            "VIN not reported by this ECU"
                        } else {
                            "Vehicle identity is read on connection"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showDashboards = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            "${dashboard.name} ▾",
                            style = MaterialTheme.typography.labelMedium,
                            color = stalker.accentSecondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            connection !is ConnectionState.Ready -> "Last values may be old"
                            vehicleOffline && live.lastEcuAt == 0L -> "No ECU response since connecting"
                            vehicleOffline -> "No ECU response for ${ageMs / 1000}s"
                            live.lastUpdatedAt == 0L -> "Waiting for data"
                            stale -> "Last update ${ageMs / 1000}s ago"
                            ageMs < 100 -> "Updated <0.1s ago"
                            else -> "Updated ${(ageMs / 100.0).toInt() / 10.0}s ago"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stale || vehicleOffline) stalker.warning else stalker.muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (singleFocus) {
                        TextButton(
                            onClick = viewModel::openPidPicker,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                "Edit",
                                style = MaterialTheme.typography.labelMedium,
                                color = stalker.accent,
                            )
                        }
                    }
                }
            }
            StatusPill(
                text = liveLabel,
                color = liveColor,
                pulsing = connection is ConnectionState.Ready && !stale && !vehicleOffline,
                onClick = onOpenConnect,
            )
            Spacer(Modifier.width(10.dp))
            RecordButton(
                recording = recording,
                enabled = connection is ConnectionState.Ready || recording,
                onClick = viewModel::toggleRecording,
            )
        }

        if (recording) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("REC", stalker.danger, pulsing = true)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recording every sample to CSV",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (connection) {
            is ConnectionState.Ready, is ConnectionState.Connecting, is ConnectionState.Initializing -> {
                if (vehicleOffline) {
                    StalkerCard(accent = stalker.warning) {
                        Column {
                            Text(
                                "No response from the vehicle",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "The adapter is still connected, but the ECU stopped answering. " +
                                    "Live values are hidden until it responds — check that the ignition is on.",
                                style = MaterialTheme.typography.bodySmall,
                                color = stalker.muted,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (dashboard.showGauges) Surface(
                    modifier = Modifier
                        .alpha(if (vehicleOffline) 0.4f else 1f)
                        .then(anchorModifier),
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(
                        Modifier.background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(stalker.accent.copy(alpha = 0.08f), Color.Transparent),
                            ),
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val dialSize = minOf(maxWidth / 2 - 4.dp, gaugeMaxHeight)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                        Alignment.CenterHorizontally,
                                    ),
                                ) {
                                    RadialGauge(
                                        value = rpm,
                                        min = 0.0,
                                        max = 8000.0,
                                        label = "Engine speed",
                                        unit = "rpm",
                                        accent = Volt,
                                        modifier = Modifier
                                            .size(dialSize)
                                            .clickable { detailPid = rpmPid },
                                        redline = 0.86f,
                                    )
                                    RadialGauge(
                                        value = speedDisplay.value,
                                        min = 0.0,
                                        max = maxSpeed,
                                        label = "Vehicle speed",
                                        unit = speedDisplay.unit,
                                        accent = Ice,
                                        modifier = Modifier
                                            .size(dialSize)
                                            .clickable { detailPid = speedPid },
                                        redline = 0.94f,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val coolant = live.values[0x05]
                                val load = live.values[0x04]
                                val volts = live.values[LiveKeys.ADAPTER_VOLTAGE] ?: live.adapterVoltage
                                val coolantDisplay = coolant?.let { Units.display(PidCatalog.find(0x05)!!, it, settings.unitSystem) }
                                MiniStat(
                                    "Coolant",
                                    coolantDisplay?.let { formatValue(it.value, 0) } ?: "—",
                                    coolantDisplay?.unit ?: "",
                                    accent = Ember,
                                    modifier = Modifier.clickable { detailPid = coolantPid },
                                )
                                MiniStat(
                                    "Load",
                                    load?.let { formatValue(it, 0) } ?: "—",
                                    "%",
                                    accent = Volt,
                                    modifier = Modifier.clickable { detailPid = loadPid },
                                )
                                MiniStat(
                                    "Battery",
                                    volts?.let { formatValue(it, 1) } ?: "—",
                                    "V",
                                    accent = Ice,
                                    modifier = Modifier.clickable { detailPid = batteryPid },
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                StalkerCard(accent = stalker.accent) {
                    Column {
                        Text(
                            "No adapter connected",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Connect a Bluetooth, USB or Wi-Fi ELM327 adapter to stream live engine data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        ActionRow(spacing = 10.dp) {
                            Button(
                                onClick = onOpenConnect,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = stalker.accent,
                                    contentColor = Color.Black,
                                ),
                            ) { Text("Connect adapter") }
                            OutlinedButton(onClick = viewModel::connectDemo) {
                                Text("Try demo")
                            }
                        }
                    }
                }
            }
        }

        if (singleFocus) {
            Spacer(Modifier.height(14.dp))
            val focusPid = dashboardPids.first()
            FocusParamCard(
                pid = focusPid,
                value = live.values[focusPid.id],
                samples = history[focusPid.id].orEmpty(),
                unitSystem = settings.unitSystem,
                statusLabel = liveLabel,
                statusOk = dataLive,
                stale = pidIsStale(focusPid.id),
                armed = armedPid == focusPid.id,
                contentModifier = Modifier.pointerInput(focusPid.id) {
                    detectDashboardCardHold(
                        onArm = { _ ->
                            armedPid = focusPid.id
                            dropTargetPid = null
                        },
                        onMove = {},
                        onRelease = { moved ->
                            if (!moved) replacePid = focusPid
                            armedPid = null
                            dropTargetPid = null
                        },
                        onCancel = {
                            armedPid = null
                            dropTargetPid = null
                        },
                    )
                },
                modifier = anchorModifier,
                onClick = { detailPid = focusPid },
            )
        } else {
            Spacer(Modifier.height(22.dp))
            // Only the topmost data block owns the anchor, otherwise the header below would
            // overwrite it and the automatic scroll would stop at the tiles instead of gauges.
            Box(if (dashboard.showGauges) Modifier else anchorModifier) {
                SectionHeader(
                    title = "Live data",
                    trailing = {
                        Row {
                            TextButton(onClick = { showDashboards = true }) {
                                Text("Dashboard", color = stalker.accentSecondary)
                            }
                            TextButton(onClick = viewModel::openPidPicker) {
                                Text("Edit", color = stalker.accent)
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))

            if (dashboardPids.isEmpty()) {
                StalkerCard {
                    Column {
                        Text(
                            "This dashboard is blank.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add exactly the values you need. Only the values on this dashboard are polled, so a single-value dashboard updates much faster than a full one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = stalker.muted,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::openPidPicker,
                                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                            ) { Text("Add parameters") }
                            OutlinedButton(onClick = { showDashboards = true }) { Text("Dashboards") }
                        }
                    }
                }
            } else {
                val sourceIndex = dashboardPids.indexOfFirst { it.id == armedPid }
                val targetIndex = dashboardPids.indexOfFirst { it.id == dropTargetPid }

                fun previewShift(index: Int): Offset {
                    if (sourceIndex < 0 || targetIndex < 0 || index == sourceIndex) return Offset.Zero
                    val previewIndex = when {
                        sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> index - 1
                        sourceIndex > targetIndex && index in targetIndex until sourceIndex -> index + 1
                        else -> return Offset.Zero
                    }
                    val from = dragBounds[dashboardPids[index].id]?.topLeft ?: return Offset.Zero
                    val to = dragBounds[dashboardPids[previewIndex].id]?.topLeft ?: return Offset.Zero
                    return to - from
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { gridBounds = it.boundsInRoot() },
                ) {
                    Column {
                        dashboardPids.chunked(2).forEach { pair ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                                    .zIndex(if (pair.any { it.id == armedPid }) 2f else 0f),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                pair.forEach { pid ->
                                    key(pid.id) {
                                        val index = dashboardPids.indexOfFirst { it.id == pid.id }
                                        val rawValue = live.values[pid.id]
                                        val display = rawValue?.let { Units.display(pid, it, settings.unitSystem) }
                                        val cardCoords = remember(pid.id) { mutableStateOf<LayoutCoordinates?>(null) }
                                        StatTile(
                                            title = pid.shortName,
                                            value = display?.let {
                                                formatValue(it.value, if (pid.unit == "km/h" || pid.unit == "mph") 0 else pid.decimals)
                                            } ?: "—",
                                            unit = display?.unit ?: pid.unit,
                                            samples = history[pid.id].orEmpty(),
                                            accent = accentFor(pid),
                                            modifier = Modifier.weight(1f),
                                            stale = pidIsStale(pid.id),
                                            armed = armedPid == pid.id,
                                            dropTarget = dropTargetPid == pid.id,
                                            dragging = armedPid == pid.id,
                                            dragTranslation = if (armedPid == pid.id) dragDelta else Offset.Zero,
                                            shift = previewShift(index),
                                            contentModifier = Modifier
                                                .onGloballyPositioned { coords ->
                                                    cardCoords.value = coords
                                                    // Keep the pre-drag layout bounds while a card is held;
                                                    // they are the hit-test reference for the preview.
                                                    if (armedPid == null) {
                                                        tileBounds[pid.id] = coords.boundsInRoot()
                                                    }
                                                }
                                                .pointerInput(pid.id, dashboardPids) {
                                                    detectDashboardCardHold(
                                                        onArm = { local ->
                                                            val root = cardCoords.value?.localToRoot(local)
                                                                ?: tileBounds[pid.id]?.center
                                                                ?: Offset.Zero
                                                            dragStartRoot = root
                                                            dragDelta = Offset.Zero
                                                            dragBounds.clear()
                                                            val currentIds = dashboardPids.mapTo(HashSet()) { it.id }
                                                            tileBounds.forEach { (id, rect) ->
                                                                if (id in currentIds) dragBounds[id] = rect
                                                            }
                                                            armedPid = pid.id
                                                            dropTargetPid = null
                                                        },
                                                        onMove = { local ->
                                                            val root = cardCoords.value?.localToRoot(local)
                                                            if (root != null) {
                                                                dragDelta = root - dragStartRoot
                                                                dropTargetPid = dragBounds.entries
                                                                    .firstOrNull { (id, rect) ->
                                                                        id != pid.id && rect.contains(root)
                                                                    }
                                                                    ?.key
                                                            }
                                                        },
                                                        onRelease = { _ ->
                                                            // The card moves with the finger, so the local
                                                            // pointer delta stays small. Use the root delta.
                                                            if (dragDelta.getDistance() <= dragSlopPx) {
                                                                replacePid = pid
                                                            } else {
                                                                dropTargetPid?.takeIf { it != pid.id }?.let { target ->
                                                                    viewModel.moveDashboardPid(pid.id, target)
                                                                }
                                                            }
                                                            armedPid = null
                                                            dropTargetPid = null
                                                            dragDelta = Offset.Zero
                                                            dragBounds.clear()
                                                        },
                                                        onCancel = {
                                                            armedPid = null
                                                            dropTargetPid = null
                                                            dragDelta = Offset.Zero
                                                            dragBounds.clear()
                                                        },
                                                    )
                                                },
                                            onClick = { detailPid = pid },
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    val gap = dropTargetPid?.let { dragBounds[it] }
                    val grid = gridBounds
                    if (armedPid != null && gap != null && grid != null) {
                        val density = LocalDensity.current
                        val lineX = with(density) { (gap.left - grid.left).toDp() } - 1.5.dp
                        val lineY = with(density) { (gap.top - grid.top).toDp() }
                        val lineHeight = with(density) { gap.height.toDp() }
                        Box(
                            Modifier
                                .offset(x = lineX, y = lineY)
                                .width(3.dp)
                                .height(lineHeight)
                                .background(stalker.accent.copy(alpha = 0.8f), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("Vehicle")
        Spacer(Modifier.height(10.dp))
        VehicleCard(
            vin = identity.vin,
            calibrationId = identity.calibrationId,
            cvn = identity.cvn,
            ecuName = identity.ecuName,
            nickname = settings.vehicleProfiles[settings.profileVehicleKey]?.nickname,
            protocol = ready?.adapter?.protocolName,
            adapter = ready?.adapter?.identifier,
            voltage = live.adapterVoltage ?: ready?.adapter?.voltage,
            supportedCount = supported.size,
        )
        Spacer(Modifier.height(24.dp))
    }

    // A floating hint rather than a banner: adding or removing layout during the hold would
    // move the cards under the finger and break the drop target.
    if (armedPid != null) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, -24),
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, stalker.accent.copy(alpha = 0.6f)),
                shadowElevation = 8.dp,
            ) {
                Text(
                    "Drop on a card to move it there, or release to replace it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }

    if (showDashboards) {
        DashboardSwitcherSheet(
            settings = settings,
            onSelect = {
                viewModel.selectDashboard(it)
                showDashboards = false
            },
            onCreateBlank = {
                viewModel.createBlankDashboard()
                showDashboards = false
            },
            onDuplicate = viewModel::duplicateDashboard,
            onRename = viewModel::renameDashboard,
            onDelete = viewModel::deleteDashboard,
            onToggleGauges = viewModel::setDashboardGauges,
            onManageParameters = {
                viewModel.openPidPicker()
                showDashboards = false
            },
            onDismiss = { showDashboards = false },
        )
    }

    detailPid?.let { pid ->
        // The detail view needs this parameter's own clock, not the dashboard-wide one: a
        // throttle value can be static while every other channel keeps updating.
        val detailLastAt = if (pid.id == LiveKeys.ADAPTER_VOLTAGE) {
            live.lastUpdatedAt
        } else {
            lastSampleAt[pid.id] ?: 0L
        }
        PidDetailSheet(
            pid = pid,
            samples = history[pid.id].orEmpty(),
            unitSystem = settings.unitSystem,
            isPinned = dashboardPids.any { it.id == pid.id },
            lastUpdatedAt = detailLastAt,
            statusLabel = liveLabel,
            statusOk = dataLive,
            vehicleOffline = vehicleOffline,
            adapterOffline = connection !is ConnectionState.Ready,
            onTogglePin = { viewModel.toggleDashboardPid(pid.id) },
            onDismiss = { detailPid = null },
        )
    }

    replacePid?.let { target ->
        ReplaceParamSheet(
            target = target,
            currentPids = dashboardPids.mapTo(HashSet()) { it.id },
            supported = supported,
            customPids = settings.customPids.filter { it.enabled },
            onReplace = { replacement ->
                viewModel.replaceDashboardPid(target.id, replacement.id)
                replacePid = null
            },
            onDismiss = { replacePid = null },
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stalker = LocalStalkerColors.current
    val animations = com.carstalker.app.ui.components.animationsEnabled()
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "recPulse",
    )
    Surface(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        color = if (recording) stalker.danger.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (recording) stalker.danger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.size(44.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                contentDescription = if (recording) "Stop recording" else "Start recording",
                tint = when {
                    recording -> stalker.danger.copy(alpha = if (animations) pulse else 1f)
                    enabled -> stalker.muted
                    else -> stalker.muted.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VehicleCard(
    vin: String?,
    calibrationId: String?,
    cvn: List<String>,
    ecuName: String?,
    nickname: String?,
    protocol: String?,
    adapter: String?,
    voltage: Double?,
    supportedCount: Int,
) {
    val stalker = LocalStalkerColors.current
    val clipboard = LocalClipboardManager.current
    StalkerCard {
        Column {
            nickname?.takeIf { it.isNotBlank() }?.let {
                InfoLine("Vehicle name", it)
                Spacer(Modifier.height(6.dp))
            }
            if (vin != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VIN", style = MaterialTheme.typography.labelSmall, color = stalker.muted)
                        Text(
                            vin,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Surface(
                        onClick = { clipboard.setText(AnnotatedString(vin)) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copy VIN",
                                tint = stalker.muted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else if (protocol != null) {
                InfoLine("VIN", "Not reported by this ECU")
                if (nickname.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No VIN was reported. Name this car in Settings → Your car; the name is stored against its ECU fingerprint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                }
            } else {
                Text(
                    "Vehicle identity is read when the adapter connects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            calibrationId?.let { InfoLine("Calibration ID", it) }
            if (cvn.isNotEmpty()) InfoLine("CVN", cvn.joinToString(", "))
            ecuName?.let { InfoLine("ECU", it) }
            if (vin == null && calibrationId == null && cvn.isEmpty() && ecuName == null && protocol != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "This vehicle did not report identity over generic OBD. Some UK/European models require manufacturer-specific diagnostics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            if (protocol != null) {
                HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                )
                InfoLine("Protocol", protocol)
                adapter?.let { InfoLine("Adapter", it) }
                voltage?.let { InfoLine("Adapter voltage", String.format(Locale.US, "%.1f V", it)) }
                InfoLine("Supported PIDs", supportedCount.toString())
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = stalker.muted)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PidDetailSheet(
    pid: Pid,
    samples: List<Float>,
    unitSystem: UnitSystem,
    isPinned: Boolean,
    lastUpdatedAt: Long,
    statusLabel: String,
    statusOk: Boolean,
    vehicleOffline: Boolean,
    adapterOffline: Boolean,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = samples.lastOrNull()?.toDouble()
    val display = current?.let { Units.display(pid, it, unitSystem) }
    val min = samples.minOrNull()
    val max = samples.maxOrNull()
    val avg = if (samples.isEmpty()) null else samples.average()

    // The sheet stays open when the car goes quiet, so its own clock has to keep running for
    // the age of the last sample to stay honest.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val pidStale = isSampleStale(lastUpdatedAt, now)
    val effectiveOk = statusOk && !pidStale
    val dimmed = !effectiveOk
    val displayLabel = if (statusOk && pidStale) "Not updating" else statusLabel
    val ageSeconds = if (lastUpdatedAt > 0) {
        ((now - lastUpdatedAt).coerceAtLeast(0) / 1000)
    } else {
        null
    }
    val accent = if (dimmed) stalker.muted else accentFor(pid)
    val throttleNote = when (pid.id) {
        0x11, 0x45, 0x47, 0x48 ->
            "Some ECUs report the throttle plate or valve here rather than pedal travel. " +
                "Compare with Pedal (0x49) and Pedal 2 (0x4A), or run Find my throttle & pedal " +
                "channels in the parameter picker before diagnosing a sensor."
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pid.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (pid.id < 0) "Adapter measurement · ${pid.category.name.lowercase(Locale.US)}"
                        else "PID ${pid.hex} · ${pid.category.name.lowercase(Locale.US)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
                StatusPill(
                    text = displayLabel,
                    color = if (effectiveOk) stalker.success else stalker.warning,
                    pulsing = effectiveOk,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${samples.size} samples in this view")
                    if (ageSeconds != null) append(" · last update ${ageSeconds}s ago")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (dimmed) stalker.warning else stalker.muted,
            )
            if (dimmed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        adapterOffline -> "The adapter is disconnected. The gauge keeps the last reading; it is not live."
                        vehicleOffline -> "The adapter is connected but the vehicle stopped answering. The gauge keeps the last reading; it is not live."
                        else -> "No new samples for ${ageSeconds ?: 0}s. The gauge keeps the last reading; it is not live."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.warning,
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().alpha(if (dimmed) 0.4f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                RadialGauge(
                    value = display?.value ?: 0.0,
                    min = pid.min,
                    max = pid.max,
                    label = pid.shortName,
                    unit = display?.unit ?: pid.unit,
                    accent = accent,
                    modifier = Modifier.size(210.dp),
                    decimals = pid.decimals,
                    redline = 0.92f,
                )
            }
            Spacer(Modifier.height(10.dp))
            Sparkline(
                samples = samples,
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(86.dp).alpha(if (dimmed) 0.5f else 1f),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailStat("Min", min?.let { formatValue(it.toDouble(), pid.decimals) } ?: "—", pid.unit)
                DetailStat("Average", avg?.let { formatValue(it, pid.decimals) } ?: "—", pid.unit)
                DetailStat("Max", max?.let { formatValue(it.toDouble(), pid.decimals) } ?: "—", pid.unit)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Reading this value on every poll cycle while this view is open.",
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            if (throttleNote != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    throttleNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            Spacer(Modifier.height(20.dp))
            if (!pid.isCustom) {
                OutlinedButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isPinned) "Remove from dashboard" else "Pin to dashboard")
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    "Custom PID — manage it in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
            ) { Text("Close") }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, unit: String) {
    val stalker = LocalStalkerColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(Locale.US), style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
    }
}

private fun accentFor(pid: Pid): Color = when (pid.category) {
    Pid.Category.ENGINE -> Volt
    Pid.Category.DRIVETRAIN -> Mint
    Pid.Category.FUEL -> Amber
    Pid.Category.TEMPERATURE -> Ember
    Pid.Category.ELECTRICAL -> Ice
    Pid.Category.EMISSIONS -> Mint
    Pid.Category.OTHER -> Ice
}


/**
 * A single-parameter dashboard card. Only the values on the active dashboard are polled, so a
 * voltage spike is visible instead of being sampled once per cycle.
 */
@SuppressLint("ModifierParameter")
@Composable
private fun FocusParamCard(
    pid: Pid,
    value: Double?,
    samples: List<Float>,
    unitSystem: UnitSystem,
    statusLabel: String,
    statusOk: Boolean,
    stale: Boolean = false,
    armed: Boolean = false,
    contentModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val display = value?.let { Units.display(pid, it, unitSystem) }
    val decimals = if (pid.unit == "km/h" || pid.unit == "mph") 0 else pid.decimals
    val stats = if (samples.isNotEmpty()) {
        Triple(samples.min().toDouble(), samples.average(), samples.max().toDouble())
    } else {
        null
    }
    val effectiveOk = statusOk && !stale
    val displayLabel = if (statusOk && stale) "Not updating" else statusLabel
    StalkerCard(
        accent = if (armed) stalker.accent else if (effectiveOk) accentFor(pid) else stalker.warning,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.then(contentModifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pid.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (pid.id < 0) "Adapter measurement" else "PID ${pid.hex}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
                StatusPill(
                    text = displayLabel,
                    color = if (effectiveOk) stalker.success else stalker.warning,
                    pulsing = effectiveOk,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    display?.let { formatValue(it.value, decimals) } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (stale) 0.55f else 1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    display?.unit ?: pid.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = stalker.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Sparkline(
                samples = samples,
                accent = if (effectiveOk) accentFor(pid) else stalker.muted,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            if (stats != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MiniStat("Min", formatValue(stats.first, decimals), display?.unit ?: pid.unit, accent = stalker.muted)
                    MiniStat("Average", formatValue(stats.second, decimals), display?.unit ?: pid.unit, accent = stalker.accent)
                    MiniStat("Max", formatValue(stats.third, decimals), display?.unit ?: pid.unit, accent = stalker.warning)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Only this value is polled, so it updates as fast as the adapter allows.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DashboardSwitcherSheet(
    settings: com.carstalker.app.data.AppSettings,
    onSelect: (String) -> Unit,
    onCreateBlank: () -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleGauges: (String, Boolean) -> Unit,
    onManageParameters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "Dashboards",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Each dashboard has its own values and gauges. Only the values on the active dashboard are polled, so a single value updates much faster.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(12.dp))
            settings.dashboards.forEach { dashboard ->
                val active = dashboard.id == settings.activeDashboardId
                Surface(
                    onClick = { onSelect(dashboard.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (active) stalker.accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (active) stalker.accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (active) "●" else "○",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) stalker.accent else stalker.muted,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dashboard.name,
                                    style = MaterialTheme.typography.titleSmall,
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
                                        append(" · ")
                                        append(if (dashboard.showGauges) "gauges" else "no gauges")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stalker.muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextButton(
                                onClick = { onToggleGauges(dashboard.id, !dashboard.showGauges) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                            ) {
                                Text(if (dashboard.showGauges) "Hide gauges" else "Show gauges", color = stalker.accentSecondary)
                            }
                            TextButton(
                                onClick = {
                                    renameTarget = dashboard.id
                                    renameText = dashboard.name
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                            ) { Text("Rename", color = stalker.accentSecondary) }
                            if (settings.dashboards.size > 1) {
                                TextButton(
                                    onClick = { onDelete(dashboard.id) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                                ) { Text("Delete", color = stalker.danger) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCreateBlank,
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) { Text("New blank dashboard") }
                OutlinedButton(onClick = { onDuplicate(settings.activeDashboardId) }) {
                    Text("Duplicate")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onManageParameters, modifier = Modifier.fillMaxWidth()) {
                Text("Manage values on this dashboard")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    renameTarget?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename dashboard") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(32) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(id, renameText)
                    renameTarget = null
                }) { Text("Save", color = stalker.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Arms a dashboard card after a deliberate hold, then consumes the gesture for move/replace.
 * Before arming it leaves movement unconsumed, so the page still scrolls and a normal tap still
 * opens the detail view.
 */
private suspend fun PointerInputScope.detectDashboardCardHold(
    holdMillis: Long = 1_000L,
    onArm: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: (moved: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var armed = false
        var moved = false
        var released = false
        var last = down.position
        var accumulated = Offset.Zero
        val deadline = System.currentTimeMillis() + holdMillis
        while (true) {
            val event = if (armed) {
                awaitPointerEvent()
            } else {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            }
            if (event == null) {
                armed = true
                onArm(last)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                released = true
                if (armed) {
                    change.consume()
                    onRelease(moved)
                }
                break
            }
            if (!armed) {
                accumulated += change.position - last
                last = change.position
                if (accumulated.getDistance() > viewConfiguration.touchSlop) break
            } else {
                if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                onMove(change.position)
                change.consume()
            }
        }
        if (armed && !released) onCancel()
    }
}

/** One entry in the replacement list, including values this car does not report. */
private data class ReplaceOption(val pid: Pid, val supported: Boolean)

/**
 * Replacement list for a long-pressed card. Values already on the dashboard are excluded so a
 * replacement can never create a duplicate. Values this car does not report stay visible and
 * are marked N/A, so the absence of a PID is not mistaken for a missing menu entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceParamSheet(
    target: Pid,
    currentPids: Set<Int>,
    supported: Set<Int>,
    customPids: List<CustomPid>,
    onReplace: (Pid) -> Unit,
    onDismiss: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = remember(currentPids, supported, customPids) {
        val standard = DashboardParams
            .filter { it.id !in currentPids }
            .map { pid ->
                ReplaceOption(
                    pid = pid,
                    supported = pid.id < 0 || supported.isEmpty() || pid.id in supported,
                )
            }
        val custom = customPids
            .mapNotNull { it.toPid() }
            .filter { it.id !in currentPids }
            .map { ReplaceOption(it, supported = true) }
        (standard + custom)
            .distinctBy { it.pid.id }
            .sortedWith(compareBy({ it.pid.category.ordinal }, { it.pid.name }))
    }
    val hasUnsupported = options.any { !it.supported }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                "Replace ${target.shortName}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Choose another value for this card. Values already on the dashboard are not listed.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            if (hasUnsupported) {
                Text(
                    "N/A values are not reported by this car.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.warning,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (options.isEmpty()) {
                Text(
                    "No other values are available for this card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.warning,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(options, key = { it.pid.id }) { option ->
                        val pid = option.pid
                        Surface(
                            onClick = { onReplace(pid) },
                            enabled = option.supported,
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .alpha(if (option.supported) 1f else 0.45f)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pid.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        if (pid.id < 0) "Adapter measurement" else "PID ${pid.hex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = stalker.muted,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (!option.supported) {
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
                                if (pid.unit.isNotBlank()) {
                                    Text(
                                        pid.unit,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = stalker.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
