package com.carstalker.app

import com.carstalker.app.data.AppSettings
import com.carstalker.app.data.DashboardConfig
import com.carstalker.app.data.LiveKeys
import com.carstalker.app.data.buildRecordingPlan
import com.carstalker.app.data.mergedPollPids
import com.carstalker.app.data.visiblePollPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlanTest {

    private val rpm = 0x0C
    private val coolant = 0x05
    private val map = 0x0B
    private val maf = 0x10
    private val supported = setOf(rpm, coolant, map, maf)

    private val engineDashboard = DashboardConfig(
        id = "engine",
        name = "Engine",
        pids = listOf(rpm, coolant),
        automatic = false,
        showGauges = false,
    )
    private val airflowDashboard = DashboardConfig(
        id = "airflow",
        name = "Airflow",
        pids = listOf(map, maf),
        automatic = false,
        showGauges = false,
    )

    @Test
    fun `recording channels survive a dashboard switch`() {
        val settings = AppSettings(
            dashboards = listOf(engineDashboard, airflowDashboard),
            activeDashboardId = engineDashboard.id,
        )
        val plan = buildRecordingPlan(settings, supported)
        assertEquals(listOf(LiveKeys.ADAPTER_VOLTAGE, rpm, coolant), plan.columns.map { it.id })

        // The user switches to a dashboard that does not show RPM or coolant.
        val switched = settings.copy(activeDashboardId = airflowDashboard.id)
        val dashboardPids = visiblePollPids(switched, supported)
        assertFalse(rpm in dashboardPids)
        assertFalse(coolant in dashboardPids)

        val merged = mergedPollPids(dashboardPids, plan.pids)
        assertTrue(merged.containsAll(listOf(rpm, coolant, map, maf)))
        assertEquals(merged.size, merged.distinct().size)
    }

    @Test
    fun `recording plan keeps the column order and units fixed`() {
        val settings = AppSettings(
            dashboards = listOf(engineDashboard),
            activeDashboardId = engineDashboard.id,
        )
        val plan = buildRecordingPlan(settings, supported)

        assertEquals(listOf(LiveKeys.ADAPTER_VOLTAGE, rpm, coolant), plan.columns.map { it.id })
        assertEquals(listOf("V", "rpm", "°C"), plan.columns.map { it.unit })
        assertEquals("Battery voltage (adapter)", plan.columns.first().name)
    }

    @Test
    fun `unsupported dashboard parameters are not recorded`() {
        val mixed = DashboardConfig(
            id = "mixed",
            name = "Mixed",
            pids = listOf(rpm, map),
            automatic = false,
            showGauges = false,
        )
        val settings = AppSettings(
            dashboards = listOf(mixed),
            activeDashboardId = mixed.id,
        )

        val plan = buildRecordingPlan(settings, setOf(rpm))

        assertEquals(listOf(LiveKeys.ADAPTER_VOLTAGE, rpm), plan.columns.map { it.id })
        assertFalse(map in plan.pids)
    }

    @Test
    fun `recording snapshots the pedal interpolation mode`() {
        val on = engineDashboard.copy(pedalInterpolation = true)
        val off = engineDashboard.copy(id = "off", name = "Off", pedalInterpolation = false)
        val settings = AppSettings(
            dashboards = listOf(on, off),
            activeDashboardId = on.id,
        )

        val plan = buildRecordingPlan(settings, supported)
        assertTrue(plan.pedalInterpolation)
        assertFalse(buildRecordingPlan(settings.copy(activeDashboardId = off.id), supported).pedalInterpolation)
        // The plan is a snapshot: a later dashboard switch cannot change an open recording.
        assertTrue(plan.pedalInterpolation)
    }

    @Test
    fun `focus parameter is merged without duplicating channels`() {
        val merged = mergedPollPids(listOf(rpm, coolant), listOf(rpm), rpm)

        assertEquals(listOf(rpm, coolant), merged)
    }
}
