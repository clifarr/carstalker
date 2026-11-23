package com.carstalker.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.carstalker.app.data.ReportPdfWriter
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReportPdfWriterTest {

    @Test
    fun writesAMultiPageReportWithHeaderFooterAndLongParagraphs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = DiagnosticReport(
            generatedAt = 1_700_000_000_000,
            appVersion = "0.3.0",
            shopName = "Cliff's Garage",
            vehicle = ReportVehicle(vin = "JF1ZCAC10D2000001", year = 2013, make = "Subaru"),
            adapter = "ELM327 v2.2",
            protocol = "ISO 15765-4 CAN (11-bit, 500k)",
            modules = (1..8).map { moduleIndex ->
                ReportModule(
                    name = "Engine module $moduleIndex",
                    address = "0x7E${moduleIndex % 10}",
                    dtcs = (1..5).map { codeIndex ->
                        ReportDtc(
                            code = "P0${(moduleIndex * 100 + codeIndex).toString().padStart(3, '0')}",
                            description = "A deliberately long DTC description for layout testing, module $moduleIndex code $codeIndex.",
                            status = "Stored + Pending",
                        )
                    },
                )
            },
            freezeFrames = listOf(
                ReportFreezeFrame(
                    frame = 0,
                    dtc = "P0420",
                    measurements = (1..20).map {
                        ReportMeasurement("Measurement $it", "$it.5", "unit")
                    },
                ),
            ),
            mode06 = (1..12).map {
                ReportMode06(
                    label = "Monitor test $it with a long label",
                    value = "12.5",
                    unit = "mV",
                    min = "0",
                    max = "10",
                    state = "FAIL",
                )
            },
            monitors = (1..10).map { ReportMonitor("Monitor $it", if (it % 2 == 0) "Ready" else "Not ready") },
            wizardResults = listOf(
                ReportWizardResult(
                    title = "Vacuum leak, MAF or fuel delivery?",
                    summary = "Bank 1 combined trim is strongly positive at idle and improves materially when airflow rises. That is consistent with unmetered air, but not proof of a specific leak.",
                    confidence = "Medium",
                    causes = (1..6).map {
                        ReportWizardCause(
                            title = "Candidate cause $it with a long title",
                            likelihood = "Supportive, not conclusive",
                            confidence = "Medium",
                            explanation = "This is a deliberately long explanation that should wrap cleanly across the page without touching the section rules or the footer.",
                            evidence = (1..4).map { evidenceIndex -> "Evidence item $evidenceIndex with a value and a unit" },
                        )
                    },
                    evidence = (1..12).map { "Observation $it: a measured value with units and a source" },
                    warnings = (1..5).map { "Warning $it: this result is not a parts order." },
                    nextSteps = (1..5).map { "Next step $it: inspect the relevant system before replacing parts." },
                ),
            ),
            notes = "A long notes paragraph for pagination testing. ".repeat(120),
        )

        val file = File(context.getExternalFilesDir(null) ?: context.cacheDir, "report-layout-test.pdf")
        file.delete()
        ReportPdfWriter.write(report, file, context)

        assertTrue("PDF was not written", file.length() > 5_000)
        // The brand mark must stay vector; a rasterized logo shows up as an image XObject.
        val pdf = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("PDF embeds a raster image (logo must be vector)", pdf.contains("/Subtype /Image"))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("Expected a multi-page report, got ${renderer.pageCount}", renderer.pageCount >= 3)
            }
        }
    }
}
