package com.carstalker.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.FuelType
import com.carstalker.core.obd.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsStore by preferencesDataStore(name = "carstalker_settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class TransportKind { BLE, USB, TCP, DEMO }

@Serializable
data class CustomPid(
    val pidHex: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val decimals: Int = 1,
    val formula: String,
    val enabled: Boolean = true,
) {
    val id: Int get() = pidHex.toIntOrNull(16) ?: -1
}

data class AppSettings(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val pollIntervalMs: Int = 0,
    val keepScreenOn: Boolean = true,
    val lastTransport: TransportKind = TransportKind.BLE,
    val tcpHost: String = "192.168.0.10",
    val tcpPort: Int = 35000,
    val usbBaud: Int = 38400,
    val dashboardPids: List<Int> = DefaultDashboardPids,
    /** When true the dashboard picks the top-weighted parameters the car supports. */
    val dashboardAuto: Boolean = true,
    val customPids: List<CustomPid> = emptyList(),
    val lastVin: String? = null,
    /** Optional shop name printed at the top of exported reports. */
    val shopName: String = "",
    /** Engine profile used by guided tests to pick the correct interpretation. */
    val fuelType: FuelType = FuelType.UNKNOWN,
    val aspiration: Aspiration = Aspiration.UNKNOWN,
    /** Serialized same-vehicle baseline for the longitudinal health guide. */
    val baselineJson: String? = null,
)

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val unitSystem = stringPreferencesKey("unit_system")
        val themeMode = stringPreferencesKey("theme_mode")
        val pollInterval = intPreferencesKey("poll_interval")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val lastTransport = stringPreferencesKey("last_transport")
        val tcpHost = stringPreferencesKey("tcp_host")
        val tcpPort = intPreferencesKey("tcp_port")
        val usbBaud = intPreferencesKey("usb_baud")
        val dashboardPids = stringPreferencesKey("dashboard_pids")
        val dashboardAuto = booleanPreferencesKey("dashboard_auto")
        val customPids = stringPreferencesKey("custom_pids")
        val lastVin = stringPreferencesKey("last_vin")
        val shopName = stringPreferencesKey("shop_name")
        val fuelType = stringPreferencesKey("fuel_type")
        val aspiration = stringPreferencesKey("aspiration")
        val baselineJson = stringPreferencesKey("baseline_json")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings()))
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val dashboardPids = this[Keys.dashboardPids]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull(16) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::migrateDashboardPids)
        // Installs from before automatic selection stored the old fixed default; treat it as auto.
        val dashboardAuto = this[Keys.dashboardAuto]
            ?: (dashboardPids == null ||
                dashboardPids == LegacyDefaultDashboardPids ||
                dashboardPids == DefaultDashboardPids)

        return AppSettings(
            unitSystem = this[Keys.unitSystem]
                ?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC,
            themeMode = this[Keys.themeMode]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            pollIntervalMs = this[Keys.pollInterval] ?: 0,
            keepScreenOn = this[Keys.keepScreenOn] ?: true,
            lastTransport = this[Keys.lastTransport]
                ?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() } ?: TransportKind.BLE,
            tcpHost = this[Keys.tcpHost] ?: "192.168.0.10",
            tcpPort = this[Keys.tcpPort] ?: 35000,
            usbBaud = this[Keys.usbBaud] ?: 38400,
            dashboardPids = dashboardPids ?: DefaultDashboardPids,
            dashboardAuto = dashboardAuto,
            customPids = this[Keys.customPids]
                ?.let { runCatching { json.decodeFromString<List<CustomPid>>(it) }.getOrNull() }
                ?: emptyList(),
            lastVin = this[Keys.lastVin],
            shopName = this[Keys.shopName] ?: "",
            fuelType = this[Keys.fuelType]
                ?.let { runCatching { FuelType.valueOf(it) }.getOrNull() } ?: FuelType.UNKNOWN,
            aspiration = this[Keys.aspiration]
                ?.let { runCatching { Aspiration.valueOf(it) }.getOrNull() } ?: Aspiration.UNKNOWN,
            baselineJson = this[Keys.baselineJson],
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.unitSystem] = settings.unitSystem.name
        this[Keys.themeMode] = settings.themeMode.name
        this[Keys.pollInterval] = settings.pollIntervalMs
        this[Keys.keepScreenOn] = settings.keepScreenOn
        this[Keys.lastTransport] = settings.lastTransport.name
        this[Keys.tcpHost] = settings.tcpHost
        this[Keys.tcpPort] = settings.tcpPort
        this[Keys.usbBaud] = settings.usbBaud
        this[Keys.dashboardPids] = settings.dashboardPids.joinToString(",") { it.toString(16) }
        this[Keys.dashboardAuto] = settings.dashboardAuto
        this[Keys.customPids] = json.encodeToString(settings.customPids)
        settings.lastVin?.let { this[Keys.lastVin] = it }
        this[Keys.shopName] = settings.shopName
        this[Keys.fuelType] = settings.fuelType.name
        this[Keys.aspiration] = settings.aspiration.name
        if (settings.baselineJson != null) {
            this[Keys.baselineJson] = settings.baselineJson
        } else {
            remove(Keys.baselineJson)
        }
    }
}
