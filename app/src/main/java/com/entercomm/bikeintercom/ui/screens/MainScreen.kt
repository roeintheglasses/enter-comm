package com.entercomm.bikeintercom.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.mesh.MeshNetworkService
import com.entercomm.bikeintercom.mesh.ServiceState
import com.entercomm.bikeintercom.ui.theme.*
import com.entercomm.bikeintercom.util.rememberHapticFeedback
import kotlinx.coroutines.delay

/**
 * App state that drives all animations cohesively
 */
enum class AppMode {
    INITIALIZING,
    STANDBY,
    CONNECTING,
    ACTIVE,
    TRANSMITTING
}

/**
 * Main screen with PTT-centric design and cohesive animations
 */
@Composable
fun IntercomMainScreen(
    meshService: MeshNetworkService?,
    isServiceBound: Boolean
) {
    val context = LocalContext.current
    var serviceState by remember { mutableStateOf(ServiceState()) }
    var audioLevel by remember { mutableStateOf(0f) }

    // Derive app mode from state - this drives all animations
    val appMode by remember(isServiceBound, serviceState) {
        derivedStateOf {
            when {
                !isServiceBound -> AppMode.INITIALIZING
                serviceState.isRecording -> AppMode.TRANSMITTING
                serviceState.isRunning -> AppMode.ACTIVE
                else -> AppMode.STANDBY
            }
        }
    }

    // Collect service state
    LaunchedEffect(meshService) {
        meshService?.serviceState?.collect { state ->
            serviceState = state
        }
    }

    // Simulate audio level when transmitting
    LaunchedEffect(serviceState.isRecording) {
        if (serviceState.isRecording) {
            while (serviceState.isRecording) {
                audioLevel = kotlin.random.Random.nextFloat() * 0.8f + 0.2f
                delay(80)
            }
        } else {
            // Animate down smoothly
            while (audioLevel > 0.01f) {
                audioLevel *= 0.85f
                delay(50)
            }
            audioLevel = 0f
        }
    }

    // Main UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
    ) {
        // Animated background glow based on app mode
        AnimatedBackgroundGlow(appMode = appMode, audioLevel = audioLevel)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top status section
            StatusHeader(
                appMode = appMode,
                connectedDevices = serviceState.connectedDevices,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(0.15f))

            // Central PTT section - the hero
            PTTHeroSection(
                appMode = appMode,
                audioLevel = audioLevel,
                isRecording = serviceState.isRecording,
                onPTTPress = {
                    if (serviceState.isRecording) {
                        meshService?.stopRecording()
                    } else {
                        meshService?.startRecording()
                    }
                },
                onStartStop = {
                    if (serviceState.isRunning) {
                        meshService?.stopMeshNetwork()
                    } else {
                        meshService?.startMeshNetwork()
                        Toast.makeText(context, "Starting mesh network...", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(0.5f)
            )

            Spacer(modifier = Modifier.weight(0.1f))

            // Bottom info section
            BottomInfoSection(
                appMode = appMode,
                connectedDevices = serviceState.connectedDevices,
                isRunning = serviceState.isRunning,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Animated background glow that responds to app state
 */
@Composable
private fun AnimatedBackgroundGlow(
    appMode: AppMode,
    audioLevel: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bgGlow")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowColor by animateColorAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> TechCyan.copy(alpha = 0.05f)
            AppMode.STANDBY -> TechGreen.copy(alpha = 0.03f)
            AppMode.CONNECTING -> TechOrange.copy(alpha = 0.08f)
            AppMode.ACTIVE -> TechGreen.copy(alpha = 0.06f)
            AppMode.TRANSMITTING -> TechRed.copy(alpha = 0.1f + audioLevel * 0.15f)
        },
        animationSpec = tween(500, easing = EaseOutCubic),
        label = "glowColor"
    )

    val glowScale = if (appMode == AppMode.TRANSMITTING) {
        1f + audioLevel * 0.3f
    } else {
        pulseScale
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .scale(glowScale)
            .blur(100.dp)
    ) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.45f),
                radius = size.minDimension * 0.8f
            )
        )
    }
}

/**
 * Top status header with mode indicator
 */
@Composable
private fun StatusHeader(
    appMode: AppMode,
    connectedDevices: Int,
    modifier: Modifier = Modifier
) {
    val statusAlpha by animateFloatAsState(
        targetValue = if (appMode == AppMode.INITIALIZING) 0.5f else 1f,
        animationSpec = tween(300),
        label = "statusAlpha"
    )

    Row(
        modifier = modifier.alpha(statusAlpha),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App title with status
        Column {
            Text(
                text = "ENTER-COMM",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            StatusIndicator(appMode = appMode)
        }

        // Connected devices badge
        AnimatedVisibility(
            visible = appMode == AppMode.ACTIVE || appMode == AppMode.TRANSMITTING,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            DeviceCountBadge(count = connectedDevices)
        }
    }
}

/**
 * Animated status indicator
 */
@Composable
private fun StatusIndicator(appMode: AppMode) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")

    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
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
        label = "statusColor"
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (appMode != AppMode.STANDBY) dotScale else 1f)
                .clip(CircleShape)
                .background(statusColor)
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Connected devices badge
 */
