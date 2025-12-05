package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.location.PeerLocation
import com.entercomm.bikeintercom.location.RadarData
import kotlin.math.*

/**
 * Canvas-based radar view showing nearby peers.
 */
@Composable
fun RadarView(radarData: RadarData, modifier: Modifier = Modifier, onRangeChange: () -> Unit = {}, onPeerClick: ((PeerLocation) -> Unit)? = null) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    // Animated sweep line
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepAngle",
    )

    // Pulsing center dot
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val textMeasurer = rememberTextMeasurer()

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(surfaceColor.copy(alpha = 0.95f)),
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2 * 0.9f

            // Draw radar grid
            drawRadarGrid(center, radius, primaryColor, onSurfaceColor)

            // Draw sweep line
            drawSweepLine(center, radius, sweepAngle, primaryColor)

            // Draw local user (center)
            if (radarData.localLocation != null) {
                drawLocalMarker(center, pulseScale, tertiaryColor)

                // Draw direction indicator (heading)
                drawHeadingIndicator(center, radius, radarData.localLocation.bearing, tertiaryColor)
            }

            // Draw peers
            radarData.peersInRange().forEach { (peer, distance) ->
                val coords = radarData.peerToRadarCoordinates(peer)
                if (coords != null) {
                    val peerOffset = Offset(
                        center.x + coords.first * radius,
                        center.y + coords.second * radius,
                    )
                    drawPeerMarker(peerOffset, secondaryColor, distance, radarData.radarRange)
                }
            }

            // Draw cardinal directions
            drawCardinalDirections(center, radius, onSurfaceColor)
        }

        // Range indicator (bottom right)
        RangeIndicator(
            range = radarData.radarRange,
            onClick = onRangeChange,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )

        // Peer count (top left)
        if (radarData.peerLocations.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = surfaceColor.copy(alpha = 0.9f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.PeopleAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = onSurfaceColor,
                    )
                    Text(
                        text = "${radarData.peersInRange().size}/${radarData.peerLocations.size}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // No location indicator
        if (radarData.localLocation == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "Location unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawRadarGrid(center: Offset, radius: Float, primaryColor: Color, textColor: Color) {
    // Draw concentric circles (25%, 50%, 75%, 100%)
    val ringAlphas = listOf(0.15f, 0.2f, 0.15f, 0.3f)
    val ringRadii = listOf(0.25f, 0.5f, 0.75f, 1f)

    ringRadii.forEachIndexed { index, ratio ->
        drawCircle(
            color = primaryColor.copy(alpha = ringAlphas[index]),
            radius = radius * ratio,
            center = center,
            style = Stroke(width = if (ratio == 1f) 2f else 1f),
        )
    }

    // Draw crosshair lines
    val lineColor = primaryColor.copy(alpha = 0.2f)

    // Horizontal line
    drawLine(
        color = lineColor,
        start = Offset(center.x - radius, center.y),
        end = Offset(center.x + radius, center.y),
        strokeWidth = 1f,
    )

    // Vertical line
    drawLine(
        color = lineColor,
        start = Offset(center.x, center.y - radius),
        end = Offset(center.x, center.y + radius),
        strokeWidth = 1f,
    )

    // Diagonal lines (45 degrees)
    val diagOffset = radius * 0.707f
    drawLine(
        color = lineColor.copy(alpha = 0.1f),
        start = Offset(center.x - diagOffset, center.y - diagOffset),
        end = Offset(center.x + diagOffset, center.y + diagOffset),
        strokeWidth = 1f,
    )
    drawLine(
        color = lineColor.copy(alpha = 0.1f),
        start = Offset(center.x + diagOffset, center.y - diagOffset),
        end = Offset(center.x - diagOffset, center.y + diagOffset),
        strokeWidth = 1f,
    )
}

private fun DrawScope.drawSweepLine(center: Offset, radius: Float, angle: Float, color: Color) {
    rotate(angle, pivot = center) {
        // Main sweep line
        drawLine(
            color = color.copy(alpha = 0.8f),
            start = center,
            end = Offset(center.x, center.y - radius),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )

        // Sweep trail (fading arc)
        val trailPath = Path().apply {
            moveTo(center.x, center.y)
            for (i in 0..30) {
                val trailAngle = Math.toRadians((-i * 1.5).toDouble())
                val trailRadius = radius * (1f - i * 0.005f)
                val x = center.x + sin(trailAngle).toFloat() * trailRadius
                val y = center.y - cos(trailAngle).toFloat() * trailRadius
                lineTo(x, y)
            }
            close()
        }
        drawPath(
            path = trailPath,
            color = color.copy(alpha = 0.1f),
        )
    }
}

private fun DrawScope.drawLocalMarker(center: Offset, scale: Float, color: Color) {
    // Outer glow
    drawCircle(
        color = color.copy(alpha = 0.2f),
        radius = 20f * scale,
        center = center,
    )

    // Inner solid
    drawCircle(
        color = color,
        radius = 10f,
        center = center,
    )

    // Center dot
    drawCircle(
        color = Color.White,
        radius = 4f,
        center = center,
    )
}

private fun DrawScope.drawHeadingIndicator(center: Offset, radius: Float, bearing: Float, color: Color) {
    rotate(bearing, pivot = center) {
        // Arrow pointing up (north/heading direction)
        val arrowPath = Path().apply {
            moveTo(center.x, center.y - radius * 0.15f)
            lineTo(center.x - 8f, center.y - radius * 0.05f)
            lineTo(center.x + 8f, center.y - radius * 0.05f)
            close()
        }
        drawPath(
            path = arrowPath,
            color = color.copy(alpha = 0.8f),
        )
    }
}

private fun DrawScope.drawPeerMarker(position: Offset, color: Color, distance: Float, maxRange: Float) {
    // Size varies slightly based on distance (closer = slightly larger)
    val sizeMultiplier = 1f - (distance / maxRange) * 0.3f
    val markerRadius = 12f * sizeMultiplier

    // Outer glow
    drawCircle(
        color = color.copy(alpha = 0.3f),
        radius = markerRadius * 1.5f,
        center = position,
    )

    // Main marker
    drawCircle(
        color = color,
        radius = markerRadius,
        center = position,
    )

    // Inner highlight
    drawCircle(
        color = Color.White.copy(alpha = 0.5f),
        radius = markerRadius * 0.4f,
        center = Offset(position.x - 2f, position.y - 2f),
    )
}

private fun DrawScope.drawCardinalDirections(center: Offset, radius: Float, color: Color) {
    val fontSize = 12.sp.toPx()
    val offset = radius + 15f

    // We'll draw simple text markers
    // N (North - top)
    drawCircle(
        color = color.copy(alpha = 0.8f),
        radius = 8f,
        center = Offset(center.x, center.y - offset),
    )
}

/**
 * Range indicator button.
 */
@Composable
private fun RangeIndicator(range: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = formatDistance(range),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Radar view with peer list.
 */
@Composable
fun RadarWithPeerList(radarData: RadarData, onRangeChange: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Radar view
        RadarView(
            radarData = radarData,
            onRangeChange = onRangeChange,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        // Peer list
        if (radarData.peerLocations.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Nearby Riders",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    radarData.peersInRange()
                        .take(5) // Show max 5 peers
                        .forEach { (peer, distance) ->
                            PeerListItem(
                                peer = peer,
                                distance = distance,
                            )
                        }

                    // Show "out of range" peers count
                    val outOfRangeCount = radarData.peerLocations.size - radarData.peersInRange().size
                    if (outOfRangeCount > 0) {
                        Text(
                            text = "+$outOfRangeCount out of range",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerListItem(peer: PeerLocation, distance: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = peer.nickname.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondary,
                )
            }

            Column {
                Text(
                    text = peer.nickname,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (peer.speed > 0) {
                    Text(
                        text = "${(peer.speed * 3.6).toInt()} km/h",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Distance
        Text(
            text = formatDistance(distance),
            style = MaterialTheme.typography.bodyMedium,
            color = getDistanceColor(distance),
        )
    }
}

@Composable
private fun getDistanceColor(distance: Float): Color {
    return when {
        distance < 50 -> MaterialTheme.colorScheme.primary // Very close
        distance < 200 -> MaterialTheme.colorScheme.secondary // Close
        distance < 500 -> MaterialTheme.colorScheme.tertiary // Medium
        else -> MaterialTheme.colorScheme.onSurfaceVariant // Far
    }
}

private fun formatDistance(meters: Float): String {
    return when {
        meters < 1000 -> "${meters.toInt()}m"
        else -> "${(meters / 1000).let { "%.1f".format(it) }}km"
    }
}

/**
 * Compact radar indicator for main screen.
 */
@Composable
fun CompactRadarIndicator(radarData: RadarData, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val peersInRange = radarData.peersInRange()
    val hasLocation = radarData.localLocation != null

    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mini radar icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (hasLocation) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Radar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (hasLocation) {
                        if (peersInRange.isEmpty()) {
                            "No riders nearby"
                        } else {
                            "${peersInRange.size} rider${if (peersInRange.size > 1) "s" else ""} in range"
                        }
                    } else {
                        "Location disabled"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Closest peer indicator
            if (peersInRange.isNotEmpty()) {
                val closest = peersInRange.first()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = formatDistance(closest.second),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open radar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
