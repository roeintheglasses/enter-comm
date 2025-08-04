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
        _connectedNodes.value = emptyList()
        
        Log.d(TAG, "Mesh network stopped")
    }
    
    fun addDirectConnection(ipAddress: String, port: Int = DISCOVERY_PORT) {
        Log.d(TAG, "Adding direct connection to $ipAddress:$port")
        
        val nodeId = generateNodeId(ipAddress)
        val node = MeshNode(
            nodeId = nodeId,
            deviceName = "Direct-$ipAddress",
            ipAddress = ipAddress,
            port = port,
            isDirectConnection = true,
            hopCount = 1
        )
        
        // Check if node already exists
        if (nodes.containsKey(nodeId)) {
            Log.d(TAG, "Node $nodeId already exists, updating...")
        }
        
        nodes[nodeId] = node
        routingTable[nodeId] = MeshRoute(
            destinationId = nodeId,
            nextHop = nodeId,
            hopCount = 1
        )
        
        updateConnectedNodesList()
        
        Log.d(TAG, "Node added to mesh network: $nodeId at $ipAddress:$port")
        Log.d(TAG, "Current mesh network has ${nodes.size} nodes: ${nodes.keys.joinToString(", ")}")
        
        // Send multiple discovery messages to establish connection
        repeat(3) { attempt ->
            Log.d(TAG, "Sending discovery message to $ipAddress:$port (attempt ${attempt + 1}/3)")
            sendDiscoveryMessage(ipAddress, port)
            if (attempt < 2) {
                // Small delay between attempts
                scope.launch {
                    delay(1000)
                }
            }
        }
        
        Log.d(TAG, "Added direct connection to $ipAddress:$port, total nodes: ${nodes.size}")
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
            
            // Send our info back
            Log.d(TAG, "Sending discovery response to $senderIp")
            sendDiscoveryResponse(senderIp)
            
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
                
                // Try multiple broadcast addresses for better coverage
                val broadcastAddresses = listOf(
                    "255.255.255.255",  // General broadcast
                    "192.168.49.255"    // WiFi Direct subnet broadcast
                )
                
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