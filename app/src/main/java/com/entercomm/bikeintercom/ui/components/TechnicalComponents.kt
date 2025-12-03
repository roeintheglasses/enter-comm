package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.*
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Technical Status Card with animated border glow effect
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
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> TechRed
            isActive -> TechGreen
            else -> DarkBorder
        },
        animationSpec = tween(400, easing = EaseOutCubic),
        label = "borderColor"
    )

    val glowColor = when {
        isError -> TechRed.copy(alpha = if (isActive || isError) pulseAlpha * 0.3f else 0f)
        isActive -> TechGreen.copy(alpha = pulseAlpha * 0.3f)
        else -> Color.Transparent
    }

    Card(
        modifier = modifier
            .drawBehind {
                if (isActive || isError) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.maxDimension
                        )
                    )
                }
            }
            .border(
                width = if (isActive || isError) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
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
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Animated status indicator dot with pulse
                StatusIndicatorDot(
                    isActive = isActive,
                    isError = isError
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) TextSecondary else TextTertiary
            )

            content()
        }
    }
}

/**
 * Animated status indicator dot with pulsing glow
 */
@Composable
fun StatusIndicatorDot(
    isActive: Boolean,
    isError: Boolean = false,
    size: Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive || isError) 1.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val color = when {
        isError -> TechRed
        isActive -> TechGreen
        else -> TextTertiary
    }

    Box(
        modifier = Modifier.size(size * 2),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        if (isActive || isError) {
            Box(
                modifier = Modifier
                    .size(size * scale * 1.5f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = glowAlpha * 0.3f))
            )
        }
        // Inner dot
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * Large Push-To-Talk button with pulsing glow animation when active.
 * Optimized for cycling with large touch target and haptic feedback.
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

    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            haptic.heavyClick()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")

    // Scale animation
    val baseScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "baseScale"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Glow animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Ring rotation for recording state
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(300, easing = EaseOutCubic),
        label = "buttonColor"
    )

    val buttonColorDark by animateColorAsState(
        targetValue = if (isRecording) TechRedDark else TechGreenDark,
        animationSpec = tween(300, easing = EaseOutCubic),
        label = "buttonColorDark"
    )

    Box(
        modifier = modifier
            .size(AppConfig.UI.PTT_TOUCH_TARGET_DP.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Outer glow layer
        if (enabled) {
            Canvas(
                modifier = Modifier
                    .size(size * 1.4f)
                    .scale(pulseScale)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = glowAlpha * 0.4f),
                            buttonColor.copy(alpha = glowAlpha * 0.1f),
                            Color.Transparent
                        )
                    )
                )
            }
        }

        // Animated ring for recording
        if (isRecording) {
            Canvas(
                modifier = Modifier
                    .size(size * 1.15f)
            ) {
                val strokeWidth = 3.dp.toPx()
                drawArc(
                    color = buttonColor.copy(alpha = 0.6f),
                    startAngle = ringRotation,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth)
                )
                drawArc(
                    color = buttonColor.copy(alpha = 0.6f),
                    startAngle = ringRotation + 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth)
                )
            }
        }

        // Main button
        Box(
            modifier = Modifier
                .size(size)
                .scale(baseScale * pulseScale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.25f),
                            buttonColorDark.copy(alpha = 0.15f),
                            DarkSurface
                        )
                    )
                )
                .border(
                    width = 3.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(buttonColor, buttonColorDark)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    tint = buttonColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isRecording) "STOP" else "TALK",
                    style = MaterialTheme.typography.titleMedium,
                    color = buttonColor,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/**
 * Technical button with gradient styling and animation.
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "btnScale"
    )

    val accentColor = when (buttonType) {
        TechnicalButtonType.PRIMARY -> TechGreen
        TechnicalButtonType.SECONDARY -> TechCyan
        TechnicalButtonType.DANGER -> TechRed
    }

    val accentColorDark = when (buttonType) {
        TechnicalButtonType.PRIMARY -> TechGreenDark
        TechnicalButtonType.SECONDARY -> TechCyanDark
        TechnicalButtonType.DANGER -> TechRedDark
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) accentColor.copy(alpha = 0.2f) else DarkSurfaceVariant,
        animationSpec = tween(200),
        label = "bgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isActive) accentColor else accentColor.copy(alpha = 0.9f),
        animationSpec = tween(200),
        label = "contentColor"
    )

    // Using Surface with clickable instead of Button for better touch handling
    Surface(
        onClick = {
            if (enabled) {
                haptic.click()
                onClick()
            }
        },
        modifier = modifier
            .heightIn(min = minHeight),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    if (enabled) accentColor else TextDisabled,
                    if (enabled) accentColorDark else TextDisabled
                )
            )
        ),
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = contentColor
            )
        }
    }
}

enum class TechnicalButtonType {
    PRIMARY, SECONDARY, DANGER
}

/**
 * Device card with smooth animations and modern styling.
 */
