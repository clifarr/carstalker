package com.carstalker.app

import com.carstalker.app.data.ReportBuilder
import com.carstalker.app.service.DtcReport
import com.carstalker.core.diagnose.Confidence
import com.carstalker.core.diagnose.Likelihood
import com.carstalker.core.diagnose.WizardCause
import com.carstalker.core.diagnose.WizardEvidence
import com.carstalker.core.diagnose.WizardResult
import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcCatalog
import com.carstalker.core.dtc.EcuDtcReport
import com.carstalker.core.guided.DiagnosticStatus
import com.carstalker.core.guided.EvidenceLevel
import com.carstalker.core.guided.GuidedFinding
import com.carstalker.core.guided.GuidedObservation
import com.carstalker.core.guided.GuidedResult
import com.carstalker.core.guided.ResultConfidence
import com.carstalker.core.vehicle.VehicleIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    @Test
    fun `combines the same code from stored and pending groups`() {
        val dtc = Dtc("P0171", DtcCatalog.describe("P0171"), Dtc.Category.POWERTRAIN)
        val report = ReportBuilder.build(
            appVersion = "0.2.0",
            shopName = "Test Garage",
            identity = VehicleIdentity(vin = "JF1ZCAC10D2000001"),
            adapter = null,
            report = DtcReport(
                modules = listOf(
                    EcuDtcReport(
                        source = 0x7E8,
                        stored = listOf(dtc),
                        pending = listOf(dtc),
                    ),
                ),
                readAt = 1,
            ),
        )

        assertEquals("2013 Subaru", report.vehicle.title)
        val module = report.modules.single()
        assertEquals("Engine", module.name)
        assertEquals(1, module.dtcs.size)
        assertTrue(module.dtcs.single().status.contains("Stored"))
        assertTrue(module.dtcs.single().status.contains("Pending"))
        assertTrue(report.toPlainText().contains("P0171"))
    }

    @Test
    fun `includes the guided diagnosis result`() {
        val wizard = WizardResult(
            wizardId = "catalyst_o2",
            title = "Catalyst or oxygen sensor?",
            summary = "The catalyst is the most likely cause.",
            confidence = Confidence.HIGH,
            causes = listOf(
                WizardCause(
                    id = "catalyst_worn",
                    title = "Catalytic converter is likely worn",
                    likelihood = Likelihood.LIKELY,
                    confidence = Confidence.HIGH,
                    explanation = "Downstream mirrors upstream and Mode 06 failed.",
                    evidence = listOf("Mode 06 catalyst test failed"),
                    nextSteps = listOf("Get a second confirmation."),
                ),
            ),
            evidence = listOf(WizardEvidence("Mode 06 catalyst", "FAIL", "Mode 06")),
            warnings = listOf("Do not replace the converter on P0420 alone."),
            nextSteps = listOf("Get a second confirmation."),
            captureQuality = null,
            generatedAt = 1,
        )
        val report = ReportBuilder.build(
            appVersion = "0.3.0",
            shopName = "",
            identity = VehicleIdentity(vin = "JF1ZCAC10D2000001"),
            adapter = null,
            report = DtcReport(readAt = 1),
            wizardResult = wizard,
        )

        assertEquals(1, report.wizardResults.size)
        assertTrue(report.toPlainText().contains("GUIDED DIAGNOSIS"))
        assertTrue(report.toPlainText().contains("Catalytic converter is likely worn"))
    }

    @Test
    fun `includes a shared guided result with evidence and alternatives`() {
        val guided = GuidedResult(
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
                    explanation = "The pattern is consistent with a leak, but not proof.",
                    supporting = listOf("Idle combined trim +24.0 %"),
                    alternatives = listOf("MAF bias", "Fuel delivery"),
                    nextCheck = "Smoke-test the intake.",
                ),
            ),
            warnings = listOf("Not a parts order."),
            nextSteps = listOf("Inspect the intake."),
            generatedAt = 1,
        )
        val report = ReportBuilder.build(
            appVersion = "0.3.0",
            shopName = "",
            identity = VehicleIdentity(vin = "JF1ZCAC10D2000001"),
            adapter = null,
            report = DtcReport(readAt = 1),
            guidedResults = listOf(guided),
        )

        assertEquals(1, report.wizardResults.size)
        val text = report.toPlainText()
        assertTrue(text.contains("Vacuum leak"))
        assertTrue(text.contains("Consistent with unmetered air"))
        assertTrue(text.contains("MAF bias"))
        assertTrue(text.contains("Warm idle combined trim B1"))
    }
}
