package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.mesh.DiscoveryPayload
import com.entercomm.bikeintercom.mesh.DistanceVectorRouter
import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.mesh.MeshNode
import com.entercomm.bikeintercom.mesh.MeshRoute
import com.entercomm.bikeintercom.mesh.protocol.BinaryDiscoveryPayload
import com.entercomm.bikeintercom.mesh.protocol.MeshProtocol
import com.entercomm.bikeintercom.mesh.protocol.NodeIdEncoder
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Handles peer discovery, broadcasting, and connection management.
 */
@Suppress("LongParameterList", "TooManyFunctions", "TooGenericExceptionCaught", "ClassOrdering", "SwallowedException")
class DiscoveryService(
    private val nodeId: String,
    private val deviceName: String,
    private val protocol: MeshProtocol,
    private val socketManager: SocketManager,
    private val nodeRegistry: NodeRegistry,
    private val router: DistanceVectorRouter,
    private val routingService: RoutingService,
    private val scope: CoroutineScope,
    private val discoveryPort: Int,
) {
    private companion object {
        const val MAX_DEVICE_NAME_LENGTH = 50
        const val MIN_DEVICE_NAME_LENGTH = 1
        val GROUP_CODE_PATTERN = Regex("^[A-Z0-9]{4,8}$")
        val UUID_PATTERN = Regex("^(node-[a-fA-F0-9]{8}|[a-fA-F0-9-]{8,36})$")
        const val FIELD_DELIMITER = '|'
    }

    // Group filtering state
    private var groupCode: String? = null
    private var groupModeEnabled = true
    private var userNickname: String = sanitizeForDelimitedFormat(deviceName)

    // LRU cache for rate-limiting discovery responses
    private val discoveryResponseCache = object : LinkedHashMap<String, Long>(
        AppConfig.Mesh.DISCOVERY_CACHE_MAX_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > AppConfig.Mesh.DISCOVERY_CACHE_MAX_SIZE
        }
    }
    private val discoveryResponseCacheLock = Any()

    // Callback for peer discovery
    var onPeerDiscovered: ((String, String) -> Unit)? = null

    /**
     * Set the group code for filtering connections.
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
     */
    fun setNickname(nickname: String) {
        userNickname = sanitizeForDelimitedFormat(nickname.take(MAX_DEVICE_NAME_LENGTH))
        logD { "Nickname set to: $userNickname" }
    }

    /**
     * Broadcast a discovery message to the local network.
     */
    fun broadcastDiscovery() {
        scope.launch {
            // Register our own node ID for binary protocol encoding
            NodeIdEncoder.register(nodeId)

            // Create binary discovery payload
            val groupCodePart = groupCode ?: "OPEN"
            val payload = BinaryDiscoveryPayload.serialize(nodeId, deviceName, groupCodePart, userNickname)
            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = "broadcast",
                messageType = MeshMessage.MessageType.DISCOVERY,
                payload = payload,
            )

            try {
                val data = protocol.serialize(message)
                logD { "Broadcasting discovery: nodeId=$nodeId, deviceName=$deviceName, dataSize=${data.size}" }

                // Get broadcast addresses
                val broadcastAddresses = NetworkInterfaceHelper.getNetworkBroadcastAddresses(
                    socketManager.boundInterfaceName,
                    socketManager.boundAddress,
                )
                logD { "Found ${broadcastAddresses.size} broadcast addresses: ${broadcastAddresses.joinToString(", ")}" }

                for (broadcastAddr in broadcastAddresses) {
                    try {
                        val broadcastAddress = InetAddress.getByName(broadcastAddr)
                        socketManager.sendDiscovery(data, broadcastAddress, discoveryPort)
                        logD { "Sent broadcast discovery to $broadcastAddr:$discoveryPort" }
                    } catch (e: Exception) {
                        logW({ "Failed to broadcast to $broadcastAddr" }, e)
                    }
                }
            } catch (e: Exception) {
                logE({ "Failed to broadcast discovery" }, e)
            }
        }
    }

    /**
     * Send a discovery message to a specific IP address.
     */
    fun sendDiscoveryMessage(ipAddress: String, port: Int) {
        scope.launch {
            try {
                logD { "Preparing discovery message to $ipAddress:$port" }

                val groupCodePart = groupCode ?: "OPEN"
                val payload = BinaryDiscoveryPayload.serialize(nodeId, deviceName, groupCodePart, userNickname)
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload,
                )

                val data = protocol.serialize(message)
                logD { "Discovery message size: ${data.size} bytes" }

                val targetAddress = InetAddress.getByName(ipAddress)
                val success = socketManager.sendDiscovery(data, targetAddress, port)

                if (success) {
                    logD { "Discovery message sent successfully to $ipAddress:$port" }
                } else {
                    logE { "Failed to send discovery to $ipAddress:$port" }
                }
            } catch (e: Exception) {
                logE({ "Failed to send discovery message to $ipAddress:$port" }, e)
            }
        }
    }

    /**
     * Send a discovery probe to a specific IP address.
     */
    fun sendDiscoveryProbe(ipAddress: String, port: Int) {
        scope.launch {
            try {
                logD { "Sending discovery probe to $ipAddress:$port" }

                val groupCodePart = groupCode ?: "OPEN"
                val payload = BinaryDiscoveryPayload.serialize(nodeId, deviceName, groupCodePart, userNickname)
                val message = MeshMessage(
                    sourceId = nodeId,
                    destinationId = "discovery",
                    messageType = MeshMessage.MessageType.DISCOVERY,
                    payload = payload,
                )

                val data = protocol.serialize(message)
                val targetAddress = InetAddress.getByName(ipAddress)
                socketManager.sendDiscovery(data, targetAddress, port)
                logD { "Discovery probe sent to $ipAddress:$port" }
            } catch (e: Exception) {
                logD { "Discovery probe failed to $ipAddress:$port: ${e.message}" }
            }
        }
    }

    /**
     * Handle an incoming discovery message.
     */
    @Suppress("LongMethod")
    fun handleDiscoveryMessage(message: MeshMessage, senderIp: String) {
        logD { "Handling discovery message from $senderIp" }
        logD { "Message payload size: ${message.payload.size} bytes" }

        // Validate the discovery payload
        val validatedPayload = validateDiscoveryPayload(message.payload)
        if (validatedPayload == null) {
            logW { "Rejecting invalid discovery message from $senderIp" }
            return
        }

        val remoteNodeId = validatedPayload.nodeId
        val remoteName = validatedPayload.deviceName
        val remoteGroupCode = validatedPayload.groupCode
        val remoteNickname = validatedPayload.nickname

        logD {
            "Parsed discovery: nodeId=$remoteNodeId, deviceName=$remoteName, " +
                "nickname=$remoteNickname, groupCode=$remoteGroupCode, senderIp=$senderIp, ourGroupCode=$groupCode"
        }

        // Ignore messages from ourselves
        if (remoteNodeId == nodeId) {
            logD { "Ignoring discovery message from self: $remoteNodeId" }
            return
        }

        // Group filtering
        val ourCode = groupCode?.uppercase()
        val theirCode = remoteGroupCode.uppercase()

        if (ourCode != null && ourCode != "OPEN" && theirCode != ourCode) {
            logD { "Ignoring node $remoteNodeId - group code mismatch (ours=$ourCode, theirs=$theirCode)" }
            return
        }

        // Rate limiting for discovery responses
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

        // Track this IP as recently seen
        nodeRegistry.recordRecentlySeenIp(senderIp)

        if (shouldSendResponse) {
            logD { "Sending discovery response to $senderIp" }
            sendDiscoveryMessage(senderIp, discoveryPort)
        }

        // Check if this is a new node
        val isNewNode = !nodeRegistry.containsNode(remoteNodeId)
        if (isNewNode) {
            logD { "Adding new node: $remoteNodeId" }
        } else {
            logD { "Updating existing node: $remoteNodeId" }
        }

        val node = MeshNode(
            nodeId = remoteNodeId,
            deviceName = remoteName,
            ipAddress = senderIp,
            port = discoveryPort,
            isDirectConnection = true,
            hopCount = 1,
            lastSeen = System.currentTimeMillis(),
            linkQuality = 1.0f,
        )

        nodeRegistry.addOrUpdateNode(node)
        routingService.addRoute(
            MeshRoute(
                destinationId = remoteNodeId,
                nextHop = remoteNodeId,
                hopCount = 1,
            ),
        )

        // Add to distance vector router for multi-hop routing
        router.addNeighbor(remoteNodeId, senderIp, discoveryPort, 1.0f)

        // Notify about new peer discovery
        if (isNewNode) {
            onPeerDiscovered?.invoke(remoteNodeId, remoteNickname)
        }

        logD { "Mesh network updated: discovered $remoteNickname ($remoteNodeId) at $senderIp" }
        logD { "Total connected nodes: ${nodeRegistry.nodeCount()}" }
        logD { "Reachable destinations: ${router.getReachableDestinations().size}" }
    }

    /**
     * Scan network and connect to available devices.
     */
    @Suppress("LongMethod")
    fun scanAndConnect() {
        scope.launch {
            logD { "Starting optimized network scan for available devices..." }

            val localIPs = NetworkInterfaceHelper.getLocalIPAddresses()
            if (localIPs.isEmpty()) {
                logW { "No local IP addresses found, cannot scan network" }
                return@launch
            }

            logD { "Local IPs: ${localIPs.joinToString(", ")}" }

            val localIP = localIPs.first()
            val subnet = localIP.substringBeforeLast(".")

            // Phase 1: Priority scan - check recently seen IPs first
            val priorityIps = nodeRegistry.getRecentlySeenIps()

            if (priorityIps.isNotEmpty()) {
                logD { "Phase 1: Scanning ${priorityIps.size} recently seen IPs with priority..." }
                val priorityJobs = priorityIps
                    .filter { !localIPs.contains(it) }
                    .filter { !nodeRegistry.isConnectedIp(it) }
                    .map { ip ->
                        launch {
                            try {
                                val address = InetAddress.getByName(ip)
                                if (address.isReachable(AppConfig.Mesh.NETWORK_SCAN_PRIORITY_TIMEOUT_MS)) {
                                    logD { "Priority: Found reachable device at $ip" }
                                    sendDiscoveryProbe(ip, discoveryPort)
                                }
                            } catch (e: Exception) {
                                // Silently ignore
                            }
                        }
                    }
                priorityJobs.joinAll()
                logD { "Phase 1 complete: Priority scan finished" }
            }

            // Phase 2: Full subnet scan
            logD { "Phase 2: Scanning subnet $subnet.* for Enter-Comm devices..." }
            val scanJobs = mutableListOf<kotlinx.coroutines.Job>()
            val priorityIpSet = priorityIps.toSet()

            for (i in 1..254) {
                val targetIP = "$subnet.$i"

                if (localIPs.contains(targetIP) ||
                    nodeRegistry.isConnectedIp(targetIP) ||
                    priorityIpSet.contains(targetIP)
                ) {
                    continue
                }

                val job = launch {
                    try {
                        val address = InetAddress.getByName(targetIP)
                        if (address.isReachable(AppConfig.Mesh.NETWORK_SCAN_TIMEOUT_MS)) {
                            logD { "Found reachable device at $targetIP, sending discovery probe..." }
                            sendDiscoveryProbe(targetIP, discoveryPort)
                        }
                    } catch (e: Exception) {
                        // Silently ignore unreachable hosts
                    }
                }
                scanJobs.add(job)

                if (scanJobs.size >= AppConfig.Mesh.NETWORK_SCAN_BATCH_SIZE) {
                    scanJobs.joinAll()
                    scanJobs.clear()
                    delay(50)
                }
            }

            scanJobs.joinAll()

            logD { "Network scan completed. Sent discovery probes to all reachable devices." }
            logD { "Actual mesh connections: ${nodeRegistry.nodeCount()} devices" }
        }
    }

    /**
     * Add a direct connection to a specific IP address.
     */
    fun addDirectConnection(ipAddress: String, port: Int) {
        val localIPs = NetworkInterfaceHelper.getLocalIPAddresses()
        if (localIPs.contains(ipAddress)) {
            logD { "Skipping direct connection to our own IP: $ipAddress" }
            return
        }

        val generatedNodeId = nodeRegistry.generateNodeId(ipAddress)

        val existingNode = nodeRegistry.getNode(generatedNodeId)
        if (existingNode != null) {
            val timeSinceUpdate = System.currentTimeMillis() - existingNode.lastSeen
            if (timeSinceUpdate < 30_000) {
                logD { "Node $generatedNodeId at $ipAddress already exists and is recent, skipping..." }
                return
            }
        }

        logD { "Attempting direct connection to $ipAddress:$port" }
        logD { "Sending discovery message to $ipAddress:$port" }
        sendDiscoveryMessage(ipAddress, port)
        logD { "Discovery message sent to $ipAddress:$port - waiting for response..." }
    }

    /**
     * Clean up expired cache entries.
     */
    fun cleanupCaches() {
        val currentTime = System.currentTimeMillis()

        synchronized(discoveryResponseCacheLock) {
            val expiredResponses = discoveryResponseCache.filter { (_, timestamp) ->
                currentTime - timestamp > AppConfig.Mesh.DISCOVERY_CACHE_TTL_MS
            }.keys.toList()

            expiredResponses.forEach { ipAddress ->
                discoveryResponseCache.remove(ipAddress)
            }
        }

        nodeRegistry.cleanupExpiredRecentlySeenIps(AppConfig.Mesh.DISCOVERY_CACHE_TTL_MS)
    }

    /**
     * Clear all caches.
     */
    fun clear() {
        synchronized(discoveryResponseCacheLock) {
            discoveryResponseCache.clear()
        }
    }

    // Validation methods

    private fun validateDiscoveryPayload(payloadBytes: ByteArray): DiscoveryPayload? {
        val parsed = BinaryDiscoveryPayload.deserialize(payloadBytes)
        if (parsed == null) {
            logW { "Invalid discovery payload: failed to parse binary format" }
            return null
        }
        return validateParsedDiscoveryPayload(parsed)
    }

    private fun validateParsedDiscoveryPayload(parsed: BinaryDiscoveryPayload.Payload): DiscoveryPayload? {
        val validNodeId = validateNodeId(parsed.nodeId) ?: return null
        val validDeviceName = validateDeviceName(parsed.deviceName) ?: return null
        val validGroupCode = validateGroupCode(parsed.groupCode) ?: return null
        val nickname = parsed.nickname.ifEmpty { validDeviceName }.take(MAX_DEVICE_NAME_LENGTH)

        return DiscoveryPayload(
            nodeId = validNodeId,
            deviceName = validDeviceName,
            groupCode = validGroupCode,
            nickname = nickname,
        )
    }

    private fun validateNodeId(nodeId: String): String? {
        if (nodeId.isEmpty() || nodeId.length > 36 || !UUID_PATTERN.matches(nodeId)) {
            logW { "Invalid discovery payload: invalid nodeId format" }
            return null
        }
        return nodeId
    }

    private fun validateDeviceName(deviceName: String): String? {
        if (deviceName.length !in MIN_DEVICE_NAME_LENGTH..MAX_DEVICE_NAME_LENGTH) {
            logW { "Invalid discovery payload: deviceName length out of range (${deviceName.length})" }
            return null
        }
        return deviceName
    }

    private fun validateGroupCode(groupCode: String): String? {
        val normalized = groupCode.uppercase()
        if (normalized != "OPEN" && !GROUP_CODE_PATTERN.matches(normalized)) {
            logW { "Invalid discovery payload: invalid groupCode format" }
            return null
        }
        return normalized
    }

    private fun sanitizeForDelimitedFormat(value: String): String {
        return value.replace(FIELD_DELIMITER, '_')
    }
}