@Composable
fun DeviceCard(
    deviceName: String,
    deviceAddress: String,
    isConnected: Boolean = false,
    signalStrength: Int = 0,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "cardScale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isConnected) TechGreen else DarkBorder,
        animationSpec = tween(300),
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) TechGreen.copy(alpha = 0.05f) else DarkSurface,
        animationSpec = tween(300),
        label = "bgColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .scale(scale)
            .border(
                width = if (isConnected) 2.dp else 1.dp,
                brush = if (isConnected) {
                    Brush.linearGradient(listOf(TechGreen, TechGreenDark))
                } else {
                    Brush.linearGradient(listOf(borderColor, borderColor))
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.click()
                onClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon with status glow
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) TechGreen.copy(alpha = 0.15f)
                        else DarkSurfaceVariant
                    )
                    .border(
                        width = 1.dp,
                        color = if (isConnected) TechGreen.copy(alpha = 0.5f) else DarkBorder,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = if (isConnected) TechGreen else TextTertiary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(TechGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CONNECTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = TechGreen,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            if (isConnected) {
                SignalStrengthIndicator(
                    strength = signalStrength,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Connect to device",
                    tint = TextTertiary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Animated signal strength indicator with bars
 */
@Composable
fun SignalStrengthIndicator(
    strength: Int,
    modifier: Modifier = Modifier
) {
    val animatedStrength by animateIntAsState(
        targetValue = strength,
        animationSpec = tween(500, easing = EaseOutCubic),
        label = "signalStrength"
    )

    Canvas(modifier = modifier) {
        val barCount = 4
        val barWidth = size.width / (barCount * 2)
        val barSpacing = barWidth * 0.6f
        val maxBarHeight = size.height

        for (i in 0 until barCount) {
            val barHeight = maxBarHeight * (i + 1) / barCount
            val x = i * (barWidth + barSpacing)
            val y = size.height - barHeight

            val isActive = animatedStrength > (i * 25)
            val color = if (isActive) {
                when {
                    i < 2 -> TechGreen
                    i < 3 -> TechOrange
                    else -> TechRed
                }
            } else {
                DarkBorder
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}

/**
 * Network topology mini visualization with animations
 */
@Composable
fun NetworkTopology(
    connectedDevices: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topology")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "topologyPulse"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = minOf(size.width, size.height) * 0.32f

        // Draw center node (this device)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(TechGreen, TechGreenDark),
                center = Offset(centerX, centerY)
            ),
            radius = 10.dp.toPx() * pulse,
            center = Offset(centerX, centerY)
        )

        // Outer glow for center
        drawCircle(
            color = TechGreen.copy(alpha = 0.3f * pulse),
            radius = 14.dp.toPx() * pulse,
            center = Offset(centerX, centerY)
        )

        // Draw connected devices
        repeat(connectedDevices) { index ->
            val angle = (index * 360f / connectedDevices - 90) * (Math.PI / 180f)
            val x = centerX + (radius * kotlin.math.cos(angle)).toFloat()
            val y = centerY + (radius * kotlin.math.sin(angle)).toFloat()

            // Connection line with gradient
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(TechGreen, TechCyan),
                    start = Offset(centerX, centerY),
                    end = Offset(x, y)
                ),
                start = Offset(centerX, centerY),
                end = Offset(x, y),
                strokeWidth = 2.dp.toPx()
            )

            // Device node
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(TechCyan, TechCyanDark),
                    center = Offset(x, y)
                ),
                radius = 7.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

/**
 * Audio level meter with smooth animated bars
 */
@Composable
fun AudioLevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    val animatedLevel by animateFloatAsState(
        targetValue = if (isRecording) level else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "audioLevel"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "meterPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "barPulse"
    )

    Canvas(modifier = modifier) {
        val barCount = 16
        val barWidth = size.width / (barCount * 1.4f)
        val barSpacing = barWidth * 0.4f
        val maxBarHeight = size.height
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())

        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / barCount
            val barHeight = maxBarHeight * animatedLevel * (normalizedIndex * 0.5f + 0.5f)
            val x = i * (barWidth + barSpacing)
            val y = size.height - barHeight

            val color = when {
                normalizedIndex < 0.5f -> TechGreen
                normalizedIndex < 0.75f -> TechOrange
                else -> TechRed
            }

            val alpha = if (barHeight > 0) pulseAlpha else 0.2f

            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(4.dp.toPx())),
                cornerRadius = cornerRadius
            )
        }
    }
}

