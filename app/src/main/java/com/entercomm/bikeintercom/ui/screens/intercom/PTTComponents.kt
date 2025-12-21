package com.entercomm.bikeintercom.ui.screens.intercom

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.ui.screens.common.AppMode
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechGreenDark
import com.entercomm.bikeintercom.ui.theme.TechRed
import com.entercomm.bikeintercom.util.rememberHapticFeedback

/**
 * Central PTT hero section - the main interaction area
 * Uses fixed size container to prevent layout jumps during transitions
 */
@Composable
fun PTTHeroSection(appMode: AppMode, audioLevel: Float, isRecording: Boolean, onPTTPress: () -> Unit, onStartStop: () -> Unit, modifier: Modifier = Modifier) {
    // Fixed size container prevents layout jumps
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp), // Fixed height for both states
        contentAlignment = Alignment.Center,
    ) {
        // Crossfade between START and PTT buttons
        Crossfade(
            targetState = appMode == AppMode.STANDBY || appMode == AppMode.INITIALIZING,
            animationSpec = tween(300, easing = EaseInOutCubic),
            label = "heroTransition",
        ) { isStandby ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isStandby) {
                    StartButton(
                        onClick = onStartStop,
                        enabled = appMode != AppMode.INITIALIZING,
                    )
                } else {
                    PTTButton(
                        isRecording = isRecording,
                        audioLevel = audioLevel,
                        onPress = onPTTPress,
                        onDisconnect = onStartStop,
                    )
                }
            }
        }
    }
}

/**
 * Animated START button
 */
@Suppress("LongMethod") // LongMethod: Compose UI function with animations
@Composable
fun StartButton(onClick: () -> Unit, enabled: Boolean) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "startScale",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "startPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    val buttonAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(300),
        label = "buttonAlpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.alpha(buttonAlpha),
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
                        shape = CircleShape,
                    ),
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
                            Color.Transparent,
                        ),
                    ),
                )
                .border(3.dp, TechGreen, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                ) {
                    haptic.heavyClick()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Start",
                    tint = TechGreen,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "START",
                    style = MaterialTheme.typography.titleMedium,
                    color = TechGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

/**
 * Main PTT (Push-to-Talk) button with audio visualization
 */
@Composable
fun PTTButton(isRecording: Boolean, audioLevel: Float, onPress: () -> Unit, onDisconnect: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val infiniteTransition = rememberInfiniteTransition(label = "pttAnim")

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isRecording -> 1.02f + audioLevel * 0.05f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "pttScale",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(200),
        label = "pttColor",
    )
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringRotation",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (isRecording) {
                repeat(3) { index -> AudioLevelRing(index, audioLevel, infiniteTransition) }
            }
            RotatingAccentRing(buttonColor, ringRotation)
            PTTButtonCore(isRecording, buttonColor, scale, interactionSource, onPress)
        }
        Spacer(modifier = Modifier.height(40.dp))
        EndSessionButton(onDisconnect)
    }
}

/**
 * Audio level ring animation for PTT button
 */
@Composable
private fun AudioLevelRing(index: Int, audioLevel: Float, infiniteTransition: InfiniteTransition) {
    val delay = index * 200
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f + index * 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = delay, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "audioRing$index",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = delay, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "audioRingAlpha$index",
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(ringScale * (1f + audioLevel * 0.2f))
            .border(width = 2.dp, color = TechRed.copy(alpha = ringAlpha * audioLevel), shape = CircleShape),
    )
}

/**
 * Rotating accent ring around PTT button
 */
@Composable
private fun RotatingAccentRing(color: Color, rotation: Float) {
    Canvas(modifier = Modifier.size(180.dp).rotate(rotation)) {
        val strokeWidth = 3.dp.toPx()
        drawArc(color.copy(alpha = 0.5f), 0f, 60f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawArc(color.copy(alpha = 0.5f), 180f, 60f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}

/**
 * Core circular PTT button with mic icon
 */
@Composable
private fun PTTButtonCore(isRecording: Boolean, buttonColor: Color, scale: Float, interactionSource: MutableInteractionSource, onPress: () -> Unit) {
    val haptic = rememberHapticFeedback()
    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    listOf(buttonColor.copy(alpha = 0.25f), buttonColor.copy(alpha = 0.1f), Color.Transparent),
                ),
            )
            .border(4.dp, buttonColor, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null) {
                haptic.heavyClick()
                onPress()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isRecording) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = if (isRecording) "Stop" else "Talk",
                tint = buttonColor,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRecording) "RELEASE" else "PUSH",
                style = MaterialTheme.typography.titleSmall,
                color = buttonColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = if (isRecording) "TO STOP" else "TO TALK",
                style = MaterialTheme.typography.labelSmall,
                color = buttonColor.copy(alpha = 0.7f),
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * End session button
 */
@Composable
private fun EndSessionButton(onDisconnect: () -> Unit) {
    val haptic = rememberHapticFeedback()
    OutlinedButton(
        onClick = {
            haptic.error()
            onDisconnect()
        },
        modifier = Modifier.height(52.dp).widthIn(min = 160.dp),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.5.dp, TechRed.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TechRed),
    ) {
        Icon(Icons.Rounded.CallEnd, null, Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text("End Session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}
