package com.carstalker.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstalker.app.data.MAX_DASHBOARD_PIDS
import com.carstalker.app.service.ConnectionState
import com.carstalker.app.transport.demo.DemoScenario
import com.carstalker.app.ui.components.PidPickerSheet
import com.carstalker.app.ui.navigation.Routes
import com.carstalker.app.ui.navigation.StalkerBottomBar
import com.carstalker.app.ui.screens.CodesScreen
import com.carstalker.app.ui.screens.ConnectScreen
import com.carstalker.app.ui.screens.DashboardScreen
import com.carstalker.app.ui.screens.DiagnoseScreen
import com.carstalker.app.ui.screens.LogsScreen
import com.carstalker.app.ui.screens.PdfPreviewScreen
import com.carstalker.app.ui.screens.SettingsScreen
import com.carstalker.core.guided.DiagnosticRegistry
import com.carstalker.core.guided.PrerequisiteAnswers

private val offlineStartupDisabledRoutes = setOf(Routes.DASHBOARD, Routes.DIAGNOSE, Routes.CODES)

@Composable
fun CarStalkerShell(
    viewModel: CarStalkerViewModel,
    initialRoute: String? = null,
    debugAction: String? = null,
    autoConnectDemo: Boolean = false,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val supported by viewModel.supportedPids.collectAsStateWithLifecycle()
    val showPidPicker by viewModel.showPidPicker.collectAsStateWithLifecycle()
    val selectedLog by viewModel.selectedLog.collectAsStateWithLifecycle()
    val pdfPreview by viewModel.pdfPreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pinnedPids = remember(settings.dashboards, settings.activeDashboardId, supported) {
        settings.resolvedDashboardPids(supported)
    }

    val startRoute = initialRoute?.takeIf {
        it in setOf(Routes.CONNECT, Routes.DASHBOARD, Routes.DIAGNOSE, Routes.CODES, Routes.LOGS, Routes.SETTINGS)
    } ?: Routes.CONNECT
    val connected = connection is ConnectionState.Ready
    var route by rememberSaveable { mutableStateOf(startRoute) }
    // Without an adapter the data tabs have nothing to show, so the startup screen keeps only
    // the offline-safe tabs (recordings and settings) active.
    val offlineStartup = route == Routes.CONNECT && !connected

    LaunchedEffect(autoConnectDemo) {
        if (autoConnectDemo) viewModel.connectDemo()
    }

    var debugActionDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(debugAction, connection) {
        if (debugAction?.startsWith("classic:") == true && connection is ConnectionState.Disconnected) {
            viewModel.connectClassicAddress(debugAction.removePrefix("classic:"))
        }
        if (debugAction == null || debugActionDone) return@LaunchedEffect
        when (debugAction) {
            "theme_light" -> {
                debugActionDone = true
                viewModel.setTheme(com.carstalker.app.data.ThemeMode.LIGHT)
            }
            "theme_dark" -> {
                debugActionDone = true
                viewModel.setTheme(com.carstalker.app.data.ThemeMode.DARK)
            }
            "clear_codes" -> if (connection is ConnectionState.Ready) {
                debugActionDone = true
                viewModel.clearCodes()
            }
            "record" -> if (connection is ConnectionState.Ready) {
                debugActionDone = true
                viewModel.toggleRecording()
                kotlinx.coroutines.delay(4_000)
                viewModel.toggleRecording()
            }
            "export_pdf" -> if (connection is ConnectionState.Ready) {
                debugActionDone = true
                kotlinx.coroutines.delay(3_000)
                viewModel.exportReport()
            }
            "guided_lean_demo" -> if (connection is ConnectionState.Ready) {
                debugActionDone = true
                route = Routes.DIAGNOSE
                viewModel.openGuide(DiagnosticRegistry.leanMixture)
                kotlinx.coroutines.delay(500)
                viewModel.startGuidedTest(
                    PrerequisiteAnswers(
                        engineWarmConfirmed = true,
                        safeLocationConfirmed = true,
                        closedLoopConfirmed = true,
                    ),
                )
                viewModel.setDemoScenario(DemoScenario.IDLE)
                kotlinx.coroutines.delay(35_000)
                viewModel.setDemoScenario(DemoScenario.STEADY_2500)
                kotlinx.coroutines.delay(30_000)
                viewModel.skipGuidedStage()
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.pauseGuidedTestForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Ready && route == Routes.CONNECT) {
            route = Routes.DASHBOARD
        }
    }
    BackHandler(
        enabled = pdfPreview == null && route != Routes.DASHBOARD && route != Routes.CONNECT && selectedLog == null,
    ) {
        route = Routes.DASHBOARD
    }
    LaunchedEffect(route) {
        if (route != Routes.LOGS) viewModel.closeLog()
        // Leaving the Diagnose tab must not keep an unattended capture running.
        if (route != Routes.DIAGNOSE) viewModel.pauseGuidedTestForBackground()
    }
    BackHandler(enabled = pdfPreview == null && route == Routes.CONNECT && connected) {
        route = Routes.DASHBOARD
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                StalkerBottomBar(
                    currentRoute = route,
                    onSelect = { route = it },
                    disabledRoutes = if (offlineStartup) offlineStartupDisabledRoutes else emptySet(),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "route",
                ) { target ->
                    when (target) {
                        Routes.DASHBOARD -> DashboardScreen(
                            viewModel = viewModel,
                            onOpenConnect = { route = Routes.CONNECT },
                        )
                        Routes.CODES -> CodesScreen(
                            viewModel = viewModel,
                            onOpenConnect = { route = Routes.CONNECT },
                        )
                        Routes.DIAGNOSE -> DiagnoseScreen(
                            viewModel = viewModel,
                            onOpenConnect = { route = Routes.CONNECT },
                        )
                        Routes.LOGS -> LogsScreen(viewModel)
                        Routes.SETTINGS -> SettingsScreen(viewModel)
                        else -> ConnectScreen(
                            viewModel = viewModel,
                            onOpenDashboard = { route = Routes.DASHBOARD },
                        )
                    }
                }
            }
        }

        pdfPreview?.let { preview ->
            PdfPreviewScreen(
                preview = preview,
                onClose = viewModel::closePdfPreview,
                onShare = viewModel::sharePdfPreview,
                onPrint = viewModel::printPdfPreview,
            )
        }
    }

    if (showPidPicker) {
        PidPickerSheet(
            pinned = pinnedPids,
            supported = supported,
            automatic = settings.activeDashboard.automatic,
            dashboardName = settings.activeDashboard.name,
            maxPids = MAX_DASHBOARD_PIDS,
            customPids = settings.customPids.filter { it.enabled },
            onToggle = viewModel::toggleDashboardPid,
            onUseRecommended = viewModel::useRecommendedDashboard,
            onDismiss = viewModel::closePidPicker,
        )
    }
}
