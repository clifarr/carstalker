package com.carstalker.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.carstalker.app.ui.screens.PdfPageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPageRendererTest {

    @Test
    fun rendersEachPageAtTheRequestedWidth() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "preview-render-test.pdf")
        writePdf(file, pages = 2)

        val renderer = PdfPageRenderer(file)
        try {
            assertEquals(2, renderer.pageCount)

            val first = renderer.renderPage(0, widthPx = 400)
            assertNotNull("First page did not render", first)
            assertEquals(400, first!!.width)
            assertTrue("Page should keep portrait proportions", first.height > first.width)

            assertNotNull("Second page did not render", renderer.renderPage(1, widthPx = 400))
        } finally {
            renderer.close()
            file.delete()
        }
    }

    private fun writePdf(file: File, pages: Int) {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawText("Page ${index + 1}", 40f, 80f, paint)
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
