@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.*
import com.entercomm.bikeintercom.ui.theme.*

/**
 * Data class containing all diagnostics-related state for UI.
 */
data class DiagnosticsState(
    val meshTopology: MeshTopology?,
    val networkStats: NetworkStats,
    val connectedNodes: List<MeshNode>,
    val networkStartTime: Long,
)

/**
 * Floating Action Button for accessing network diagnostics.
 */
@Composable
fun NetworkDiagnosticsFAB(onClick: () -> Unit, connectedPeersCount: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        FloatingActionButton(onClick = onClick, containerColor = TechCyan, contentColor = PitchBlack) {
            Icon(imageVector = Icons.Rounded.Hub, contentDescription = "Network Diagnostics")
        }
        if (connectedPeersCount > 0) {
            PeerCountBadge(count = connectedPeersCount, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PeerCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(x = 4.dp, y = (-4).dp)
            .size(20.dp)
            .background(TechGreen, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = PitchBlack,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Summary bar showing key network metrics at a glance.
 */
@Composable
fun DiagnosticsSummaryBar(peerCount: Int, averageSignalStrength: Float, packetLossPercent: Float, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryChip(Icons.Rounded.Group, "Peers", peerCount.toString(), if (peerCount > 0) TechGreen else TextTertiary, Modifier.weight(1f))
        SummaryChip(Icons.Rounded.SignalCellularAlt, "Signal", "${(averageSignalStrength * 100).toInt()}%", getSignalColor(peerCount, averageSignalStrength), Modifier.weight(1f))
        SummaryChip(Icons.Rounded.SwapVert, "Loss", "${packetLossPercent.toInt()}%", getLossColor(peerCount, packetLossPercent), Modifier.weight(1f))
    }
}

private fun getSignalColor(peerCount: Int, signal: Float): Color {
    if (peerCount == 0) return TextTertiary
    val percent = (signal * 100).toInt()
    return when {
        percent >= 70 -> TechGreen
        percent >= 40 -> TechOrange
        else -> TechRed
    }
}

private fun getLossColor(peerCount: Int, loss: Float): Color {
    if (peerCount == 0) return TextTertiary
    return when {
        loss <= 5f -> TechGreen
        loss <= 15f -> TechOrange
        else -> TechRed
    }
}

@Composable
private fun SummaryChip(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val animatedColor by animateColorAsState(color, tween(300, easing = EaseOutCubic), label = "chipColor")
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = DarkSurfaceElevated, border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = animatedColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium, color = animatedColor, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

/**
 * Compact list item for displaying a connected peer with signal strength.
 */
@Composable
fun PeerListItem(node: MeshNode, topologyNode: TopologyNode? = null, modifier: Modifier = Modifier) {
    val signalStrength = topologyNode?.signalStrength ?: node.linkQuality
    val signalColor = getSignalQualityColor(topologyNode?.getSignalQuality() ?: deriveSignalQuality(signalStrength))

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = DarkSurfaceElevated, border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PeerAvatar(name = node.deviceName, color = signalColor)
            Spacer(modifier = Modifier.width(12.dp))
            PeerInfo(node = node, modifier = Modifier.weight(1f))
            SignalStrengthIndicator(strength = (signalStrength * 4).toInt().coerceIn(0, 4), modifier = Modifier.size(28.dp))
        }
    }
}

private fun deriveSignalQuality(strength: Float): TopologyNode.SignalQuality = when {
    strength >= 0.8f -> TopologyNode.SignalQuality.EXCELLENT
    strength >= 0.6f -> TopologyNode.SignalQuality.GOOD
    strength >= 0.4f -> TopologyNode.SignalQuality.FAIR
    strength >= 0.2f -> TopologyNode.SignalQuality.POOR
    else -> TopologyNode.SignalQuality.CRITICAL
}

private fun getSignalQualityColor(quality: TopologyNode.SignalQuality): Color = when (quality) {
    TopologyNode.SignalQuality.EXCELLENT -> TechGreen
    TopologyNode.SignalQuality.GOOD -> TechGreenMuted
    TopologyNode.SignalQuality.FAIR -> TechOrange
    TopologyNode.SignalQuality.POOR -> TechOrangeDark
    TopologyNode.SignalQuality.CRITICAL -> TechRed
}

@Composable
private fun PeerAvatar(name: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Text(text = name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeerInfo(node: MeshNode, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = node.deviceName, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (node.hopCount > 1) "${node.hopCount} hops" else "Direct", style = MaterialTheme.typography.labelSmall, color = if (node.hopCount > 1) TechCyan else TechGreen)
            Text(text = node.nodeId.take(12), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

/**
 * Modal bottom sheet containing the full network diagnostics dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(state: DiagnosticsState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = PitchBlack.copy(alpha = 0.7f),
        dragHandle = { DiagnosticsDragHandle() },
        modifier = modifier,
    ) {
        DiagnosticsSheetContent(state = state, onClose = onDismiss)
    }
}

@Composable
private fun DiagnosticsDragHandle() {
    Box(modifier = Modifier.padding(vertical = 12.dp).width(40.dp).height(4.dp).background(TechCyan.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
}

@Composable
private fun DiagnosticsSheetContent(state: DiagnosticsState, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        DiagnosticsHeader(onClose = onClose)
        Spacer(modifier = Modifier.height(16.dp))
        DiagnosticsSummaryBar(peerCount = state.connectedNodes.size, averageSignalStrength = state.meshTopology?.averageSignalStrength ?: 0f, packetLossPercent = state.networkStats.packetLossPercent)
        Spacer(modifier = Modifier.height(20.dp))
        ConnectedPeersSection(nodes = state.connectedNodes, topology = state.meshTopology)
        Spacer(modifier = Modifier.height(16.dp))
        TopologySection(topology = state.meshTopology)
        StatsSection(stats = state.networkStats, startTime = state.networkStartTime, isActive = state.connectedNodes.isNotEmpty())
    }
}

@Composable
private fun ConnectedPeersSection(nodes: List<MeshNode>, topology: MeshTopology?) {
    if (nodes.isNotEmpty()) {
        SectionHeader(title = "Connected Peers", icon = Icons.Rounded.Group)
        Spacer(modifier = Modifier.height(8.dp))
        nodes.forEach { node ->
            PeerListItem(node = node, topologyNode = topology?.nodes?.find { it.nodeId == node.nodeId })
            Spacer(modifier = Modifier.height(8.dp))
        }
    } else {
        EmptyPeersMessage()
    }
}

@Composable
private fun TopologySection(topology: MeshTopology?) {
    if (topology != null && topology.nodes.isNotEmpty()) {
        SectionHeader(title = "Network Topology", icon = Icons.Rounded.AccountTree)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = DarkSurfaceElevated, border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)) {
            EnhancedNetworkTopology(topology = topology, modifier = Modifier.fillMaxWidth().height(250.dp).padding(8.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StatsSection(stats: NetworkStats, startTime: Long, isActive: Boolean) {
    SectionHeader(title = "Network Statistics", icon = Icons.Rounded.Analytics)
    Spacer(modifier = Modifier.height(8.dp))
    NetworkStatsCard(stats = stats, startTime = startTime, isActive = isActive)
}

@Composable
private fun DiagnosticsHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = Icons.Rounded.Hub, contentDescription = null, tint = TechCyan, modifier = Modifier.size(24.dp))
            Text(text = "Network Diagnostics", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close", tint = TextTertiary)
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = TechCyan, modifier = Modifier.size(18.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextSecondary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyPeersMessage(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = DarkSurfaceElevated, border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = Icons.Rounded.GroupOff, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "No peers connected", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            Text(text = "Other riders will appear here when in range", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
    }
}
