package com.carstalker.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * How the phone's battery management treats a recording that keeps running in the background.
 *
 * A foreground service and a partial wake lock are not enough on some ROMs. MIUI/HyperOS freeze
 * an app they consider idle once the screen is off, and the ongoing notification stays visible
 * because the system draws it, not the frozen process. The user has to exempt CarStalker from
 * battery saving, so the app checks the setting and offers a direct way to change it.
 */
object BackgroundRecordingPolicy {

    /** True when Android will not apply Doze or app-freezer restrictions to this app. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** MIUI and HyperOS add a second, per-app battery saver that AOSP does not have. */
    fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("redmi", ignoreCase = true) ||
            Build.BRAND.equals("poco", ignoreCase = true)

    /**
     * Opens the per-app battery screen. On MIUI/HyperOS that is the "Battery saver" choice
     * ("No restrictions"), which is the switch that actually stops the freeze.
     */
    fun openAppBatterySettings(context: Context): Boolean {
        if (isXiaomiDevice()) {
            val miui = Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", appLabel(context))
            }
            if (start(context, miui)) return true
        }
        return openAppSettings(context)
    }

    /** Opens MIUI's Autostart list, when the ROM exposes it. */
    fun openAutostartSettings(context: Context): Boolean {
        if (!isXiaomiDevice()) return false
        val autostart = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }
        return start(context, autostart)
    }

    private fun openAppSettings(context: Context): Boolean = start(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun appLabel(context: Context): String = runCatching {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault("CarStalker")
}
