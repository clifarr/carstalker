package com.carstalker.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import android.os.Build
import android.view.View
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carstalker.app.R
import com.carstalker.app.ui.theme.LocalIsDarkTheme
import com.carstalker.app.ui.theme.LocalStalkerColors
import java.util.Locale
import kotlinx.coroutines.delay

/** Small status capsule with an optional pulsing dot. */
/** Respect the system "remove animations" accessibility setting. */
@Composable
fun animationsEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val animations = animationsEnabled()
    val alpha = if (pulsing && animations) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Rounded panel used across screens. */
@Composable
fun StalkerCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = accent?.copy(alpha = 0.28f) ?: MaterialTheme.colorScheme.outline,
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

/** Section title with optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val stalker = LocalStalkerColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
        trailing?.invoke()
    }
}

/** Segmented single-choice row used for small option sets. */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = active,
                        role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { onSelect(value) },
                    ),
                shape = RoundedCornerShape(12.dp),
                color = if (active) stalker.accent else Color.Transparent,
            ) {
                Row(
                    Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Text("✓", style = MaterialTheme.typography.labelMedium, color = Color.Black)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) Color.Black else stalker.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Thin signal-strength bars for BLE results. */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val stalker = LocalStalkerColors.current
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        else -> 1
    }
    val active = stalker.accent
    Canvas(modifier.size(width = 22.dp, height = 16.dp)) {
        val barWidth = size.width / 7f
        for (i in 0 until 4) {
            val height = size.height * (0.32f + i * 0.22f)
            drawRoundRect(
                color = if (i < bars) active else stalker.grid,
                topLeft = Offset(i * barWidth * 1.9f, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            )
        }
    }
}

/**
 * The CarStalker mark: the car-under-a-magnifier artwork on its carbon backdrop.
 * The launcher icon insets this same drawable into its adaptive foreground, so the
 * in-app logo and the app icon can never diverge.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = "CarStalker",
        modifier = modifier.clip(CircleShape),
    )
}

/** Empty-state block with a short message. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val stalker = LocalStalkerColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.invoke()
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = stalker.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * Material's ModalBottomSheet uses its own dialog window. On a light system theme that window
 * keeps a light navigation-bar contrast scrim even when the app is forced dark, which leaves a
 * pale strip under the sheet. Disable the scrim and let the sheet's surface colour reach the
 * bottom edge.
 */
@Composable
fun FollowAppThemeInDialogWindow() {
    val view = LocalView.current
    val dark = LocalIsDarkTheme.current
    val surface = MaterialTheme.colorScheme.surface

    fun apply() {
        var current: View? = view
        while (current != null && current !is DialogWindowProvider) {
            current = current.parent as? View
        }
        val window = (current as? DialogWindowProvider)?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Some 3-button system taskbars ignore a transparent navigation bar and keep their
        // own light background. Setting the sheet surface colour explicitly makes the bar
        // follow the app theme where the platform still honours this value.
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface.toArgb()
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !dark
            isAppearanceLightStatusBars = !dark
        }
    }

    DisposableEffect(view, dark) {
        apply()
        onDispose { }
    }
    // Material3 sets the dialog window's bar appearance when it shows, after this content is
    // first composed. Apply again once the sheet is on screen so a forced dark app does not
    // keep light-navigation-bar (dark) icons on a dark sheet.
    LaunchedEffect(view, dark) {
        delay(100)
        apply()
    }
}

/**
 * One switch style for the whole app. Material's default unchecked thumb and track resolve to
 * nearly the same surface in this theme, which makes an off switch look like an empty pill.
 * A lighter thumb, a distinct track and a visible border keep the control readable.
 */
@Composable
fun stalkerSwitchColors(): SwitchColors {
    val stalker = LocalStalkerColors.current
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = stalker.accent,
        checkedBorderColor = stalker.accent,
        uncheckedThumbColor = stalker.muted,
        uncheckedTrackColor = scheme.surfaceContainerHighest,
        uncheckedBorderColor = scheme.outline,
        disabledCheckedThumbColor = scheme.onPrimary.copy(alpha = 0.5f),
        disabledCheckedTrackColor = stalker.accent.copy(alpha = 0.35f),
        disabledCheckedBorderColor = stalker.accent.copy(alpha = 0.35f),
        disabledUncheckedThumbColor = stalker.muted.copy(alpha = 0.5f),
        disabledUncheckedTrackColor = scheme.surfaceContainerHighest.copy(alpha = 0.5f),
        disabledUncheckedBorderColor = scheme.outline.copy(alpha = 0.5f),
    )
}

/**
 * Row of actions that wraps onto a second line when the labels no longer fit.
 * A plain Row squeezes the children instead, which breaks "Try demo" into a
 * two-line button on 320dp-wide phones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}
