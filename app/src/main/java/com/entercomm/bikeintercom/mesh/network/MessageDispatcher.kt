package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.mesh.DistanceVectorRouter
import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.mesh.MeshNode
import com.entercomm.bikeintercom.mesh.RoutingStats
import com.entercomm.bikeintercom.mesh.protocol.MeshProtocol
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Handles message dispatch, routing, caching, and forwarding for the mesh network.
 */
@Suppress("LongParameterList", "TooManyFunctions", "ClassOrdering", "TooGenericExceptionCaught")
class MessageDispatcher(
    private val nodeId: String,
    private val protocol: MeshProtocol,
    private val router: DistanceVectorRouter,
    private val socketManager: SocketManager,
    private val nodeRegistry: NodeRegistry,
    private val statsCollector: NetworkStatsCollector,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val MAX_MESSAGE_CACHE_SIZE = 1000
    }

    // Routing statistics
    private val _routingStats = MutableStateFlow(RoutingStats())
    val routingStats: StateFlow<RoutingStats> = _routingStats.asStateFlow()

    // LRU cache for message deduplication
    private val messageCache = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_MESSAGE_CACHE_SIZE
        }
    }
    private val messageCacheLock = Any()

    // Message type callbacks (set by MeshNetworkManager)
    var onDiscoveryMessage: ((MeshMessage, String) -> Unit)? = null
    var onRouteUpdateMessage: ((MeshMessage) -> Unit)? = null
    var onControlMessage: ((String, String) -> Unit)? = null
    var onHeartbeatMessage: ((MeshMessage, String) -> Unit)? = null
    var onGroupMessage: ((MeshMessage) -> Unit)? = null
    var onLocationMessage: ((MeshMessage) -> Unit)? = null
    var onAudioMessage: ((MeshMessage) -> Unit)? = null

    /**
     * Handle an incoming message from the network.
     * Performs deduplication, tracks statistics, and dispatches to appropriate handler.
     */
    @Suppress("CyclomaticComplexMethod")
    fun handleMessage(message: MeshMessage, senderIp: String) {
        // Ignore messages from ourselves
        if (message.sourceId == nodeId) {
            logD { "Ignoring message from self: ${message.messageId}" }
            return
        }

        // Check message cache to avoid processing duplicates
        synchronized(messageCacheLock) {
            if (messageCache.containsKey(message.messageId)) {
                return
            }
            messageCache[message.messageId] = System.currentTimeMillis()
        }

        // Track received packet statistics
        statsCollector.recordPacketReceived(message.payload.size, message.messageType)

        // Dispatch based on message type with exception handling
        try {
            when (message.messageType) {
                MeshMessage.MessageType.DISCOVERY -> {
                    onDiscoveryMessage?.invoke(message, senderIp)
                }
                MeshMessage.MessageType.ROUTE_UPDATE -> {
                    onRouteUpdateMessage?.invoke(message)
                }
                MeshMessage.MessageType.CONTROL -> {
                    if (message.destinationId == nodeId) {
                        onControlMessage?.invoke(String(message.payload), message.sourceId)
                    } else {
                        forwardMessage(message)
                    }
                }
                MeshMessage.MessageType.HEARTBEAT -> {
                    onHeartbeatMessage?.invoke(message, senderIp)
                }
                MeshMessage.MessageType.AUDIO_DATA -> {
                    // Audio is handled separately in audio listener
                    onAudioMessage?.invoke(message)
                }
                MeshMessage.MessageType.GROUP -> {
                    if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                        onGroupMessage?.invoke(message)
                    } else {
                        forwardMessage(message)
                    }
                }
                MeshMessage.MessageType.LOCATION -> {
                    if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                        onLocationMessage?.invoke(message)
                    } else {
                        forwardMessage(message)
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Exception in message callback for ${message.messageType}" }, e)
        }
    }

    /**
     * Send a message to its destination using the routing table.
     */
    fun sendMessage(message: MeshMessage) {
        // Use the distance vector router for next hop lookup
        val nextHopId = router.getNextHop(message.destinationId)

        if (nextHopId != null) {
            // Get the neighbor info for the next hop
            val neighbor = router.getNeighbor(nextHopId)
            if (neighbor != null) {
                val targetNode = MeshNode(
                    nodeId = nextHopId,
                    deviceName = nextHopId,
                    ipAddress = neighbor.ipAddress,
                    port = neighbor.port,
                    isDirectConnection = true,
                    hopCount = 1,
                )
                sendToNode(message, targetNode)
                incrementMessagesRouted()
            } else {
                // Fallback to node registry
                val targetNode = nodeRegistry.getNode(nextHopId)
                if (targetNode != null) {
                    sendToNode(message, targetNode)
                } else {
                    logW { "No route to destination: ${message.destinationId}" }
                    incrementMessagesDropped()
                }
            }
        } else {
            // Try broadcasting if destination is "broadcast"
            if (message.destinationId == "broadcast") {
                broadcastToNeighbors(message)
            } else {
                logW { "No route to destination: ${message.destinationId}" }
                incrementMessagesDropped()
            }
        }
    }

    /**
     * Send a message directly to a specific node.
     * Uses coroutine for non-audio messages, direct call for audio (hot path).
     */
    fun sendToNode(message: MeshMessage, node: MeshNode) {
        val isAudioMessage = message.messageType == MeshMessage.MessageType.AUDIO_DATA

        if (isAudioMessage) {
            // Hot path for audio - avoid coroutine overhead
            sendToNodeDirect(message, node)
        } else {
            // Non-audio messages can use coroutine
            scope.launch {
                sendToNodeDirect(message, node)
            }
        }
    }

    /**
     * Direct synchronous send to a node.
     * Optimized for low-latency audio transmission.
     */
    private fun sendToNodeDirect(message: MeshMessage, node: MeshNode) {
        try {
            val data = protocol.serialize(message)
            val isAudioMessage = message.messageType == MeshMessage.MessageType.AUDIO_DATA
            val targetPort = if (isAudioMessage) node.port + 1 else node.port
            val targetAddress = InetAddress.getByName(node.ipAddress)

            val success = if (isAudioMessage) {
                socketManager.sendAudio(data, targetAddress, targetPort)
            } else {
                socketManager.sendDiscovery(data, targetAddress, targetPort)
            }

            if (success) {
                statsCollector.recordPacketSent(data.size, message.messageType)
            }
        } catch (e: Exception) {
            logE({ "Failed to send message to ${node.deviceName}" }, e)
        }
    }

    /**
     * Broadcast a message to all direct neighbors.
     */
    fun broadcastToNeighbors(message: MeshMessage) {
        router.getNeighbors().forEach { neighbor ->
            val targetNode = MeshNode(
                nodeId = neighbor.nodeId,
                deviceName = neighbor.nodeId,
                ipAddress = neighbor.ipAddress,
                port = neighbor.port,
                isDirectConnection = true,
                hopCount = 1,
            )
            sendToNode(message, targetNode)
        }
    }

    /**
     * Forward a message towards its destination.
     */
    fun forwardMessage(message: MeshMessage) {
        if (message.ttl <= 0) {
            logD { "Dropping message ${message.messageId} - TTL expired" }
            incrementMessagesDropped()
            return
        }

        // Decrement TTL and forward
        val forwardedMessage = message.copy(ttl = message.ttl - 1)

        if (router.isReachable(message.destinationId)) {
            sendMessage(forwardedMessage)
            incrementMessagesForwarded()
            logD { "Forwarded message to ${message.destinationId} via ${router.getNextHop(message.destinationId)}" }
        } else {
            logW { "Cannot forward - no route to ${message.destinationId}" }
            incrementMessagesDropped()
        }
    }

    /**
     * Check if a message has already been processed (duplicate).
     */
    fun isMessageDuplicate(messageId: String): Boolean {
        return synchronized(messageCacheLock) {
            messageCache.containsKey(messageId)
        }
    }

    /**
     * Clean up expired entries from the message cache.
     */
    fun cleanupCache() {
        val currentTime = System.currentTimeMillis()
        synchronized(messageCacheLock) {
            val expiredMessages = messageCache.filter { (_, timestamp) ->
                currentTime - timestamp > 60_000 // 1 minute
            }.keys.toList()

            expiredMessages.forEach { messageId ->
                messageCache.remove(messageId)
            }
        }
    }

    /**
     * Clear all callbacks to prevent memory leaks.
     */
    fun clearCallbacks() {
        onDiscoveryMessage = null
        onRouteUpdateMessage = null
        onControlMessage = null
        onHeartbeatMessage = null
        onGroupMessage = null
        onLocationMessage = null
        onAudioMessage = null
    }

    /**
     * Clear the message cache.
     */
    fun clear() {
        synchronized(messageCacheLock) {
            messageCache.clear()
        }
    }

    /**
     * Update routing statistics with current route information.
     */
    fun updateRoutingStats(totalRoutes: Int, directNeighbors: Int, multiHopRoutes: Int, maxHopCount: Int) {
        _routingStats.value = _routingStats.value.copy(
            totalRoutes = totalRoutes,
            directNeighbors = directNeighbors,
            multiHopRoutes = multiHopRoutes,
            maxHopCount = maxHopCount,
        )
    }

    /**
     * Increment route advertisements sent counter.
     */
    fun incrementRouteAdvertisementsSent(count: Int = 1) {
        _routingStats.value = _routingStats.value.copy(
            routeAdvertisementsSent = _routingStats.value.routeAdvertisementsSent + count,
        )
    }

    private fun incrementMessagesRouted() {
        _routingStats.value = _routingStats.value.copy(
            messagesRouted = _routingStats.value.messagesRouted + 1,
        )
    }

    private fun incrementMessagesForwarded() {
        _routingStats.value = _routingStats.value.copy(
            messagesForwarded = _routingStats.value.messagesForwarded + 1,
        )
    }

    private fun incrementMessagesDropped() {
        _routingStats.value = _routingStats.value.copy(
            messagesDropped = _routingStats.value.messagesDropped + 1,
        )
    }
}
