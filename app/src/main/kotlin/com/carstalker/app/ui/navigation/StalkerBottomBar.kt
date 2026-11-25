package com.carstalker.app.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertChartOutlined
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstalker.app.ui.theme.LocalStalkerColors

object Routes {
    const val CONNECT = "connect"
    const val DASHBOARD = "dashboard"
    const val DIAGNOSE = "diagnose"
    const val CODES = "codes"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
}

private data class BarItem(val route: String, val label: String, val icon: ImageVector)

private val items = listOf(
    BarItem(Routes.DASHBOARD, "Dash", Icons.Rounded.Speed),
    BarItem(Routes.DIAGNOSE, "Diagnose", Icons.Rounded.Troubleshoot),
    BarItem(Routes.CODES, "Codes", Icons.Rounded.WarningAmber),
    BarItem(Routes.LOGS, "Recordings", Icons.Rounded.InsertChartOutlined),
    BarItem(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun StalkerBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    disabledRoutes: Set<String> = emptySet(),
) {
    val stalker = LocalStalkerColors.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Five tabs have to share 320dp on the smallest supported phones. Tracking is dropped
        // and the type steps down so "Diagnose" and "Recordings" stay on one line instead of
        // wrapping and making the bar taller than the content.
        val narrow = maxWidth < 380.dp
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = if (narrow) 10.dp else 20.dp, vertical = 12.dp),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 18.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    val enabled = item.route !in disabledRoutes
                    val alpha by animateFloatAsState(
                        targetValue = when {
                            !enabled -> 0.3f
                            selected -> 1f
                            else -> 0.55f
                        },
                        animationSpec = spring(),
                        label = "barAlpha",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (selected) stalker.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(enabled = enabled) { onSelect(item.route) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected && enabled) stalker.accent else stalker.muted.copy(alpha = alpha),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (narrow) 10.sp else 11.sp,
                                letterSpacing = if (narrow) 0.sp else 0.2.sp,
                            ),
                            color = if (selected && enabled) stalker.accent else stalker.muted.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}


/**
 * Landscape replacement for [StalkerBottomBar]. On a phone held sideways the bottom bar plus
 * the system bars eat about half the screen height, so the same destinations move to a rail
 * along the leading edge where there is horizontal room to spare.
 */
@Composable
fun StalkerNavRail(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    disabledRoutes: Set<String> = emptySet(),
) {
    val stalker = LocalStalkerColors.current
    Surface(
        modifier = modifier
            .width(68.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 6.dp, horizontal = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val enabled = item.route !in disabledRoutes
                val tint = when {
                    !enabled -> stalker.muted.copy(alpha = 0.3f)
                    selected -> stalker.accent
                    else -> stalker.muted.copy(alpha = 0.55f)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) stalker.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(item.route) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            letterSpacing = 0.sp,
                        ),
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
