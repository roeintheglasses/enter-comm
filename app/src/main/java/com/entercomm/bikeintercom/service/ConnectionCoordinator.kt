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
@Suppress("TooManyFunctions") // Coordinator requires many methods for connection lifecycle management
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

    // Expose group code state from WiFiDirectManager for UI observation
    val groupCode: StateFlow<String?> = wifiDirectManager.groupCode
    val groupModeEnabled: StateFlow<Boolean> = wifiDirectManager.groupModeEnabled

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

            // Set group code if provided - propagates to both WiFiDirectManager and MeshNetworkManager
            groupCode?.let { code ->
                if (!setGroupCode(code)) {
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
     * Set the group code for WiFi Direct service discovery and mesh networking.
     * This propagates the group code to both WiFiDirectManager (for service discovery filtering)
     * and MeshNetworkManager (for mesh network group filtering).
     *
     * @param code The group code to set (4-8 alphanumeric characters, or null for open mode)
     * @return true if the group code was set successfully on both managers, false if validation failed
     */
    fun setGroupCode(code: String?): Boolean {
        // Set on WiFiDirectManager first - it performs validation
        val wifiDirectSuccess = wifiDirectManager.setGroupCode(code)
        if (!wifiDirectSuccess) {
            logW { "Failed to set group code on WiFiDirectManager: $code" }
            return false
        }

        // Propagate to MeshNetworkManager for mesh-level filtering
        meshNetworkManager.setGroupCode(code)

        logD { "Group code synchronized: $code" }
        return true
    }

    /**
     * Get the current group code.
     *
     * @return The current group code, or null if in open mode
     */
    fun getGroupCode(): String? = wifiDirectManager.getGroupCode()

    /**
     * Enable or disable group mode filtering.
     * When enabled, only devices with matching group codes will be considered for connection.
     *
     * @param enabled true to enable group code filtering, false for open mode
     */
    fun setGroupModeEnabled(enabled: Boolean) {
        wifiDirectManager.setGroupModeEnabled(enabled)
        logD { "Group mode ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if group mode filtering is enabled.
     *
     * @return true if group code filtering is active
     */
    fun isGroupModeEnabled(): Boolean = wifiDirectManager.isGroupModeEnabled()

    /**
     * Clear the group code and switch to open mode.
     */
    fun clearGroupCode() {
        wifiDirectManager.clearGroupCode()
        meshNetworkManager.setGroupCode(null)
        logD { "Group code cleared - open mode" }
    }

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
            // WiFi P2P state events
            is WiFiDirectEvent.WiFiP2pEnabled -> handleWiFiP2pEnabled()
            is WiFiDirectEvent.WiFiP2pDisabled -> handleWiFiP2pDisabled()
            is WiFiDirectEvent.DeviceChanged -> handleDeviceChanged(event)

            // Peer discovery events (fallback mechanism)
            is WiFiDirectEvent.PeersChanged -> handlePeersChanged(event)

            // Service discovery events (primary mechanism)
            is WiFiDirectEvent.LocalServiceRegistered -> handleLocalServiceRegistered()
            is WiFiDirectEvent.LocalServiceUnregistered -> handleLocalServiceUnregistered()
            is WiFiDirectEvent.ServiceDiscovered -> handleServiceDiscovered(event)
            is WiFiDirectEvent.MatchingServiceDiscovered -> handleMatchingServiceDiscovered(event)
            is WiFiDirectEvent.ServiceDiscoveryStarted -> handleServiceDiscoveryStarted()
            is WiFiDirectEvent.ServiceDiscoveryStopped -> handleServiceDiscoveryStopped()

            // Group and connection events
            is WiFiDirectEvent.GroupCodeChanged -> handleGroupCodeChanged(event)
            is WiFiDirectEvent.GroupModeChanged -> handleGroupModeChanged(event)
            is WiFiDirectEvent.ConnectionChanged -> handleConnectionChanged(event)
            is WiFiDirectEvent.GroupInfoChanged -> handleGroupInfoChanged(event)

            // Auto-connection events
            is WiFiDirectEvent.AutoConnectionStarted -> handleAutoConnectionStarted(event)
            is WiFiDirectEvent.AutoConnectionFailed -> handleAutoConnectionFailed(event)

            // Error events
            is WiFiDirectEvent.Error -> handleError(event)
        }
    }

    private fun handleWiFiP2pEnabled() {
        logD { "WiFi P2P enabled - service discovery available" }
    }

    private fun handleWiFiP2pDisabled() {
        logW { "WiFi P2P disabled - stopping service discovery" }
        // WiFi P2P being disabled means we can't continue service discovery
        _isServiceDiscovering.value = false
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null

        if (_connectionState.value == ConnectionState.DISCOVERING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun handleDeviceChanged(event: WiFiDirectEvent.DeviceChanged) {
        event.device?.let { device ->
            logD { "Local device changed: ${device.deviceName} (${device.deviceAddress})" }
        }
    }

    private suspend fun handlePeersChanged(event: WiFiDirectEvent.PeersChanged) {
        event.peers.forEach { peer ->
            logD { "Discovered peer: ${peer.deviceName} (${peer.deviceAddress})" }
            _events.emit(ConnectionEvent.DeviceDiscovered(peer.deviceName, peer.deviceAddress))
        }
    }

    private fun handleLocalServiceRegistered() {
        logD { "Local service registered - now discoverable by other devices" }
    }

    private fun handleLocalServiceUnregistered() {
        logD { "Local service unregistered - no longer discoverable" }
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

        // Auto-connect if enabled and not already connecting/connected
        if (shouldAutoConnect()) {
            logD { "Auto-connecting to matching service: ${event.service.instanceName}" }
            val connected = wifiDirectManager.autoConnectToMatchingPeer(event.service)
            if (connected) {
                isConnecting = true
                _connectionState.value = ConnectionState.CONNECTING
            }
        }
    }

    /**
     * Check if auto-connect should be attempted.
     * Returns true if auto-connect is enabled and we're not already connecting or connected.
     */
    private fun shouldAutoConnect(): Boolean {
        return wifiDirectManager.isAutoConnectEnabled() &&
            !isConnecting &&
            _connectionState.value != ConnectionState.CONNECTED
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

    private fun handleGroupCodeChanged(event: WiFiDirectEvent.GroupCodeChanged) {
        logD { "Group code changed: ${event.previousCode} -> ${event.newCode}" }

        // Synchronize group code to MeshNetworkManager for mesh-level filtering
        meshNetworkManager.setGroupCode(event.newCode)
        logD { "Synchronized group code to MeshNetworkManager: ${event.newCode}" }

        // If we're currently discovering, we may need to re-register our service
        // with the new group code so other devices can find us
        if (_isServiceDiscovering.value && event.newCode != null) {
            logD { "Re-registering local service with new group code: ${event.newCode}" }
            wifiDirectManager.unregisterLocalService()
            wifiDirectManager.registerLocalService(event.newCode)
        }
    }

    private fun handleGroupModeChanged(event: WiFiDirectEvent.GroupModeChanged) {
        logD { "Group mode changed: ${if (event.enabled) "enabled" else "disabled"}" }
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
