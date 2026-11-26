package com.carstalker.app.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.carstalker.app.data.ThemeMode

data class StalkerColors(
    val accent: Color,
    val accentSecondary: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val glow: Color,
    val grid: Color,
    val muted: Color,
)

val LocalIsDarkTheme = staticCompositionLocalOf { true }

val LocalStalkerColors = staticCompositionLocalOf {
    StalkerColors(
        accent = Volt,
        accentSecondary = Ice,
        danger = Ember,
        warning = Amber,
        success = Mint,
        glow = Volt.copy(alpha = 0.18f),
        grid = Line,
        muted = Mist,
    )
}

private val DarkColors = darkColorScheme(
    primary = Volt,
    onPrimary = Carbon,
    primaryContainer = Color(0xFF2A3A12),
    onPrimaryContainer = Volt,
    secondary = Ice,
    onSecondary = Carbon,
    secondaryContainer = Color(0xFF12303A),
    onSecondaryContainer = Ice,
    tertiary = Amber,
    onTertiary = Carbon,
    background = Carbon,
    onBackground = Chalk,
    surface = Graphite,
    onSurface = Chalk,
    surfaceVariant = Slate,
    onSurfaceVariant = Mist,
    surfaceContainerLowest = Carbon,
    surfaceContainerLow = Graphite,
    surfaceContainer = Slate,
    surfaceContainerHigh = Steel,
    surfaceContainerHighest = Line,
    outline = Line,
    outlineVariant = Steel,
    error = Ember,
    onError = Carbon,
)

private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF2B8),
    onPrimaryContainer = Color(0xFF1B2B00),
    secondary = Color(0xFF00758F),
    onSecondary = Color.White,
    tertiary = Color(0xFF9A5B00),
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = PaperVariant,
    surfaceContainerHigh = Color(0xFFDCE2E8),
    surfaceContainerHighest = Color(0xFFCDD5DD),
    outline = Color(0xFFC3CCD5),
    outlineVariant = Color(0xFFDDE3E9),
    error = Color(0xFFC43A22),
    onError = Color.White,
)

val CarStalkerShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

@Composable
fun CarStalkerTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val view = LocalView.current
    // Keep the Android theme (including Material's bottom-sheet dialog theme) aligned with the
    // app's Compose theme. Without this, a forced dark app on a light system keeps light system
    // bars and dialog surfaces.
    LaunchedEffect(dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO,
            )
        }
    }
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            val window = activity?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            // The system bar scrim is chosen by enableEdgeToEdge from the *system* dark mode.
            // Re-apply it from the app's theme so a forced dark theme does not leave a light
            // navigation-bar strip under bottom sheets.
            if (activity is ComponentActivity) {
                val transparent = android.graphics.Color.TRANSPARENT
                activity.enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
            }
        }
    }

    val colors = if (dark) DarkColors else LightColors
    val stalker = if (dark) {
        StalkerColors(
            accent = Volt,
            accentSecondary = Ice,
            danger = Ember,
            warning = Amber,
            success = Mint,
            glow = Volt.copy(alpha = 0.16f),
            grid = Line,
            muted = Mist,
        )
    } else {
        StalkerColors(
            accent = Moss,
            accentSecondary = Color(0xFF00758F),
            danger = Color(0xFFC43A22),
            warning = Color(0xFF9A5B00),
            success = Color(0xFF1E7A4C),
            glow = Moss.copy(alpha = 0.12f),
            grid = Color(0xFFD5DDE4),
            muted = InkMuted,
        )
    }

    CompositionLocalProvider(
        LocalStalkerColors provides stalker,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = CarStalkerTypography,
            shapes = CarStalkerShapes,
            content = content,
        )
    }
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
