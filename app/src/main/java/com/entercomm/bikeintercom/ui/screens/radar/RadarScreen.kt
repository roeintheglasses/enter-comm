package com.entercomm.bikeintercom.ui.screens.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.location.RadarData
import com.entercomm.bikeintercom.ui.components.RadarWithPeerList
import com.entercomm.bikeintercom.ui.theme.DarkSurface
import com.entercomm.bikeintercom.ui.theme.DarkSurfaceElevated
import com.entercomm.bikeintercom.ui.theme.PitchBlack
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechOrange
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.ui.theme.TextSecondary
import com.entercomm.bikeintercom.ui.theme.TextTertiary

/**
 * Radar tab content
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Compose UI function with multiple states
@Composable
fun RadarContent(
    radarData: RadarData,
    isTracking: Boolean,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onRangeChange: () -> Unit,
) {
    val hasLocation = radarData.localLocation != null
    val peersInRange = radarData.peersInRange()

    // Debug log when radar tab is displayed
    SideEffect {
        android.util.Log.d("RadarScreen", "Rendering RADAR tab: isTracking=$isTracking, hasLocation=$hasLocation")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Header section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    text = "Rider Radar",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isTracking -> "GPS disabled"
                        !hasLocation -> "Acquiring signal..."
                        peersInRange.isEmpty() -> "No riders nearby"
                        else -> "${peersInRange.size} rider${if (peersInRange.size > 1) "s" else ""} in range"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !isTracking -> TextTertiary
                        !hasLocation -> TechOrange
                        peersInRange.isEmpty() -> TextSecondary
                        else -> TechGreen
                    },
                )
            }

            // Status indicator pill
            if (isTracking) {
                Row(
                    modifier = Modifier
                        .background(
                            color = if (hasLocation) TechGreen.copy(alpha = 0.15f) else TechOrange.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!hasLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = TechOrange,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(TechGreen, CircleShape),
                        )
                    }
                    Text(
                        text = if (hasLocation) "LIVE" else "GPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasLocation) TechGreen else TechOrange,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Location info bar (when available)
        AnimatedVisibility(
            visible = hasLocation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            radarData.localLocation?.let { loc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = TechGreen,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = "Your Location",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                            )
                            Text(
                                text = "%.4f, %.4f".format(loc.latitude, loc.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (loc.speed > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = TechCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "${(loc.speed * 3.6).toInt()} km/h",
                                style = MaterialTheme.typography.bodySmall,
                                color = TechCyan,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // Acquiring GPS message
        AnimatedVisibility(
            visible = isTracking && !hasLocation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TechOrange.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = TechOrange,
                )
                Text(
                    text = "Searching for GPS signal...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TechOrange,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Radar view (main content)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            RadarWithPeerList(
                radarData = radarData,
                onRangeChange = onRangeChange,
                modifier = Modifier.fillMaxSize(),
            )

            // Empty state overlay when GPS is off
            if (!isTracking) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PitchBlack.copy(alpha = 0.85f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "GPS Disabled",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable GPS to see nearby riders",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // GPS toggle button at bottom
        Button(
            onClick = {
                if (isTracking) onStopTracking() else onStartTracking()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isTracking && hasLocation -> TechGreen
                    isTracking -> TechOrange
                    else -> DarkSurfaceElevated
                },
                contentColor = if (isTracking) Color.White else TechCyan,
            ),
            border = if (!isTracking) BorderStroke(1.5.dp, TechCyan.copy(alpha = 0.6f)) else null,
        ) {
            Icon(
                imageVector = when {
                    isTracking && hasLocation -> Icons.Default.LocationOn
                    isTracking -> Icons.Default.GpsNotFixed
                    else -> Icons.Default.LocationOff
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when {
                    isTracking && hasLocation -> "GPS Active"
                    isTracking -> "Acquiring GPS..."
                    else -> "Enable GPS"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
