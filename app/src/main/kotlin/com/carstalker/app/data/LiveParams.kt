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
    weight = 86,
) { 0.0 }.copy(isCustom = true)

/** Every parameter the dashboard can show, including the adapter measurement. */
val DashboardParams: List<Pid> = listOf(AdapterVoltageParam) + PidCatalog.standard

/** How many parameters an automatic dashboard shows. */
const val DEFAULT_DASHBOARD_SIZE = 10

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
 * Older builds pinned real PID 42 for battery voltage. Adapter voltage now has its own id,
 * so swap that entry once; a deliberately removed battery tile stays removed.
 */
fun migrateDashboardPids(pids: List<Int>): List<Int> {
    if (0x42 !in pids) return pids
    return pids.map { if (it == 0x42) LiveKeys.ADAPTER_VOLTAGE else it }.distinct()
}
