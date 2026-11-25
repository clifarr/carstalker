package com.carstalker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.service.toScanContext
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.components.AvailabilityPill
import com.carstalker.app.ui.components.SectionHeader
import com.carstalker.app.ui.components.StalkerCard
import com.carstalker.app.ui.components.StatusPill
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.diagnose.CatalystWizard
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.AvailabilityReport
import com.carstalker.core.guided.CapabilityResolver
import com.carstalker.core.guided.DiagnosticAvailability
import com.carstalker.core.guided.DiagnosticCategory
import com.carstalker.core.guided.DiagnosticDefinition
import com.carstalker.core.guided.DiagnosticPhase
import com.carstalker.core.guided.DiagnosticRegistry
import com.carstalker.core.guided.FuelType
import com.carstalker.core.guided.VehicleCapabilities

@Composable
fun DiagnoseScreen(viewModel: CarStalkerViewModel, onOpenConnect: () -> Unit) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val report by viewModel.dtcReport.collectAsStateWithLifecycle()
    val capabilities by viewModel.vehicleCapabilities.collectAsStateWithLifecycle()
    val activeGuide by viewModel.activeGuide.collectAsStateWithLifecycle()
    val stalker = LocalStalkerColors.current
    var showPlanned by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CatalogueFilter.ALL) }
    var expanded by remember { mutableStateOf(setOf(DiagnosticCategory.ENGINE_FUEL.name)) }
    val connected = connection is ConnectionState.Ready

    val guide = activeGuide
    if (guide != null) {
        if (guide.legacyWizardId != null) {
            CatalystFlowScreen(
                viewModel = viewModel,
                onBack = { viewModel.closeGuide() },
                onOpenConnect = {
                    viewModel.closeGuide()
                    onOpenConnect()
                },
            )
        } else {
            GuidedTestScreen(
                definition = guide,
                viewModel = viewModel,
                onBack = { viewModel.closeGuide() },
                onOpenConnect = {
                    viewModel.closeGuide()
                    onOpenConnect()
                },
            )
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("GUIDED DIAGNOSIS", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
        Spacer(Modifier.height(3.dp))
        Text(
            "Find the cause, not just the code",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Step-by-step tests that use live data and the ECU's own self-tests. Evidence, alternatives and one clear next check — no account, no cloud, no parts cannon.",
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
        )

        if (!connected) {
            Spacer(Modifier.height(14.dp))
            StalkerCard(accent = stalker.accentSecondary) {
                Column {
                    Text("Browse the catalogue", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You can read every guide before connecting. Availability and Start need a connected adapter or the demo car.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenConnect,
                            colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                        ) { Text("Connect adapter") }
                        OutlinedButton(onClick = viewModel::connectDemo) { Text("Try demo") }
                    }
                }
            }
        }

        if (connected) {
            Spacer(Modifier.height(18.dp))
            EngineProfileCard(capabilities = capabilities, viewModel = viewModel)
            Spacer(Modifier.height(14.dp))
            CurrentCodesCard(report = report, onReadCodes = viewModel::readCodes)
        }
        Spacer(Modifier.height(16.dp))

        val codes = report.modules.flatMap { module ->
            listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
        }.distinct()
        val scan = report.toScanContext()
        val suggested = DiagnosticRegistry.suggestedForCodes(codes)
        val implemented = DiagnosticRegistry.implemented()
        val visible = implemented.filter { definition ->
            definition.matchesQuery(query) && when (filter) {
                CatalogueFilter.ALL -> true
                CatalogueFilter.STARTABLE -> CapabilityResolver.resolve(definition, capabilities, scan).availability.startable
                CatalogueFilter.LIMITED -> CapabilityResolver.resolve(definition, capabilities, scan).availability in setOf(
                    DiagnosticAvailability.LIMITED,
                    DiagnosticAvailability.NEEDS_COLD_ENGINE,
                    DiagnosticAvailability.NEEDS_WARM_ENGINE,
                )
                CatalogueFilter.UNSUPPORTED -> !CapabilityResolver.resolve(definition, capabilities, scan).availability.startable
            }
        }

        if (suggested.isNotEmpty() && connected) {
            SectionHeader("Suggested from this scan")
            Spacer(Modifier.height(8.dp))
            suggested.take(3).forEach { definition ->
                val availability = CapabilityResolver.resolve(definition, capabilities, scan)
                GuideCard(
                    definition = definition,
                    availability = availability,
                    connected = true,
                    matchedCode = codes.firstOrNull { definition.matchesCode(it) },
                    scanReadAt = report.readAt,
                    onOpen = { viewModel.openGuide(definition) },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by code, symptom or keyword") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CatalogueFilter.entries.forEach { option ->
                val active = option == filter
                Surface(
                    onClick = { filter = option },
                    shape = RoundedCornerShape(50),
                    color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) stalker.accent else stalker.muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (visible.isEmpty()) {
            StalkerCard {
                Text(
                    "No guide matches this search. Try a code such as P0420, a symptom such as \"won't start\", or clear the filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        }

        DiagnosticCategory.entries.forEach { category ->
            val categoryGuides = visible.filter { it.category == category }
            if (categoryGuides.isEmpty()) return@forEach
            val isExpanded = query.isNotBlank() || category.name in expanded
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = {
                    expanded = if (category.name in expanded) expanded - category.name else expanded + category.name
                },
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (isExpanded) "▾" else "▸"} ${category.label} · ${categoryGuides.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.accent,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (isExpanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
            }
            if (isExpanded) {
                Spacer(Modifier.height(6.dp))
                categoryGuides.forEach { definition ->
                    val availability = CapabilityResolver.resolve(definition, capabilities, scan)
                    GuideCard(
                        definition = definition,
                        availability = availability,
                        connected = connected,
                        matchedCode = codes.firstOrNull { definition.matchesCode(it) },
                        scanReadAt = report.readAt,
                        onOpen = { viewModel.openGuide(definition) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        val planned = DiagnosticRegistry.planned()
        if (planned.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Planned workflows", modifier = Modifier.weight(1f))
                TextButton(onClick = { showPlanned = !showPlanned }) {
                    Text(if (showPlanned) "Hide" else "Show ${planned.size}")
                }
            }
            if (showPlanned) {
                Spacer(Modifier.height(6.dp))
                planned.forEach { definition ->
                    val availability = CapabilityResolver.resolve(definition, capabilities, scan)
                    PlannedRow(definition, availability)
                }
            } else {
                Text(
                    "Workflows that still need a reliable data source, trigger or baseline store stay listed here. They never offer Start until they can collect and interpret their minimum signals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = stalker.muted,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

private enum class CatalogueFilter(val label: String) {
    ALL("All"),
    STARTABLE("Ready to run"),
    LIMITED("Limited"),
    UNSUPPORTED("Unavailable"),
}

private fun DiagnosticDefinition.matchesQuery(query: String): Boolean {
    val text = query.trim().lowercase()
    if (text.isEmpty()) return true
    return title.lowercase().contains(text) ||
        subtitle.lowercase().contains(text) ||
        description.lowercase().contains(text) ||
        dtcs.any { it.lowercase().contains(text) } ||
        keywords.any { it.lowercase().contains(text) } ||
        category.label.lowercase().contains(text)
}

@Composable
private fun EngineProfileCard(capabilities: VehicleCapabilities, viewModel: CarStalkerViewModel) {
    val stalker = LocalStalkerColors.current
    val profile = capabilities.profile
    StalkerCard {
        Column {
            Text("Vehicle profile", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "The engine type changes which rules apply. It is never guessed from one live value.",
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(8.dp))
            Text("Fuel", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(FuelType.UNKNOWN, FuelType.PETROL, FuelType.DIESEL).forEach { fuel ->
                    ProfileChip(fuel.label, profile.fuelType == fuel) { viewModel.setFuelType(fuel) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Aspiration", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Aspiration.UNKNOWN, Aspiration.NATURALLY_ASPIRATED, Aspiration.FORCED_INDUCTION).forEach { aspiration ->
                    ProfileChip(aspiration.label, profile.aspiration == aspiration) { viewModel.setAspiration(aspiration) }
                }
            }
            if (profile.fuelType == FuelType.UNKNOWN) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set fuel and aspiration for the most precise interpretation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.warning,
                )
            }
        }
    }
}

@Composable
private fun ProfileChip(label: String, active: Boolean, onClick: () -> Unit) {
    val stalker = LocalStalkerColors.current
    Surface(
        modifier = Modifier.selectable(
            selected = active,
            role = androidx.compose.ui.semantics.Role.RadioButton,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(50),
        color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                Spacer(Modifier.width(3.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) stalker.accent else stalker.muted,
            )
        }
    }
}

@Composable
private fun CurrentCodesCard(report: com.carstalker.app.service.DtcReport, onReadCodes: () -> Unit) {
    val stalker = LocalStalkerColors.current
    val codes = report.modules.flatMap { module ->
        listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
    }.distinct()
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val scanAge = if (report.readAt > 0) System.currentTimeMillis() - report.readAt else null
    StalkerCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last code scan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onReadCodes) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Read")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                codes.joinToString().ifBlank { "No stored, pending or permanent codes read yet." },
                style = MaterialTheme.typography.bodyMedium,
                color = if (codes.isEmpty()) stalker.muted else MaterialTheme.colorScheme.onSurface,
            )
            if (report.readAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("Scanned ${timeFormat.format(java.util.Date(report.readAt))}")
                        if (scanAge != null && scanAge > 10 * 60_000) append(" · scan is over 10 minutes old")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (scanAge != null && scanAge > 10 * 60_000) stalker.warning else stalker.muted,
                )
            }
            if (codes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Guides that match a code are marked Suggested. A code is a starting point, not a diagnosis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideCard(
    definition: DiagnosticDefinition,
    availability: AvailabilityReport,
    connected: Boolean,
    matchedCode: String?,
    scanReadAt: Long,
    onOpen: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val accent = when {
        !connected -> stalker.muted
        availability.availability == DiagnosticAvailability.READY -> stalker.accent
        availability.availability in setOf(
            DiagnosticAvailability.LIMITED,
            DiagnosticAvailability.NEEDS_COLD_ENGINE,
            DiagnosticAvailability.NEEDS_WARM_ENGINE,
        ) -> stalker.warning
        availability.availability == DiagnosticAvailability.NEEDS_SCAN -> stalker.accentSecondary
        availability.availability in setOf(
            DiagnosticAvailability.VEHICLE_UNSUPPORTED,
            DiagnosticAvailability.REQUIRES_ENHANCED_PID,
        ) -> stalker.danger
        else -> stalker.muted
    }
    StalkerCard(
        modifier = Modifier.clickable(onClick = onOpen),
        accent = accent,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        guideIcon(definition.id),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        definition.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        definition.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                definition.description,
                style = MaterialTheme.typography.bodySmall,
                color = stalker.muted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (connected) {
                    AvailabilityPill(availability.availability)
                } else {
                    StatusPill("Connect to check", stalker.muted)
                }
                StatusPill("~${definition.estimatedMinutes} min", stalker.accentSecondary)
                matchedCode?.let {
                    StatusPill("Matches $it", stalker.success)
                }
            }
            if (matchedCode != null && scanReadAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Detected in the ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scanReadAt))} scan. A code is a starting point, not a diagnosis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
            }
            if (connected && availability.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                availability.reasons.take(3).forEach { reason ->
                    Text(
                        "• $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (availability.availability == DiagnosticAvailability.READY) stalker.muted else accent,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Needs: " + definition.requiredSignals.joinToString { it.label },
                style = MaterialTheme.typography.labelSmall,
                color = stalker.muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    !connected -> "View details →"
                    availability.availability.startable -> "Open guide →"
                    else -> "Why unavailable →"
                },
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

@Composable
private fun PlannedRow(definition: DiagnosticDefinition, availability: AvailabilityReport) {
    val stalker = LocalStalkerColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                definition.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            AvailabilityPill(availability.availability)
        }
        Text(
            definition.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
        definition.catalogueNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = stalker.muted)
        }
        Text(
            "Needs: " + definition.requiredSignals.joinToString { it.label },
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
    }
}

private fun guideIcon(id: String) = when (id) {
    CatalystWizard.ID -> Icons.Rounded.WarningAmber
    "lean_mixture" -> Icons.Rounded.Air
    "thermostat_ect" -> Icons.Rounded.Thermostat
    "charging_system" -> Icons.Rounded.Bolt
    "map_plausibility" -> Icons.Rounded.Speed
    else -> Icons.Rounded.Search
}
