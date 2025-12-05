package com.entercomm.bikeintercom.mesh

/**
 * Represents a node in the mesh topology visualization.
 */
data class TopologyNode(
    val nodeId: String,
    val displayName: String,
    val isDirectNeighbor: Boolean,
    val hopCount: Int,
    val signalStrength: Float,  // 0.0-1.0, estimated from link quality
    val lastSeen: Long,
    val isActive: Boolean = true
) {
    /**
     * Get signal quality category.
     */
    fun getSignalQuality(): SignalQuality {
        return when {
            signalStrength >= 0.8f -> SignalQuality.EXCELLENT
            signalStrength >= 0.6f -> SignalQuality.GOOD
            signalStrength >= 0.4f -> SignalQuality.FAIR
            signalStrength >= 0.2f -> SignalQuality.POOR
            else -> SignalQuality.CRITICAL
        }
    }

    enum class SignalQuality {
        EXCELLENT, GOOD, FAIR, POOR, CRITICAL
    }
}

/**
 * Represents a connection between two nodes.
 */
data class TopologyConnection(
    val fromNodeId: String,
    val toNodeId: String,
    val isActive: Boolean,
    val linkQuality: Float,  // 0.0-1.0
    val isDirect: Boolean,   // Direct neighbor connection
    val latency: Long = 0    // Estimated latency in ms
)

/**
 * Complete mesh topology snapshot for visualization.
 */
data class MeshTopology(
    val localNodeId: String,
    val localDisplayName: String,
    val nodes: List<TopologyNode>,
    val connections: List<TopologyConnection>,
    val routePaths: Map<String, List<String>>,  // destination -> path through nodes
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Get the total number of reachable nodes.
     */
    val reachableCount: Int
        get() = nodes.count { it.isActive }

    /**
     * Get direct neighbors only.
     */
    val directNeighbors: List<TopologyNode>
        get() = nodes.filter { it.isDirectNeighbor }

    /**
     * Get multi-hop nodes.
     */
    val multiHopNodes: List<TopologyNode>
        get() = nodes.filter { !it.isDirectNeighbor }

    /**
     * Get the maximum hop count in the network.
     */
    val maxHopCount: Int
        get() = nodes.maxOfOrNull { it.hopCount } ?: 0

    /**
     * Get average signal strength across all nodes.
     */
    val averageSignalStrength: Float
        get() = if (nodes.isEmpty()) 0f else nodes.map { it.signalStrength }.average().toFloat()

    /**
     * Get path to a specific node.
     */
    fun getPathTo(nodeId: String): List<String> {
        return routePaths[nodeId] ?: emptyList()
    }

    companion object {
        /**
         * Create an empty topology.
         */
        fun empty(localNodeId: String, localDisplayName: String = "This Device"): MeshTopology {
            return MeshTopology(
                localNodeId = localNodeId,
                localDisplayName = localDisplayName,
                nodes = emptyList(),
                connections = emptyList(),
                routePaths = emptyMap()
            )
        }
    }
}

/**
 * Builder for creating MeshTopology from router data.
 */
class TopologyBuilder(
    private val localNodeId: String,
    private val localDisplayName: String
) {
    private val nodes = mutableListOf<TopologyNode>()
    private val connections = mutableListOf<TopologyConnection>()
    private val routePaths = mutableMapOf<String, List<String>>()

    /**
     * Add a node from router data.
     */
    fun addNode(
        nodeId: String,
        displayName: String,
        isDirectNeighbor: Boolean,
        hopCount: Int,
        linkQuality: Float,
        lastSeen: Long
    ): TopologyBuilder {
        nodes.add(TopologyNode(
            nodeId = nodeId,
            displayName = displayName,
            isDirectNeighbor = isDirectNeighbor,
            hopCount = hopCount,
            signalStrength = linkQuality,
            lastSeen = lastSeen,
            isActive = true
        ))
        return this
    }

    /**
     * Add a connection.
     */
    fun addConnection(
        fromNodeId: String,
        toNodeId: String,
        linkQuality: Float,
        isDirect: Boolean
    ): TopologyBuilder {
        connections.add(TopologyConnection(
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            isActive = true,
            linkQuality = linkQuality,
            isDirect = isDirect
        ))
        return this
    }

    /**
     * Add a route path.
     */
    fun addRoutePath(destination: String, path: List<String>): TopologyBuilder {
        routePaths[destination] = path
        return this
    }

    /**
     * Build the topology.
     */
    fun build(): MeshTopology {
        return MeshTopology(
            localNodeId = localNodeId,
            localDisplayName = localDisplayName,
            nodes = nodes.toList(),
            connections = connections.toList(),
            routePaths = routePaths.toMap()
        )
    }
}
