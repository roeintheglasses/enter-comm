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

data class MeshNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val isDirectConnection: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    val hopCount: Int = 1
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
        HEARTBEAT
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
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val connectedNodes: StateFlow<List<MeshNode>> = _connectedNodes.asStateFlow()
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    private val nodes = ConcurrentHashMap<String, MeshNode>()
    private val routingTable = ConcurrentHashMap<String, MeshRoute>()
    private val messageCache = ConcurrentHashMap<String, Long>()
    private val discoveryResponseCache = ConcurrentHashMap<String, Long>() // Rate limit discovery responses
    
    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var isRunning = false
    
    // Callbacks for audio and control messages
    var onAudioDataReceived: ((ByteArray, String) -> Unit)? = null
    var onControlMessageReceived: ((String, String) -> Unit)? = null
    
    fun startMeshNetwork(localPort: Int = DISCOVERY_PORT) {
        if (isRunning) {
            Log.d(TAG, "Mesh network already running")
            return
        }
        
        scope.launch {
            try {
                Log.d(TAG, "Starting mesh network on port $localPort...")
                isRunning = true
                _isActive.value = true
                
                // Initialize sockets
                discoverySocket = DatagramSocket(localPort)
                audioSocket = DatagramSocket(localPort + 1)
                
                Log.d(TAG, "Mesh network sockets created: discovery=$localPort, audio=${localPort + 1}")
                Log.d(TAG, "Node ID: $nodeId, Device name: $deviceName")
                
                // Start discovery and routing services
                launch { startDiscoveryService() }
                launch { startRoutingService() }
                launch { startHeartbeatService() }
                launch { startMessageListener() }
                launch { startAudioListener() }
                
                Log.d(TAG, "Mesh network services started successfully on port $localPort")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh network on port $localPort", e)
                e.printStackTrace()
                stopMeshNetwork()
                throw e
            }
        }
    }
    
    fun stopMeshNetwork() {
        isRunning = false
        _isActive.value = false
        
        discoverySocket?.close()
        audioSocket?.close()
        
        nodes.clear()
        routingTable.clear()
        messageCache.clear()
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
        
        // Check message cache to avoid processing duplicates
        if (messageCache.containsKey(message.messageId)) {
            return
        }
        messageCache[message.messageId] = System.currentTimeMillis()
        
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
        }
    }
    
    private fun handleDiscoveryMessage(message: MeshMessage, senderIp: String) {
        Log.d(TAG, "Handling discovery message from $senderIp")
        Log.d(TAG, "Message payload: ${String(message.payload)}")
        
        val nodeInfo = String(message.payload).split("|")
        if (nodeInfo.size >= 2) {
            val remoteNodeId = nodeInfo[0]
            val deviceName = nodeInfo[1]
            
            Log.d(TAG, "Parsed discovery: nodeId=$remoteNodeId, deviceName=$deviceName, senderIp=$senderIp")
            
            // Ignore messages from ourselves
            if (remoteNodeId == nodeId) {
                Log.d(TAG, "Ignoring discovery message from self: $remoteNodeId")
                return
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
                lastSeen = System.currentTimeMillis()
            )
            
            nodes[remoteNodeId] = node
            routingTable[remoteNodeId] = MeshRoute(
                destinationId = remoteNodeId,
                nextHop = remoteNodeId,
                hopCount = 1
            )
            
            updateConnectedNodesList()
            
            Log.d(TAG, "Mesh network updated: discovered $deviceName ($remoteNodeId) at $senderIp")
            Log.d(TAG, "Total connected nodes: ${nodes.size}")
        } else {
            Log.w(TAG, "Invalid discovery message format from $senderIp: ${String(message.payload)}")
        }
    }
    
    private fun handleRouteUpdate(message: MeshMessage) {
        // Update routing table based on received route information
        val routeInfo = String(message.payload)
        // Parse and update routes (implementation depends on route format)
    }
    
    private fun handleHeartbeat(message: MeshMessage, senderIp: String) {
        nodes[message.sourceId]?.let { node ->
            nodes[message.sourceId] = node.copy(lastSeen = System.currentTimeMillis())
        }
    }
    
    private fun sendMessage(message: MeshMessage) {
        val route = routingTable[message.destinationId]
        if (route != null) {
            val targetNode = nodes[route.nextHop]
            if (targetNode != null) {
                sendMessageToNode(message, targetNode)
            }
        }
    }
    
    private fun forwardMessage(message: MeshMessage) {
        if (message.ttl > 0) {
            val forwardedMessage = message.copy(ttl = message.ttl - 1)
            sendMessage(forwardedMessage)
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
            val payload = "$nodeId|$deviceName".toByteArray()
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
                
                val payload = "$nodeId|$deviceName".toByteArray()
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
        // Implement distance vector routing algorithm
        // This is a simplified version - a full implementation would be more complex
    }
    
    private fun cleanupOldEntries() {
        val currentTime = System.currentTimeMillis()
        
        // Remove old nodes
        val expiredNodes = nodes.filter { (_, node) ->
            currentTime - node.lastSeen > NODE_TIMEOUT
        }.keys
        
        expiredNodes.forEach { nodeId ->
            nodes.remove(nodeId)
            routingTable.remove(nodeId)
        }
        
        // Remove old routes
        val expiredRoutes = routingTable.filter { (_, route) ->
            currentTime - route.lastUpdated > MAX_ROUTE_AGE
        }.keys
        
        expiredRoutes.forEach { destinationId ->
            routingTable.remove(destinationId)
        }
        
        // Remove old message cache entries
        val expiredMessages = messageCache.filter { (_, timestamp) ->
            currentTime - timestamp > 60000 // 1 minute
        }.keys
        
        expiredMessages.forEach { messageId ->
            messageCache.remove(messageId)
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
}