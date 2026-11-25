package com.carstalker.app.data

import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog

/** Synthetic parameter ids for values CarStalker measures itself rather than reading from a PID. */
object LiveKeys {
    /** Supply voltage measured by the adapter with `ATRV`. Not an OBD parameter. */
    const val ADAPTER_VOLTAGE = -1
}

/**
 * The adapter's own voltage reading, presented like any other dashboard parameter.
 *
 * It has no OBD PID; [LiveKeys.ADAPTER_VOLTAGE] keeps it distinct from real PID 42
 * (`Control module voltage`), which some ECUs do report.
 */
val AdapterVoltageParam: Pid = Pid.of(
    id = LiveKeys.ADAPTER_VOLTAGE,
    name = "Battery voltage (adapter)",
    shortName = "Battery",
    unit = "V",
    min = 8.0,
    max = 16.0,
    category = Pid.Category.ELECTRICAL,
    decimals = 2,
    weight = 82,
) { 0.0 }

/** Every parameter the dashboard can show, including the adapter measurement. */
val DashboardParams: List<Pid> = listOf(AdapterVoltageParam) + PidCatalog.standard

/** How many parameters an automatic dashboard shows by default. */
const val DEFAULT_DASHBOARD_SIZE = 6

/** Hard cap for any dashboard, automatic or manual. */
const val MAX_DASHBOARD_PIDS = 10

/**
 * How long a parameter may go without a new sample before its tile is marked stale. Slow
 * parameters are scheduled every fourth cycle, which can be several seconds on Bluetooth
 * Classic, so this is deliberately longer than one cycle.
 */
const val PID_STALE_AFTER_MS = 10_000L

/** True when a parameter has produced a sample and the newest one is older than [thresholdMs]. */
fun isSampleStale(
    lastSampleAt: Long,
    now: Long,
    thresholdMs: Long = PID_STALE_AFTER_MS,
): Boolean = lastSampleAt > 0 && now - lastSampleAt > thresholdMs

/**
 * Adds or removes [pidId]. Returns null when adding would exceed [max], so the caller can
 * explain why nothing changed instead of silently dropping the request.
 */
fun toggledDashboardPids(
    pids: List<Int>,
    pidId: Int,
    max: Int = MAX_DASHBOARD_PIDS,
): List<Int>? = when {
    pidId in pids -> pids - pidId
    pids.size >= max -> null
    else -> pids + pidId
}

/**
 * Moves [fromId] to [toId]'s position. Dropping onto a card places the dragged value just
 * before it, which is how the two-column grid reads. Returns null when either id is absent.
 */
fun movedDashboardPids(pids: List<Int>, fromId: Int, toId: Int): List<Int>? {
    if (fromId == toId) return pids
    val from = pids.indexOf(fromId)
    val to = pids.indexOf(toId)
    if (from < 0 || to < 0) return null
    val reordered = pids.toMutableList()
    reordered.removeAt(from)
    val insertAt = (if (from < to) to - 1 else to).coerceIn(0, reordered.size)
    reordered.add(insertAt, fromId)
    return reordered
}

/**
 * Replaces [oldId] at its position. Returns null when [newId] is already present, so a
 * replacement can never create a duplicate.
 */
fun replacedDashboardPids(pids: List<Int>, oldId: Int, newId: Int): List<Int>? {
    if (oldId == newId) return pids
    val index = pids.indexOf(oldId)
    if (index < 0 || newId in pids) return null
    return pids.toMutableList().also { it[index] = newId }
}

/**
 * Dashboard selection used before weighting was introduced. It is treated as "automatic"
 * when the setting is migrated, so existing installs gain the weighted selection too.
 */
internal val LegacyDefaultDashboardPids: List<Int> = listOf(
    LiveKeys.ADAPTER_VOLTAGE, 0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F, 0x10, 0x2F,
)

/**
 * The [DEFAULT_DASHBOARD_SIZE] most useful parameters the car actually reports.
 *
 * When support is not known yet (no adapter connected) every parameter is a candidate.
 * Real PID 42 is skipped because [AdapterVoltageParam] already covers battery voltage
 * and is available on every adapter.
 */
fun recommendedDashboardPids(
    supported: Set<Int>,
    limit: Int = DEFAULT_DASHBOARD_SIZE,
): List<Int> = DashboardParams
    .filter { pid ->
        pid.id != 0x42 && (pid.id == LiveKeys.ADAPTER_VOLTAGE || supported.isEmpty() || pid.id in supported)
    }
    .sortedByDescending { it.weight }
    .take(limit)
    .map { it.id }

/** Top-weighted defaults, used before the car's supported-PID bitmap is known. */
val DefaultDashboardPids: List<Int> = recommendedDashboardPids(emptySet())

fun dashboardParam(id: Int): Pid? =
    if (id == LiveKeys.ADAPTER_VOLTAGE) AdapterVoltageParam else PidCatalog.find(id)

/**
 * Applies the pedal finder's calibration to a raw channel value. Interpolation is on by
 * default, so a car that reports 12 % released and 88 % pressed reads 0–100 % like a car
 * that already uses the full range.
 */
fun applyPedalCalibration(pidId: Int, value: Double, settings: AppSettings): Double {
    if (!settings.pedalCalibrationEnabled) return value
    val calibration = settings.pedalCalibrations.firstOrNull {
        it.pidId == pidId && it.vehicleKey == settings.profileVehicleKey
    } ?: return value
    return calibration.normalize(value) ?: value
}

/**
 * Older builds pinned real PID 42 for battery voltage. Adapter voltage now has its own id,
 * so swap that entry once; a deliberately removed battery tile stays removed.
 */
fun migrateDashboardPids(pids: List<Int>): List<Int> {
    if (0x42 !in pids) return pids
    return pids.map { if (it == 0x42) LiveKeys.ADAPTER_VOLTAGE else it }.distinct()
}

/**
 * One user dashboard. Each dashboard has its own parameter list, its own gauge visibility and
 * an optional focus mode that polls only its own parameters.
 */
@kotlinx.serialization.Serializable
data class DashboardConfig(
    val id: String,
    val name: String,
    val pids: List<Int> = emptyList(),
    /** Use the weighted recommendation for this car instead of [pids]. */
    val automatic: Boolean = false,
    /** Show the two large gauges and mini stats at the top. */
    val showGauges: Boolean = true,
) {
    fun resolvePids(supported: Set<Int>): List<Int> =
        if (automatic) recommendedDashboardPids(supported) else pids

    val isBlank: Boolean get() = pids.isEmpty() && !automatic
}

val DefaultDashboardConfig = DashboardConfig(
    id = "default",
    name = "Default",
    pids = DefaultDashboardPids,
    automatic = true,
    showGauges = true,
)

/** A blank dashboard is intentionally empty and fast: the user adds exactly what they need. */
fun blankDashboard(id: String, name: String): DashboardConfig = DashboardConfig(
    id = id,
    name = name,
    pids = emptyList(),
    automatic = false,
    showGauges = false,
)
