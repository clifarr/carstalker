package com.carstalker.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression coverage for the PDF preview zoom: a real pinch, a one-finger pan and pinching back to
 * fit must all leave the rendered page visible rather than blank.
 */
@RunWith(AndroidJUnit4::class)
class PdfPreviewZoomTest {

    @Test
    fun pinchPanAndResetKeepThePageVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val shots = File(context.cacheDir, "zoom-probe").apply { mkdirs() }
        // Keep the screen awake so MIUI does not blank it mid-gesture.
        device.wakeUp()
        instrumentation.uiAutomation.executeShellCommand("svc power stayon true").close()

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("demo", true),
        )
        if (!device.wait(Until.hasObject(By.text("Dashboard")), 10_000)) {
            val dash = device.wait(Until.findObject(By.text("Dash")), 5_000)
            if (dash != null) {
                dash.click()
            } else {
                device.wait(Until.findObject(By.text("Demo")), 5_000)?.click()
                device.wait(Until.findObject(By.text("Start demo")), 5_000)?.click()
            }
        }
        assertTrue("Dashboard did not appear", device.wait(Until.hasObject(By.text("Dashboard")), 20_000))
        device.findObject(By.text("Codes")).click()
        val preview = device.wait(Until.findObject(By.desc("Preview PDF report")), 10_000)
        assertTrue("Preview action missing", preview != null)
        // The action stays disabled until the demo adapter has reported its trouble codes.
        val deadline = System.currentTimeMillis() + 20_000
        while (preview != null && !preview.isEnabled && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        assertTrue("Preview action never enabled", preview?.isEnabled == true)
        var opened = false
        for (attempt in 1..3) {
            preview!!.click()
            if (device.wait(Until.hasObject(By.text("REPORT PREVIEW")), 4_000)) {
                opened = true
                break
            }
            Thread.sleep(500)
        }
        assertTrue("Report preview did not open", opened)
        Thread.sleep(1_500)

        val page = device.wait(Until.findObject(By.desc("Report page 1")), 10_000)
        assertTrue("Rendered page missing", page != null)
        val bounds = Rect(page!!.visibleBounds)
        assertVisibleContent(device, bounds, "fit", shots, 300)

        page.pinchOpen(0.4f)
        Thread.sleep(800)
        assertTrue("Zoom badge missing after pinch", device.wait(Until.hasObject(By.textContains("Reset")), 3_000))
        assertVisibleContent(device, bounds, "pinched", shots, 300)

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        device.swipe(cx, cy, cx + 300, cy - 300, 40)
        Thread.sleep(800)
        assertVisibleContent(device, bounds, "panned", shots, 300)

        device.findObject(By.textContains("Reset")).click()
        Thread.sleep(800)
        assertFalse("Zoom badge should disappear after reset", device.hasObject(By.textContains("Reset")))
        assertVisibleContent(device, bounds, "fit-again", shots, 300)
        shots.deleteRecursively()
        instrumentation.uiAutomation.executeShellCommand("svc power stayon false").close()
    }

    private fun assertVisibleContent(
        device: UiDevice,
        bounds: Rect,
        name: String,
        shots: File,
        minimum: Int,
    ) {
        val file = File(shots, "$name.png")
        assertTrue("Could not take screenshot", device.takeScreenshot(file))
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertTrue("Could not decode screenshot", bitmap != null)
        var nonWhite = 0
        var y = bounds.top.coerceAtLeast(0)
        while (y < bounds.bottom.coerceAtMost(bitmap.height)) {
            var x = bounds.left.coerceAtLeast(0)
            while (x < bounds.right.coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 245 || Color.green(pixel) < 245 || Color.blue(pixel) < 245) {
                    nonWhite++
                }
                x += 4
            }
            y += 4
        }
        bitmap.recycle()
        assertTrue("$name page looks blank ($nonWhite sampled pixels)", nonWhite > minimum)
    }
}
