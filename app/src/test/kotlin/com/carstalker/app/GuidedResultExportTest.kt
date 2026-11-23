package com.carstalker.app

import com.carstalker.app.data.GuidedResultExport
import com.carstalker.core.guided.DiagnosticStatus
import com.carstalker.core.guided.EvidenceLevel
import com.carstalker.core.guided.GuidedFinding
import com.carstalker.core.guided.GuidedObservation
import com.carstalker.core.guided.GuidedResult
import com.carstalker.core.guided.ResultConfidence
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedResultExportTest {

    @Test
    fun `exports the evidence and limitations as structured json`() {
        val result = GuidedResult(
            diagnosticId = "lean_mixture",
            definitionVersion = "1.0.0",
            title = "Vacuum leak, MAF or fuel delivery?",
            status = DiagnosticStatus.SUSPICIOUS,
            confidence = ResultConfidence.MEDIUM,
            summary = "Idle trims improve at higher airflow.",
            observations = listOf(GuidedObservation("Warm idle combined trim B1", "+24.0 %")),
            findings = listOf(
                GuidedFinding(
                    id = "unmetered_air",
                    title = "Consistent with unmetered air",
                    evidenceLevel = EvidenceLevel.SUPPORTIVE,
                    confidence = ResultConfidence.MEDIUM,
                    explanation = "Not proof of a specific leak.",
                    supporting = listOf("Idle combined trim +24.0 %"),
                    alternatives = listOf("MAF bias", "Fuel delivery"),
                    nextCheck = "Smoke-test the intake.",
                ),
            ),
            limitations = listOf("A single capture cannot prove a component failed."),
            warnings = listOf("Not a parts order."),
            nextSteps = listOf("Inspect the intake."),
            generatedAt = 1_700_000_000_000,
        )

        val json = GuidedResultExport.encode(result)

        assertTrue(json.contains("\"diagnosticId\": \"lean_mixture\""))
        assertTrue(json.contains("\"status\": \"SUSPICIOUS\""))
        assertTrue(json.contains("\"id\": \"unmetered_air\""))
        assertTrue(json.contains("\"alternatives\""))
        assertTrue(json.contains("\"limitations\""))
        assertTrue(json.contains("\"schemaVersion\": 1"))
    }
}
