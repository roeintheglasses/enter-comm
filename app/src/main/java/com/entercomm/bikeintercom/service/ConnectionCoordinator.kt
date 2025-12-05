package com.entercomm.bikeintercom.service

import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.mesh.MeshNetworkManager
import com.entercomm.bikeintercom.util.Result
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
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
     * Start device discovery
     */
    fun startDiscovery(): Result<Unit> {
        return try {
            wifiDirectManager.initialize()
            wifiDirectManager.startDiscovery()
            _connectionState.value = ConnectionState.DISCOVERING
            Result.success(Unit)
        } catch (e: Exception) {
            logE({ "Failed to start discovery" }, e)
            Result.error("Failed to start discovery: ${e.message}", e)
        }
    }

    /**
     * Stop device discovery
     */
    fun stopDiscovery() {
        wifiDirectManager.stopDiscovery()
        if (_connectionState.value == ConnectionState.DISCOVERING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
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
     * Disconnect from current connection
     */
    fun disconnect() {
        wifiDirectManager.disconnect()
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

    private suspend fun handleWiFiDirectEvent(event: WiFiDirectEvent) {
        logD { "Received WiFi Direct event: ${event::class.simpleName}" }

        when (event) {
            is WiFiDirectEvent.PeersChanged -> {
                event.peers.forEach { peer ->
                    logD { "Discovered peer: ${peer.deviceName} (${peer.deviceAddress})" }
                    _events.emit(ConnectionEvent.DeviceDiscovered(peer.deviceName, peer.deviceAddress))
                }
            }

            is WiFiDirectEvent.ConnectionChanged -> {
                event.info?.let { info ->
                    logD { "Connection changed - Group formed: ${info.groupFormed}, Is owner: ${info.isGroupOwner}" }

                    if (info.groupFormed) {
                        isConnecting = false
                        _connectionState.value = ConnectionState.CONNECTED

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
                    } else {
                        logD { "WiFi Direct group disbanded" }
                        isConnecting = false
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }

            is WiFiDirectEvent.GroupInfoChanged -> {
                logD { "Group info: ${event.clients.size} clients, isGroupOwner: ${event.isGroupOwner}" }
            }

            is WiFiDirectEvent.Error -> {
                logE { "WiFi Direct error: ${event.message}" }
                _events.emit(ConnectionEvent.ConnectionFailed(event.message))
                if (isConnecting) {
                    isConnecting = false
                    _connectionState.value = ConnectionState.ERROR
                }
            }

            else -> {
                logD { "Unhandled event: ${event::class.simpleName}" }
            }
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
