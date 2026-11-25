package com.carstalker.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end smoke test of the main flows on a real device.
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CarStalkerUiTest {

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            }
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertTrue("Connect screen did not appear", waitFor("CarStalker", 15_000))
    }

    @Test
    fun offlineStartupOnlyKeepsOfflineTabsEnabled() {
        val stillOnStartup = "Connect an OBD-II adapter"

        click("Dash")
        assertFalse(
            "Dash should stay disabled without an adapter",
            device.wait(Until.gone(By.text(stillOnStartup)), 1_500),
        )
        click("Diagnose")
        assertFalse(
            "Diagnose should stay disabled without an adapter",
            device.wait(Until.gone(By.text(stillOnStartup)), 1_500),
        )
        click("Codes")
        assertFalse(
            "Codes should stay disabled without an adapter",
            device.wait(Until.gone(By.text(stillOnStartup)), 1_500),
        )
        assertTrue(
            "The redundant Settings button is still on the connect screen",
            device.findObjects(By.text("Settings")).size == 1,
        )
        assertTrue(
            "The redundant Recordings button is still on the connect screen",
            device.findObjects(By.text("Recordings")).size == 1,
        )

        click("Recordings")
        assertTrue("Recordings should stay available without an adapter", waitFor("RECORDINGS"))
        click("Settings")
        assertTrue("Settings should stay available without an adapter", waitFor("SETTINGS"))

        // Leave the app on the startup screen for the other smoke test.
        device.pressBack()
        assertTrue("Back from Settings did not reach the dashboard", waitFor("No adapter connected"))
        click("Connect adapter")
        assertTrue("Did not return to the startup screen", waitFor(stillOnStartup))
    }

    @Test
    fun mainFlows() {
        screenshot("01-connect")

        // Demo connection
        click("Demo")
        assertTrue("Demo pane did not appear", waitFor("Start demo"))
        screenshot("02-demo")
        click("Start demo")
        openDashboardAndResetScroll()
        assertTrue("Live status missing", waitFor("Live", 15_000))
        Thread.sleep(1_000)
        screenshot("03-dashboard")

        // PID detail sheet
        click("RPM")
        assertTrue("PID detail did not open", waitFor("Engine speed", 8_000))
        screenshot("04-pid-detail")
        click("Close")

        // Diagnostics and clearing
        click("Codes")
        assertTrue("Codes screen did not appear", waitFor("DIAGNOSTICS"))
        assertTrue("No DTCs reported by demo", waitFor("Check-engine light on", 15_000))
        screenshot("05-codes")

        click("Clear codes")
        assertTrue("Clear confirmation missing", waitFor("Clear trouble codes?"))
        clickLast("Clear codes")
        assertTrue("Codes were not cleared", waitFor("No faults reported", 15_000))
        screenshot("06-codes-cleared")

        // Recording and playback
        click("Dash")
        openDashboardAndResetScroll()
        clickDesc("Start recording")
        Thread.sleep(7_000)
        scrollToBeginning()
        clickDesc("Stop recording")
        click("Recordings")
        assertTrue("Recordings screen did not appear", waitFor("RECORDINGS"))
        assertTrue("No recorded session found", waitForContains("samples", 10_000))
        assertFalse("Recording banner still visible", device.hasObject(By.text("Recording now")))
        screenshot("07-logs")

        // Playback
        val sessionRow = device.wait(Until.findObject(By.textContains("samples")), 10_000)
        assertTrue("Recorded session row not found", sessionRow != null)
        sessionRow!!.click()
        assertTrue("Playback screen did not open", waitFor("RECORDING", 10_000))
        assertFalse(
            "The moving playhead is replaced by pinch zoom",
            device.hasObject(By.desc("Play")),
        )
        val chart = device.wait(Until.findObject(By.desc("Playback chart")), 5_000)
        assertTrue("Playback chart not found", chart != null)
        chart!!.pinchOpen(0.6f)
        assertTrue("Pinching did not zoom the chart", waitFor("Reset zoom", 5_000))
        screenshot("07b-playback")
        device.pressBack()
        assertTrue("Back from playback failed", waitFor("Your recordings", 5_000))

        // Settings, picker, custom PID and light theme
        click("Settings")
        assertTrue("Settings did not appear", waitFor("SETTINGS"))
        assertFalse(
            "Connection setup should live on the Connect screen",
            device.hasObject(By.text("Connection defaults")),
        )
        screenshot("08-settings")

        clickScrolling("Edit")
        assertTrue("PID picker did not open", waitFor("DASHBOARD PARAMETERS"))
        screenshot("09-pid-picker")
        // Close explicitly: the picker is a full-screen dialog and back is not reliable on all ROMs.
        // The settings page stays scrolled behind the dialog, so check that the dialog is gone
        // instead of waiting for the SETTINGS header.
        clickDesc("Close")
        if (!device.wait(Until.gone(By.desc("Close")), 3_000)) device.pressBack()
        assertTrue("Picker did not close", device.wait(Until.gone(By.desc("Close")), 3_000))

        val reached = scrollTo("Add custom PID")
        if (!reached) screenshot("debug-settings-bottom")
        assertTrue("Custom PID section not reachable", reached)
        device.waitForIdle(1_500)
        var dialogOpened = false
        repeat(3) {
            if (!device.hasObject(By.text("Add custom PID"))) scrollTo("Add custom PID")
            val button = device.wait(Until.findObject(By.text("Add custom PID")), 4_000)
            if (button != null) {
                button.click()
                device.waitForIdle(800)
                dialogOpened = waitFor("New custom PID", 4_000)
            }
            if (!dialogOpened) Thread.sleep(600)
        }
        assertTrue("Custom PID dialog did not open", dialogOpened)
        screenshot("10-custom-pid")
        device.pressBack()
        Thread.sleep(500)

        scrollToBeginning()
        click("Light")
        click("Dash")
        openDashboardAndResetScroll()
        screenshot("11-dashboard-light")
        click("Settings")
        scrollToBeginning()
        click("Dark")
        Thread.sleep(500)

        // Connected screen: open dashboard, back navigation, then disconnect
        click("Dash")
        openDashboardAndResetScroll()
        click("Live")
        assertTrue("Connected screen did not open", waitFor("Adapter connected", 5_000))
        assertTrue("Open dashboard action missing", waitFor("Open dashboard", 5_000))
        assertTrue("Adapter health missing from connected screen", waitFor("Adapter health", 5_000))
        assertTrue("Benchmark action missing", waitFor("Run benchmark", 5_000))
        screenshot("12-connected")
        click("Open dashboard")
        openDashboardAndResetScroll()

        click("Live")
        assertTrue(waitFor("Adapter connected", 5_000))
        device.pressBack()
        openDashboardAndResetScroll()

        click("Live")
        assertTrue(waitFor("Adapter connected", 5_000))
        click("Disconnect")
        assertTrue("Disconnect did not restore the picker", waitFor("Connect an OBD-II adapter", 10_000))
        click("USB")
        assertTrue("USB serial speed missing", waitFor("Serial speed", 5_000))
        click("Bluetooth")
        assertTrue("Bluetooth pane did not open", waitFor("Bluetooth Classic", 5_000))
        assertFalse("Classic discovery started automatically", device.hasObject(By.text("Discovering devices")))

        click("Bluetooth LE")
        assertFalse("BLE scan started automatically", device.hasObject(By.text("Scanning for adapters")))
        click("Scan")
        assertTrue("BLE scan did not start", waitFor("Scanning for adapters", 5_000))
        assertTrue("Stop button missing while scanning", waitFor("Stop", 5_000))
        click("Stop")

        click("Bluetooth Classic")
        assertTrue("Classic tab did not open", waitFor("PAIRED", 5_000).or(waitFor("No paired adapters", 2_000)))
        screenshot("13-connect-bluetooth")
    }

    private fun click(text: String, timeout: Long = 10_000) {
        repeat(3) {
            val target = device.wait(Until.findObject(By.text(text)), timeout)
                ?: error("UI element not found: $text")
            try {
                target.click()
                device.waitForIdle(1_000)
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // Compose replaced the node between lookup and click; resolve it again.
                device.waitForIdle(300)
            }
        }
        error("UI element kept going stale: $text")
    }

    private fun clickLast(text: String, timeout: Long = 10_000) {
        device.wait(Until.hasObject(By.text(text)), timeout)
        val targets = device.findObjects(By.text(text))
        assertTrue("No UI element found: $text", targets.isNotEmpty())
        targets.maxByOrNull { it.visibleBounds.top }?.click()
        device.waitForIdle(1_000)
    }

    private fun clickDesc(description: String, timeout: Long = 10_000) {
        repeat(3) {
            val target = device.wait(Until.findObject(By.desc(description)), timeout)
                ?: error("UI element not found by description: $description")
            try {
                target.click()
                device.waitForIdle(1_000)
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                device.waitForIdle(300)
            }
        }
        error("UI element kept going stale: $description")
    }

    private fun waitFor(text: String, timeout: Long = 10_000): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeout)

    private fun waitForContains(text: String, timeout: Long = 10_000): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), timeout)

    /**
     * Waits for the dashboard, lets its automatic first-block scroll finish, then returns to
     * the top so header actions and the status pill are reachable.
     */
    private fun openDashboardAndResetScroll() {
        if (!waitFor("Dashboard", 20_000)) {
            // The demo pane may still have been animating when Start demo was tapped.
            device.wait(Until.findObject(By.text("Start demo")), 2_000)?.click()
            device.waitForIdle(1_000)
        }
        assertTrue("Dashboard did not appear", waitFor("Dashboard", 20_000))
        Thread.sleep(1_500)
        scrollToBeginning()
    }

    private fun clickScrolling(text: String) {
        if (!device.hasObject(By.text(text))) scrollTo(text)
        click(text)
    }

    /** Scrolls one small step. Re-resolves the container because Compose replaces the node. */
    private fun scrollStep(direction: Direction): Boolean {
        val scrollable = device.findObject(By.scrollable(true)) ?: return false
        return runCatching { scrollable.scroll(direction, 0.2f) }.getOrDefault(false)
    }

    private fun scrollTo(text: String): Boolean {
        if (device.hasObject(By.text(text))) return true
        // Swipes fling past short settings pages, so drive the scroll container directly.
        if (device.findObject(By.scrollable(true)) != null) {
            // Small steps: a 0.6-page step can jump straight over a short settings row.
            repeat(25) {
                scrollStep(Direction.DOWN)
                device.waitForIdle(200)
                if (device.hasObject(By.text(text))) return true
            }
            repeat(25) {
                scrollStep(Direction.UP)
                device.waitForIdle(200)
                if (device.hasObject(By.text(text))) return true
            }
            return device.hasObject(By.text(text))
        }
        repeat(12) {
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * 0.70).toInt(),
                device.displayWidth / 2,
                (device.displayHeight * 0.50).toInt(),
                100,
            )
            device.waitForIdle(500)
            Thread.sleep(150)
            if (device.hasObject(By.text(text))) return true
        }
        return device.hasObject(By.text(text))
    }

    private fun scrollToBeginning() {
        if (device.findObject(By.scrollable(true)) != null) {
            repeat(20) {
                scrollStep(Direction.UP)
                device.waitForIdle(150)
            }
            return
        }
        repeat(8) {
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * 0.30).toInt(),
                device.displayWidth / 2,
                (device.displayHeight * 0.70).toInt(),
                100,
            )
            device.waitForIdle(300)
        }
        Thread.sleep(300)
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir("screenshots") ?: context.filesDir
        directory.mkdirs()
        device.takeScreenshot(File(directory, "$name.png"))
    }
}
