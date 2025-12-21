package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.mesh.MeshNode
import com.entercomm.bikeintercom.util.logD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the registry of mesh network nodes.
 * Handles node storage, lookup, and lifecycle tracking.
 */
@Suppress("TooManyFunctions")
class NodeRegistry {

    private val nodes = ConcurrentHashMap<String, MeshNode>()

    private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()

    // LRU cache of recently seen IPs for priority scanning (faster reconnection)
    private val recentlySeenIps = object : LinkedHashMap<String, Long>(
        AppConfig.Mesh.RECENTLY_SEEN_IPS_MAX_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > AppConfig.Mesh.RECENTLY_SEEN_IPS_MAX_SIZE
        }
    }
    private val recentlySeenIpsLock = Any()

    // Callbacks for node lifecycle events
    var onNodeAdded: ((MeshNode) -> Unit)? = null
    var onNodeRemoved: ((String) -> Unit)? = null

    /**
     * Add or update a node in the registry.
     * @return true if this is a new node, false if updating existing
     */
    fun addOrUpdateNode(node: MeshNode): Boolean {
        val isNew = !nodes.containsKey(node.nodeId)
        nodes[node.nodeId] = node
        updateConnectedNodesList()

        if (isNew) {
            onNodeAdded?.invoke(node)
        }

        return isNew
    }

    /**
     * Remove a node from the registry.
     * @return the removed node, or null if not found
     */
    fun removeNode(nodeId: String): MeshNode? {
        val removed = nodes.remove(nodeId)
        if (removed != null) {
            updateConnectedNodesList()
            onNodeRemoved?.invoke(nodeId)
        }
        return removed
    }

    /**
     * Get a node by ID.
     */
    fun getNode(nodeId: String): MeshNode? = nodes[nodeId]

    /**
     * Get all nodes as a map.
     */
    fun getAllNodes(): Map<String, MeshNode> = nodes.toMap()

    /**
     * Get all node IDs.
     */
    fun getAllNodeIds(): Set<String> = nodes.keys.toSet()

    /**
     * Check if a node exists.
     */
    fun containsNode(nodeId: String): Boolean = nodes.containsKey(nodeId)

    /**
     * Get the number of nodes.
     */
    fun nodeCount(): Int = nodes.size

    /**
     * Update a node's last seen timestamp.
     */
    fun updateNodeLastSeen(nodeId: String) {
        nodes[nodeId]?.let { node ->
            nodes[nodeId] = node.copy(lastSeen = System.currentTimeMillis())
        }
    }

    /**
     * Record an IP address as recently seen for priority scanning.
     */
    fun recordRecentlySeenIp(ipAddress: String) {
        synchronized(recentlySeenIpsLock) {
            recentlySeenIps[ipAddress] = System.currentTimeMillis()
        }
    }

    /**
     * Get the list of recently seen IP addresses for priority scanning.
     */
    fun getRecentlySeenIps(): List<String> {
        return synchronized(recentlySeenIpsLock) {
            recentlySeenIps.keys.toList()
        }
    }

    /**
     * Clean up expired nodes based on timeout.
     * @param timeout Node timeout in milliseconds
     * @return List of removed node IDs
     */
    fun cleanupExpiredNodes(timeout: Long): List<String> {
        val currentTime = System.currentTimeMillis()
        val expiredNodes = nodes.filter { (_, node) ->
            currentTime - node.lastSeen > timeout
        }.keys.toList()

        expiredNodes.forEach { nodeId ->
            nodes.remove(nodeId)
            onNodeRemoved?.invoke(nodeId)
            logD { "Removed expired node: $nodeId" }
        }

        if (expiredNodes.isNotEmpty()) {
            updateConnectedNodesList()
        }

        return expiredNodes
    }

    /**
     * Clean up expired recently seen IPs.
     */
    fun cleanupExpiredRecentlySeenIps(ttlMs: Long) {
        val currentTime = System.currentTimeMillis()
        synchronized(recentlySeenIpsLock) {
            val expiredIps = recentlySeenIps.filter { (_, timestamp) ->
                currentTime - timestamp > ttlMs
            }.keys.toList()

            expiredIps.forEach { ip ->
                recentlySeenIps.remove(ip)
            }
        }
    }

    /**
     * Clear all nodes. Note: Does not clear recently seen IPs for faster reconnection.
     */
    fun clear() {
        nodes.clear()
        _connectedNodes.value = emptyList()
    }

    /**
     * Clear all data including recently seen IPs.
     */
    fun clearAll() {
        clear()
        synchronized(recentlySeenIpsLock) {
            recentlySeenIps.clear()
        }
    }

    /**
     * Generate a node ID from an IP address.
     */
    fun generateNodeId(ipAddress: String): String {
        return "node-${ipAddress.replace(".", "-")}"
    }

    /**
     * Check if an IP belongs to a connected node.
     */
    fun isConnectedIp(ipAddress: String): Boolean {
        return nodes.values.any { it.ipAddress == ipAddress }
    }

    /**
     * Update the connected nodes StateFlow.
     */
    private fun updateConnectedNodesList() {
        _connectedNodes.value = nodes.values.toList()
    }
}
