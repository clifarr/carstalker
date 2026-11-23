package com.carstalker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    val guide = activeGuide
    if (connection is ConnectionState.Ready && guide != null) {
        if (guide.legacyWizardId != null) {
            CatalystFlowScreen(
                viewModel = viewModel,
                onBack = { viewModel.closeGuide() },
            )
        } else {
            GuidedTestScreen(
                definition = guide,
                viewModel = viewModel,
                onBack = { viewModel.closeGuide() },
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

        if (connection !is ConnectionState.Ready) {
            Spacer(Modifier.height(18.dp))
            StalkerCard(accent = stalker.warning) {
                Column {
                    Text("Adapter not connected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect to run a guided test. The demo adapter works too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = stalker.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                    ) { Text("Connect") }
                }
            }
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        Spacer(Modifier.height(18.dp))
        EngineProfileCard(capabilities = capabilities, viewModel = viewModel)
        Spacer(Modifier.height(14.dp))
        CurrentCodesCard(report = report, onReadCodes = viewModel::readCodes)
        Spacer(Modifier.height(20.dp))

        val codes = report.modules.flatMap { module ->
            listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
        }.distinct()
        val scan = report.toScanContext()
        val suggested = DiagnosticRegistry.suggestedForCodes(codes).map { it.id }.toSet()
        val implemented = DiagnosticRegistry.implemented()
            .sortedByDescending { it.id in suggested }
        val planned = DiagnosticRegistry.planned()

        DiagnosticPhase.entries.forEach { phase ->
            val phaseGuides = implemented.filter { it.phase == phase }
            if (phaseGuides.isEmpty()) return@forEach
            Spacer(Modifier.height(10.dp))
            SectionHeader("${phase.label} · ${phaseGuides.size} guide(s)")
            Spacer(Modifier.height(10.dp))
            phaseGuides.forEach { definition ->
                val availability = CapabilityResolver.resolve(definition, capabilities, scan)
                GuideCard(
                    definition = definition,
                    availability = availability,
                    suggested = definition.id in suggested,
                    onOpen = { viewModel.openGuide(definition) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

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
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) stalker.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) stalker.accent else stalker.muted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CurrentCodesCard(report: com.carstalker.app.service.DtcReport, onReadCodes: () -> Unit) {
    val stalker = LocalStalkerColors.current
    val codes = report.modules.flatMap { module ->
        listOf(module.stored, module.pending, module.permanent).flatten().map { it.code }
    }.distinct()
    StalkerCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current codes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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

@Composable
private fun GuideCard(
    definition: DiagnosticDefinition,
    availability: AvailabilityReport,
    suggested: Boolean,
    onOpen: () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val accent = when (availability.availability) {
        DiagnosticAvailability.READY -> stalker.accent
        DiagnosticAvailability.LIMITED, DiagnosticAvailability.NEEDS_COLD_ENGINE, DiagnosticAvailability.NEEDS_WARM_ENGINE -> stalker.warning
        DiagnosticAvailability.NEEDS_SCAN -> stalker.accentSecondary
        DiagnosticAvailability.VEHICLE_UNSUPPORTED, DiagnosticAvailability.REQUIRES_ENHANCED_PID -> stalker.danger
        DiagnosticAvailability.PLANNED -> stalker.muted
    }
    StalkerCard(
        modifier = Modifier.clickable(enabled = availability.availability.startable, onClick = onOpen),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AvailabilityPill(availability.availability)
                StatusPill("~${definition.estimatedMinutes} min", stalker.accentSecondary)
                if (suggested) StatusPill("Suggested", stalker.success)
            }
            if (availability.reasons.isNotEmpty()) {
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
            if (availability.availability.startable) {
                Spacer(Modifier.height(10.dp))
                Text("Open guide →", style = MaterialTheme.typography.labelMedium, color = accent)
            }
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