/**
 * Loading indicator with modern styling
 */
@Composable
fun LoadingIndicator(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = TechCyan,
                strokeWidth = 3.dp,
                trackColor = DarkBorder
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = message.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Error display with retry option and animations
 */
@Composable
fun ErrorDisplay(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()

    val infiniteTransition = rememberInfiniteTransition(label = "error")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "errorPulse"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(listOf(TechRed, TechRedDark)),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = TechRed.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = "Error",
                tint = TechRed,
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulseScale)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "ERROR",
                style = MaterialTheme.typography.titleMedium,
                color = TechRed,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            onRetry?.let {
                Spacer(modifier = Modifier.height(20.dp))
                TechnicalButton(
                    text = "RETRY",
                    onClick = {
                        haptic.click()
                        it()
                    },
                    icon = Icons.Rounded.Refresh,
                    buttonType = TechnicalButtonType.DANGER,
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        }
    }
}

/**
 * Empty state display with modern styling
 */
@Composable
fun EmptyStateDisplay(
    title: String,
    message: String,
    icon: ImageVector = Icons.Rounded.Info,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
                .border(1.dp, DarkBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(28.dp))
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
 * Connection status banner with animations
 */
@Composable
fun ConnectionStatusBanner(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    deviceCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "banner")

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bannerPulse"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isConnecting -> TechOrange.copy(alpha = pulseAlpha)
            isConnected -> TechGreen.copy(alpha = 0.15f)
            else -> TechRed.copy(alpha = 0.15f)
        },
        animationSpec = tween(400),
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = textColor,
                    strokeWidth = 2.dp,
                    trackColor = textColor.copy(alpha = 0.2f)
                )
            } else {
                StatusIndicatorDot(
                    isActive = isConnected,
                    isError = !isConnected && !isConnecting,
                    size = 10.dp
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = when {
                    isConnecting -> "CONNECTING..."
                    isConnected -> "CONNECTED"
                    else -> "DISCONNECTED"
                },
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        AnimatedVisibility(
            visible = isConnected && deviceCount > 0,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it }
        ) {
            Text(
                text = "$deviceCount DEVICE${if (deviceCount > 1) "S" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                letterSpacing = 0.5.sp
            )
        }
    }
}