@Composable
private fun DeviceCountBadge(count: Int) {
    Row(
        modifier = Modifier
            .background(DarkSurfaceVariant, RoundedCornerShape(20.dp))
            .border(1.dp, TechGreen.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = TechGreen,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = TechGreen,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Central PTT hero section - the main interaction area
 * Uses fixed size container to prevent layout jumps during transitions
 */
@Composable
private fun PTTHeroSection(
    appMode: AppMode,
    audioLevel: Float,
    isRecording: Boolean,
    onPTTPress: () -> Unit,
    onStartStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fixed size container prevents layout jumps
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp), // Fixed height for both states
        contentAlignment = Alignment.Center
    ) {
        // Crossfade between START and PTT buttons
        Crossfade(
            targetState = appMode == AppMode.STANDBY || appMode == AppMode.INITIALIZING,
            animationSpec = tween(300, easing = EaseInOutCubic),
            label = "heroTransition"
        ) { isStandby ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isStandby) {
                    StartButton(
                        onClick = onStartStop,
                        enabled = appMode != AppMode.INITIALIZING
                    )
                } else {
                    PTTButton(
                        isRecording = isRecording,
                        audioLevel = audioLevel,
                        onPress = onPTTPress,
                        onLongPress = onStartStop
                    )
                }
            }
        }
    }
}

/**
 * Animated START button
 */
@Composable
private fun StartButton(
    onClick: () -> Unit,
    enabled: Boolean
) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "startScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "startPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    val buttonAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(300),
        label = "buttonAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.alpha(buttonAlpha)
    ) {
        // Pulsing outer ring
        if (enabled) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(ringScale)
                    .border(
                        width = 2.dp,
                        color = TechGreen.copy(alpha = ringAlpha),
                        shape = CircleShape
                    )
            )
        }

        // Main button
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TechGreen.copy(alpha = 0.2f),
                            TechGreenDark.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
                .border(3.dp, TechGreen, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled
                ) {
                    haptic.heavyClick()
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Start",
                    tint = TechGreen,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "START",
                    style = MaterialTheme.typography.titleMedium,
                    color = TechGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

/**
 * Main PTT (Push-to-Talk) button with audio visualization
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PTTButton(
    isRecording: Boolean,
    audioLevel: Float,
    onPress: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isRecording -> 1.02f + audioLevel * 0.05f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "pttScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(200),
        label = "pttColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pttAnim")

    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )

    Box(
        contentAlignment = Alignment.Center
    ) {
        // Audio level rings (only when recording)
        if (isRecording) {
            repeat(3) { index ->
                val delay = index * 200
                val ringScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.5f + index * 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, delayMillis = delay, easing = EaseOut),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "audioRing$index"
                )
                val ringAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, delayMillis = delay, easing = EaseOut),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "audioRingAlpha$index"
                )

                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(ringScale * (1f + audioLevel * 0.2f))
                        .border(
                            width = 2.dp,
                            color = TechRed.copy(alpha = ringAlpha * audioLevel),
                            shape = CircleShape
                        )
                )
            }
        }

        // Rotating accent ring
        Canvas(
            modifier = Modifier
                .size(180.dp)
                .rotate(ringRotation)
        ) {
            val strokeWidth = 3.dp.toPx()
            drawArc(
                color = buttonColor.copy(alpha = 0.5f),
                startAngle = 0f,
                sweepAngle = 60f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = buttonColor.copy(alpha = 0.5f),
                startAngle = 180f,
                sweepAngle = 60f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Main PTT button
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.25f),
                            buttonColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
                .border(4.dp, buttonColor, CircleShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptic.heavyClick()
                        onPress()
                    },
                    onLongClick = {
                        haptic.error() // Different haptic for long press
                        onLongPress()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = if (isRecording) "Stop" else "Talk",
                    tint = buttonColor,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRecording) "RELEASE" else "PUSH",
                    style = MaterialTheme.typography.titleSmall,
                    color = buttonColor,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = if (isRecording) "TO STOP" else "TO TALK",
                    style = MaterialTheme.typography.labelSmall,
                    color = buttonColor.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
            }
        }

        // Long press hint for stopping network
        Text(
            text = "Hold to disconnect",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 100.dp)
        )
    }
}

/**
 * Bottom info section with network details
 */
@Composable
private fun BottomInfoSection(
    appMode: AppMode,
    connectedDevices: Int,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val contentAlpha by animateFloatAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> 0.3f
            AppMode.STANDBY -> 0.5f
            else -> 1f
        },
        animationSpec = tween(300),
        label = "bottomAlpha"
    )

    Column(
        modifier = modifier.alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Network status cards
        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoCard(
                    icon = Icons.Rounded.Wifi,
                    label = "NETWORK",
                    value = if (isRunning) "MESH" else "OFF",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f)
                )
                InfoCard(
                    icon = Icons.Rounded.Group,
                    label = "RIDERS",
                    value = "$connectedDevices",
                    isActive = connectedDevices > 0,
                    modifier = Modifier.weight(1f)
                )
                InfoCard(
                    icon = Icons.Rounded.SignalCellularAlt,
                    label = "SIGNAL",
                    value = if (isRunning) "GOOD" else "--",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Help text for standby mode
        AnimatedVisibility(
            visible = !isRunning,
            enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
            exit = fadeOut()
        ) {
            Text(
                text = "Tap START to create mesh network\nand connect with nearby riders",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * Info card for bottom section
 */
@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) TechGreen.copy(alpha = 0.3f) else DarkBorder,
        animationSpec = tween(300),
        label = "borderColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isActive) TechGreen else TextTertiary,
        animationSpec = tween(300),
        label = "iconColor"
    )

    Column(
        modifier = modifier
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (isActive) TextPrimary else TextTertiary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            letterSpacing = 0.5.sp
        )
    }
}
