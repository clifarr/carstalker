package com.carstalker.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carstalker.app.ui.CarStalkerShell
import com.carstalker.app.ui.CarStalkerViewModel
import com.carstalker.app.ui.theme.CarStalkerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val graph = (application as CarStalkerApp).graph
            val viewModel: CarStalkerViewModel = viewModel(factory = CarStalkerViewModel.factory(graph))
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val debugRoute = if (BuildConfig.DEBUG) intent.getStringExtra("route") else null
            val debugAction = if (BuildConfig.DEBUG) intent.getStringExtra("action") else null
            val autoDemo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
            CarStalkerTheme(themeMode = settings.themeMode) {
                KeepScreenOn(enabled = settings.keepScreenOn)
                CarStalkerShell(
                    viewModel = viewModel,
                    initialRoute = debugRoute,
                    debugAction = debugAction,
                    autoConnectDemo = autoDemo,
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val activity = view.context as? Activity
        val window = activity?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
