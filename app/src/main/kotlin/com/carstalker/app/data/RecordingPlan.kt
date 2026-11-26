package com.carstalker.app.data

import com.carstalker.core.obd.PidCatalog

/** Values the visible gauges and mini stats need: RPM, speed, load and coolant. */
private val GAUGE_PIDS = listOf(0x0C, 0x0D, 0x04, 0x05)

/**
 * Every OBD parameter the active dashboard currently wants, before the poll scheduler thins the
 * list. Kept separate from recording subscriptions so a dashboard switch only changes what is
 * displayed, never what an open recording keeps sampling.
 */
fun visiblePollPids(settings: AppSettings, supported: Set<Int>): List<Int> {
    val dashboard = settings.activeDashboard
    val dashboardPids = dashboard.resolvePids(supported)
    val gaugePids = if (dashboard.showGauges) GAUGE_PIDS else emptyList()
    return (dashboardPids + gaugePids).distinct().filter { it >= 0 }
}

/**
 * The fixed channel set of one recording. Column ids, names, units and order are snapshotted
 * here and persisted with the file, so a later dashboard change cannot reinterpret a CSV column.
 */
data class RecordingPlan(
    val columns: List<LogColumnMeta>,
    val pids: List<Int>,
    /** Pedal interpolation mode at start; dashboard switches cannot change the file's meaning. */
    val pedalInterpolation: Boolean = false,
)

fun buildRecordingPlan(settings: AppSettings, supported: Set<Int>): RecordingPlan {
    val custom = settings.customPids
        .filter { it.enabled }
        .mapNotNull { it.toPid() }
        .associateBy { it.id }
    val pids = visiblePollPids(settings, supported)
        .filter { id -> id in custom || supported.isEmpty() || id in supported }
        .distinct()
    val params = pids.mapNotNull { id -> custom[id] ?: PidCatalog.find(id) }
    val columns = (listOf(AdapterVoltageParam) + params)
        .distinctBy { it.id }
        .map { LogColumnMeta(it.id, it.name, it.unit) }
    return RecordingPlan(columns, pids, settings.activeDashboard.pedalInterpolation)
}

/**
 * Merges dashboard, recording and detail-view subscriptions for one poll cycle. Recording
 * channels stay present after the user switches to a dashboard that does not show them.
 */
fun mergedPollPids(dashboard: List<Int>, recording: List<Int>, focus: Int? = null): List<Int> =
    (dashboard + recording + listOfNotNull(focus)).distinct()
