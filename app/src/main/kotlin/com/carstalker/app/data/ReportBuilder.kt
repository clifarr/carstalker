package com.carstalker.app.data

import com.carstalker.app.service.DtcReport
import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcGroup
import com.carstalker.core.diagnose.WizardResult
import com.carstalker.core.elm.AdapterInfo
import com.carstalker.core.guided.GuidedResult
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.report.DiagnosticReport
import com.carstalker.core.report.ReportDtc
import com.carstalker.core.report.ReportFreezeFrame
import com.carstalker.core.report.ReportMeasurement
import com.carstalker.core.report.ReportMode06
import com.carstalker.core.report.ReportModule
import com.carstalker.core.report.ReportMonitor
import com.carstalker.core.report.ReportVehicle
import com.carstalker.core.report.ReportWizardCause
import com.carstalker.core.report.ReportWizardResult
import com.carstalker.core.vehicle.VehicleIdentity
import java.util.Locale

/** Converts the live session state into the shared report model. */
object ReportBuilder {

    fun build(
        appVersion: String,
        shopName: String,
        identity: VehicleIdentity,
        adapter: AdapterInfo?,
        report: DtcReport,
        wizardResult: WizardResult? = null,
        guidedResults: List<GuidedResult> = emptyList(),
    ): DiagnosticReport {
        val modules = report.modules.map { module ->
            val byCode = LinkedHashMap<String, Pair<Dtc, MutableList<String>>>()
            for (group in DtcGroup.entries) {
                for (dtc in module.forGroup(group)) {
                    val entry = byCode.getOrPut(dtc.code) { dtc to mutableListOf() }
                    if (group.label !in entry.second) entry.second += group.label
                }
            }
            val dtcs = byCode.values.map { (dtc, statuses) ->
                ReportDtc(
                    code = dtc.code,
                    description = dtc.description,
                    status = statuses.joinToString(" + "),
                )
            }
            ReportModule(
                name = module.name,
                address = module.source?.let { "0x${it.toString(16).uppercase(Locale.US)}" },
                dtcs = dtcs,
            )
        }

        val freezeFrames = report.freezeFrames.map { frame ->
            ReportFreezeFrame(
                frame = frame.frame,
                dtc = frame.dtc,
                measurements = frame.values.mapNotNull { (id, value) ->
                    val pid = PidCatalog.find(id) ?: return@mapNotNull null
                    ReportMeasurement(
                        name = pid.name,
                        value = DiagnosticReport.formatValue(value, pid.decimals),
                        unit = pid.unit,
                    )
                }.sortedBy { it.name },
            )
        }

        val mode06 = report.mode06.results.map { result ->
            ReportMode06(
                label = result.label,
                value = DiagnosticReport.formatValue(result.value, 2),
                unit = result.unit,
                min = result.min?.let { DiagnosticReport.formatValue(it, 2) },
                max = result.max?.let { DiagnosticReport.formatValue(it, 2) },
                state = when (result.passed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "NOT COMPLETED"
                },
            )
        }

        val monitors = report.monitor?.monitors.orEmpty().map { monitor ->
            ReportMonitor(
                name = monitor.name,
                state = when {
                    !monitor.supported -> "N/A"
                    monitor.complete -> "Ready"
                    else -> "Not ready"
                },
            )
        }

        val wizardResults = wizardResult?.let { wizard ->
            listOf(
                ReportWizardResult(
                    title = wizard.title,
                    summary = wizard.summary,
                    confidence = wizard.confidence.label,
                    causes = wizard.causes.map { cause ->
                        ReportWizardCause(
                            title = cause.title,
                            likelihood = cause.likelihood.label,
                            confidence = cause.confidence.label,
                            explanation = cause.explanation,
                            evidence = cause.evidence,
                        )
                    },
                    evidence = wizard.evidence.map { "${it.label}: ${it.value} (${it.source})" },
                    warnings = wizard.warnings,
                    nextSteps = wizard.nextSteps,
                ),
            )
        }.orEmpty() + guidedResults.map { guided ->
            ReportWizardResult(
                title = guided.title,
                summary = guided.summary,
                confidence = guided.confidence.label,
                causes = guided.findings.map { finding ->
                    ReportWizardCause(
                        title = finding.title,
                        likelihood = finding.evidenceLevel.label,
                        confidence = finding.confidence.label,
                        explanation = finding.explanation,
                        evidence = finding.supporting + finding.alternatives.map { "Alternative: $it" },
                    )
                },
                evidence = guided.observations.map { "${it.label}: ${it.value} (${it.source})" },
                warnings = (guided.warnings + guided.limitations).distinct(),
                nextSteps = guided.nextSteps,
            )
        }

        return DiagnosticReport(
            generatedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            shopName = shopName.takeIf { it.isNotBlank() },
            vehicle = ReportVehicle(
                vin = identity.vin,
                year = identity.modelYear,
                make = identity.make,
            ),
            adapter = adapter?.identifier,
            protocol = adapter?.protocolName,
            modules = modules,
            freezeFrames = freezeFrames,
            mode06 = mode06,
            monitors = monitors,
            wizardResults = wizardResults,
        )
    }
}
