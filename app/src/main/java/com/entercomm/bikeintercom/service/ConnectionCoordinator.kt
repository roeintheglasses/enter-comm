package com.entercomm.bikeintercom.service

import android.net.wifi.p2p.WifiP2pInfo
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.mesh.MeshNetworkManager
import com.entercomm.bikeintercom.util.Result
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import com.entercomm.bikeintercom.wifidirect.DiscoveredService
import com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent
import com.entercomm.bikeintercom.wifidirect.WiFiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Connection state representing the current connection status
 */
enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * Events emitted by the ConnectionCoordinator
 */
sealed class ConnectionEvent {
    data class DeviceDiscovered(val deviceName: String, val deviceAddress: String) : ConnectionEvent()
    data class ServiceDiscovered(val service: DiscoveredService) : ConnectionEvent()
    data class MatchingServiceDiscovered(val service: DiscoveredService) : ConnectionEvent()
    data class ConnectionEstablished(val address: String) : ConnectionEvent()
    data class ConnectionFailed(val reason: String) : ConnectionEvent()
    data class StateChanged(val state: ConnectionState) : ConnectionEvent()
}

/**
 * Coordinates WiFi Direct and Mesh Network connections.
 * Extracted from MeshNetworkService to handle connection lifecycle.
 */
class ConnectionCoordinator(
    private val wifiDirectManager: WiFiDirectManager,
    private val meshNetworkManager: MeshNetworkManager,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>()
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    private var isConnecting = false
    private var lastConnectionAttempt = 0L
    private var monitorJob: Job? = null
    private var serviceDiscoveryJob: Job? = null

    // Service discovery is the primary mechanism; peer discovery is fallback
    private val _isServiceDiscovering = MutableStateFlow(false)
    val isServiceDiscovering: StateFlow<Boolean> = _isServiceDiscovering.asStateFlow()

    /**
     * Start monitoring WiFi Direct events
     */
    fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            monitorWiFiDirectEvents()
        }
    }

    /**
     * Stop monitoring WiFi Direct events
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Start device discovery using WiFi Direct service discovery as primary mechanism.
     * Falls back to peer discovery if service discovery fails.
     *
     * @param groupCode Optional group code for service discovery filtering
     */
    fun startDiscovery(groupCode: String? = null): Result<Unit> {
        return try {
            wifiDirectManager.initialize()
            _connectionState.value = ConnectionState.DISCOVERING

            // Set group code if provided
            groupCode?.let { code ->
                if (!wifiDirectManager.setGroupCode(code)) {
                    logW { "Invalid group code format: $code" }
                }
            }

            // Start service discovery as primary mechanism
            startServiceDiscoveryPrimary()

            Result.success(Unit)
        } catch (e: Exception) {
            logE({ "Failed to start discovery" }, e)
            Result.error("Failed to start discovery: ${e.message}", e)
        }
    }

    /**
     * Start WiFi Direct service discovery as the primary discovery mechanism.
     * Service discovery allows filtering by group code via TXT records.
     * Falls back to peer discovery if service discovery fails or times out.
     */
    private fun startServiceDiscoveryPrimary() {
        serviceDiscoveryJob?.cancel()

        // Register local service so other devices can discover us
        val groupCode = wifiDirectManager.getGroupCode()
        if (groupCode != null) {
            wifiDirectManager.registerLocalService(groupCode)
            logD { "Registered local service with group code: $groupCode" }
        }

        // Start service discovery to find other devices
        wifiDirectManager.startServiceDiscovery()
        _isServiceDiscovering.value = true
        logD { "Started WiFi Direct service discovery (primary mechanism)" }

        // Schedule fallback to peer discovery if no services found
        serviceDiscoveryJob = scope.launch {
            delay(AppConfig.WiFiDirect.DISCOVERY_TIMEOUT_MS)
            if (_connectionState.value == ConnectionState.DISCOVERING &&
                wifiDirectManager.discoveredServices.value.isEmpty()
            ) {
                logW { "Service discovery timeout with no services found, falling back to peer discovery" }
                startPeerDiscoveryFallback()
            }
        }
    }

    /**
     * Start peer discovery as fallback when service discovery doesn't find devices.
     * Peer discovery finds all WiFi Direct devices but without group code filtering.
     */
    private fun startPeerDiscoveryFallback() {
        logD { "Starting peer discovery as fallback mechanism" }
        wifiDirectManager.startDiscovery()
    }

    /**
     * Start discovery with a specific group code.
     * Convenience method that sets the group code and starts discovery.
     *
     * @param groupCode The group code for filtering (4-8 alphanumeric characters)
     */
    fun startDiscoveryWithGroupCode(groupCode: String): Result<Unit> {
        return startDiscovery(groupCode)
    }

    /**
     * Stop device discovery (both service discovery and peer discovery)
     */
    fun stopDiscovery() {
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null

        // Stop service discovery (primary)
        wifiDirectManager.stopServiceDiscovery()
        _isServiceDiscovering.value = false

        // Stop peer discovery (fallback)
        wifiDirectManager.stopDiscovery()

        // Unregister local service
        wifiDirectManager.unregisterLocalService()

        if (_connectionState.value == ConnectionState.DISCOVERING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        logD { "Stopped all discovery mechanisms" }
    }

    /**
     * Connect to a specific device
     */
    fun connectToDevice(deviceAddress: String): Result<Unit> {
        val now = System.currentTimeMillis()

        // Prevent rapid connection attempts
        if (isConnecting) {
            logW { "Connection already in progress" }
            return Result.error("Connection already in progress")
        }

        if (now - lastConnectionAttempt < AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS) {
            val waitTime = (AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS - (now - lastConnectionAttempt)) / 1000
            logW { "Connection attempt too soon, wait $waitTime seconds" }
            return Result.error("Please wait $waitTime seconds before trying again")
        }

        // Check if already connected
        val connectionInfo = wifiDirectManager.connectionInfo.value
        if (connectionInfo?.groupFormed == true) {
            logW { "Already connected to a WiFi Direct group" }
            return Result.error("Already connected to another device")
        }

        // Find the device in available peers
        val targetDevice = wifiDirectManager.getAvailablePeers().find { it.deviceAddress == deviceAddress }
            ?: run {
                logE { "Device $deviceAddress not found in available peers" }
                return Result.error("Device not available for connection")
            }

        isConnecting = true
        lastConnectionAttempt = now
        _connectionState.value = ConnectionState.CONNECTING

        logD { "Initiating connection to ${targetDevice.deviceName} ($deviceAddress)" }
        wifiDirectManager.connectToPeer(targetDevice)

        // Set connection timeout
        scope.launch {
            delay(AppConfig.WiFiDirect.CONNECTION_TIMEOUT_MS)
            if (isConnecting) {
                logW { "Connection timeout" }
                isConnecting = false
                _connectionState.value = ConnectionState.ERROR
                _events.emit(ConnectionEvent.ConnectionFailed("Connection timeout"))
            }
        }

        return Result.success(Unit)
    }

    /**
     * Connect to a discovered service.
     * This is the preferred method when connecting via service discovery (primary mechanism).
     *
     * @param service The discovered service to connect to
     * @return Result indicating success or failure
     */
    fun connectToService(service: DiscoveredService): Result<Unit> {
        val device = service.device
            ?: run {
                logE { "Cannot connect to service: device info not available" }
                return Result.error("Device info not available for this service")
            }

        // Validate connection state before proceeding
        val validationError = validateConnectionState()
        if (validationError != null) {
            return Result.error(validationError)
        }

        isConnecting = true
        lastConnectionAttempt = System.currentTimeMillis()
        _connectionState.value = ConnectionState.CONNECTING

        logD {
            "Initiating connection to service: ${service.instanceName} " +
                "(${device.deviceName}, group code: ${service.groupCode})"
        }
        wifiDirectManager.connectToPeer(device)

        // Set connection timeout
        scope.launch {
            delay(AppConfig.WiFiDirect.CONNECTION_TIMEOUT_MS)
            if (isConnecting) {
                logW { "Connection timeout" }
                isConnecting = false
                _connectionState.value = ConnectionState.ERROR
                _events.emit(ConnectionEvent.ConnectionFailed("Connection timeout"))
            }
        }

        return Result.success(Unit)
    }

    /**
     * Validates that the connection state allows a new connection attempt.
     * @return Error message if validation fails, null if connection can proceed
     */
    private fun validateConnectionState(): String? {
        if (isConnecting) {
            logW { "Connection already in progress" }
            return "Connection already in progress"
        }

        val now = System.currentTimeMillis()
        if (now - lastConnectionAttempt < AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS) {
            val waitTime = (AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS - (now - lastConnectionAttempt)) / 1000
            logW { "Connection attempt too soon, wait $waitTime seconds" }
            return "Please wait $waitTime seconds before trying again"
        }

        val connectionInfo = wifiDirectManager.connectionInfo.value
        if (connectionInfo?.groupFormed == true) {
            logW { "Already connected to a WiFi Direct group" }
            return "Already connected to another device"
        }

        return null
    }

    /**
     * Enable or disable auto-connect to matching services.
     * When enabled, the coordinator will automatically connect to services with matching group codes.
     *
     * @param enabled true to enable auto-connect, false to disable
     */
    fun setAutoConnectEnabled(enabled: Boolean) {
        wifiDirectManager.setAutoConnectEnabled(enabled)
        logD { "Auto-connect ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if auto-connect is enabled.
     *
     * @return true if auto-connect is enabled
     */
    fun isAutoConnectEnabled(): Boolean = wifiDirectManager.isAutoConnectEnabled()

    /**
     * Get matching services that have been discovered.
     * These are services with group codes that match the current group code.
     *
     * @return List of discovered services matching the current group criteria
     */
    fun getMatchingServices(): List<DiscoveredService> = wifiDirectManager.getMatchingServices()

    /**
     * Get all discovered services regardless of group code.
     *
     * @return List of all discovered services
     */
    fun getDiscoveredServices(): List<DiscoveredService> = wifiDirectManager.discoveredServices.value

    /**
     * Disconnect from current connection
     */
    fun disconnect() {
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null
        _isServiceDiscovering.value = false

        wifiDirectManager.disconnect()
        wifiDirectManager.unregisterLocalService()
        meshNetworkManager.stopMeshNetwork()
        isConnecting = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun monitorWiFiDirectEvents() {
        try {
            logD { "Starting WiFi Direct event monitoring" }
            wifiDirectManager.getEvents().collect { event ->
                handleWiFiDirectEvent(event)
            }
        } catch (e: Exception) {
            logE({ "Error in WiFi Direct event monitoring" }, e)
            // Try to restart monitoring after delay
            delay(AppConfig.Service.WIFI_DIRECT_MONITOR_RESTART_DELAY_MS)
            if (monitorJob?.isActive == true) {
                monitorWiFiDirectEvents()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // WiFi Direct event handling requires many event types
    private suspend fun handleWiFiDirectEvent(event: WiFiDirectEvent) {
        logD { "Received WiFi Direct event: ${event::class.simpleName}" }

        when (event) {
            is WiFiDirectEvent.PeersChanged -> handlePeersChanged(event)
            is WiFiDirectEvent.ServiceDiscovered -> handleServiceDiscovered(event)
            is WiFiDirectEvent.MatchingServiceDiscovered -> handleMatchingServiceDiscovered(event)
            is WiFiDirectEvent.ServiceDiscoveryStarted -> handleServiceDiscoveryStarted()
            is WiFiDirectEvent.ServiceDiscoveryStopped -> handleServiceDiscoveryStopped()
            is WiFiDirectEvent.ConnectionChanged -> handleConnectionChanged(event)
            is WiFiDirectEvent.GroupInfoChanged -> handleGroupInfoChanged(event)
            is WiFiDirectEvent.AutoConnectionStarted -> handleAutoConnectionStarted(event)
            is WiFiDirectEvent.AutoConnectionFailed -> handleAutoConnectionFailed(event)
            is WiFiDirectEvent.Error -> handleError(event)
            else -> logD { "Unhandled event: ${event::class.simpleName}" }
        }
    }

    private suspend fun handlePeersChanged(event: WiFiDirectEvent.PeersChanged) {
        event.peers.forEach { peer ->
            logD { "Discovered peer: ${peer.deviceName} (${peer.deviceAddress})" }
            _events.emit(ConnectionEvent.DeviceDiscovered(peer.deviceName, peer.deviceAddress))
        }
    }

    private suspend fun handleServiceDiscovered(event: WiFiDirectEvent.ServiceDiscovered) {
        logD {
            "Service discovered: ${event.service.instanceName} " +
                "from ${event.service.deviceAddress}, group code: ${event.service.groupCode}"
        }
        _events.emit(ConnectionEvent.ServiceDiscovered(event.service))
    }

    private suspend fun handleMatchingServiceDiscovered(event: WiFiDirectEvent.MatchingServiceDiscovered) {
        logD {
            "Matching service discovered: ${event.service.instanceName} " +
                "from ${event.service.device?.deviceName}, group code: ${event.service.groupCode}"
        }
        _events.emit(ConnectionEvent.MatchingServiceDiscovered(event.service))

        // Cancel the fallback to peer discovery since we found a matching service
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null
    }

    private fun handleServiceDiscoveryStarted() {
        logD { "Service discovery started" }
        _isServiceDiscovering.value = true
    }

    private fun handleServiceDiscoveryStopped() {
        logD { "Service discovery stopped" }
        _isServiceDiscovering.value = false
    }

    private suspend fun handleConnectionChanged(event: WiFiDirectEvent.ConnectionChanged) {
        event.info?.let { info ->
            logD { "Connection changed - Group formed: ${info.groupFormed}, Is owner: ${info.isGroupOwner}" }

            if (info.groupFormed) {
                handleGroupFormed(info)
            } else {
                logD { "WiFi Direct group disbanded" }
                isConnecting = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private suspend fun handleGroupFormed(info: WifiP2pInfo) {
        isConnecting = false
        _connectionState.value = ConnectionState.CONNECTED

        // Stop discovery since we're now connected
        _isServiceDiscovering.value = false
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null

        val groupOwnerAddress = info.groupOwnerAddress?.hostAddress

        if (!info.isGroupOwner) {
            // We're a client, connect to group owner's mesh network
            val targetIP = groupOwnerAddress ?: getWiFiDirectGroupOwnerIP()
            logD { "CLIENT: Connecting to group owner mesh at $targetIP" }
            connectToMeshWithRetry(targetIP)
        } else {
            // We're the group owner
            logD { "GROUP OWNER: Ready for client connections at ${groupOwnerAddress ?: getLocalWiFiDirectIP()}" }
            wifiDirectManager.requestGroupInfo()
        }
    }

    private fun handleGroupInfoChanged(event: WiFiDirectEvent.GroupInfoChanged) {
        logD { "Group info: ${event.clients.size} clients, isGroupOwner: ${event.isGroupOwner}" }
    }

    private fun handleAutoConnectionStarted(event: WiFiDirectEvent.AutoConnectionStarted) {
        logD { "Auto-connection started to ${event.service.device?.deviceName}" }
        isConnecting = true
        _connectionState.value = ConnectionState.CONNECTING
    }

    private fun handleAutoConnectionFailed(event: WiFiDirectEvent.AutoConnectionFailed) {
        logW { "Auto-connection failed: ${event.reason}" }
        wifiDirectManager.resetAutoConnectState()
    }

    private suspend fun handleError(event: WiFiDirectEvent.Error) {
        logE { "WiFi Direct error: ${event.message}" }
        _events.emit(ConnectionEvent.ConnectionFailed(event.message))
        if (isConnecting) {
            isConnecting = false
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private suspend fun connectToMeshWithRetry(targetIP: String) {
        repeat(AppConfig.Service.RETRY_MAX_ATTEMPTS) { attempt ->
            try {
                logD { "Mesh connection attempt ${attempt + 1}/${AppConfig.Service.RETRY_MAX_ATTEMPTS}" }
                meshNetworkManager.addDirectConnection(targetIP, AppConfig.Mesh.DISCOVERY_PORT)

                delay(AppConfig.Service.MESH_CONNECTION_VERIFY_DELAY_MS)

                if (meshNetworkManager.connectedNodes.value.isNotEmpty()) {
                    logD { "Mesh connection established successfully" }
                    _events.emit(ConnectionEvent.ConnectionEstablished(targetIP))
                    return
                }

                logW { "Mesh connection not established, retrying..." }
                delay(AppConfig.Service.RETRY_DELAY_MS * (attempt + 1))
            } catch (e: Exception) {
                logE({ "Mesh connection attempt failed" }, e)
                if (attempt == AppConfig.Service.RETRY_MAX_ATTEMPTS - 1) {
                    _events.emit(ConnectionEvent.ConnectionFailed("Failed to establish mesh connection"))
                }
            }
        }
    }

    private fun getWiFiDirectGroupOwnerIP(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val name = networkInterface.name.lowercase()
                if (name.contains("p2p") || name.contains("direct")) {
                    for (addr in networkInterface.interfaceAddresses) {
                        val inetAddr = addr.address
                        if (inetAddr is Inet4Address && !inetAddr.hostAddress.isNullOrEmpty()) {
                            val ip = inetAddr.hostAddress!!
                            if (!ip.startsWith("127.")) {
                                val subnet = ip.substringBeforeLast(".")
                                return "$subnet.1"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error detecting group owner IP" }, e)
        }
        return AppConfig.WiFiDirect.GROUP_OWNER_DEFAULT_IP
    }

    private fun getLocalWiFiDirectIP(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val name = networkInterface.name.lowercase()
                if (name.contains("p2p") || name.contains("direct")) {
                    for (addr in networkInterface.interfaceAddresses) {
                        val inetAddr = addr.address
                        if (inetAddr is Inet4Address && !inetAddr.hostAddress.isNullOrEmpty()) {
                            val ip = inetAddr.hostAddress!!
                            if (!ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error detecting local IP" }, e)
        }

        // Fallback
        val localIPs = meshNetworkManager.getLocalIPAddresses()
        return localIPs.firstOrNull() ?: AppConfig.WiFiDirect.GROUP_OWNER_DEFAULT_IP
    }
}
