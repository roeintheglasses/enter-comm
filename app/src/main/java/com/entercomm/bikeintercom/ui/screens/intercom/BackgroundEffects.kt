package com.entercomm.bikeintercom.ui.screens.intercom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.ui.screens.common.AppMode
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechOrange
import com.entercomm.bikeintercom.ui.theme.TechRed

/**
 * Animated background glow that responds to app state
 */
@Composable
fun AnimatedBackgroundGlow(appMode: AppMode, audioLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "bgGlow")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
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
        label = "glowColor",
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
            .blur(100.dp),
    ) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.45f),
                radius = size.minDimension * 0.8f,
            ),
        )
    }
}
