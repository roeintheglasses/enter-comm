package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.ui.theme.*
import com.entercomm.bikeintercom.util.rememberHapticFeedback

/**
 * Technical Status Card with border glow effect
 */
@Composable
fun TechnicalStatusCard(
    title: String,
    status: String,
    isActive: Boolean = false,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> TechRed
            isActive -> TechGreen
            else -> DarkBorder
        },
        animationSpec = tween(300),
        label = "borderColor"
    )
    
    Card(
        modifier = modifier
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isError -> TechRed
                                isActive -> TechGreen
                                else -> TextTertiary
                            }
                        )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            
            content()
        }
    }
}

/**
 * Large Push-To-Talk button with pulsing animation when active.
 * Optimized for cycling with large touch target (140dp default) and haptic feedback.
 */
@Composable
fun PTTButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = AppConfig.UI.PTT_BUTTON_SIZE_DP.dp
) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Trigger haptic on press
    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            haptic.heavyClick()
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isRecording -> 1.05f
            else -> 1.0f
        },
        animationSpec = if (isRecording) {
            infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(100)
        },
        label = "scale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(200),
        label = "buttonColor"
    )

    // Outer touch target area (larger than visible button)
    Box(
        modifier = modifier
            .size(AppConfig.UI.PTT_TOUCH_TARGET_DP.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button
            ) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // Visible button
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    color = buttonColor.copy(alpha = 0.2f)
                )
                .border(
                    width = 4.dp, // Thicker border for visibility
                    color = buttonColor,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    tint = buttonColor,
                    modifier = Modifier.size(40.dp) // Larger icon
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRecording) "STOP" else "TALK",
                    style = MaterialTheme.typography.titleMedium,
                    color = buttonColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Technical button with custom styling.
 * Optimized for cycling with larger touch target (min 64dp height) and haptic feedback.
 */
@Composable
fun TechnicalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    buttonType: TechnicalButtonType = TechnicalButtonType.PRIMARY,
    enabled: Boolean = true,
    minHeight: Dp = AppConfig.UI.MIN_BUTTON_HEIGHT_DP.dp
) {
    val haptic = rememberHapticFeedback()

    val colors = when (buttonType) {
        TechnicalButtonType.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = if (isActive) TechGreen else DarkSurfaceVariant,
            contentColor = if (isActive) PitchBlack else TechGreen
        )
        TechnicalButtonType.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = if (isActive) TechCyan else DarkSurfaceVariant,
            contentColor = if (isActive) PitchBlack else TechCyan
        )
        TechnicalButtonType.DANGER -> ButtonDefaults.buttonColors(
            containerColor = if (isActive) TechRed else DarkSurfaceVariant,
            contentColor = if (isActive) TextPrimary else TechRed
        )
    }

    Button(
        onClick = {
            haptic.click()
            onClick()
        },
        modifier = modifier.heightIn(min = minHeight),
        colors = colors,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp), // Slightly larger radius
        border = BorderStroke(
            width = 2.dp, // Thicker border for visibility
            color = when (buttonType) {
                TechnicalButtonType.PRIMARY -> TechGreen
                TechnicalButtonType.SECONDARY -> TechCyan
                TechnicalButtonType.DANGER -> TechRed
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp) // Larger icon
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall, // Larger text
                fontWeight = FontWeight.Bold
            )
        }
    }
}

enum class TechnicalButtonType {
    PRIMARY, SECONDARY, DANGER
}

/**
 * Device card showing connection status with technical styling.
 * Includes haptic feedback and larger touch target for cycling use.
 */
