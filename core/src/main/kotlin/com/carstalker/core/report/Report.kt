package com.carstalker.core.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReportVehicle(
    val vin: String? = null,
    val year: Int? = null,
    val make: String? = null,
    val model: String? = null,
    val nickname: String? = null,
) {
    val title: String
        get() = listOfNotNull(
            nickname?.takeIf { it.isNotBlank() },
            year?.toString(),
            make?.takeIf { it.isNotBlank() },
            model?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { vin?.let { "VIN ${it.takeLast(6)}" } ?: "Unknown vehicle" }
}

data class ReportDtc(
    val code: String,
    val description: String,
    val status: String,
)

data class ReportModule(
    val name: String,
    val address: String?,
    val dtcs: List<ReportDtc>,
)

data class ReportMeasurement(
    val name: String,
    val value: String,
    val unit: String,
)

data class ReportFreezeFrame(
    val frame: Int,
    val dtc: String?,
    val measurements: List<ReportMeasurement>,
)

data class ReportMonitor(
    val name: String,
    val state: String,
)

data class ReportMode06(
    val label: String,
    val value: String,
    val unit: String,
    val min: String?,
    val max: String?,
    val state: String,
    val mappingTrusted: Boolean = true,
    val raw: String? = null,
)

data class ReportWizardCause(
    val title: String,
    val likelihood: String,
    val confidence: String,
    val explanation: String,
    val evidence: List<String>,
)

data class ReportWizardResult(
    val title: String,
    val summary: String,
    val confidence: String,
    val ruleVersion: String? = null,
    val causes: List<ReportWizardCause>,
    val evidence: List<String>,
    val warnings: List<String>,
    val nextSteps: List<String>,
)

/**
 * A self-contained diagnostic report. The same model feeds the PDF renderer and plain-text
 * sharing, so the two can never disagree.
 */
data class DiagnosticReport(
    val generatedAt: Long,
    val appVersion: String,
    val shopName: String?,
    val vehicle: ReportVehicle,
    val adapter: String?,
    val protocol: String?,
    val modules: List<ReportModule>,
    val freezeFrames: List<ReportFreezeFrame> = emptyList(),
    val mode06: List<ReportMode06> = emptyList(),
    /** Explains why Mode 06 counts are absent; absence is not zero failures. */
    val mode06Note: String? = null,
    val monitors: List<ReportMonitor> = emptyList(),
    val wizardResults: List<ReportWizardResult> = emptyList(),
    val notes: String? = null,
    /** True when the report came from the demo transport; it must never look like a real car. */
    val isDemo: Boolean = false,
) {
    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    val generatedLabel: String
        get() = dateFormat.format(Instant.ofEpochMilli(generatedAt).atZone(ZoneId.systemDefault()))

    val totalCodes: Int get() = modules.sumOf { it.dtcs.size }

    fun toPlainText(): String = buildString {
        if (!shopName.isNullOrBlank()) {
            appendLine(shopName)
            appendLine()
        }
        appendLine("CarStalker diagnostic report")
        if (isDemo) appendLine("DEMO DATA — simulated vehicle, not a real diagnostic")
        appendLine("Generated: $generatedLabel")
        appendLine("Vehicle: ${vehicle.title}")
        vehicle.vin?.let { appendLine("VIN: $it") }
        adapter?.let { appendLine("Adapter: $it") }
        protocol?.let { appendLine("Protocol: $it") }
        appendLine()

        appendLine("TROUBLE CODES (${totalCodes})")
        if (modules.isEmpty()) {
            appendLine("No module responded.")
        } else {
            for (module in modules) {
                appendLine()
                appendLine("${module.name}${module.address?.let { " [$it]" } ?: ""}")
                if (module.dtcs.isEmpty()) {
                    appendLine("  No codes reported.")
                } else {
                    for (dtc in module.dtcs) {
                        appendLine("  ${dtc.code}  ${dtc.description}  (${dtc.status})")
                    }
                }
            }
        }

        if (monitors.isNotEmpty()) {
            appendLine()
            appendLine("READINESS MONITORS")
            for (monitor in monitors) {
                appendLine("  ${monitor.name}: ${monitor.state}")
            }
        }

        if (freezeFrames.isNotEmpty()) {
            appendLine()
            appendLine("FREEZE FRAMES")
            for (frame in freezeFrames) {
                appendLine("  Frame ${frame.frame}${frame.dtc?.let { " · $it" } ?: ""}")
                for (measurement in frame.measurements) {
                    appendLine("    ${measurement.name}: ${measurement.value} ${measurement.unit}")
                }
            }
        }

        if (mode06.isNotEmpty()) {
            appendLine()
            appendLine("MODE 06 MONITOR TESTS")
            for (test in mode06) {
                val limits = listOfNotNull(
                    test.min?.let { "min $it" },
                    test.max?.let { "max $it" },
                ).joinToString(", ")
                appendLine("  ${test.label}: ${test.value} ${test.unit}${if (limits.isNotEmpty()) " ($limits)" else ""} — ${test.state}")
            }
        }

        if (wizardResults.isNotEmpty()) {
            appendLine()
            appendLine("GUIDED DIAGNOSIS")
            for (wizard in wizardResults) {
                appendLine()
                appendLine("${wizard.title} — ${wizard.confidence} confidence")
                appendLine(wizard.summary)
                if (wizard.causes.isNotEmpty()) {
                    appendLine("Findings:")
                    for (cause in wizard.causes) {
                        appendLine("  ${cause.likelihood}: ${cause.title} (${cause.confidence} confidence)")
                        appendLine("    ${cause.explanation}")
                        for (item in cause.evidence) appendLine("    • $item")
                    }
                }
                if (wizard.evidence.isNotEmpty()) {
                    appendLine("Evidence:")
                    for (item in wizard.evidence) appendLine("  • $item")
                }
                if (wizard.warnings.isNotEmpty()) {
                    appendLine("Warnings:")
                    for (item in wizard.warnings) appendLine("  • $item")
                }
                if (wizard.nextSteps.isNotEmpty()) {
                    appendLine("Next steps:")
                    for (item in wizard.nextSteps) appendLine("  • $item")
                }
            }
        }

        if (!notes.isNullOrBlank()) {
            appendLine()
            appendLine("NOTES")
            appendLine(notes)
        }

        appendLine()
        appendLine("Generated by CarStalker $appVersion. Generic OBD-II data only; verify with service information.")
    }

    companion object {
        fun formatValue(value: Double, decimals: Int = 1): String =
            if (value == value.toLong().toDouble()) value.toLong().toString()
            else String.format(Locale.US, "%.${decimals.coerceIn(0, 3)}f", value)
    }
}
