package com.entercomm.bikeintercom.mesh

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import com.entercomm.bikeintercom.location.LocationMessageType

data class MeshNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val isDirectConnection: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    val hopCount: Int = 1,
    val linkQuality: Float = 1.0f
)

data class MeshRoute(
    val destinationId: String,
    val nextHop: String,
    val hopCount: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String,
    val messageType: MessageType,
    val payload: ByteArray,
    val ttl: Int = 10,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class MessageType {
        DISCOVERY,
        ROUTE_UPDATE,
        AUDIO_DATA,
        CONTROL,
        HEARTBEAT,
        GROUP,          // Group management messages
        LOCATION        // Location sharing messages
    }
}

class MeshNetworkManager(
    private val nodeId: String,
    private val deviceName: String
) {
    companion object {
        private const val TAG = "MeshNetworkManager"
        const val DISCOVERY_PORT = 8888
        private const val AUDIO_PORT = 8889
        private const val HEARTBEAT_INTERVAL = 5000L
        private const val NODE_TIMEOUT = 15000L
        private const val MAX_ROUTE_AGE = 30000L
        private const val MAX_MESSAGE_CACHE_SIZE = 1000
        private const val ROUTE_UPDATE_INTERVAL = 10000L  // Send route updates every 10s
    }

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var networkJob: Job? = null

    private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Routing statistics
    private val _routingStats = MutableStateFlow(RoutingStats())
    val routingStats: StateFlow<RoutingStats> = _routingStats.asStateFlow()

    private val nodes = ConcurrentHashMap<String, MeshNode>()
    private val routingTable = ConcurrentHashMap<String, MeshRoute>()

    // Distance vector router for multi-hop routing
    private val router = DistanceVectorRouter(nodeId)
    // LRU cache for message deduplication with max size to prevent unbounded growth
    private val messageCache = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_MESSAGE_CACHE_SIZE
        }
    }
    private val messageCacheLock = Any()
    private val discoveryResponseCache = ConcurrentHashMap<String, Long>() // Rate limit discovery responses
    
    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var isRunning = false

    // Group filtering - only connect with nodes in the same group
    private var groupCode: String? = null
    private var groupModeEnabled = true  // Default to group mode for privacy

    /**
     * Set the group code for filtering connections.
     * Only nodes with matching group codes will connect.
     */
    fun setGroupCode(code: String?) {
        groupCode = code?.uppercase()?.replace("-", "")
        Log.d(TAG, "Group code set to: $groupCode")
    }

    /**
     * Get the current group code.
     */
    fun getGroupCode(): String? = groupCode

    /**
     * Enable or disable group mode filtering.
     * When disabled, connects to all nearby nodes (open mode).
     */
    fun setGroupModeEnabled(enabled: Boolean) {
        groupModeEnabled = enabled
        Log.d(TAG, "Group mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if group mode is enabled.
     */
    fun isGroupModeEnabled(): Boolean = groupModeEnabled

    // Callbacks for audio, control, group, and location messages
    var onAudioDataReceived: ((ByteArray, String) -> Unit)? = null
    var onControlMessageReceived: ((String, String) -> Unit)? = null
    var onGroupMessageReceived: ((GroupMessageType, String, ByteArray) -> Unit)? = null
    var onLocationMessageReceived: ((LocationMessageType, String, ByteArray) -> Unit)? = null
    
    fun startMeshNetwork(localPort: Int = DISCOVERY_PORT) {
        if (isRunning) {
            Log.d(TAG, "Mesh network already running")
            return
        }

        // Cancel any existing network job
        networkJob?.cancel()

        networkJob = scope.launch {
            try {
                Log.d(TAG, "Starting mesh network on port $localPort...")
                isRunning = true
                _isActive.value = true

                // Close existing sockets first to prevent leaks
                discoverySocket?.close()
                audioSocket?.close()

                // Initialize sockets with proper configuration
                discoverySocket = DatagramSocket(localPort).apply {
                    reuseAddress = true
                    broadcast = true
                }
                audioSocket = DatagramSocket(localPort + 1).apply {
                    reuseAddress = true
                }

                Log.d(TAG, "Mesh network sockets created: discovery=$localPort, audio=${localPort + 1}")
                Log.d(TAG, "Node ID: $nodeId, Device name: $deviceName")

                // Initialize the distance vector router
                router.initialize()
                Log.d(TAG, "Distance vector router initialized")

                // Start discovery and routing services
                launch { startDiscoveryService() }
                launch { startRoutingService() }
                launch { startHeartbeatService() }
                launch { startMessageListener() }
                launch { startAudioListener() }
                launch { startRouteAdvertisementService() }  // Multi-hop routing

                Log.d(TAG, "Mesh network services started successfully on port $localPort")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh network on port $localPort", e)
                e.printStackTrace()
                stopMeshNetwork()
            }
        }
    }
    
    fun stopMeshNetwork() {
        Log.d(TAG, "Stopping mesh network...")
        isRunning = false
        _isActive.value = false

        // Cancel all network coroutines first
        networkJob?.cancel()
        networkJob = null

        // Close sockets safely
        try {
            discoverySocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing discovery socket", e)
        }
        try {
            audioSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing audio socket", e)
        }
        discoverySocket = null
        audioSocket = null

        // Clear all data structures
        nodes.clear()
        routingTable.clear()
        router.clear()
        synchronized(messageCacheLock) {
            messageCache.clear()
        }
        discoveryResponseCache.clear()
        _connectedNodes.value = emptyList()

        Log.d(TAG, "Mesh network stopped")
    }
    
    fun scanAndConnectToAvailableDevices() {
        scope.launch {
            Log.d(TAG, "Starting network scan for available devices...")
            
            val localIPs = getLocalIPAddresses()
            if (localIPs.isEmpty()) {
                Log.w(TAG, "No local IP addresses found, cannot scan network")
                return@launch
            }
            
            Log.d(TAG, "Local IPs: ${localIPs.joinToString(", ")}")
            
            // Use the first available local IP to determine network subnet
            val localIP = localIPs.first()
            val subnet = localIP.substringBeforeLast(".")
            
            Log.d(TAG, "Scanning subnet $subnet.* for Enter-Comm devices (excluding our own IPs)...")
            
            // Scan common IP ranges in the subnet
            val scanJobs = mutableListOf<Job>()
            
            for (i in 1..254) {
                val targetIP = "$subnet.$i"
                
                // Skip our own IPs and any already connected nodes
                if (localIPs.contains(targetIP) || nodes.values.any { it.ipAddress == targetIP }) {
                    continue
                }
                
                // Launch concurrent discovery probes
                val job = launch {
                    try {
                        // Quick check if host is reachable
                        val address = InetAddress.getByName(targetIP)
                        if (address.isReachable(500)) { // 500ms timeout for quick scan
                            Log.d(TAG, "Found reachable device at $targetIP, sending discovery probe...")
                            sendDiscoveryProbe(targetIP, DISCOVERY_PORT)
                        }
                    } catch (e: Exception) {
                        // Silently ignore unreachable hosts to avoid spam
                    }
                }
                scanJobs.add(job)
                
                // Limit concurrent scans to avoid overwhelming the network
                if (scanJobs.size >= 20) {
                    scanJobs.joinAll()
                    scanJobs.clear()
                    delay(100) // Small delay between batches
                }
            }
            
            // Wait for remaining scan jobs
            scanJobs.joinAll()
            
            Log.d(TAG, "Network scan completed. Sent discovery probes to all reachable devices.")
            Log.d(TAG, "Actual mesh connections: ${nodes.size} devices")
        }
    }
    
    fun addDirectConnection(ipAddress: String, port: Int = DISCOVERY_PORT) {
        // Check if this is our own IP
        val localIPs = getLocalIPAddresses()
        if (localIPs.contains(ipAddress)) {
            Log.d(TAG, "Skipping direct connection to our own IP: $ipAddress")
            return
        }
        
        val nodeId = generateNodeId(ipAddress)
        
        // Check if node already exists and was recently updated
        val existingNode = nodes[nodeId]
        if (existingNode != null) {
            val timeSinceUpdate = System.currentTimeMillis() - existingNode.lastSeen
            if (timeSinceUpdate < 30000) { // Don't re-add if updated within last 30 seconds
                Log.d(TAG, "Node $nodeId at $ipAddress already exists and is recent, skipping...")
                return
            }
        }
        
        Log.d(TAG, "Attempting direct connection to $ipAddress:$port")
        
        // Only send discovery message - don't create phantom nodes
        // Nodes will be created only when they respond with discovery messages
        Log.d(TAG, "Sending discovery message to $ipAddress:$port")
        sendDiscoveryMessage(ipAddress, port)
        
        Log.d(TAG, "Discovery message sent to $ipAddress:$port - waiting for response...")
    }
    
    fun sendAudioData(audioData: ByteArray, destinationId: String? = null) {
        if (destinationId != null) {
            // Send to specific destination
            sendMessage(MeshMessage(
                sourceId = nodeId,
                destinationId = destinationId,
                messageType = MeshMessage.MessageType.AUDIO_DATA,
                payload = audioData
            ))
        } else {
            // Broadcast to all connected nodes
            nodes.keys.forEach { nodeId ->
                sendMessage(MeshMessage(
                    sourceId = this.nodeId,
                    destinationId = nodeId,
                    messageType = MeshMessage.MessageType.AUDIO_DATA,
                    payload = audioData
                ))
            }
        }
    }
    
    private suspend fun startDiscoveryService() {
        Log.d(TAG, "Starting discovery service - broadcasting every 10 seconds...")
        while (isRunning) {
            try {
                // Broadcast discovery message to local network
                Log.d(TAG, "Broadcasting discovery message...")
                broadcastDiscovery()
                delay(10000) // Discovery every 10 seconds
            } catch (e: Exception) {
                Log.e(TAG, "Discovery service error", e)
                delay(5000)
            }
        }
    }
    
    private suspend fun startRoutingService() {
        while (isRunning) {
            try {
                // Clean up old routes and nodes
                cleanupOldEntries()
                
                // Update routing table
                updateRoutingTable()
                
                delay(5000) // Update routing every 5 seconds
            } catch (e: Exception) {
                Log.e(TAG, "Routing service error", e)
                delay(5000)
            }
        }
    }
    
    private suspend fun startHeartbeatService() {
        while (isRunning) {
            try {
                // Send heartbeat to all known nodes
                sendHeartbeats()
                delay(HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat service error", e)
                delay(5000)
            }
        }
    }
    
    private suspend fun startMessageListener() {
        Log.d(TAG, "Starting message listener on discovery port...")
        while (isRunning) {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                Log.d(TAG, "Waiting for messages on discovery socket...")
                discoverySocket?.receive(packet)
                
                val senderAddress = packet.address.hostAddress ?: "unknown"
                Log.d(TAG, "Received message from $senderAddress, length: ${packet.length}")
                
                val message = deserializeMessage(packet.data, packet.length)
                if (message != null) {
                    Log.d(TAG, "Successfully deserialized message: type=${message.messageType}, source=${message.sourceId}, dest=${message.destinationId}")
                    handleIncomingMessage(message, senderAddress)
                } else {
                    Log.w(TAG, "Failed to deserialize message from $senderAddress")
                    Log.w(TAG, "Raw data: ${String(packet.data, 0, packet.length)}")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Message listener error", e)
                    delay(1000)
                } else {
                    Log.d(TAG, "Message listener stopped")
                }
            }
        }
    }
    
    private suspend fun startAudioListener() {
        Log.d(TAG, "Starting audio listener on port ${DISCOVERY_PORT + 1}...")
        while (isRunning) {
            try {
                val socket = audioSocket
                if (socket == null || socket.isClosed) {
                    Log.e(TAG, "Audio socket is null or closed, cannot receive audio")
                    delay(1000)
                    continue
                }
                
                val buffer = ByteArray(4096) // Larger buffer for audio
                val packet = DatagramPacket(buffer, buffer.size)
                
                socket.receive(packet)
                Log.d(TAG, "Received audio packet from ${packet.address.hostAddress}, length: ${packet.length}")
                
                val message = deserializeMessage(packet.data, packet.length)
                if (message != null) {
                    Log.d(TAG, "Deserialized audio message: type=${message.messageType}, source=${message.sourceId}")
                    
                    // Don't filter out audio messages from ourselves - we might need to handle echo cancellation differently
                    if (message.messageType == MeshMessage.MessageType.AUDIO_DATA) {
                        if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                            // Audio data for us
                            Log.d(TAG, "Playing audio data from ${message.sourceId}, size: ${message.payload.size}")
                            try {
                                onAudioDataReceived?.invoke(message.payload, message.sourceId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in audio callback", e)
                            }
                        } else {
                            // Forward audio data
                            Log.d(TAG, "Forwarding audio data to ${message.destinationId}")
                            forwardMessage(message)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to deserialize audio message")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Audio listener error", e)
                    e.printStackTrace()
                    delay(1000)
                } else {
                    Log.d(TAG, "Audio listener stopped")
                }
            }
        }
    }
    
    private fun handleIncomingMessage(message: MeshMessage, senderIp: String) {
        // Ignore messages from ourselves
        if (message.sourceId == nodeId) {
            Log.d(TAG, "Ignoring message from self: ${message.messageId}")
            return
        }

        // Check message cache to avoid processing duplicates (synchronized for LRU map)
        synchronized(messageCacheLock) {
            if (messageCache.containsKey(message.messageId)) {
                return
            }
            messageCache[message.messageId] = System.currentTimeMillis()
        }
        
        when (message.messageType) {
            MeshMessage.MessageType.DISCOVERY -> {
                handleDiscoveryMessage(message, senderIp)
            }
            MeshMessage.MessageType.ROUTE_UPDATE -> {
                handleRouteUpdate(message)
            }
            MeshMessage.MessageType.CONTROL -> {
                if (message.destinationId == nodeId) {
                    onControlMessageReceived?.invoke(String(message.payload), message.sourceId)
                } else {
                    forwardMessage(message)
                }
            }
            MeshMessage.MessageType.HEARTBEAT -> {
                handleHeartbeat(message, senderIp)
            }
            MeshMessage.MessageType.AUDIO_DATA -> {
                // Audio is handled in audio listener
            }
            MeshMessage.MessageType.GROUP -> {
                if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                    handleGroupMessage(message)
                } else {
                    forwardMessage(message)
                }
            }
            MeshMessage.MessageType.LOCATION -> {
                if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                    handleLocationMessage(message)
                } else {
                    forwardMessage(message)
                }
            }
        }
    }
    
    private fun handleDiscoveryMessage(message: MeshMessage, senderIp: String) {
        Log.d(TAG, "Handling discovery message from $senderIp")
        Log.d(TAG, "Message payload: ${String(message.payload)}")

        val nodeInfo = String(message.payload).split("|")
        if (nodeInfo.size >= 2) {
            val remoteNodeId = nodeInfo[0]
            val deviceName = nodeInfo[1]
            val remoteGroupCode = if (nodeInfo.size >= 3) nodeInfo[2] else "OPEN"

            Log.d(TAG, "Parsed discovery: nodeId=$remoteNodeId, deviceName=$deviceName, groupCode=$remoteGroupCode, senderIp=$senderIp")

            // Ignore messages from ourselves
            if (remoteNodeId == nodeId) {
                Log.d(TAG, "Ignoring discovery message from self: $remoteNodeId")
                return
            }

            // Group filtering: Only accept nodes with matching group code when group mode is enabled
            if (groupModeEnabled && groupCode != null) {
                val ourCode = groupCode!!
                val theirCode = remoteGroupCode.uppercase()
                if (ourCode != theirCode && theirCode != "OPEN" && ourCode != "OPEN") {
                    Log.d(TAG, "Ignoring node $remoteNodeId - group code mismatch (ours=$ourCode, theirs=$theirCode)")
                    return
                }
            }
            
            // Check if we should respond (rate limiting)
            val currentTime = System.currentTimeMillis()
            val lastResponseTime = discoveryResponseCache[senderIp] ?: 0
            val timeSinceLastResponse = currentTime - lastResponseTime
            
            if (timeSinceLastResponse < 5000) { // Don't respond more than once every 5 seconds to same IP
                Log.d(TAG, "Rate limiting discovery response to $senderIp (last response ${timeSinceLastResponse}ms ago)")
                // Still update the node but don't send another response
            } else {
                // Send our info back
                Log.d(TAG, "Sending discovery response to $senderIp")
                sendDiscoveryResponse(senderIp)
                discoveryResponseCache[senderIp] = currentTime
            }
            
            // Check if this is a new node or an update
            val existingNode = nodes[remoteNodeId]
            if (existingNode != null) {
                Log.d(TAG, "Updating existing node: $remoteNodeId")
            } else {
                Log.d(TAG, "Adding new node: $remoteNodeId")
            }
            
            val node = MeshNode(
                nodeId = remoteNodeId,
                deviceName = deviceName,
                ipAddress = senderIp,
                port = DISCOVERY_PORT,
                isDirectConnection = true,
                hopCount = 1,
                lastSeen = System.currentTimeMillis(),
                linkQuality = 1.0f
            )

            nodes[remoteNodeId] = node
            routingTable[remoteNodeId] = MeshRoute(
                destinationId = remoteNodeId,
                nextHop = remoteNodeId,
                hopCount = 1
            )

            // Add to distance vector router for multi-hop routing
            router.addNeighbor(remoteNodeId, senderIp, DISCOVERY_PORT, 1.0f)

            updateConnectedNodesList()

            Log.d(TAG, "Mesh network updated: discovered $deviceName ($remoteNodeId) at $senderIp")
            Log.d(TAG, "Total connected nodes: ${nodes.size}")
            Log.d(TAG, "Reachable destinations: ${router.getReachableDestinations().size}")
        } else {
            Log.w(TAG, "Invalid discovery message format from $senderIp: ${String(message.payload)}")
        }
    }
    
    private fun handleRouteUpdate(message: MeshMessage) {
        // Parse route advertisement from neighbor
        val advertisement = router.deserializeAdvertisement(message.payload)
        if (advertisement == null) {
            Log.w(TAG, "Failed to parse route advertisement from ${message.sourceId}")
            return
        }

        Log.d(TAG, "Processing route update from ${message.sourceId} with ${advertisement.routes.size} routes")

        // Get sender's IP for verification
        val senderNode = nodes[message.sourceId]
        if (senderNode == null) {
            Log.w(TAG, "Received route update from unknown node: ${message.sourceId}")
            return
        }

        // Process the route advertisement (Bellman-Ford update)
        val changed = router.processRouteAdvertisement(advertisement, senderNode.ipAddress)

        if (changed) {
            // Update our routing table from the router
            syncRoutingTableFromRouter()

            // Update connected nodes list to reflect new reachable nodes
            updateConnectedNodesList()

            // Update routing statistics
            updateRoutingStats()

            Log.d(TAG, "Routing table updated from ${message.sourceId}")
            Log.d(TAG, router.dumpRoutingTable())
        }
    }

    /**
     * Handle incoming group messages.
     */
    private fun handleGroupMessage(message: MeshMessage) {
        // Parse group message type from payload prefix
        val payload = message.payload
        if (payload.isEmpty()) return

        // Format: groupMessageType|actualPayload
        val payloadString = String(payload)
        val pipeIndex = payloadString.indexOf('|')
        if (pipeIndex == -1) return

        val typeString = payloadString.substring(0, pipeIndex)
        val actualPayload = payload.copyOfRange(pipeIndex + 1, payload.size)

        try {
            val groupMessageType = GroupMessageType.valueOf(typeString)
            onGroupMessageReceived?.invoke(groupMessageType, message.sourceId, actualPayload)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown group message type: $typeString")
        }
    }

    /**
     * Send a group management message.
     */
    fun sendGroupMessage(type: GroupMessageType, destinationId: String, payload: ByteArray) {
        // Prefix payload with message type
        val typePrefix = "${type.name}|".toByteArray()
        val fullPayload = typePrefix + payload

        val message = MeshMessage(
            sourceId = nodeId,
            destinationId = destinationId,
            messageType = MeshMessage.MessageType.GROUP,
            payload = fullPayload
        )

        if (destinationId == "broadcast") {
            broadcastToAllNeighbors(message)
        } else {
            sendMessage(message)
        }
    }

    /**
     * Handle incoming location messages.
     */
    private fun handleLocationMessage(message: MeshMessage) {
        val payload = message.payload
        if (payload.isEmpty()) return

        // Format: locationMessageType|actualPayload
        val payloadString = String(payload)
        val pipeIndex = payloadString.indexOf('|')
        if (pipeIndex == -1) return

        val typeString = payloadString.substring(0, pipeIndex)
        val actualPayload = payload.copyOfRange(pipeIndex + 1, payload.size)

        try {
            val locationMessageType = LocationMessageType.valueOf(typeString)
            onLocationMessageReceived?.invoke(locationMessageType, message.sourceId, actualPayload)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown location message type: $typeString")
        }
    }

    /**
     * Send a location message.
     */
    fun sendLocationMessage(type: LocationMessageType, destinationId: String, payload: ByteArray) {
        val typePrefix = "${type.name}|".toByteArray()
        val fullPayload = typePrefix + payload

        val message = MeshMessage(
            sourceId = nodeId,
            destinationId = destinationId,
            messageType = MeshMessage.MessageType.LOCATION,
            payload = fullPayload,
            ttl = 3  // Location messages don't need many hops
        )

        if (destinationId == "broadcast") {
            broadcastToAllNeighbors(message)
        } else {
            sendMessage(message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleHeartbeat(message: MeshMessage, senderIp: String) {
        val sourceId = message.sourceId

        // Update node's last seen time
        nodes[sourceId]?.let { node ->
            nodes[sourceId] = node.copy(lastSeen = System.currentTimeMillis())
        }

        // Update router's neighbor tracking
        router.updateNeighborHeartbeat(sourceId)
    }
    
    private fun sendMessage(message: MeshMessage) {
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
                    hopCount = 1
                )
                sendMessageToNode(message, targetNode)
                _routingStats.value = _routingStats.value.copy(
                    messagesRouted = _routingStats.value.messagesRouted + 1
                )
            } else {
                // Fallback to legacy routing table
                val route = routingTable[message.destinationId]
                val targetNode = route?.let { nodes[it.nextHop] }
                if (targetNode != null) {
                    sendMessageToNode(message, targetNode)
                } else {
                    Log.w(TAG, "No route to destination: ${message.destinationId}")
                    _routingStats.value = _routingStats.value.copy(
                        messagesDropped = _routingStats.value.messagesDropped + 1
                    )
                }
            }
        } else {
            // Try broadcasting if destination is "broadcast"
            if (message.destinationId == "broadcast") {
                broadcastToAllNeighbors(message)
            } else {
                Log.w(TAG, "No route to destination: ${message.destinationId}")
                _routingStats.value = _routingStats.value.copy(
                    messagesDropped = _routingStats.value.messagesDropped + 1
                )
            }
        }
    }

    private fun forwardMessage(message: MeshMessage) {
        if (message.ttl <= 0) {
            Log.d(TAG, "Dropping message ${message.messageId} - TTL expired")
            _routingStats.value = _routingStats.value.copy(
                messagesDropped = _routingStats.value.messagesDropped + 1
            )
            return
        }

        // Decrement TTL and forward
        val forwardedMessage = message.copy(ttl = message.ttl - 1)

        // Check if we have a route to the destination
        if (router.isReachable(message.destinationId)) {
            sendMessage(forwardedMessage)
            _routingStats.value = _routingStats.value.copy(
                messagesForwarded = _routingStats.value.messagesForwarded + 1
            )
            Log.d(TAG, "Forwarded message to ${message.destinationId} via ${router.getNextHop(message.destinationId)}")
        } else {
            Log.w(TAG, "Cannot forward - no route to ${message.destinationId}")
            _routingStats.value = _routingStats.value.copy(
                messagesDropped = _routingStats.value.messagesDropped + 1
            )
        }
    }

    private fun broadcastToAllNeighbors(message: MeshMessage) {
        router.getNeighbors().forEach { neighbor ->
            val targetNode = MeshNode(
                nodeId = neighbor.nodeId,
                deviceName = neighbor.nodeId,
                ipAddress = neighbor.ipAddress,
                port = neighbor.port,
                isDirectConnection = true,
                hopCount = 1
            )
            sendMessageToNode(message, targetNode)
        }
    }
    
    private fun sendMessageToNode(message: MeshMessage, node: MeshNode) {
        scope.launch {
            try {
                val data = serializeMessage(message)
                val packet = DatagramPacket(
                    data, data.size,
                    InetAddress.getByName(node.ipAddress),
                    if (message.messageType == MeshMessage.MessageType.AUDIO_DATA) node.port + 1 else node.port
                )
                
                val socket = if (message.messageType == MeshMessage.MessageType.AUDIO_DATA) audioSocket else discoverySocket
                socket?.send(packet)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to ${node.deviceName}", e)
            }
        }
    }
    
    private fun broadcastDiscovery() {
        scope.launch {
            // Include group code in discovery payload for filtering
            val groupCodePart = groupCode ?: "OPEN"
            val payload = "$nodeId|$deviceName|$groupCodePart".toByteArray()
            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = "broadcast",
                messageType = MeshMessage.MessageType.DISCOVERY,
                payload = payload
            )
            
            // Broadcast to local network
            try {
                val data = serializeMessage(message)
                Log.d(TAG, "Broadcasting discovery: nodeId=$nodeId, deviceName=$deviceName, dataSize=${data.size}")
                
                // Dynamically get broadcast addresses for all active network interfaces
                val broadcastAddresses = getNetworkBroadcastAddresses()
                Log.d(TAG, "Found ${broadcastAddresses.size} broadcast addresses: ${broadcastAddresses.joinToString(", ")}")
                
                for (broadcastAddr in broadcastAddresses) {
                    try {
                        val broadcastAddress = InetAddress.getByName(broadcastAddr)
                        val packet = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
                        discoverySocket?.send(packet)
                        Log.d(TAG, "Sent broadcast discovery to $broadcastAddr:$DISCOVERY_PORT")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to broadcast to $broadcastAddr", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast discovery", e)
            }
        }
    }
    
    private fun sendDiscoveryMessage(ipAddress: String, port: Int) {
        scope.launch {
            try {
                Log.d(TAG, "Preparing discovery message to $ipAddress:$port")

                // Include group code in discovery payload for filtering
                val groupCodePart = groupCode ?: "OPEN"
                val payload = "$nodeId|$deviceName|$groupCodePart".toByteArray()
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload
                )
                
                val data = serializeMessage(message)
                Log.d(TAG, "Discovery message size: ${data.size} bytes")
                
                val targetAddress = InetAddress.getByName(ipAddress)
                Log.d(TAG, "Resolved IP address: $ipAddress -> ${targetAddress.hostAddress}")
                
                val packet = DatagramPacket(data, data.size, targetAddress, port)
                
                val socket = discoverySocket
                if (socket != null && !socket.isClosed) {
                    socket.send(packet)
                    Log.d(TAG, "Discovery message sent successfully to $ipAddress:$port")
                } else {
                    Log.e(TAG, "Discovery socket is null or closed, cannot send to $ipAddress:$port")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send discovery message to $ipAddress:$port", e)
                Log.e(TAG, "Exception details: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun sendDiscoveryProbe(ipAddress: String, port: Int) {
        scope.launch {
            try {
                Log.d(TAG, "Sending discovery probe to $ipAddress:$port")
                
                val payload = "$nodeId|$deviceName".toByteArray()
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload
                )
                
                val data = serializeMessage(message)
                val targetAddress = InetAddress.getByName(ipAddress)
                val packet = DatagramPacket(data, data.size, targetAddress, port)
                
                val socket = discoverySocket
                if (socket != null && !socket.isClosed) {
                    socket.send(packet)
                    Log.d(TAG, "Discovery probe sent to $ipAddress:$port")
                } else {
                    Log.w(TAG, "Discovery socket not available for probe to $ipAddress:$port")
                }
                
            } catch (e: Exception) {
                // Silently fail for probes - most devices won't be running Enter-Comm
                Log.v(TAG, "Discovery probe failed to $ipAddress:$port: ${e.message}")
            }
        }
    }
    
    private fun sendDiscoveryResponse(ipAddress: String) {
        sendDiscoveryMessage(ipAddress, DISCOVERY_PORT)
    }
    
    private fun sendHeartbeats() {
        val payload = "heartbeat".toByteArray()
        nodes.values.forEach { node ->
            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = node.nodeId,
                messageType = MeshMessage.MessageType.HEARTBEAT,
                payload = payload
            )
            sendMessageToNode(message, node)
        }
    }
    
    private fun updateRoutingTable() {
        // Perform router maintenance (expire routes, remove dead neighbors)
        val removedDestinations = router.performMaintenance()

        // Remove expired nodes from our node map
        removedDestinations.forEach { destId ->
            nodes.remove(destId)
        }

        // Sync our legacy routing table with the router
        syncRoutingTableFromRouter()

        // Check for triggered updates
        if (router.hasPendingUpdate()) {
            // Send triggered route advertisements to all neighbors
            sendRouteAdvertisements()
            router.clearPendingUpdate()
        }

        // Update statistics
        updateRoutingStats()
    }

    /**
     * Route advertisement service - sends periodic route updates to neighbors.
     */
    private suspend fun startRouteAdvertisementService() {
        Log.d(TAG, "Starting route advertisement service...")
        while (isRunning) {
            try {
                // Send route advertisements to all neighbors
                sendRouteAdvertisements()

                delay(ROUTE_UPDATE_INTERVAL)
            } catch (e: Exception) {
                Log.e(TAG, "Route advertisement service error", e)
                delay(5000)
            }
        }
    }

    /**
     * Send route advertisements to all direct neighbors.
     */
    private fun sendRouteAdvertisements() {
        val neighbors = router.getNeighbors()
        if (neighbors.isEmpty()) {
            return
        }

        Log.d(TAG, "Sending route advertisements to ${neighbors.size} neighbors")

        for (neighbor in neighbors) {
            // Generate advertisement with split-horizon poison-reverse
            val advertisement = router.generateRouteAdvertisement(neighbor.nodeId)
            val payload = router.serializeAdvertisement(advertisement)

            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = neighbor.nodeId,
                messageType = MeshMessage.MessageType.ROUTE_UPDATE,
                payload = payload,
                ttl = 1  // Route updates are single-hop only
            )

            val targetNode = MeshNode(
                nodeId = neighbor.nodeId,
                deviceName = neighbor.nodeId,
                ipAddress = neighbor.ipAddress,
                port = neighbor.port,
                isDirectConnection = true,
                hopCount = 1
            )

            sendMessageToNode(message, targetNode)
        }

        _routingStats.value = _routingStats.value.copy(
            routeAdvertisementsSent = _routingStats.value.routeAdvertisementsSent + neighbors.size
        )
    }

    /**
     * Sync our legacy routing table with the distance vector router.
     */
    private fun syncRoutingTableFromRouter() {
        // Clear and rebuild from router
        routingTable.clear()

        router.getAllRoutes().forEach { routeEntry ->
            routingTable[routeEntry.destination] = MeshRoute(
                destinationId = routeEntry.destination,
                nextHop = routeEntry.nextHop,
                hopCount = routeEntry.hopCount,
                lastUpdated = routeEntry.lastUpdated
            )
        }
    }

    /**
     * Update routing statistics.
     */
    private fun updateRoutingStats() {
        val routes = router.getAllRoutes()
        val neighbors = router.getNeighbors()

        _routingStats.value = _routingStats.value.copy(
            totalRoutes = routes.size,
            directNeighbors = neighbors.size,
            multiHopRoutes = routes.count { !it.isDirectNeighbor },
            maxHopCount = routes.maxOfOrNull { it.hopCount } ?: 0
        )
    }
    
    private fun cleanupOldEntries() {
        val currentTime = System.currentTimeMillis()

        // Remove old nodes and notify router
        val expiredNodes = nodes.filter { (_, node) ->
            currentTime - node.lastSeen > NODE_TIMEOUT
        }.keys

        expiredNodes.forEach { expiredNodeId ->
            nodes.remove(expiredNodeId)
            routingTable.remove(expiredNodeId)
            // Notify router about removed neighbor
            router.removeNeighbor(expiredNodeId)
            Log.d(TAG, "Removed expired node: $expiredNodeId")
        }

        // Remove old routes (router handles its own route expiration)
        val expiredRoutes = routingTable.filter { (_, route) ->
            currentTime - route.lastUpdated > MAX_ROUTE_AGE
        }.keys

        expiredRoutes.forEach { destinationId ->
            routingTable.remove(destinationId)
        }

        // Remove old message cache entries (synchronized for LRU map)
        synchronized(messageCacheLock) {
            val expiredMessages = messageCache.filter { (_, timestamp) ->
                currentTime - timestamp > 60000 // 1 minute
            }.keys.toList() // Create a copy to avoid ConcurrentModification

            expiredMessages.forEach { messageId ->
                messageCache.remove(messageId)
            }
        }

        // Remove old discovery response cache entries
        val expiredResponses = discoveryResponseCache.filter { (_, timestamp) ->
            currentTime - timestamp > 300000 // 5 minutes
        }.keys

        expiredResponses.forEach { ipAddress ->
            discoveryResponseCache.remove(ipAddress)
        }

        if (expiredNodes.isNotEmpty() || expiredRoutes.isNotEmpty()) {
            updateConnectedNodesList()
        }
    }
    
    private fun updateConnectedNodesList() {
        _connectedNodes.value = nodes.values.toList()
    }
    
    private fun generateNodeId(ipAddress: String): String {
        return "node-${ipAddress.replace(".", "-")}"
    }
    
    private fun serializeMessage(message: MeshMessage): ByteArray {
        // Simple serialization - in production, use more robust serialization
        val data = "${message.messageId}|${message.sourceId}|${message.destinationId}|${message.messageType}|${message.ttl}|${message.timestamp}|"
        return data.toByteArray() + message.payload
    }
    
    private fun getNetworkBroadcastAddresses(): List<String> {
        val broadcastAddresses = mutableListOf<String>()
        
        try {
            // Always include general broadcast
            broadcastAddresses.add("255.255.255.255")
            
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            
            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                
                Log.d(TAG, "Checking network interface: ${networkInterface.name}")
                
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        val broadcastAddr = broadcast.hostAddress
                        if (broadcastAddr != null && !broadcastAddresses.contains(broadcastAddr)) {
                            broadcastAddresses.add(broadcastAddr)
                            Log.d(TAG, "Found broadcast address: $broadcastAddr for interface ${networkInterface.name}")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network broadcast addresses", e)
            // Fallback to common addresses if dynamic detection fails
            if (broadcastAddresses.size <= 1) {
                broadcastAddresses.add("192.168.49.255") // WiFi Direct common subnet
                broadcastAddresses.add("192.168.1.255")  // Common home network
                broadcastAddresses.add("10.0.0.255")     // Common mobile hotspot
            }
        }
        
        return broadcastAddresses
    }
    
    fun getLocalIPAddresses(): List<String> {
        val ipAddresses = mutableListOf<String>()
        
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            
            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val inetAddress = interfaceAddress.address
                    if (inetAddress is Inet4Address) {
                        val ipAddress = inetAddress.hostAddress
                        if (ipAddress != null && !ipAddress.startsWith("127.")) {
                            ipAddresses.add(ipAddress)
                            Log.d(TAG, "Found local IP: $ipAddress on interface ${networkInterface.name}")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP addresses", e)
        }
        
        return ipAddresses
    }
    
    private fun deserializeMessage(data: ByteArray, length: Int): MeshMessage? {
        try {
            val dataString = String(data, 0, length)
            Log.d(TAG, "Deserializing message: $dataString")
            
            // Find the position of the 6th pipe character (after timestamp)
            var pipeCount = 0
            var headerEnd = -1
            for (i in dataString.indices) {
                if (dataString[i] == '|') {
                    pipeCount++
                    if (pipeCount == 6) {
                        headerEnd = i
                        break
                    }
                }
            }
            
            if (headerEnd == -1) {
                Log.w(TAG, "Could not find 6 pipe characters in message header")
                return null
            }
            
            val headerParts = dataString.substring(0, headerEnd).split("|")
            Log.d(TAG, "Header parts: $headerParts")
            
            if (headerParts.size != 6) {
                Log.w(TAG, "Expected 6 header parts, got ${headerParts.size}")
                return null
            }
            
            val payload = data.copyOfRange(headerEnd + 1, length)
            Log.d(TAG, "Payload size: ${payload.size} bytes")
            
            val message = MeshMessage(
                messageId = headerParts[0],
                sourceId = headerParts[1],
                destinationId = headerParts[2],
                messageType = MeshMessage.MessageType.valueOf(headerParts[3]),
                ttl = headerParts[4].toInt(),
                timestamp = headerParts[5].toLong(),
                payload = payload
            )
            
            Log.d(TAG, "Successfully deserialized message: ${message.messageType} from ${message.sourceId}")
            return message
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize message", e)
            Log.e(TAG, "Raw message was: ${String(data, 0, length)}")
            return null
        }
    }

    /**
     * Get routing table debug information.
     */
    fun getRoutingTableDump(): String {
        return router.dumpRoutingTable()
    }

    /**
     * Get path information to a destination.
     */
    fun getPathInfo(destinationId: String): PathInfo? {
        return router.getPathInfo(destinationId)
    }

    /**
     * Check if a destination is reachable via multi-hop routing.
     */
    fun isReachable(destinationId: String): Boolean {
        return router.isReachable(destinationId)
    }

    /**
     * Get all reachable destinations.
     */
    fun getReachableDestinations(): Set<String> {
        return router.getReachableDestinations()
    }

    /**
     * Generate current mesh topology for visualization.
     */
    fun getMeshTopology(): MeshTopology {
        val builder = TopologyBuilder(nodeId, deviceName)

        // Add all nodes from our node map
        nodes.forEach { (id, node) ->
            val routeEntry = router.getRoute(id)
            builder.addNode(
                nodeId = id,
                displayName = node.deviceName,
                isDirectNeighbor = routeEntry?.isDirectNeighbor ?: node.isDirectConnection,
                hopCount = routeEntry?.hopCount ?: node.hopCount,
                linkQuality = routeEntry?.linkQuality ?: node.linkQuality,
                lastSeen = node.lastSeen
            )
        }

        // Add connections from router
        router.getNeighbors().forEach { neighbor ->
            builder.addConnection(
                fromNodeId = nodeId,
                toNodeId = neighbor.nodeId,
                linkQuality = neighbor.linkQuality,
                isDirect = true
            )
        }

        // Add route paths
        router.getAllRoutes().forEach { route ->
            if (!route.isDirectNeighbor) {
                // For multi-hop routes, show path through next hop
                builder.addRoutePath(route.destination, listOf(route.nextHop, route.destination))
            }
        }

        return builder.build()
    }
}

/**
 * Routing statistics for monitoring and debugging.
 */
data class RoutingStats(
    val totalRoutes: Int = 0,
    val directNeighbors: Int = 0,
    val multiHopRoutes: Int = 0,
    val maxHopCount: Int = 0,
    val messagesRouted: Long = 0,
    val messagesForwarded: Long = 0,
    val messagesDropped: Long = 0,
    val routeAdvertisementsSent: Long = 0
)