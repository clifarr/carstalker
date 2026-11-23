package com.carstalker.app.data

import com.carstalker.core.guided.GuidedResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Structured, privacy-conscious export of a guided result. The JSON contains the evidence and
 * limitations, not the raw samples (those stay in the recording CSV unless the user saves them).
 */
object GuidedResultExport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(result: GuidedResult): String = json.encodeToString(result.toExport())

    private fun GuidedResult.toExport(): GuidedExport = GuidedExport(
        diagnosticId = diagnosticId,
        definitionVersion = definitionVersion,
        title = title,
        status = status.name,
        statusLabel = status.label,
        confidence = confidence.name,
        confidenceLabel = confidence.label,
        summary = summary,
        profile = ExportProfile(
            fuelType = profile.fuelType.name,
            aspiration = profile.aspiration.name,
            bankCount = profile.bankCount,
        ),
        observations = observations.map { ExportObservation(it.label, it.value, it.source) },
        findings = findings.map {
            ExportFinding(
                id = it.id,
                title = it.title,
                evidenceLevel = it.evidenceLevel.name,
                confidence = it.confidence.name,
                explanation = it.explanation,
                supporting = it.supporting,
                against = it.against,
                alternatives = it.alternatives,
                nextCheck = it.nextCheck,
            )
        },
        stages = stages.map {
            ExportStage(
                stageId = it.stageId,
                title = it.title,
                valid = it.valid,
                completed = it.completed,
                sampleCount = it.sampleCount,
                durationSeconds = it.durationSeconds,
                effectiveHz = it.effectiveHz,
                gaps = it.gaps,
                metrics = it.metrics,
                notes = it.notes,
            )
        },
        quality = ExportQuality(
            requiredSignalsPresent = quality.requiredSignalsPresent,
            samplingAdequate = quality.samplingAdequate,
            missingSignals = quality.missingSignals,
            notes = quality.notes,
            interruptions = quality.interruptions,
        ),
        limitations = limitations,
        warnings = warnings,
        nextSteps = nextSteps,
        generatedAt = generatedAt,
        skippedStages = stagesSkipped,
    )
}

@Serializable
data class GuidedExport(
    val schemaVersion: Int = 1,
    val diagnosticId: String,
    val definitionVersion: String,
    val title: String,
    val status: String,
    val statusLabel: String,
    val confidence: String,
    val confidenceLabel: String,
    val summary: String,
    val profile: ExportProfile,
    val observations: List<ExportObservation>,
    val findings: List<ExportFinding>,
    val stages: List<ExportStage>,
    val quality: ExportQuality,
    val limitations: List<String>,
    val warnings: List<String>,
    val nextSteps: List<String>,
    val generatedAt: Long,
    val skippedStages: List<String>,
)

@Serializable
data class ExportProfile(
    val fuelType: String,
    val aspiration: String,
    val bankCount: Int,
)

@Serializable
data class ExportObservation(
    val label: String,
    val value: String,
    val source: String,
)

@Serializable
data class ExportFinding(
    val id: String,
    val title: String,
    val evidenceLevel: String,
    val confidence: String,
    val explanation: String,
    val supporting: List<String>,
    val against: List<String>,
    val alternatives: List<String>,
    val nextCheck: String,
)

@Serializable
data class ExportStage(
    val stageId: String,
    val title: String,
    val valid: Boolean,
    val completed: Boolean,
    val sampleCount: Int,
    val durationSeconds: Double,
    val effectiveHz: Double,
    val gaps: Int,
    val metrics: Map<String, Double>,
    val notes: List<String>,
)

@Serializable
data class ExportQuality(
    val requiredSignalsPresent: Boolean,
    val samplingAdequate: Boolean,
    val missingSignals: List<String>,
    val notes: List<String>,
    val interruptions: Int,
)
