@file:Suppress("TooManyFunctions")

package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.*
import com.entercomm.bikeintercom.ui.theme.*

/**
 * Floating Action Button for accessing network diagnostics.
 * Shows a badge with connected peer count when peers are connected.
 */
@Composable
fun NetworkDiagnosticsFAB(
    onClick: () -> Unit,
    connectedPeersCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = TechCyan,
            contentColor = PitchBlack,
        ) {
            Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = "Network Diagnostics",
            )
        }

        // Badge showing peer count
        if (connectedPeersCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .background(TechGreen, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (connectedPeersCount > 9) "9+" else connectedPeersCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PitchBlack,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Summary bar showing key network metrics at a glance.
 */
@Composable
fun DiagnosticsSummaryBar(
    peerCount: Int,
    averageSignalStrength: Float,
    packetLossPercent: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Peers chip
        SummaryChip(
            icon = Icons.Rounded.Group,
            label = "Peers",
            value = peerCount.toString(),
            color = if (peerCount > 0) TechGreen else TextTertiary,
            modifier = Modifier.weight(1f),
        )

        // Signal chip
        val signalPercent = (averageSignalStrength * 100).toInt()
        val signalColor = when {
            signalPercent >= 70 -> TechGreen
            signalPercent >= 40 -> TechOrange
            else -> TechRed
        }
        SummaryChip(
            icon = Icons.Rounded.SignalCellularAlt,
            label = "Signal",
            value = "$signalPercent%",
            color = if (peerCount > 0) signalColor else TextTertiary,
            modifier = Modifier.weight(1f),
        )

        // Packet loss chip
        val lossColor = when {
            packetLossPercent <= 5f -> TechGreen
            packetLossPercent <= 15f -> TechOrange
            else -> TechRed
        }
        SummaryChip(
            icon = Icons.Rounded.SwapVert,
            label = "Loss",
            value = "${packetLossPercent.toInt()}%",
            color = if (peerCount > 0) lossColor else TextTertiary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300, easing = EaseOutCubic),
        label = "chipColor",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = animatedColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = animatedColor,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

/**
 * Compact list item for displaying a connected peer with signal strength.
 */
@Composable
fun PeerListItem(
    node: MeshNode,
    topologyNode: TopologyNode? = null,
    modifier: Modifier = Modifier,
) {
    val signalStrength = topologyNode?.signalStrength ?: node.linkQuality
    val signalQuality = topologyNode?.getSignalQuality()
        ?: when {
            signalStrength >= 0.8f -> TopologyNode.SignalQuality.EXCELLENT
            signalStrength >= 0.6f -> TopologyNode.SignalQuality.GOOD
            signalStrength >= 0.4f -> TopologyNode.SignalQuality.FAIR
            signalStrength >= 0.2f -> TopologyNode.SignalQuality.POOR
            else -> TopologyNode.SignalQuality.CRITICAL
        }

    val signalColor = when (signalQuality) {
        TopologyNode.SignalQuality.EXCELLENT -> TechGreen
        TopologyNode.SignalQuality.GOOD -> TechGreenMuted
        TopologyNode.SignalQuality.FAIR -> TechOrange
        TopologyNode.SignalQuality.POOR -> TechOrangeDark
        TopologyNode.SignalQuality.CRITICAL -> TechRed
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar with first letter
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(signalColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = node.deviceName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = signalColor,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name and info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Hop count badge
                    if (node.hopCount > 1) {
                        Text(
                            text = "${node.hopCount} hops",
                            style = MaterialTheme.typography.labelSmall,
                            color = TechCyan,
                        )
                    } else {
                        Text(
                            text = "Direct",
                            style = MaterialTheme.typography.labelSmall,
                            color = TechGreen,
                        )
                    }
                    // Node ID (truncated)
                    Text(
                        text = node.nodeId.take(12),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }

            // Signal strength indicator
            SignalStrengthIndicator(
                strength = (signalStrength * 4).toInt().coerceIn(0, 4),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Modal bottom sheet containing the full network diagnostics dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(
    meshTopology: MeshTopology?,
    networkStats: NetworkStats,
    connectedNodes: List<MeshNode>,
    networkStartTime: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = PitchBlack.copy(alpha = 0.7f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(TechCyan.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            DiagnosticsHeader(onClose = onDismiss)

            Spacer(modifier = Modifier.height(16.dp))

            // Summary bar
            DiagnosticsSummaryBar(
                peerCount = connectedNodes.size,
                averageSignalStrength = meshTopology?.averageSignalStrength ?: 0f,
                packetLossPercent = networkStats.packetLossPercent,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Connected peers section
            if (connectedNodes.isNotEmpty()) {
                SectionHeader(title = "Connected Peers", icon = Icons.Rounded.Group)
                Spacer(modifier = Modifier.height(8.dp))

                connectedNodes.forEach { node ->
                    val topologyNode = meshTopology?.nodes?.find { it.nodeId == node.nodeId }
                    PeerListItem(
                        node = node,
                        topologyNode = topologyNode,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                EmptyPeersMessage()
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Network topology visualization
            if (meshTopology != null && meshTopology.nodes.isNotEmpty()) {
                SectionHeader(title = "Network Topology", icon = Icons.Rounded.AccountTree)
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                ) {
                    EnhancedNetworkTopology(
                        topology = meshTopology,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .padding(8.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Detailed network statistics
            SectionHeader(title = "Network Statistics", icon = Icons.Rounded.Analytics)
            Spacer(modifier = Modifier.height(8.dp))

            NetworkStatsCard(
                stats = networkStats,
                startTime = networkStartTime,
                isActive = connectedNodes.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun DiagnosticsHeader(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = null,
                tint = TechCyan,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "Network Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close",
                tint = TextTertiary,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyPeersMessage(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.GroupOff,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No peers connected",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            Text(
                text = "Other riders will appear here when in range",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}
