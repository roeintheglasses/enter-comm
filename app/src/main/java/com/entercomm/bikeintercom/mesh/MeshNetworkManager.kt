package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.location.LocationMessageType
import com.entercomm.bikeintercom.mesh.protocol.MeshProtocol
import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class MeshNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val isDirectConnection: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    val hopCount: Int = 1,
    val linkQuality: Float = 1.0f,
)

data class MeshRoute(
    val destinationId: String,
    val nextHop: String,
    val hopCount: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)

data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String,
    val messageType: MessageType,
    val payload: ByteArray,
    val ttl: Int = 10,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class MessageType {
        DISCOVERY,
        ROUTE_UPDATE,
        AUDIO_DATA,
        CONTROL,
        HEARTBEAT,
        GROUP, // Group management messages
        LOCATION, // Location sharing messages
    }
}

class MeshNetworkManager(
    private val nodeId: String,
    deviceName: String,
    private val protocol: MeshProtocol = MeshProtocol.default(),
) {
    companion object {
        const val DISCOVERY_PORT = 8888
        private const val AUDIO_PORT = 8889
        private const val HEARTBEAT_INTERVAL = 5000L
        private const val NODE_TIMEOUT = 15000L
        private const val MAX_ROUTE_AGE = 30000L
        private const val MAX_MESSAGE_CACHE_SIZE = 1000
        private const val ROUTE_UPDATE_INTERVAL = 10000L // Send route updates every 10s
        private const val SOCKET_TIMEOUT_MS = 1000 // 1 second timeout for socket receive operations

        // Input validation constants
        private const val MAX_DEVICE_NAME_LENGTH = 50
        private const val MIN_DEVICE_NAME_LENGTH = 1
        private const val MIN_GROUP_CODE_LENGTH = 4
        private const val MAX_GROUP_CODE_LENGTH = 8
        private val GROUP_CODE_PATTERN = Regex("^[A-Z0-9]{4,8}$")

        // Accepts node-XXXXXXXX format or UUID-like format
        private val UUID_PATTERN = Regex("^(node-[a-fA-F0-9]{8}|[a-fA-F0-9-]{8,36})$")

        /**
         * Delimiter used in pipe-delimited message format.
         * Fields containing this character must be sanitized before serialization.
         */
        private const val FIELD_DELIMITER = '|'

        /**
         * Sanitizes a string for use in pipe-delimited message format.
         * Replaces pipe characters with underscores to prevent parsing issues.
         *
         * Note: This is a simple sanitization approach. For more robust serialization,
         * consider migrating to a binary protocol (protobuf, MessagePack) in the future.
         */
        fun sanitizeForDelimitedFormat(value: String): String {
            return value.replace(FIELD_DELIMITER, '_')
        }
    }

    /**
     * Validated discovery message payload.
     */
    data class DiscoveryPayload(
        val nodeId: String,
        val deviceName: String,
        val groupCode: String,
        val nickname: String,
    )

    /**
     * Validates a discovery message payload and returns a validated DiscoveryPayload if valid.
     * Returns null if the message is malformed or fails validation.
     * Format: nodeId|deviceName|groupCode|nickname (nickname is optional for backwards compatibility)
     */
    private fun validateDiscoveryPayload(payload: String): DiscoveryPayload? {
        val parts = payload.split("|")
        if (parts.size < 2) {
            logW { "Invalid discovery payload: too few fields" }
            return null
        }

        // Validate node ID (must be non-empty and match UUID-like pattern)
        val nodeId = parts[0]
        if (nodeId.isEmpty() || nodeId.length > 36) {
            logW { "Invalid discovery payload: invalid nodeId length" }
            return null
        }
        if (!UUID_PATTERN.matches(nodeId)) {
            logW { "Invalid discovery payload: nodeId doesn't match expected format" }
            return null
        }

        // Validate device name (1-50 chars)
        val deviceName = parts[1]
        if (deviceName.length !in MIN_DEVICE_NAME_LENGTH..MAX_DEVICE_NAME_LENGTH) {
            logW { "Invalid discovery payload: deviceName length out of range (${deviceName.length})" }
            return null
        }

        // Validate group code (4-8 alphanumeric, or "OPEN")
        val groupCode = if (parts.size >= 3) parts[2].uppercase() else "OPEN"
        if (groupCode != "OPEN" && !GROUP_CODE_PATTERN.matches(groupCode)) {
            logW { "Invalid discovery payload: invalid groupCode format" }
            return null
        }

        // Get nickname (optional field, defaults to deviceName for backwards compatibility)
        val nickname = if (parts.size >= 4 && parts[3].isNotEmpty()) {
            sanitizeForDelimitedFormat(parts[3].take(MAX_DEVICE_NAME_LENGTH))
        } else {
            sanitizeForDelimitedFormat(deviceName)
        }

        return DiscoveryPayload(
            nodeId = nodeId,
            deviceName = sanitizeForDelimitedFormat(deviceName),
            groupCode = sanitizeForDelimitedFormat(groupCode),
            nickname = nickname,
        )
    }

    // Sanitize device name at construction to ensure safe serialization
    private val deviceName: String = sanitizeForDelimitedFormat(deviceName)

    // User's nickname for display in group member lists
    private var userNickname: String = sanitizeForDelimitedFormat(deviceName)

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

    // Network statistics for diagnostics
    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    // Network statistics counters
    private var statsPacketsSent = 0L
    private var statsPacketsReceived = 0L
    private var statsBytesSent = 0L
    private var statsBytesReceived = 0L
    private var statsDiscoveryRequestsSent = 0L
    private var statsDiscoveryResponsesReceived = 0L
    private var statsAudioPacketsSent = 0L
    private var statsAudioPacketsReceived = 0L
    private var statsHeartbeatsSent = 0L
    private var statsHeartbeatsReceived = 0L
    private var networkStartTime = 0L

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

    // LRU cache for rate-limiting discovery responses with max size
    private val discoveryResponseCache = object : LinkedHashMap<String, Long>(
        AppConfig.Mesh.DISCOVERY_CACHE_MAX_SIZE,
        0.75f,
        true, // Access order for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > AppConfig.Mesh.DISCOVERY_CACHE_MAX_SIZE
        }
    }
    private val discoveryResponseCacheLock = Any()

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

    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var isRunning = false

    // Group filtering - only connect with nodes in the same group
    private var groupCode: String? = null
    private var groupModeEnabled = true // Default to group mode for privacy

    // Callbacks for audio, control, group, and location messages
    var onAudioDataReceived: ((ByteArray, String) -> Unit)? = null
    var onControlMessageReceived: ((String, String) -> Unit)? = null
    var onGroupMessageReceived: ((GroupMessageType, String, ByteArray) -> Unit)? = null
    var onLocationMessageReceived: ((LocationMessageType, String, ByteArray) -> Unit)? = null

    // Callback for peer discovery - called when a peer with matching group code is discovered
    // Parameters: nodeId, deviceName
    var onPeerDiscovered: ((String, String) -> Unit)? = null

    // Callback for peer disconnection - called when a peer is removed from the network
    // Parameter: nodeId
    var onPeerDisconnected: ((String) -> Unit)? = null

    /**
     * Set the group code for filtering connections.
     * Only nodes with matching group codes will connect.
     */
    fun setGroupCode(code: String?) {
        groupCode = code?.uppercase()?.replace("-", "")?.let { sanitizeForDelimitedFormat(it) }
        logD { "Group code set to: $groupCode" }
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
        logD { "Group mode ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if group mode is enabled.
     */
    fun isGroupModeEnabled(): Boolean = groupModeEnabled

    /**
     * Set the user's nickname for discovery messages.
     * This is the name displayed to other users in the group.
     */
    fun setNickname(nickname: String) {
        userNickname = sanitizeForDelimitedFormat(nickname.take(MAX_DEVICE_NAME_LENGTH))
        logD { "Nickname set to: $userNickname" }
    }

    fun startMeshNetwork(localPort: Int = DISCOVERY_PORT) {
        if (isRunning) {
            logD { "Mesh network already running" }
            return
        }

        // Cancel any existing network job
        networkJob?.cancel()

        networkJob = scope.launch {
            try {
                logD { "Starting mesh network on port $localPort..." }
                isRunning = true
                _isActive.value = true
                networkStartTime = System.currentTimeMillis()
                resetNetworkStats()

                // Close existing sockets first to prevent leaks
                discoverySocket?.close()
                audioSocket?.close()

                // Initialize sockets with proper configuration
                // soTimeout prevents receive() from blocking indefinitely, allowing
                // the listener loops to check isRunning and respond to cancellation
                discoverySocket = DatagramSocket(localPort).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = SOCKET_TIMEOUT_MS
                }
                audioSocket = DatagramSocket(localPort + 1).apply {
                    reuseAddress = true
                    soTimeout = SOCKET_TIMEOUT_MS
                }

                logD { "Mesh network sockets created: discovery=$localPort, audio=${localPort + 1}" }
                logD { "Node ID: $nodeId, Device name: $deviceName" }

                // Initialize the distance vector router
                router.initialize()
                logD { "Distance vector router initialized" }

                // Start discovery and routing services
                launch { startDiscoveryService() }
                launch { startRoutingService() }
                launch { startHeartbeatService() }
                launch { startMessageListener() }
                launch { startAudioListener() }
                launch { startRouteAdvertisementService() } // Multi-hop routing

                logD { "Mesh network services started successfully on port $localPort" }
            } catch (e: Exception) {
                logE({ "Failed to start mesh network on port $localPort" }, e)
                e.printStackTrace()
                stopMeshNetwork()
            }
        }
    }

    fun stopMeshNetwork() {
        logD { "Stopping mesh network..." }
        isRunning = false
        _isActive.value = false

        // Cancel all network coroutines first
        networkJob?.cancel()
        networkJob = null

        // Close sockets safely
        try {
            discoverySocket?.close()
        } catch (e: Exception) {
            logW({ "Error closing discovery socket" }, e)
        }
        try {
            audioSocket?.close()
        } catch (e: Exception) {
            logW({ "Error closing audio socket" }, e)
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
        synchronized(discoveryResponseCacheLock) {
            discoveryResponseCache.clear()
        }
        // Note: Don't clear recentlySeenIps - keep for faster reconnection
        _connectedNodes.value = emptyList()

        logD { "Mesh network stopped" }
    }

    fun scanAndConnectToAvailableDevices() {
        scope.launch {
            logD { "Starting optimized network scan for available devices..." }

            val localIPs = getLocalIPAddresses()
            if (localIPs.isEmpty()) {
                logW { "No local IP addresses found, cannot scan network" }
                return@launch
            }

            logD { "Local IPs: ${localIPs.joinToString(", ")}" }

            // Use the first available local IP to determine network subnet
            val localIP = localIPs.first()
            val subnet = localIP.substringBeforeLast(".")

            // Phase 1: Priority scan - check recently seen IPs first (faster reconnection)
            val priorityIps = synchronized(recentlySeenIpsLock) {
                recentlySeenIps.keys.toList()
            }

            if (priorityIps.isNotEmpty()) {
                logD { "Phase 1: Scanning ${priorityIps.size} recently seen IPs with priority..." }
                val priorityJobs = priorityIps
                    .filter { !localIPs.contains(it) }
                    .filter { !nodes.values.any { node -> node.ipAddress == it } }
                    .map { ip ->
                        launch {
                            try {
                                val address = InetAddress.getByName(ip)
                                // Use shorter timeout for known IPs
                                if (address.isReachable(AppConfig.Mesh.NETWORK_SCAN_PRIORITY_TIMEOUT_MS)) {
                                    logD { "Priority: Found reachable device at $ip" }
                                    sendDiscoveryProbe(ip, DISCOVERY_PORT)
                                }
                            } catch (e: Exception) {
                                // Silently ignore
                            }
                        }
                    }
                priorityJobs.joinAll()
                logD { "Phase 1 complete: Priority scan finished" }
            }

            // Phase 2: Full subnet scan (excluding priority IPs already scanned)
            logD { "Phase 2: Scanning subnet $subnet.* for Enter-Comm devices..." }
            val scanJobs = mutableListOf<Job>()
            val priorityIpSet = priorityIps.toSet()

            for (i in 1..254) {
                val targetIP = "$subnet.$i"

                // Skip our own IPs, already connected nodes, and priority IPs (already scanned)
                if (localIPs.contains(targetIP) ||
                    nodes.values.any { it.ipAddress == targetIP } ||
                    priorityIpSet.contains(targetIP)
                ) {
                    continue
                }

                // Launch concurrent discovery probes
                val job = launch {
                    try {
                        val address = InetAddress.getByName(targetIP)
                        // Use optimized timeout
                        if (address.isReachable(AppConfig.Mesh.NETWORK_SCAN_TIMEOUT_MS)) {
                            logD { "Found reachable device at $targetIP, sending discovery probe..." }
                            sendDiscoveryProbe(targetIP, DISCOVERY_PORT)
                        }
                    } catch (e: Exception) {
                        // Silently ignore unreachable hosts
                    }
                }
                scanJobs.add(job)

                // Use larger batch size for faster scanning
                if (scanJobs.size >= AppConfig.Mesh.NETWORK_SCAN_BATCH_SIZE) {
                    scanJobs.joinAll()
                    scanJobs.clear()
                    delay(50) // Reduced delay between batches
                }
            }

            // Wait for remaining scan jobs
            scanJobs.joinAll()

            logD { "Network scan completed. Sent discovery probes to all reachable devices." }
            logD { "Actual mesh connections: ${nodes.size} devices" }
        }
    }

    fun addDirectConnection(ipAddress: String, port: Int = DISCOVERY_PORT) {
        // Check if this is our own IP
        val localIPs = getLocalIPAddresses()
        if (localIPs.contains(ipAddress)) {
            logD { "Skipping direct connection to our own IP: $ipAddress" }
            return
        }

        val nodeId = generateNodeId(ipAddress)

        // Check if node already exists and was recently updated
        val existingNode = nodes[nodeId]
        if (existingNode != null) {
            val timeSinceUpdate = System.currentTimeMillis() - existingNode.lastSeen
            if (timeSinceUpdate < 30000) { // Don't re-add if updated within last 30 seconds
                logD { "Node $nodeId at $ipAddress already exists and is recent, skipping..." }
                return
            }
        }

        logD { "Attempting direct connection to $ipAddress:$port" }

        // Only send discovery message - don't create phantom nodes
        // Nodes will be created only when they respond with discovery messages
        logD { "Sending discovery message to $ipAddress:$port" }
        sendDiscoveryMessage(ipAddress, port)

        logD { "Discovery message sent to $ipAddress:$port - waiting for response..." }
    }

    fun sendAudioData(audioData: ByteArray, destinationId: String? = null) {
        if (destinationId != null) {
            // Send to specific destination
            sendMessage(
                MeshMessage(
                    sourceId = nodeId,
                    destinationId = destinationId,
                    messageType = MeshMessage.MessageType.AUDIO_DATA,
                    payload = audioData,
                ),
            )
        } else {
            // Broadcast to all connected nodes
            nodes.keys.forEach { nodeId ->
                sendMessage(
                    MeshMessage(
                        sourceId = this.nodeId,
                        destinationId = nodeId,
                        messageType = MeshMessage.MessageType.AUDIO_DATA,
                        payload = audioData,
                    ),
                )
            }
        }
    }

    private suspend fun startDiscoveryService() {
        logD { "Starting discovery service - broadcasting every 10 seconds..." }
        while (isRunning) {
            try {
                // Broadcast discovery message to local network
                logD { "Broadcasting discovery message..." }
                broadcastDiscovery()
                delay(10000) // Discovery every 10 seconds
            } catch (e: Exception) {
                logE({ "Discovery service error" }, e)
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
                logE({ "Routing service error" }, e)
                delay(5000)
            }
        }
    }

    private suspend fun startHeartbeatService() {
        while (isRunning) {
            try {
                // Send heartbeat to all known nodes
                sendHeartbeats()

                // Update network statistics periodically
                updateNetworkStats()

                delay(HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                logE({ "Heartbeat service error" }, e)
                delay(5000)
            }
        }
    }

    private suspend fun startMessageListener() {
        logD { "Starting message listener on discovery port..." }
        while (isRunning) {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                discoverySocket?.receive(packet)

                val senderAddress = packet.address.hostAddress ?: "unknown"
                logD { "Received message from $senderAddress, length: ${packet.length}" }

                val message = protocol.deserialize(packet.data, packet.length)
                if (message != null) {
                    logD { "Deserialized: type=${message.messageType}, src=${message.sourceId}" }
                    handleIncomingMessage(message, senderAddress)
                } else {
                    logW { "Failed to deserialize message from $senderAddress" }
                    logW { "Raw data: ${String(packet.data, 0, packet.length)}" }
                }
            } catch (e: SocketTimeoutException) {
                // Normal timeout - allows loop to check isRunning and respond to cancellation
                continue
            } catch (e: Exception) {
                if (isRunning) {
                    logE({ "Message listener error" }, e)
                    delay(1000)
                } else {
                    logD { "Message listener stopped" }
                }
            }
        }
    }

    private suspend fun startAudioListener() {
        logD { "Starting audio listener on port ${DISCOVERY_PORT + 1}..." }
        while (isRunning) {
            try {
                val socket = audioSocket
                if (socket == null || socket.isClosed) {
                    logE { "Audio socket is null or closed, cannot receive audio" }
                    delay(1000)
                    continue
                }

                val buffer = ByteArray(4096) // Larger buffer for audio
                val packet = DatagramPacket(buffer, buffer.size)

                socket.receive(packet)
                logD { "Received audio packet from ${packet.address.hostAddress}, length: ${packet.length}" }

                val message = protocol.deserialize(packet.data, packet.length)
                if (message != null) {
                    logD { "Deserialized audio message: type=${message.messageType}, source=${message.sourceId}" }

                    // Don't filter out audio messages from ourselves -
                    // we might need to handle echo cancellation differently
                    if (message.messageType == MeshMessage.MessageType.AUDIO_DATA) {
                        if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                            // Audio data for us
                            logD { "Playing audio data from ${message.sourceId}, size: ${message.payload.size}" }
                            try {
                                onAudioDataReceived?.invoke(message.payload, message.sourceId)
                            } catch (e: Exception) {
                                logE({ "Error in audio callback" }, e)
                            }
                        } else {
                            // Forward audio data
                            logD { "Forwarding audio data to ${message.destinationId}" }
                            forwardMessage(message)
                        }
                    }
                } else {
                    logW { "Failed to deserialize audio message" }
                }
            } catch (e: SocketTimeoutException) {
                // Normal timeout - allows loop to check isRunning and respond to cancellation
                continue
            } catch (e: Exception) {
                if (isRunning) {
                    logE({ "Audio listener error" }, e)
                    delay(1000)
                } else {
                    logD { "Audio listener stopped" }
                }
            }
        }
    }

    private fun handleIncomingMessage(message: MeshMessage, senderIp: String) {
        // Ignore messages from ourselves
        if (message.sourceId == nodeId) {
            logD { "Ignoring message from self: ${message.messageId}" }
            return
        }

        // Check message cache to avoid processing duplicates (synchronized for LRU map)
        synchronized(messageCacheLock) {
            if (messageCache.containsKey(message.messageId)) {
                return
            }
            messageCache[message.messageId] = System.currentTimeMillis()
        }

        // Track received packet statistics (after duplicate check)
        recordPacketReceived(message.payload.size, message.messageType)

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
        logD { "Handling discovery message from $senderIp" }
        logD { "Message payload: ${String(message.payload)}" }

        // Validate the discovery payload
        val payloadString = String(message.payload)
        val validatedPayload = validateDiscoveryPayload(payloadString)
        if (validatedPayload == null) {
            logW { "Rejecting invalid discovery message from $senderIp: $payloadString" }
            return
        }

        val remoteNodeId = validatedPayload.nodeId
        val deviceName = validatedPayload.deviceName
        val remoteGroupCode = validatedPayload.groupCode
        val remoteNickname = validatedPayload.nickname

        logD {
            "Parsed discovery: nodeId=$remoteNodeId, deviceName=$deviceName, " +
                "nickname=$remoteNickname, groupCode=$remoteGroupCode, senderIp=$senderIp, ourGroupCode=$groupCode"
        }

        // Ignore messages from ourselves
        if (remoteNodeId == nodeId) {
            logD { "Ignoring discovery message from self: $remoteNodeId" }
            return
        }

        // Group filtering based on group code
        // - If we have no group code (null/OPEN), accept everyone
        // - If we have a group code, only accept nodes with the same code
        val ourCode = groupCode?.uppercase()
        val theirCode = remoteGroupCode.uppercase()

        // We're in a group - only accept matching codes
        if (ourCode != null && ourCode != "OPEN" && theirCode != ourCode) {
            logD { "Ignoring node $remoteNodeId - group code mismatch (ours=$ourCode, theirs=$theirCode)" }
            return
        }
        // If we have no group code or are OPEN, accept all nodes

        // Check if we should respond (rate limiting with synchronized access)
        val currentTime = System.currentTimeMillis()
        var shouldSendResponse = false

        synchronized(discoveryResponseCacheLock) {
            val lastResponseTime = discoveryResponseCache[senderIp] ?: 0
            val timeSinceLastResponse = currentTime - lastResponseTime

            if (timeSinceLastResponse < AppConfig.Mesh.DISCOVERY_COOLDOWN_MS) {
                logD { "Rate limiting discovery response to $senderIp (last response ${timeSinceLastResponse}ms ago)" }
            } else {
                shouldSendResponse = true
                discoveryResponseCache[senderIp] = currentTime
            }
        }

        // Track this IP as recently seen for priority scanning
        synchronized(recentlySeenIpsLock) {
            recentlySeenIps[senderIp] = currentTime
        }

        if (shouldSendResponse) {
            logD { "Sending discovery response to $senderIp" }
            sendDiscoveryResponse(senderIp)
        }

        // Check if this is a new node or an update
        val existingNode = nodes[remoteNodeId]
        if (existingNode != null) {
            logD { "Updating existing node: $remoteNodeId" }
        } else {
            logD { "Adding new node: $remoteNodeId" }
        }

        val node = MeshNode(
            nodeId = remoteNodeId,
            deviceName = deviceName,
            ipAddress = senderIp,
            port = DISCOVERY_PORT,
            isDirectConnection = true,
            hopCount = 1,
            lastSeen = System.currentTimeMillis(),
            linkQuality = 1.0f,
        )

        // Check if this is a new node (not just an update)
        val isNewNode = !nodes.containsKey(remoteNodeId)

        nodes[remoteNodeId] = node
        routingTable[remoteNodeId] = MeshRoute(
            destinationId = remoteNodeId,
            nextHop = remoteNodeId,
            hopCount = 1,
        )

        // Add to distance vector router for multi-hop routing
        router.addNeighbor(remoteNodeId, senderIp, DISCOVERY_PORT, 1.0f)

        updateConnectedNodesList()

        // Notify about new peer discovery (for GroupManager sync)
        // Use nickname for display, falling back to deviceName if not available
        if (isNewNode) {
            onPeerDiscovered?.invoke(remoteNodeId, remoteNickname)
        }

        logD { "Mesh network updated: discovered $remoteNickname ($remoteNodeId) at $senderIp" }
        logD { "Total connected nodes: ${nodes.size}" }
        logD { "Reachable destinations: ${router.getReachableDestinations().size}" }
    }

    private fun handleRouteUpdate(message: MeshMessage) {
        // Parse route advertisement from neighbor
        val advertisement = router.deserializeAdvertisement(message.payload)
        if (advertisement == null) {
            logW { "Failed to parse route advertisement from ${message.sourceId}" }
            return
        }

        logD { "Processing route update from ${message.sourceId} with ${advertisement.routes.size} routes" }

        // Get sender's IP for verification
        val senderNode = nodes[message.sourceId]
        if (senderNode == null) {
            logW { "Received route update from unknown node: ${message.sourceId}" }
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

            logD { "Routing table updated from ${message.sourceId}" }
            logD { router.dumpRoutingTable() }
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
            logW { "Unknown group message type: $typeString" }
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
            payload = fullPayload,
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
            logW { "Unknown location message type: $typeString" }
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
            ttl = 3, // Location messages don't need many hops
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
                    hopCount = 1,
                )
                sendMessageToNode(message, targetNode)
                _routingStats.value = _routingStats.value.copy(
                    messagesRouted = _routingStats.value.messagesRouted + 1,
                )
            } else {
                // Fallback to legacy routing table
                val route = routingTable[message.destinationId]
                val targetNode = route?.let { nodes[it.nextHop] }
                if (targetNode != null) {
                    sendMessageToNode(message, targetNode)
                } else {
                    logW { "No route to destination: ${message.destinationId}" }
                    _routingStats.value = _routingStats.value.copy(
                        messagesDropped = _routingStats.value.messagesDropped + 1,
                    )
                }
            }
        } else {
            // Try broadcasting if destination is "broadcast"
            if (message.destinationId == "broadcast") {
                broadcastToAllNeighbors(message)
            } else {
                logW { "No route to destination: ${message.destinationId}" }
                _routingStats.value = _routingStats.value.copy(
                    messagesDropped = _routingStats.value.messagesDropped + 1,
                )
            }
        }
    }

    private fun forwardMessage(message: MeshMessage) {
        if (message.ttl <= 0) {
            logD { "Dropping message ${message.messageId} - TTL expired" }
            _routingStats.value = _routingStats.value.copy(
                messagesDropped = _routingStats.value.messagesDropped + 1,
            )
            return
        }

        // Decrement TTL and forward
        val forwardedMessage = message.copy(ttl = message.ttl - 1)

        // Check if we have a route to the destination
        if (router.isReachable(message.destinationId)) {
            sendMessage(forwardedMessage)
            _routingStats.value = _routingStats.value.copy(
                messagesForwarded = _routingStats.value.messagesForwarded + 1,
            )
            logD { "Forwarded message to ${message.destinationId} via ${router.getNextHop(message.destinationId)}" }
        } else {
            logW { "Cannot forward - no route to ${message.destinationId}" }
            _routingStats.value = _routingStats.value.copy(
                messagesDropped = _routingStats.value.messagesDropped + 1,
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
                hopCount = 1,
            )
            sendMessageToNode(message, targetNode)
        }
    }

    private fun sendMessageToNode(message: MeshMessage, node: MeshNode) {
        scope.launch {
            try {
                val data = protocol.serialize(message)
                val isAudioMessage = message.messageType == MeshMessage.MessageType.AUDIO_DATA
                val targetPort = if (isAudioMessage) node.port + 1 else node.port
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(node.ipAddress),
                    targetPort,
                )

                val socket = if (isAudioMessage) audioSocket else discoverySocket
                socket?.send(packet)

                // Track statistics
                recordPacketSent(data.size, message.messageType)
            } catch (e: Exception) {
                logE({ "Failed to send message to ${node.deviceName}" }, e)
            }
        }
    }

    private fun broadcastDiscovery() {
        scope.launch {
            // Include group code in discovery payload for filtering
            val groupCodePart = groupCode ?: "OPEN"
            val payload = "$nodeId|$deviceName|$groupCodePart|$userNickname".toByteArray()
            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = "broadcast",
                messageType = MeshMessage.MessageType.DISCOVERY,
                payload = payload,
            )

            // Broadcast to local network
            try {
                val data = protocol.serialize(message)
                logD { "Broadcasting discovery: nodeId=$nodeId, deviceName=$deviceName, dataSize=${data.size}" }

                // Dynamically get broadcast addresses for all active network interfaces
                val broadcastAddresses = getNetworkBroadcastAddresses()
                logD {
                    "Found ${broadcastAddresses.size} broadcast addresses: " +
                        broadcastAddresses.joinToString(", ")
                }

                for (broadcastAddr in broadcastAddresses) {
                    try {
                        val broadcastAddress = InetAddress.getByName(broadcastAddr)
                        val packet = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
                        discoverySocket?.send(packet)
                        logD { "Sent broadcast discovery to $broadcastAddr:$DISCOVERY_PORT" }
                    } catch (e: Exception) {
                        logW({ "Failed to broadcast to $broadcastAddr" }, e)
                    }
                }
            } catch (e: Exception) {
                logE({ "Failed to broadcast discovery" }, e)
            }
        }
    }

    private fun sendDiscoveryMessage(ipAddress: String, port: Int) {
        scope.launch {
            try {
                logD { "Preparing discovery message to $ipAddress:$port" }

                // Include group code in discovery payload for filtering
                val groupCodePart = groupCode ?: "OPEN"
                val payload = "$nodeId|$deviceName|$groupCodePart|$userNickname".toByteArray()
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload,
                )

                val data = protocol.serialize(message)
                logD { "Discovery message size: ${data.size} bytes" }

                val targetAddress = InetAddress.getByName(ipAddress)
                logD { "Resolved IP address: $ipAddress -> ${targetAddress.hostAddress}" }

                val packet = DatagramPacket(data, data.size, targetAddress, port)

                val socket = discoverySocket
                if (socket != null && !socket.isClosed) {
                    socket.send(packet)
                    logD { "Discovery message sent successfully to $ipAddress:$port" }
                } else {
                    logE { "Discovery socket is null or closed, cannot send to $ipAddress:$port" }
                }
            } catch (e: Exception) {
                logE({ "Failed to send discovery message to $ipAddress:$port" }, e)
                logE { "Exception details: ${e.javaClass.simpleName}: ${e.message}" }
                e.printStackTrace()
            }
        }
    }

    private fun sendDiscoveryProbe(ipAddress: String, port: Int) {
        scope.launch {
            try {
                logD { "Sending discovery probe to $ipAddress:$port" }

                // Include group code in discovery probe for filtering (matches broadcastDiscovery format)
                val groupCodePart = groupCode ?: "OPEN"
                val payload = "$nodeId|$deviceName|$groupCodePart|$userNickname".toByteArray()
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload,
                )

                val data = protocol.serialize(message)
                val targetAddress = InetAddress.getByName(ipAddress)
                val packet = DatagramPacket(data, data.size, targetAddress, port)

                val socket = discoverySocket
                if (socket != null && !socket.isClosed) {
                    socket.send(packet)
                    logD { "Discovery probe sent to $ipAddress:$port" }
                } else {
                    logW { "Discovery socket not available for probe to $ipAddress:$port" }
                }
            } catch (e: Exception) {
                // Silently fail for probes - most devices won't be running Enter-Comm
                logD { "Discovery probe failed to $ipAddress:$port: ${e.message}" }
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
                payload = payload,
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
        logD { "Starting route advertisement service..." }
        while (isRunning) {
            try {
                // Send route advertisements to all neighbors
                sendRouteAdvertisements()

                delay(ROUTE_UPDATE_INTERVAL)
            } catch (e: Exception) {
                logE({ "Route advertisement service error" }, e)
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

        logD { "Sending route advertisements to ${neighbors.size} neighbors" }

        for (neighbor in neighbors) {
            // Generate advertisement with split-horizon poison-reverse
            val advertisement = router.generateRouteAdvertisement(neighbor.nodeId)
            val payload = router.serializeAdvertisement(advertisement)

            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = neighbor.nodeId,
                messageType = MeshMessage.MessageType.ROUTE_UPDATE,
                payload = payload,
                ttl = 1, // Route updates are single-hop only
            )

            val targetNode = MeshNode(
                nodeId = neighbor.nodeId,
                deviceName = neighbor.nodeId,
                ipAddress = neighbor.ipAddress,
                port = neighbor.port,
                isDirectConnection = true,
                hopCount = 1,
            )

            sendMessageToNode(message, targetNode)
        }

        _routingStats.value = _routingStats.value.copy(
            routeAdvertisementsSent = _routingStats.value.routeAdvertisementsSent + neighbors.size,
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
                lastUpdated = routeEntry.lastUpdated,
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
            maxHopCount = routes.maxOfOrNull { it.hopCount } ?: 0,
        )
    }

    /**
     * Update network statistics snapshot.
     */
    private fun updateNetworkStats() {
        _networkStats.value = NetworkStats(
            packetsSent = statsPacketsSent,
            packetsReceived = statsPacketsReceived,
            bytesSent = statsBytesSent,
            bytesReceived = statsBytesReceived,
            discoveryRequestsSent = statsDiscoveryRequestsSent,
            discoveryResponsesReceived = statsDiscoveryResponsesReceived,
            audioPacketsSent = statsAudioPacketsSent,
            audioPacketsReceived = statsAudioPacketsReceived,
            heartbeatsSent = statsHeartbeatsSent,
            heartbeatsReceived = statsHeartbeatsReceived,
            lastUpdateTime = System.currentTimeMillis(),
        )
    }

    /**
     * Record packet sent for statistics.
     */
    private fun recordPacketSent(bytes: Int, messageType: MeshMessage.MessageType) {
        statsPacketsSent++
        statsBytesSent += bytes
        when (messageType) {
            MeshMessage.MessageType.DISCOVERY -> statsDiscoveryRequestsSent++
            MeshMessage.MessageType.AUDIO_DATA -> statsAudioPacketsSent++
            MeshMessage.MessageType.HEARTBEAT -> statsHeartbeatsSent++
            else -> { /* Other types not tracked separately */ }
        }
    }

    /**
     * Record packet received for statistics.
     */
    private fun recordPacketReceived(bytes: Int, messageType: MeshMessage.MessageType) {
        statsPacketsReceived++
        statsBytesReceived += bytes
        when (messageType) {
            MeshMessage.MessageType.DISCOVERY -> statsDiscoveryResponsesReceived++
            MeshMessage.MessageType.AUDIO_DATA -> statsAudioPacketsReceived++
            MeshMessage.MessageType.HEARTBEAT -> statsHeartbeatsReceived++
            else -> { /* Other types not tracked separately */ }
        }
    }

    /**
     * Reset network statistics.
     */
    fun resetNetworkStats() {
        statsPacketsSent = 0
        statsPacketsReceived = 0
        statsBytesSent = 0
        statsBytesReceived = 0
        statsDiscoveryRequestsSent = 0
        statsDiscoveryResponsesReceived = 0
        statsAudioPacketsSent = 0
        statsAudioPacketsReceived = 0
        statsHeartbeatsSent = 0
        statsHeartbeatsReceived = 0
        networkStartTime = System.currentTimeMillis()
        updateNetworkStats()
        logD { "Network statistics reset" }
    }

    /**
     * Get network uptime in milliseconds.
     */
    fun getNetworkUptime(): Long {
        return if (networkStartTime > 0) {
            System.currentTimeMillis() - networkStartTime
        } else {
            0
        }
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
            // Notify about peer disconnection (for GroupManager sync)
            onPeerDisconnected?.invoke(expiredNodeId)
            logD { "Removed expired node: $expiredNodeId" }
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

        // Remove old discovery response cache entries (with proper synchronization)
        synchronized(discoveryResponseCacheLock) {
            val expiredResponses = discoveryResponseCache.filter { (_, timestamp) ->
                currentTime - timestamp > AppConfig.Mesh.DISCOVERY_CACHE_TTL_MS
            }.keys.toList()

            expiredResponses.forEach { ipAddress ->
                discoveryResponseCache.remove(ipAddress)
            }
        }

        // Remove old recently seen IPs
        synchronized(recentlySeenIpsLock) {
            val expiredIps = recentlySeenIps.filter { (_, timestamp) ->
                currentTime - timestamp > AppConfig.Mesh.DISCOVERY_CACHE_TTL_MS
            }.keys.toList()

            expiredIps.forEach { ip ->
                recentlySeenIps.remove(ip)
            }
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

                logD { "Checking network interface: ${networkInterface.name}" }

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        val broadcastAddr = broadcast.hostAddress
                        if (broadcastAddr != null && !broadcastAddresses.contains(broadcastAddr)) {
                            broadcastAddresses.add(broadcastAddr)
                            logD { "Found broadcast address: $broadcastAddr for interface ${networkInterface.name}" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error getting network broadcast addresses" }, e)
            // Fallback to common addresses if dynamic detection fails
            if (broadcastAddresses.size <= 1) {
                broadcastAddresses.add("192.168.49.255") // WiFi Direct common subnet
                broadcastAddresses.add("192.168.1.255") // Common home network
                broadcastAddresses.add("10.0.0.255") // Common mobile hotspot
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
                            logD { "Found local IP: $ipAddress on interface ${networkInterface.name}" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error getting local IP addresses" }, e)
        }

        return ipAddresses
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
                lastSeen = node.lastSeen,
            )
        }

        // Add connections from router
        router.getNeighbors().forEach { neighbor ->
            builder.addConnection(
                fromNodeId = nodeId,
                toNodeId = neighbor.nodeId,
                linkQuality = neighbor.linkQuality,
                isDirect = true,
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
    val routeAdvertisementsSent: Long = 0,
)

/**
 * Comprehensive network statistics for diagnostics UI.
 */
data class NetworkStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val discoveryRequestsSent: Long = 0,
    val discoveryResponsesReceived: Long = 0,
    val audioPacketsSent: Long = 0,
    val audioPacketsReceived: Long = 0,
    val heartbeatsSent: Long = 0,
    val heartbeatsReceived: Long = 0,
    val lastUpdateTime: Long = System.currentTimeMillis(),
) {
    /** Calculate packet loss percentage (0-100) */
    val packetLossPercent: Float
        get() {
            if (packetsSent == 0L) return 0f
            val expectedResponses = packetsSent
            val actualResponses = packetsReceived
            if (actualResponses >= expectedResponses) return 0f
            return ((expectedResponses - actualResponses) * 100f / expectedResponses).coerceIn(0f, 100f)
        }

    /** Get formatted uptime string */
    fun getUptimeString(startTime: Long): String {
        val uptimeMs = System.currentTimeMillis() - startTime
        val seconds = (uptimeMs / 1000) % 60
        val minutes = (uptimeMs / (1000 * 60)) % 60
        val hours = uptimeMs / (1000 * 60 * 60)
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
