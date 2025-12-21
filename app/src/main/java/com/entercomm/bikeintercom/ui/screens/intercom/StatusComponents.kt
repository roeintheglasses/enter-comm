package com.entercomm.bikeintercom.ui.screens.intercom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.ui.screens.common.AppMode
import com.entercomm.bikeintercom.ui.theme.DarkBorder
import com.entercomm.bikeintercom.ui.theme.DarkSurface
import com.entercomm.bikeintercom.ui.theme.DarkSurfaceVariant
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechOrange
import com.entercomm.bikeintercom.ui.theme.TechRed
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.ui.theme.TextTertiary

/**
 * Top status header with mode indicator
 */
@Composable
fun StatusHeader(appMode: AppMode, connectedDevices: Int, modifier: Modifier = Modifier) {
    val statusAlpha by animateFloatAsState(
        targetValue = if (appMode == AppMode.INITIALIZING) 0.5f else 1f,
        animationSpec = tween(300),
        label = "statusAlpha",
    )

    Row(
        modifier = modifier.alpha(statusAlpha),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App title with status
        Column {
            Text(
                text = "ENTER-COMM",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(4.dp))

            StatusIndicator(appMode = appMode)
        }

        // Connected devices badge
        AnimatedVisibility(
            visible = appMode == AppMode.ACTIVE || appMode == AppMode.TRANSMITTING,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            DeviceCountBadge(count = connectedDevices)
        }
    }
}

/**
 * Animated status indicator
 */
@Composable
fun StatusIndicator(appMode: AppMode) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")

    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )

    val statusColor by animateColorAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> TechCyan
            AppMode.STANDBY -> TextTertiary
            AppMode.CONNECTING -> TechOrange
            AppMode.ACTIVE -> TechGreen
            AppMode.TRANSMITTING -> TechRed
        },
        animationSpec = tween(300),
        label = "statusColor",
    )

    val statusText = when (appMode) {
        AppMode.INITIALIZING -> "INITIALIZING"
        AppMode.STANDBY -> "STANDBY"
        AppMode.CONNECTING -> "CONNECTING"
        AppMode.ACTIVE -> "ACTIVE"
        AppMode.TRANSMITTING -> "ON AIR"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (appMode != AppMode.STANDBY) dotScale else 1f)
                .clip(CircleShape)
                .background(statusColor),
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Connected devices badge
 */
@Composable
fun DeviceCountBadge(count: Int) {
    Row(
        modifier = Modifier
            .background(DarkSurfaceVariant, RoundedCornerShape(20.dp))
            .border(1.dp, TechGreen.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = TechGreen,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = TechGreen,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Bottom info section with network details
 */
@Suppress("LongMethod") // LongMethod: Compose UI function with animations
@Composable
fun BottomInfoSection(appMode: AppMode, connectedDevices: Int, isRunning: Boolean, modifier: Modifier = Modifier) {
    val contentAlpha by animateFloatAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> 0.3f
            AppMode.STANDBY -> 0.5f
            else -> 1f
        },
        animationSpec = tween(300),
        label = "bottomAlpha",
    )

    Column(
        modifier = modifier.alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Network status cards
        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoCard(
                    icon = Icons.Rounded.Wifi,
                    label = "NETWORK",
                    value = if (isRunning) "MESH" else "OFF",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f),
                )
                InfoCard(
                    icon = Icons.Rounded.Group,
                    label = "RIDERS",
                    value = "$connectedDevices",
                    isActive = connectedDevices > 0,
                    modifier = Modifier.weight(1f),
                )
                InfoCard(
                    icon = Icons.Rounded.SignalCellularAlt,
                    label = "SIGNAL",
                    value = if (isRunning) "GOOD" else "--",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Help text for standby mode
        AnimatedVisibility(
            visible = !isRunning,
            enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
            exit = fadeOut(),
        ) {
            Text(
                text = "Tap START to create mesh network\nand connect with nearby riders",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * Info card for bottom section
 */
@Composable
fun InfoCard(icon: ImageVector, label: String, value: String, isActive: Boolean, modifier: Modifier = Modifier) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) TechGreen.copy(alpha = 0.3f) else DarkBorder,
        animationSpec = tween(300),
        label = "borderColor",
    )

    val iconColor by animateColorAsState(
        targetValue = if (isActive) TechGreen else TextTertiary,
        animationSpec = tween(300),
        label = "iconColor",
    )

    Column(
        modifier = modifier
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (isActive) TextPrimary else TextTertiary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            letterSpacing = 0.5.sp,
        )
    }
}
