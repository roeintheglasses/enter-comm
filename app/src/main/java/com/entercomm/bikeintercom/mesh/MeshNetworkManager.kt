package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.location.LocationMessageType
import com.entercomm.bikeintercom.mesh.network.DiscoveryService
import com.entercomm.bikeintercom.mesh.network.MessageDispatcher
import com.entercomm.bikeintercom.mesh.network.NetworkInterfaceHelper
import com.entercomm.bikeintercom.mesh.network.NetworkStatsCollector
import com.entercomm.bikeintercom.mesh.network.NodeRegistry
import com.entercomm.bikeintercom.mesh.network.RoutingService
import com.entercomm.bikeintercom.mesh.network.SocketManager
import com.entercomm.bikeintercom.mesh.protocol.MeshProtocol
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Facade for the mesh network system.
 * Orchestrates all network services and exposes the public API.
 */
class MeshNetworkManager(
    private val nodeId: String,
    deviceName: String,
    private val protocol: MeshProtocol = MeshProtocol.default(),
) {

    companion object {
        const val DISCOVERY_PORT = 8888
        private const val HEARTBEAT_INTERVAL = 5000L
        private const val NODE_TIMEOUT = 15000L
        private const val DISCOVERY_INTERVAL = 10000L
        private const val ROUTING_INTERVAL = 5000L
        private const val ROUTE_UPDATE_INTERVAL = 10000L

        /**
         * Delimiter used in pipe-delimited message format.
         */
        private const val FIELD_DELIMITER = '|'

        /**
         * Sanitizes a string for use in pipe-delimited message format.
         */
        fun sanitizeForDelimitedFormat(value: String): String {
            return value.replace(FIELD_DELIMITER, '_')
        }
    }

    // Sanitize device name at construction
    private val deviceName: String = sanitizeForDelimitedFormat(deviceName)

    // Coroutine infrastructure
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var networkJob: Job? = null

    @Volatile private var isRunning = false
    private val lifecycleLock = Any()

    // Active state
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Service components
    private val socketManager = SocketManager()
    private val nodeRegistry = NodeRegistry()
    private val statsCollector = NetworkStatsCollector()
    private val router = DistanceVectorRouter(nodeId)

    // Late-initialized services (depend on scope/sockets)
    private lateinit var messageDispatcher: MessageDispatcher
    private lateinit var routingService: RoutingService
    private lateinit var discoveryService: DiscoveryService

    // Delegated StateFlows
    val connectedNodes: StateFlow<List<MeshNode>> get() = nodeRegistry.connectedNodes
    val routingStats: StateFlow<RoutingStats> get() = messageDispatcher.routingStats
    val networkStats: StateFlow<NetworkStats> get() = statsCollector.networkStats

    // Callbacks for messages
    var onAudioDataReceived: ((ByteArray, String) -> Unit)? = null
    var onControlMessageReceived: ((String, String) -> Unit)? = null
    var onGroupMessageReceived: ((GroupMessageType, String, ByteArray) -> Unit)? = null
    var onLocationMessageReceived: ((LocationMessageType, String, ByteArray) -> Unit)? = null
    var onPeerDiscovered: ((String, String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null

    /**
     * Start the mesh network on all available interfaces.
     */
    fun startMeshNetwork(localPort: Int = DISCOVERY_PORT) {
        synchronized(lifecycleLock) {
            if (isRunning) {
                logD { "Mesh network already running" }
                return
            }

            networkJob?.cancel()

            networkJob = scope.launch {
                try {
                    logD { "Starting mesh network on port $localPort..." }

                    // Create sockets first - before setting isRunning
                    if (!socketManager.createSockets(localPort)) {
                        logE { "Failed to create sockets" }
                        return@launch
                    }

                    // Initialize services
                    initializeServices(localPort)

                    // Mark as running only after successful initialization
                    synchronized(lifecycleLock) {
                        isRunning = true
                        _isActive.value = true
                    }

                    // Start all services
                    startServices()

                    logD { "Mesh network services started successfully on port $localPort" }
                } catch (e: Exception) {
                    logE({ "Failed to start mesh network on port $localPort" }, e)
                    stopMeshNetwork()
                }
            }
        }
    }

    /**
     * Stop the mesh network.
     */
    fun stopMeshNetwork() {
        synchronized(lifecycleLock) {
            if (!isRunning && networkJob == null) {
                logD { "Mesh network not running" }
                return
            }

            logD { "Stopping mesh network..." }
            isRunning = false
            _isActive.value = false

            networkJob?.cancel()
            networkJob = null
        }

        // Close sockets outside lock to avoid blocking
        socketManager.closeSockets()

        // Clear node registry callbacks before clearing data
        nodeRegistry.onNodeAdded = null
        nodeRegistry.onNodeRemoved = null
        nodeRegistry.clear()
        router.clear()

        if (::messageDispatcher.isInitialized) {
            messageDispatcher.clearCallbacks()
            messageDispatcher.clear()
        }
        if (::discoveryService.isInitialized) {
            discoveryService.onPeerDiscovered = null
            discoveryService.clear()
        }
        if (::routingService.isInitialized) {
            routingService.clear()
        }

        logD { "Mesh network stopped" }
    }

    /**
     * Start mesh network bound to a specific network interface.
     * Used for WiFi Direct P2P groups.
     */
    fun startMeshNetworkOnInterface(interfaceName: String, localPort: Int = DISCOVERY_PORT) {
        synchronized(lifecycleLock) {
            if (isRunning) {
                logD { "Mesh network already running, stopping first to rebind to interface" }
                // Release lock before stopping to avoid deadlock
            }
        }

        // Stop outside lock if needed
        if (isRunning) {
            stopMeshNetwork()
        }

        synchronized(lifecycleLock) {
            networkJob?.cancel()

            networkJob = scope.launch {
                try {
                    logD { "Starting mesh network on interface $interfaceName..." }

                    // Try to create sockets on the specific interface
                    val success = socketManager.createSocketsOnInterface(interfaceName, localPort)
                    if (!success) {
                        logE { "Interface $interfaceName not available, falling back to default" }
                        startMeshNetwork(localPort)
                        return@launch
                    }

                    // Initialize services
                    initializeServices(localPort)

                    // Mark as running only after successful initialization
                    synchronized(lifecycleLock) {
                        isRunning = true
                        _isActive.value = true
                    }

                    // Start all services
                    startServices()

                    // Log network interfaces for debugging
                    NetworkInterfaceHelper.logNetworkInterfaces(
                        socketManager.boundInterfaceName,
                        socketManager.boundAddress,
                    )

                    logD { "Mesh network services started successfully on interface $interfaceName" }
                } catch (e: Exception) {
                    logE({ "Failed to start mesh network on interface $interfaceName" }, e)
                    stopMeshNetwork()
                }
            }
        }
    }

    // ==================== Scanning & Connection ====================

    fun scanAndConnectToAvailableDevices() {
        if (::discoveryService.isInitialized) {
            discoveryService.scanAndConnect()
        }
    }

    fun addDirectConnection(ipAddress: String, port: Int = DISCOVERY_PORT) {
        if (::discoveryService.isInitialized) {
            discoveryService.addDirectConnection(ipAddress, port)
        }
    }

    // ==================== Audio ====================

    fun sendAudioData(audioData: ByteArray, destinationId: String? = null) {
        if (!::messageDispatcher.isInitialized) return

        if (destinationId != null) {
            messageDispatcher.sendMessage(
                MeshMessage(
                    sourceId = nodeId,
                    destinationId = destinationId,
                    messageType = MeshMessage.MessageType.AUDIO_DATA,
                    payload = audioData,
                ),
            )
        } else {
            nodeRegistry.getAllNodeIds().forEach { targetId ->
                messageDispatcher.sendMessage(
                    MeshMessage(
                        sourceId = nodeId,
                        destinationId = targetId,
                        messageType = MeshMessage.MessageType.AUDIO_DATA,
                        payload = audioData,
                    ),
                )
            }
        }
    }

    // ==================== Group Management ====================

    fun setGroupCode(code: String?) {
        if (::discoveryService.isInitialized) {
            discoveryService.setGroupCode(code)
        }
    }

    fun getGroupCode(): String? {
        return if (::discoveryService.isInitialized) {
            discoveryService.getGroupCode()
        } else {
            null
        }
    }

    fun setGroupModeEnabled(enabled: Boolean) {
        if (::discoveryService.isInitialized) {
            discoveryService.setGroupModeEnabled(enabled)
        }
    }

    fun isGroupModeEnabled(): Boolean {
        return if (::discoveryService.isInitialized) {
            discoveryService.isGroupModeEnabled()
        } else {
            true
        }
    }

    fun setNickname(nickname: String) {
        if (::discoveryService.isInitialized) {
            discoveryService.setNickname(nickname)
        }
    }

    fun sendGroupMessage(type: GroupMessageType, destinationId: String, payload: ByteArray) {
        if (!::messageDispatcher.isInitialized) return

        val typePrefix = "${type.name}|".toByteArray()
        val fullPayload = typePrefix + payload

        val message = MeshMessage(
            sourceId = nodeId,
            destinationId = destinationId,
            messageType = MeshMessage.MessageType.GROUP,
            payload = fullPayload,
        )

        if (destinationId == "broadcast") {
            messageDispatcher.broadcastToNeighbors(message)
        } else {
            messageDispatcher.sendMessage(message)
        }
    }

    fun sendLocationMessage(type: LocationMessageType, destinationId: String, payload: ByteArray) {
        if (!::messageDispatcher.isInitialized) return

        val typePrefix = "${type.name}|".toByteArray()
        val fullPayload = typePrefix + payload

        val message = MeshMessage(
            sourceId = nodeId,
            destinationId = destinationId,
            messageType = MeshMessage.MessageType.LOCATION,
            payload = fullPayload,
            ttl = 3,
        )

        if (destinationId == "broadcast") {
            messageDispatcher.broadcastToNeighbors(message)
        } else {
            messageDispatcher.sendMessage(message)
        }
    }

    // ==================== Routing & Topology ====================

    fun getRoutingTableDump(): String {
        return if (::routingService.isInitialized) {
            routingService.getRoutingTableDump()
        } else {
            "Routing service not initialized"
        }
    }

    fun getPathInfo(destinationId: String): PathInfo? {
        return if (::routingService.isInitialized) {
            routingService.getPathInfo(destinationId)
        } else {
            null
        }
    }

    fun isReachable(destinationId: String): Boolean {
        return if (::routingService.isInitialized) {
            routingService.isReachable(destinationId)
        } else {
            false
        }
    }

    fun getReachableDestinations(): Set<String> {
        return if (::routingService.isInitialized) {
            routingService.getReachableDestinations()
        } else {
            emptySet()
        }
    }

    fun getMeshTopology(): MeshTopology {
        return if (::routingService.isInitialized) {
            routingService.getMeshTopology()
        } else {
            TopologyBuilder(nodeId, deviceName).build()
        }
    }

    // ==================== P2P Interface Support ====================

    fun getWiFiDirectInterfaceAddress(): Pair<String, InetAddress>? {
        return NetworkInterfaceHelper.getWiFiDirectInterfaceAddress()
    }

    fun isBoundToP2PInterface(): Boolean = socketManager.isBoundToP2PInterface()

    fun getBoundInterfaceName(): String? = socketManager.boundInterfaceName

    fun logNetworkInterfaces() {
        NetworkInterfaceHelper.logNetworkInterfaces(
            socketManager.boundInterfaceName,
            socketManager.boundAddress,
        )
    }

    fun getLocalIPAddresses(): List<String> = NetworkInterfaceHelper.getLocalIPAddresses()

    // ==================== Statistics ====================

    fun resetNetworkStats() {
        statsCollector.reset()
    }

    fun getNetworkUptime(): Long = statsCollector.getUptime()

    // ==================== Private Implementation ====================

    private fun initializeServices(localPort: Int) {
        statsCollector.start()

        messageDispatcher = MessageDispatcher(
            nodeId = nodeId,
            protocol = protocol,
            router = router,
            socketManager = socketManager,
            nodeRegistry = nodeRegistry,
            statsCollector = statsCollector,
            scope = scope,
        )

        routingService = RoutingService(
            nodeId = nodeId,
            deviceName = deviceName,
            router = router,
            nodeRegistry = nodeRegistry,
            messageDispatcher = messageDispatcher,
        )

        discoveryService = DiscoveryService(
            nodeId = nodeId,
            deviceName = deviceName,
            protocol = protocol,
            socketManager = socketManager,
            nodeRegistry = nodeRegistry,
            router = router,
            routingService = routingService,
            scope = scope,
            discoveryPort = localPort,
        )

        // Wire callbacks
        wireCallbacks()

        // Initialize router
        routingService.initialize()

        logD { "Services initialized. Node ID: $nodeId, Device name: $deviceName" }
    }

    private fun wireCallbacks() {
        // Wire discovery service callbacks
        discoveryService.onPeerDiscovered = { peerId, nickname ->
            onPeerDiscovered?.invoke(peerId, nickname)
        }

        // Wire node registry callbacks
        nodeRegistry.onNodeRemoved = { removedNodeId ->
            onPeerDisconnected?.invoke(removedNodeId)
            router.removeNeighbor(removedNodeId)
            routingService.removeRoute(removedNodeId)
        }

        // Wire message dispatcher callbacks
        messageDispatcher.onDiscoveryMessage = { message, senderIp ->
            discoveryService.handleDiscoveryMessage(message, senderIp)
        }

        messageDispatcher.onRouteUpdateMessage = { message ->
            routingService.handleRouteUpdate(message)
        }

        messageDispatcher.onControlMessage = { payload, sourceId ->
            onControlMessageReceived?.invoke(payload, sourceId)
        }

        messageDispatcher.onHeartbeatMessage = { message, _ ->
            nodeRegistry.updateNodeLastSeen(message.sourceId)
            router.updateNeighborHeartbeat(message.sourceId)
        }

        messageDispatcher.onGroupMessage = { message ->
            handleGroupMessage(message)
        }

        messageDispatcher.onLocationMessage = { message ->
            handleLocationMessage(message)
        }

        messageDispatcher.onAudioMessage = { message ->
            // Audio handled in audio listener
        }
    }

    private fun handleGroupMessage(message: MeshMessage) {
        val payload = message.payload
        if (payload.isEmpty()) return

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

    private fun handleLocationMessage(message: MeshMessage) {
        val payload = message.payload
        if (payload.isEmpty()) return

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

    private fun startServices() {
        scope.launch { startDiscoveryService() }
        scope.launch { startRoutingService() }
        scope.launch { startHeartbeatService() }
        scope.launch { startMessageListener() }
        scope.launch { startAudioListener() }
        scope.launch { startRouteAdvertisementService() }
    }

    private suspend fun startDiscoveryService() {
        logD { "Starting discovery service - broadcasting every 10 seconds..." }
        while (isRunning) {
            try {
                logD { "Broadcasting discovery message..." }
                discoveryService.broadcastDiscovery()
                delay(DISCOVERY_INTERVAL)
            } catch (e: Exception) {
                logE({ "Discovery service error" }, e)
                delay(5000)
            }
        }
    }

    private suspend fun startRoutingService() {
        while (isRunning) {
            try {
                // Clean up old nodes
                val expiredNodeIds = nodeRegistry.cleanupExpiredNodes(NODE_TIMEOUT)
                expiredNodeIds.forEach { nodeId ->
                    routingService.removeRoute(nodeId)
                }

                // Perform routing maintenance
                routingService.performMaintenance()

                // Clean up caches
                messageDispatcher.cleanupCache()
                discoveryService.cleanupCaches()

                delay(ROUTING_INTERVAL)
            } catch (e: Exception) {
                logE({ "Routing service error" }, e)
                delay(5000)
            }
        }
    }

    private suspend fun startHeartbeatService() {
        while (isRunning) {
            try {
                sendHeartbeats()
                statsCollector.updateStats()
                delay(HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                logE({ "Heartbeat service error" }, e)
                delay(5000)
            }
        }
    }

    private fun sendHeartbeats() {
        val payload = "heartbeat".toByteArray()
        nodeRegistry.getAllNodes().forEach { (_, node) ->
            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = node.nodeId,
                messageType = MeshMessage.MessageType.HEARTBEAT,
                payload = payload,
            )
            messageDispatcher.sendToNode(message, node)
        }
    }

    private suspend fun startMessageListener() {
        logD { "Starting message listener on discovery port..." }
        while (isRunning) {
            try {
                val socket = socketManager.discoverySocket ?: continue
                val buffer = ByteArray(1024)
                val packet = java.net.DatagramPacket(buffer, buffer.size)

                socket.receive(packet)

                val senderAddress = packet.address.hostAddress ?: "unknown"
                logD { "Received message from $senderAddress, length: ${packet.length}" }

                val message = protocol.deserialize(packet.data, packet.length)
                if (message != null) {
                    logD { "Deserialized: type=${message.messageType}, src=${message.sourceId}" }
                    messageDispatcher.handleMessage(message, senderAddress)
                } else {
                    logW { "Failed to deserialize message from $senderAddress" }
                }
            } catch (e: SocketTimeoutException) {
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
        logD { "Starting audio listener..." }
        while (isRunning) {
            try {
                val socket = socketManager.audioSocket
                if (socket == null || socket.isClosed) {
                    logE { "Audio socket is null or closed" }
                    delay(1000)
                    continue
                }

                val buffer = ByteArray(4096)
                val packet = java.net.DatagramPacket(buffer, buffer.size)

                socket.receive(packet)
                logD { "Received audio packet from ${packet.address.hostAddress}, length: ${packet.length}" }

                val message = protocol.deserialize(packet.data, packet.length)
                if (message != null) {
                    logD { "Deserialized audio message: type=${message.messageType}, source=${message.sourceId}" }

                    if (message.messageType == MeshMessage.MessageType.AUDIO_DATA) {
                        if (message.destinationId == nodeId || message.destinationId == "broadcast") {
                            logD { "Playing audio data from ${message.sourceId}, size: ${message.payload.size}" }
                            try {
                                onAudioDataReceived?.invoke(message.payload, message.sourceId)
                            } catch (e: Exception) {
                                logE({ "Error in audio callback" }, e)
                            }
                        } else {
                            logD { "Forwarding audio data to ${message.destinationId}" }
                            messageDispatcher.forwardMessage(message)
                        }
                    }
                } else {
                    logW { "Failed to deserialize audio message" }
                }
            } catch (e: SocketTimeoutException) {
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

    private suspend fun startRouteAdvertisementService() {
        logD { "Starting route advertisement service..." }
        while (isRunning) {
            try {
                routingService.sendRouteAdvertisements()
                delay(ROUTE_UPDATE_INTERVAL)
            } catch (e: Exception) {
                logE({ "Route advertisement service error" }, e)
                delay(5000)
            }
        }
    }
}