@Composable
fun DeviceCard(
    deviceName: String,
    deviceAddress: String,
    isConnected: Boolean = false,
    signalStrength: Int = 0, // 0-100
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val borderColor = if (isConnected) TechGreen else DarkBorder

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp) // Minimum height for touch target
            .border(
                width = if (isConnected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable {
                haptic.click()
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon with status
            Box(
                modifier = Modifier
                    .size(48.dp) // Larger icon area
                    .clip(CircleShape)
                    .background(
                        if (isConnected) TechGreen.copy(alpha = 0.2f)
                        else DarkSurfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeviceHub,
                    contentDescription = null,
                    tint = if (isConnected) TechGreen else TextTertiary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (isConnected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "CONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        color = TechGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Signal strength indicator
            if (isConnected) {
                SignalStrengthIndicator(
                    strength = signalStrength,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Connect to device",
                    tint = TextTertiary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Signal strength indicator with bars
 */
@Composable
fun SignalStrengthIndicator(
    strength: Int, // 0-100
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barCount = 4
        val barWidth = size.width / (barCount * 2)
        val barSpacing = barWidth * 0.5f
        val maxBarHeight = size.height
        
        for (i in 0 until barCount) {
            val barHeight = maxBarHeight * (i + 1) / barCount
            val x = i * (barWidth + barSpacing)
            val y = size.height - barHeight
            
            val isActive = strength > (i * 25) // Each bar represents 25% signal
            val color = if (isActive) TechGreen else DarkBorder
            
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

/**
 * Network topology mini visualization
 */
@Composable
fun NetworkTopology(
    connectedDevices: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = minOf(size.width, size.height) * 0.3f
        
        // Draw center node (this device)
        drawCircle(
            color = TechGreen,
            radius = 8.dp.toPx(),
            center = Offset(centerX, centerY)
        )
        
        // Draw connected devices
        repeat(connectedDevices) { index ->
            val angle = (index * 360f / connectedDevices) * (Math.PI / 180f)
            val x = centerX + (radius * kotlin.math.cos(angle)).toFloat()
            val y = centerY + (radius * kotlin.math.sin(angle)).toFloat()
            
            // Connection line
            drawLine(
                color = TechCyan,
                start = Offset(centerX, centerY),
                end = Offset(x, y),
                strokeWidth = 2.dp.toPx()
            )
            
            // Device node
            drawCircle(
                color = TechCyan,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

/**
 * Audio level meter with animated bars
 */
@Composable
fun AudioLevelMeter(
    level: Float, // 0.0 to 1.0
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    val animatedLevel by animateFloatAsState(
        targetValue = if (isRecording) level else 0f,
        animationSpec = tween(100),
        label = "audioLevel"
    )

    Canvas(modifier = modifier) {
        val barCount = 20
        val barWidth = size.width / (barCount * 1.5f)
        val barSpacing = barWidth * 0.5f
        val maxBarHeight = size.height

        for (i in 0 until barCount) {
            val barHeight = maxBarHeight * animatedLevel * (i + 1) / barCount
            val x = i * (barWidth + barSpacing)
            val y = size.height - barHeight

            val color = when {
                i < barCount * 0.6 -> TechGreen
                i < barCount * 0.8 -> TechOrange
                else -> TechRed
            }

            drawRect(
                color = color.copy(alpha = if (barHeight > 0) 1f else 0.3f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

/**
 * Loading indicator with technical styling
 */
@Composable
fun LoadingIndicator(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = TechCyan,
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Error display with retry option
 */
@Composable
fun ErrorDisplay(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = TechRed,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = TechRed.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = TechRed,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ERROR",
                style = MaterialTheme.typography.titleMedium,
                color = TechRed,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            onRetry?.let {
                Spacer(modifier = Modifier.height(16.dp))
                TechnicalButton(
                    text = "RETRY",
                    onClick = {
                        haptic.click()
                        it()
                    },
                    icon = Icons.Default.Refresh,
                    buttonType = TechnicalButtonType.DANGER,
                    modifier = Modifier.fillMaxWidth(0.6f)
                )
            }
        }
    }
}

/**
 * Empty state display for when no content is available
 */
@Composable
fun EmptyStateDisplay(
    title: String,
    message: String,
    icon: ImageVector = Icons.Default.Info,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            TechnicalButton(
                text = actionText,
                onClick = {
                    haptic.click()
                    onAction()
                },
                buttonType = TechnicalButtonType.SECONDARY
            )
        }
    }
}

/**
 * Connection status banner for quick feedback
 */
@Composable
fun ConnectionStatusBanner(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    deviceCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isConnecting -> TechOrange.copy(alpha = 0.2f)
            isConnected -> TechGreen.copy(alpha = 0.2f)
            else -> TechRed.copy(alpha = 0.2f)
        },
        animationSpec = tween(300),
        label = "bannerColor"
    )

    val textColor = when {
        isConnecting -> TechOrange
        isConnected -> TechGreen
        else -> TechRed
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = textColor,
                    strokeWidth = 2.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when {
                    isConnecting -> "CONNECTING..."
                    isConnected -> "CONNECTED"
                    else -> "DISCONNECTED"
                },
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }

        if (isConnected && deviceCount > 0) {
            Text(
                text = "$deviceCount DEVICE${if (deviceCount > 1) "S" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = textColor
            )
        }
    }
}