package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.ui.theme.*

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
 * Large Push-To-Talk button with pulsing animation when active
 */
@Composable
fun PTTButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.1f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(200),
        label = "buttonColor"
    )
    
    Box(
        modifier = modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                color = buttonColor.copy(alpha = 0.2f)
            )
            .border(
                width = 3.dp,
                color = buttonColor,
                shape = CircleShape
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = buttonColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isRecording) "PTT" else "TALK",
                style = MaterialTheme.typography.labelMedium,
                color = buttonColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Technical button with custom styling
 */
@Composable
fun TechnicalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    buttonType: TechnicalButtonType = TechnicalButtonType.PRIMARY,
    enabled: Boolean = true
) {
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
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = colors,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
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
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

enum class TechnicalButtonType {
    PRIMARY, SECONDARY, DANGER
}

/**
 * Device card showing connection status with technical styling
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
    val borderColor = if (isConnected) TechGreen else DarkBorder
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        shape = RoundedCornerShape(8.dp)
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
                    .size(40.dp)
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
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (isConnected) {
                    Text(
                        text = "CONNECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = TechGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Signal strength indicator
            if (isConnected) {
                SignalStrengthIndicator(
                    strength = signalStrength,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextTertiary
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