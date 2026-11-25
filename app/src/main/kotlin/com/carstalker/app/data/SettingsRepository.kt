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

/**
 * Maps one pedal or throttle channel from the values measured by the pedal finder to 0–100 %.
 * [releasedValue] was read with the pedal up, [pressedValue] with it fully pressed.
 */
@Serializable
data class PedalCalibration(
    val pidId: Int,
    val releasedValue: Double,
    val pressedValue: Double,
    /** VIN/calibration/fingerprint key this calibration was measured on. */
    val vehicleKey: String = "",
) {
    /** Returns null when the captured span is too small to interpolate safely. */
    fun normalize(value: Double): Double? {
        val span = pressedValue - releasedValue
        if (span < 1.0) return null
        return ((value - releasedValue) / span * 100.0).coerceIn(0.0, 100.0)
    }
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
    val dashboards: List<DashboardConfig> = listOf(DefaultDashboardConfig),
    val activeDashboardId: String = DefaultDashboardConfig.id,
    val customPids: List<CustomPid> = emptyList(),
    val lastVin: String? = null,
    /** Optional shop name printed at the top of exported reports. */
    val shopName: String = "",
    /** Engine profile used by guided tests to pick the correct interpretation. */
    val fuelType: FuelType = FuelType.UNKNOWN,
    val aspiration: Aspiration = Aspiration.UNKNOWN,
    /** Serialized same-vehicle baseline for the longitudinal health guide. */
    val baselineJson: String? = null,
    /** Per-vehicle engine profile, keyed by VIN or a stable demo/unknown key. */
    val vehicleProfiles: Map<String, VehicleProfileEntry> = emptyMap(),
    val profileVehicleKey: String = "unknown",
    val includeVinInReports: Boolean = false,
    /** Pedal/throttle channels rescaled from the finder's released and pressed readings. */
    val pedalCalibrations: List<PedalCalibration> = emptyList(),
    /** Rescale calibrated pedal channels to 0–100 %. Enabled by default. */
    val pedalCalibrationEnabled: Boolean = true,
) {
    val activeDashboard: DashboardConfig
        get() = dashboards.firstOrNull { it.id == activeDashboardId }
            ?: dashboards.firstOrNull()
            ?: DefaultDashboardConfig

    fun resolvedDashboardPids(supported: Set<Int>): List<Int> = activeDashboard.resolvePids(supported)
}

@kotlinx.serialization.Serializable
data class VehicleProfileEntry(
    val fuelType: FuelType = FuelType.UNKNOWN,
    val aspiration: Aspiration = Aspiration.UNKNOWN,
    /** User-chosen name, shown when the car does not report a VIN. */
    val nickname: String? = null,
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
        val dashboards = stringPreferencesKey("dashboards")
        val activeDashboardId = stringPreferencesKey("active_dashboard_id")
        val dashboardPids = stringPreferencesKey("dashboard_pids")
        val dashboardAuto = booleanPreferencesKey("dashboard_auto")
        val customPids = stringPreferencesKey("custom_pids")
        val lastVin = stringPreferencesKey("last_vin")
        val shopName = stringPreferencesKey("shop_name")
        val fuelType = stringPreferencesKey("fuel_type")
        val aspiration = stringPreferencesKey("aspiration")
        val baselineJson = stringPreferencesKey("baseline_json")
        val vehicleProfiles = stringPreferencesKey("vehicle_profiles")
        val profileVehicleKey = stringPreferencesKey("profile_vehicle_key")
        val includeVinInReports = booleanPreferencesKey("include_vin_in_reports")
        val pedalCalibrations = stringPreferencesKey("pedal_calibrations")
        val pedalCalibrationEnabled = booleanPreferencesKey("pedal_calibration_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings()))
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val legacyPids = this[Keys.dashboardPids]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull(16) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::migrateDashboardPids)
        // Installs from before automatic selection stored the old fixed default; treat it as auto.
        val legacyAuto = this[Keys.dashboardAuto]
            ?: (legacyPids == null ||
                legacyPids == LegacyDefaultDashboardPids ||
                legacyPids == DefaultDashboardPids)
        val dashboards = this[Keys.dashboards]
            ?.let { runCatching { json.decodeFromString<List<DashboardConfig>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                DefaultDashboardConfig.copy(
                    pids = legacyPids ?: DefaultDashboardPids,
                    automatic = legacyAuto,
                ),
            )
        val activeDashboardId = this[Keys.activeDashboardId]
            ?.takeIf { id -> dashboards.any { it.id == id } }
            ?: dashboards.first().id

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
            dashboards = dashboards,
            activeDashboardId = activeDashboardId,
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
            vehicleProfiles = this[Keys.vehicleProfiles]
                ?.let { runCatching { json.decodeFromString<Map<String, VehicleProfileEntry>>(it) }.getOrNull() }
                ?: emptyMap(),
            profileVehicleKey = this[Keys.profileVehicleKey] ?: "unknown",
            includeVinInReports = this[Keys.includeVinInReports] ?: false,
            pedalCalibrations = this[Keys.pedalCalibrations]
                ?.let { runCatching { json.decodeFromString<List<PedalCalibration>>(it) }.getOrNull() }
                ?: emptyList(),
            pedalCalibrationEnabled = this[Keys.pedalCalibrationEnabled] ?: true,
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
        this[Keys.dashboards] = json.encodeToString(settings.dashboards)
        this[Keys.activeDashboardId] = settings.activeDashboardId
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
        this[Keys.vehicleProfiles] = json.encodeToString(settings.vehicleProfiles)
        this[Keys.profileVehicleKey] = settings.profileVehicleKey
        this[Keys.includeVinInReports] = settings.includeVinInReports
        this[Keys.pedalCalibrations] = json.encodeToString(settings.pedalCalibrations)
        this[Keys.pedalCalibrationEnabled] = settings.pedalCalibrationEnabled
    }
}
