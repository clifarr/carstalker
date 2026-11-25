package com.carstalker.app.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.carstalker.app.R
import com.carstalker.core.report.DiagnosticReport
import java.io.File
import java.io.FileOutputStream

/**
 * Dependency-free PDF renderer for [DiagnosticReport].
 *
 * The layout uses a top-down cursor and real font metrics rather than treating every drawText
 * baseline as the next block's top. Sections draw their rule above the heading, paragraphs are
 * paginated by line, and every page carries the brand mark plus a footer.
 */
object ReportPdfWriter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
    private const val FOOTER_HEIGHT = 22f
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
    private const val FIRST_LOGO_SIZE = 42f
    private const val NEXT_LOGO_SIZE = 24f

    // MiSans (the default HyperOS sans) produces a Type 3 subset whose ToUnicode map loses
    // punctuation in the PDF text layer. The condensed family is a static sans that keeps
    // colons/periods searchable and copyable on both HyperOS and AOSP.
    private val sans: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val sansBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun write(report: DiagnosticReport, file: File, context: Context? = null) {
        file.parentFile?.mkdirs()
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
        )
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = textPaint(18f, bold = true, color = Color.rgb(20, 20, 20))
        val shopPaint = textPaint(12f, bold = true, color = Color.rgb(60, 90, 20))
        val headingPaint = textPaint(11f, bold = true, color = Color.rgb(20, 20, 20))
        val headerPaint = textPaint(9.5f, bold = true, color = Color.rgb(40, 40, 40))
        val bodyPaint = textPaint(9.5f, color = Color.rgb(40, 40, 40))
        val mutedPaint = textPaint(8.5f, color = Color.rgb(100, 100, 100))
        val footerPaint = textPaint(7.5f, color = Color.rgb(120, 120, 120))
        val codePaint = textPaint(9.5f, bold = true, color = Color.rgb(160, 30, 30), mono = true)
        val demoPaint = textPaint(7.5f, bold = true, color = Color.rgb(180, 40, 20))
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 211, 218)
            strokeWidth = 0.8f
        }
        val brandMark = context?.let { BrandMark.load(it) }

        fun drawTextAt(x: Float, top: Float, text: String, paint: Paint) {
            val baseline = top - paint.fontMetrics.ascent
            canvas.drawText(text, x, baseline, paint)
        }

        fun drawLogo(x: Float, top: Float, size: Float) {
            val mark = brandMark ?: return
            canvas.save()
            val clip = Path().apply {
                addCircle(x + size / 2f, top + size / 2f, size / 2f, Path.Direction.CW)
            }
            canvas.clipPath(clip)
            mark.draw(canvas, x, top, size)
            canvas.restore()
        }

        fun drawHeader() {
            if (pageNumber == 1) {
                drawLogo(MARGIN, MARGIN, FIRST_LOGO_SIZE)
                val textX = MARGIN + FIRST_LOGO_SIZE + 12f
                var top = MARGIN
                report.shopName?.takeIf { it.isNotBlank() }?.let {
                    drawTextAt(textX, top, it, shopPaint)
                    top += 17f
                }
                drawTextAt(textX, top, "CarStalker diagnostic report", titlePaint)
                top += 25f
                drawTextAt(textX, top, "Generated ${report.generatedLabel}", mutedPaint)
                top += 12f
                if (report.isDemo) {
                    drawTextAt(textX, top, "DEMO DATA — simulated vehicle, not a real diagnostic", demoPaint)
                    top += 12f
                }
                y = maxOf(MARGIN + FIRST_LOGO_SIZE + 14f, top + 6f)
            } else {
                drawLogo(MARGIN, MARGIN, NEXT_LOGO_SIZE)
                drawTextAt(
                    MARGIN + NEXT_LOGO_SIZE + 8f,
                    MARGIN + 6f,
                    "CarStalker diagnostic report",
                    headerPaint,
                )
                if (report.isDemo) {
                    drawTextAt(
                        MARGIN + NEXT_LOGO_SIZE + 8f,
                        MARGIN + 16f,
                        "DEMO DATA — simulated vehicle",
                        demoPaint,
                    )
                }
                val pageLabel = "Page $pageNumber"
                drawTextAt(
                    PAGE_WIDTH - MARGIN - mutedPaint.measureText(pageLabel),
                    MARGIN + 6f,
                    pageLabel,
                    mutedPaint,
                )
                y = MARGIN + NEXT_LOGO_SIZE + 8f
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
                y += 6f
            }
        }

        fun drawFooter() {
            val ruleY = PAGE_HEIGHT - MARGIN + 2f
            canvas.drawLine(MARGIN, ruleY, PAGE_WIDTH - MARGIN, ruleY, rulePaint)
            val top = ruleY + 4f
            drawTextAt(
                MARGIN,
                top,
                "CarStalker ${report.appVersion} · Generic OBD-II data only; verify with service information.",
                footerPaint,
            )
            val pageLabel = "Page $pageNumber"
            drawTextAt(
                PAGE_WIDTH - MARGIN - footerPaint.measureText(pageLabel),
                top,
                pageLabel,
                footerPaint,
            )
        }

        fun finishCurrentPage() {
            drawFooter()
            document.finishPage(page)
        }

        fun startNewPage() {
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
            )
            canvas = page.canvas
            drawHeader()
        }

        fun newPageIfNeeded(needed: Float) {
            if (y + needed <= CONTENT_BOTTOM) return
            finishCurrentPage()
            startNewPage()
        }

        fun textLine(text: String, paint: Paint, gap: Float = 4f, x: Float = MARGIN) {
            val metrics = paint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent
            newPageIfNeeded(lineHeight + gap)
            drawTextAt(x, y, text, paint)
            y += lineHeight + gap
        }

        fun paragraph(text: String, paint: Paint, indent: Float = 0f, spacing: Float = 4f) {
            if (text.isBlank()) return
            val paintCopy = TextPaint(paint)
            val width = (CONTENT_WIDTH - indent).toInt().coerceAtLeast(40)
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paintCopy, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()

            fun lineTop(index: Int): Float =
                if (index >= layout.lineCount) layout.height.toFloat()
                else layout.getLineTop(index).toFloat()

            var line = 0
            while (line < layout.lineCount) {
                val available = CONTENT_BOTTOM - y
                val top = lineTop(line)
                val advance = lineTop(line + 1) - top
                if (available < advance) {
                    finishCurrentPage()
                    startNewPage()
                    continue
                }
                var endLine = line
                while (endLine < layout.lineCount && lineTop(endLine + 1) - top <= available) {
                    endLine++
                }
                if (endLine == line) {
                    finishCurrentPage()
                    startNewPage()
                    continue
                }
                canvas.save()
                canvas.translate(MARGIN + indent, y - top)
                // Clip at the top of the next line, not at the raw page bottom: otherwise the
                // last line is drawn partially and looks sliced in half.
                canvas.clipRect(0f, top, width.toFloat(), lineTop(endLine))
                layout.draw(canvas)
                canvas.restore()
                y += lineTop(endLine) - top
                line = endLine
                if (line < layout.lineCount) {
                    finishCurrentPage()
                    startNewPage()
                }
            }
            y += spacing
        }

        fun section(title: String) {
            y += 10f
            newPageIfNeeded(headingPaint.textSize + 24f)
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += 5f
            textLine(title, headingPaint, gap = 6f)
        }

        drawHeader()

        textLine("Vehicle: ${report.vehicle.title}", bodyPaint)
        report.vehicle.vin?.let { textLine("VIN: $it", bodyPaint) }
        report.adapter?.let { textLine("Adapter: $it", bodyPaint) }
        report.protocol?.let { textLine("Protocol: $it", bodyPaint) }

        section("TROUBLE CODES (${report.totalCodes})")
        if (report.modules.isEmpty()) {
            paragraph("No module responded to the code request.", bodyPaint)
        } else {
            for (module in report.modules) {
                val address = module.address
                    ?.takeIf { !module.name.contains(it, ignoreCase = true) }
                    ?.let { "  [$it]" }
                    .orEmpty()
                textLine("${module.name}$address", headingPaint, gap = 4f)
                if (module.dtcs.isEmpty()) {
                    paragraph("No codes reported.", mutedPaint, indent = 10f)
                } else {
                    for (dtc in module.dtcs) {
                        textLine("${dtc.code}  ${dtc.status}", codePaint, gap = 3f)
                        paragraph(dtc.description, bodyPaint, indent = 12f)
                    }
                }
                y += 4f
            }
        }

        if (report.monitors.isNotEmpty()) {
            section("READINESS MONITORS")
            for (monitor in report.monitors) {
                textLine("${monitor.name}: ${monitor.state}", bodyPaint, gap = 2.5f)
            }
        }

        if (report.freezeFrames.isNotEmpty()) {
            section("FREEZE FRAMES")
            for (frame in report.freezeFrames) {
                textLine("Frame ${frame.frame}${frame.dtc?.let { " · $it" } ?: ""}", headingPaint, gap = 4f)
                for (measurement in frame.measurements) {
                    textLine("${measurement.name}: ${measurement.value} ${measurement.unit}", bodyPaint, gap = 2.5f)
                }
                y += 4f
            }
        }

        if (report.mode06.isNotEmpty() || report.mode06Note != null) {
            section("MODE 06 MONITOR TESTS")
            report.mode06Note?.let { paragraph(it, mutedPaint) }
            for (test in report.mode06) {
                val limits = listOfNotNull(
                    test.min?.let { "min $it" },
                    test.max?.let { "max $it" },
                ).joinToString(", ")
                val suffix = if (limits.isNotEmpty()) " ($limits)" else ""
                val trust = if (test.mappingTrusted) "" else " [unverified mapping]"
                textLine("${test.label}: ${test.value} ${test.unit}$suffix — ${test.state}$trust", bodyPaint, gap = 2.5f)
                test.raw?.let { textLine("  raw: $it", mutedPaint, gap = 2f, x = MARGIN + 8f) }
            }
        }

        if (report.wizardResults.isNotEmpty()) {
            section("GUIDED DIAGNOSIS")
            for (wizard in report.wizardResults) {
                textLine("${wizard.title} — ${wizard.confidence} confidence", headingPaint, gap = 4f)
                wizard.ruleVersion?.let { textLine("Rules $it", mutedPaint, gap = 2f) }
                paragraph(wizard.summary, bodyPaint)
                for (cause in wizard.causes) {
                    textLine("${cause.likelihood}: ${cause.title} (${cause.confidence} confidence)", bodyPaint, gap = 3f)
                    paragraph(cause.explanation, mutedPaint, indent = 10f)
                    for (item in cause.evidence) paragraph("• $item", mutedPaint, indent = 14f)
                }
                if (wizard.evidence.isNotEmpty()) {
                    textLine("Evidence", bodyPaint, gap = 2f)
                    for (item in wizard.evidence) paragraph("• $item", mutedPaint, indent = 10f)
                }
                if (wizard.warnings.isNotEmpty()) {
                    textLine("Warnings", bodyPaint, gap = 2f)
                    for (item in wizard.warnings) paragraph("• $item", mutedPaint, indent = 10f)
                }
                if (wizard.nextSteps.isNotEmpty()) {
                    textLine("Next steps", bodyPaint, gap = 2f)
                    for (item in wizard.nextSteps) paragraph("• $item", mutedPaint, indent = 10f)
                }
                y += 4f
            }
        }

        report.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            section("NOTES")
            paragraph(notes, bodyPaint)
        }

        y += 10f
        textLine(
            "Generated by CarStalker ${report.appVersion}. Generic OBD-II data only; verify with service information.",
            mutedPaint,
        )

        finishCurrentPage()
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    private fun textPaint(
        size: Float,
        bold: Boolean = false,
        color: Int = Color.BLACK,
        mono: Boolean = false,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> sansBold
            else -> sans
        }
    }
}
