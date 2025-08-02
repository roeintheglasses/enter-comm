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
        private const val DISCOVERY_PORT = 8888
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
        if (isRunning) return
        
        scope.launch {
            try {
                isRunning = true
                _isActive.value = true
                
                // Initialize sockets
                discoverySocket = DatagramSocket(localPort)
                audioSocket = DatagramSocket(localPort + 1)
                
                Log.d(TAG, "Mesh network started on port $localPort")
                
                // Start discovery and routing services
                launch { startDiscoveryService() }
                launch { startRoutingService() }
                launch { startHeartbeatService() }
                launch { startMessageListener() }
                launch { startAudioListener() }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh network", e)
                stopMeshNetwork()
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
        val node = MeshNode(
            nodeId = generateNodeId(ipAddress),
            deviceName = "Direct-$ipAddress",
            ipAddress = ipAddress,
            port = port,
            isDirectConnection = true,
            hopCount = 1
        )
        
        nodes[node.nodeId] = node
        routingTable[node.nodeId] = MeshRoute(
            destinationId = node.nodeId,
            nextHop = node.nodeId,
            hopCount = 1
        )
        
        updateConnectedNodesList()
        
        // Send discovery message to establish connection
        sendDiscoveryMessage(ipAddress, port)
        
        Log.d(TAG, "Added direct connection to $ipAddress")
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
        while (isRunning) {
            try {
                // Broadcast discovery message to local network
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
        while (isRunning) {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                discoverySocket?.receive(packet)
                
                val message = deserializeMessage(packet.data, packet.length)
                if (message != null) {
                    handleIncomingMessage(message, packet.address.hostAddress ?: "")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Message listener error", e)
                    delay(1000)
                }
            }
        }
    }
    
    private suspend fun startAudioListener() {
        while (isRunning) {
            try {
                val buffer = ByteArray(4096) // Larger buffer for audio
                val packet = DatagramPacket(buffer, buffer.size)
                
                audioSocket?.receive(packet)
                
                val message = deserializeMessage(packet.data, packet.length)
                if (message?.messageType == MeshMessage.MessageType.AUDIO_DATA) {
                    if (message.destinationId == nodeId) {
                        // Audio data for us
                        onAudioDataReceived?.invoke(message.payload, message.sourceId)
                    } else {
                        // Forward audio data
                        forwardMessage(message)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Audio listener error", e)
                    delay(1000)
                }
            }
        }
    }
    
    private fun handleIncomingMessage(message: MeshMessage, senderIp: String) {
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
        val nodeInfo = String(message.payload).split("|")
        if (nodeInfo.size >= 2) {
            val remoteNodeId = nodeInfo[0]
            val deviceName = nodeInfo[1]
            
            val node = MeshNode(
                nodeId = remoteNodeId,
                deviceName = deviceName,
                ipAddress = senderIp,
                port = DISCOVERY_PORT,
                isDirectConnection = true,
                hopCount = 1
            )
            
            nodes[remoteNodeId] = node
            routingTable[remoteNodeId] = MeshRoute(
                destinationId = remoteNodeId,
                nextHop = remoteNodeId,
                hopCount = 1
            )
            
            updateConnectedNodesList()
            
            // Send our info back
            sendDiscoveryResponse(senderIp)
            
            Log.d(TAG, "Discovered node: $deviceName ($remoteNodeId) at $senderIp")
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
    
    private fun broadcastDiscovery() {
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
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
            discoverySocket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast discovery", e)
        }
    }
    
    private fun sendDiscoveryMessage(ipAddress: String, port: Int) {
        val payload = "$nodeId|$deviceName".toByteArray()
        val message = MeshMessage(
            sourceId = nodeId,
            destinationId = "discovery",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = payload
        )
        
        try {
            val data = serializeMessage(message)
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName(ipAddress), port
            )
            discoverySocket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send discovery to $ipAddress", e)
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
            val headerEnd = dataString.indexOf("|", dataString.indexOf("|", dataString.indexOf("|", dataString.indexOf("|", dataString.indexOf("|") + 1) + 1) + 1) + 1)
            if (headerEnd == -1) return null
            
            val headerParts = dataString.substring(0, headerEnd).split("|")
            if (headerParts.size < 6) return null
            
            val payload = data.copyOfRange(headerEnd + 1, length)
            
            return MeshMessage(
                messageId = headerParts[0],
                sourceId = headerParts[1],
                destinationId = headerParts[2],
                messageType = MeshMessage.MessageType.valueOf(headerParts[3]),
                ttl = headerParts[4].toInt(),
                timestamp = headerParts[5].toLong(),
                payload = payload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize message", e)
            return null
        }
    }
}