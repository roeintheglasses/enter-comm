package com.entercomm.bikeintercom.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class PeerDevice(
    val deviceName: String,
    val deviceAddress: String,
    val isGroupOwner: Boolean = false,
    val ipAddress: String? = null,
    val isConnected: Boolean = false,
)

/**
 * Represents a discovered WiFi Direct service with group code information.
 */
data class DiscoveredService(
    val deviceAddress: String,
    val instanceName: String,
    val serviceType: String,
    val groupCode: String?,
    val device: WifiP2pDevice? = null,
)

sealed class WiFiDirectEvent {
    object WiFiP2pEnabled : WiFiDirectEvent()
    object WiFiP2pDisabled : WiFiDirectEvent()
    data class PeersChanged(val peers: List<WifiP2pDevice>) : WiFiDirectEvent()
    data class ConnectionChanged(val info: WifiP2pInfo?) : WiFiDirectEvent()
    data class DeviceChanged(val device: WifiP2pDevice?) : WiFiDirectEvent()
    data class GroupInfoChanged(val clients: List<PeerDevice>, val isGroupOwner: Boolean) : WiFiDirectEvent()
    object LocalServiceRegistered : WiFiDirectEvent()
    object LocalServiceUnregistered : WiFiDirectEvent()
    data class ServiceDiscovered(val service: DiscoveredService) : WiFiDirectEvent()
    data class MatchingServiceDiscovered(val service: DiscoveredService) : WiFiDirectEvent()
    object ServiceDiscoveryStarted : WiFiDirectEvent()
    object ServiceDiscoveryStopped : WiFiDirectEvent()
    data class AutoConnectionStarted(val service: DiscoveredService) : WiFiDirectEvent()
    data class AutoConnectionFailed(val reason: String) : WiFiDirectEvent()
    data class Error(val message: String) : WiFiDirectEvent()
}

@Suppress("TooManyFunctions") // WiFi Direct manager requires many public methods for service lifecycle
class WiFiDirectManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
) {

    companion object {
        private const val TXT_RECORD_GROUP_CODE_KEY = "group_code"
    }

    private var isReceiverRegistered = false
    private var isLocalServiceRegistered = false
    private var currentServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var isServiceDiscoveryActive = false
    private var currentServiceRequest: WifiP2pDnsSdServiceRequest? = null
    private var isAutoConnecting = false
    private var lastAutoConnectAttempt = 0L
    private var autoConnectEnabled = false

    private val _groupCode = MutableStateFlow<String?>(null)
    val groupCode: StateFlow<String?> = _groupCode.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val discoveredServices: StateFlow<List<DiscoveredService>> = _discoveredServices.asStateFlow()

    private val _isServiceDiscovering = MutableStateFlow(false)
    val isServiceDiscovering: StateFlow<Boolean> = _isServiceDiscovering.asStateFlow()

    /**
     * Check if required WiFi Direct permissions are granted
     */
    private fun hasWifiDirectPermission(): Boolean {
        // On Android 13+, NEARBY_WIFI_DEVICES is required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        // On all versions, ACCESS_FINE_LOCATION is required for WiFi Direct
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission()
    private fun requestPeersIfPermitted() {
        if (!hasWifiDirectPermission()) {
            logW { "Missing permission to request peers" }
            return
        }
        manager.requestPeers(channel) { peers ->
            val peerList = peers.deviceList.toList()
            _availablePeers.value = peerList
            eventChannel.trySend(WiFiDirectEvent.PeersChanged(peerList))
            logD { "Peers changed: ${peerList.size} devices found" }
        }
    }

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _availablePeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val availablePeers: StateFlow<List<WifiP2pDevice>> = _availablePeers.asStateFlow()

    private val eventChannel = Channel<WiFiDirectEvent>(Channel.UNLIMITED)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        eventChannel.trySend(WiFiDirectEvent.WiFiP2pEnabled)
                        logD { "WiFi P2P enabled" }
                    } else {
                        eventChannel.trySend(WiFiDirectEvent.WiFiP2pDisabled)
                        logD { "WiFi P2P disabled" }
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeersIfPermitted()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    _connectionInfo.value = info
                    eventChannel.trySend(WiFiDirectEvent.ConnectionChanged(info))

                    if (info?.groupFormed == true) {
                        logD { "Group formed. Group Owner: ${info.isGroupOwner}" }
                        requestGroupInfo()
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    eventChannel.trySend(WiFiDirectEvent.DeviceChanged(device))
                    logD { "This device changed: ${device?.deviceName}" }
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun initialize() {
        if (!isReceiverRegistered) {
            context.registerReceiver(receiver, intentFilter)
            isReceiverRegistered = true
            logD { "WiFiDirectManager initialized" }
        } else {
            logD { "WiFiDirectManager already initialized" }
        }
    }

    fun cleanup() {
        try {
            if (isReceiverRegistered) {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
            }
            clearServiceRequests()
            clearLocalServices()
            stopDiscovery()
            disconnect()
        } catch (e: IllegalArgumentException) {
            logW { "Receiver was not registered" }
            isReceiverRegistered = false
        } catch (e: Exception) {
            logE({ "Error during cleanup" }, e)
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission() above
    fun startDiscovery() {
        if (!hasWifiDirectPermission()) {
            val message = "WiFi Direct permissions not granted"
            logE { message }
            eventChannel.trySend(WiFiDirectEvent.Error(message))
            return
        }

        manager.discoverPeers(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    _isDiscovering.value = true
                    logD { "Discovery started successfully" }
                }

                override fun onFailure(reason: Int) {
                    _isDiscovering.value = false
                    val message = "Discovery failed: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    fun stopDiscovery() {
        manager.stopPeerDiscovery(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    _isDiscovering.value = false
                    logD { "Discovery stopped successfully" }
                }

                override fun onFailure(reason: Int) {
                    logE { "Failed to stop discovery: ${getErrorMessage(reason)}" }
                }
            },
        )
    }

    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission() above
    fun connectToPeer(device: WifiP2pDevice) {
        if (!hasWifiDirectPermission()) {
            val message = "WiFi Direct permissions not granted"
            logE { message }
            eventChannel.trySend(WiFiDirectEvent.Error(message))
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        manager.connect(
            channel,
            config,
            object : ActionListener {
                override fun onSuccess() {
                    logD { "Connecting to ${device.deviceName}" }
                }

                override fun onFailure(reason: Int) {
                    val message = "Connection failed: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    fun disconnect() {
        manager.removeGroup(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    logD { "Group removed successfully" }
                }

                override fun onFailure(reason: Int) {
                    logE { "Failed to remove group: ${getErrorMessage(reason)}" }
                }
            },
        )
    }

    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission() above
    fun createGroup() {
        if (!hasWifiDirectPermission()) {
            val message = "WiFi Direct permissions not granted"
            logE { message }
            eventChannel.trySend(WiFiDirectEvent.Error(message))
            return
        }

        manager.createGroup(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    logD { "Group created successfully" }
                }

                override fun onFailure(reason: Int) {
                    val message = "Failed to create group: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    fun getAvailablePeers(): List<WifiP2pDevice> {
        return _availablePeers.value
    }

    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission() above
    fun requestGroupInfo() {
        if (!hasWifiDirectPermission()) {
            logW { "Missing permission to request group info" }
            return
        }

        manager.requestGroupInfo(channel) { group ->
            if (group != null) {
                val peers = group.clientList.map { client ->
                    PeerDevice(
                        deviceName = client.deviceName,
                        deviceAddress = client.deviceAddress,
                        isConnected = true,
                    )
                }.toMutableList()

                val connectionInfo = _connectionInfo.value
                val isGroupOwner = connectionInfo?.isGroupOwner ?: false

                // Add group owner if we're not the group owner
                if (!isGroupOwner && connectionInfo != null) {
                    peers.add(
                        0,
                        PeerDevice(
                            deviceName = "Group Owner",
                            deviceAddress = "",
                            isGroupOwner = true,
                            ipAddress = connectionInfo.groupOwnerAddress?.hostAddress,
                            isConnected = true,
                        ),
                    )
                }

                _connectedPeers.value = peers
                logD { "Group info updated: ${peers.size} connected peers, isGroupOwner: $isGroupOwner" }

                // Emit group info event
                eventChannel.trySend(WiFiDirectEvent.GroupInfoChanged(peers, isGroupOwner))
            }
        }
    }

    /**
     * Set the group code for service discovery filtering.
     * If a local service is already registered, it will be re-registered with the new group code.
     */
    fun setGroupCode(code: String?) {
        val previousCode = _groupCode.value
        _groupCode.value = code
        logD { "Group code set: $code (was: $previousCode)" }

        // Re-register local service if already registered with new group code
        if (isLocalServiceRegistered && code != previousCode) {
            unregisterLocalService()
            if (code != null) {
                registerLocalService(code)
            }
        }
    }

    /**
     * Get the current group code.
     */
    fun getGroupCode(): String? = _groupCode.value

    /**
     * Register a local WiFi Direct service with the group code in the TXT record.
     * This allows other devices to discover this device via service discovery
     * and filter by group code.
     */
    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission()
    fun registerLocalService(groupCode: String) {
        if (!hasWifiDirectPermission()) {
            val message = "WiFi Direct permissions not granted for service registration"
            logE { message }
            eventChannel.trySend(WiFiDirectEvent.Error(message))
            return
        }

        // Unregister existing service first if any
        if (isLocalServiceRegistered) {
            unregisterLocalService()
        }

        // Build TXT record with group code
        val txtRecord = mapOf(
            TXT_RECORD_GROUP_CODE_KEY to groupCode,
        )

        // Create DNS-SD service info
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            AppConfig.WiFiDirect.SERVICE_INSTANCE_NAME,
            AppConfig.WiFiDirect.SERVICE_TYPE,
            txtRecord,
        )

        manager.addLocalService(
            channel,
            serviceInfo,
            object : ActionListener {
                override fun onSuccess() {
                    isLocalServiceRegistered = true
                    currentServiceInfo = serviceInfo
                    _groupCode.value = groupCode
                    logD { "Local service registered with group code: $groupCode" }
                    eventChannel.trySend(WiFiDirectEvent.LocalServiceRegistered)
                }

                override fun onFailure(reason: Int) {
                    val message = "Failed to register local service: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    /**
     * Unregister the local WiFi Direct service.
     */
    fun unregisterLocalService() {
        if (!isLocalServiceRegistered) {
            logD { "No local service to unregister" }
            return
        }

        val serviceInfo = currentServiceInfo
        if (serviceInfo == null) {
            logW { "Local service marked as registered but serviceInfo is null" }
            isLocalServiceRegistered = false
            return
        }

        manager.removeLocalService(
            channel,
            serviceInfo,
            object : ActionListener {
                override fun onSuccess() {
                    isLocalServiceRegistered = false
                    currentServiceInfo = null
                    logD { "Local service unregistered" }
                    eventChannel.trySend(WiFiDirectEvent.LocalServiceUnregistered)
                }

                override fun onFailure(reason: Int) {
                    // Still mark as unregistered to avoid stuck state
                    isLocalServiceRegistered = false
                    currentServiceInfo = null
                    logE { "Failed to unregister local service: ${getErrorMessage(reason)}" }
                }
            },
        )
    }

    /**
     * Clear all registered local services.
     */
    fun clearLocalServices() {
        manager.clearLocalServices(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    isLocalServiceRegistered = false
                    currentServiceInfo = null
                    logD { "All local services cleared" }
                }

                override fun onFailure(reason: Int) {
                    isLocalServiceRegistered = false
                    currentServiceInfo = null
                    logE { "Failed to clear local services: ${getErrorMessage(reason)}" }
                }
            },
        )
    }

    /**
     * Check if a local service is currently registered.
     */
    fun isLocalServiceRegistered(): Boolean = isLocalServiceRegistered

    /**
     * Set up DNS-SD service discovery listeners.
     * These listeners receive callbacks when services with TXT records are discovered.
     */
    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission()
    private fun setupServiceDiscoveryListeners() {
        // Listener for TXT records - provides the group code from service record
        val txtListener = DnsSdTxtRecordListener { fullDomainName, txtRecordMap, srcDevice ->
            val groupCode = txtRecordMap[TXT_RECORD_GROUP_CODE_KEY]
            logD { "TXT record received: domain=$fullDomainName, groupCode=$groupCode, device=${srcDevice.deviceName}" }

            val service = DiscoveredService(
                deviceAddress = srcDevice.deviceAddress,
                instanceName = fullDomainName.substringBefore('.'),
                serviceType = fullDomainName.substringAfter('.'),
                groupCode = groupCode,
                device = srcDevice,
            )

            // Add to discovered services if not already present
            val currentServices = _discoveredServices.value.toMutableList()
            val existingIndex = currentServices.indexOfFirst { it.deviceAddress == service.deviceAddress }
            if (existingIndex >= 0) {
                currentServices[existingIndex] = service
            } else {
                currentServices.add(service)
            }
            _discoveredServices.value = currentServices

            // Emit general service discovered event
            eventChannel.trySend(WiFiDirectEvent.ServiceDiscovered(service))

            // Check if group code matches our current group code
            val ourGroupCode = _groupCode.value
            if (ourGroupCode != null && groupCode == ourGroupCode) {
                logD { "Found matching service! Group code: $groupCode, device: ${srcDevice.deviceName}" }
                eventChannel.trySend(WiFiDirectEvent.MatchingServiceDiscovered(service))
            }
        }

        // Listener for service records - provides device info
        val serviceListener = DnsSdServiceResponseListener { instanceName, serviceType, srcDevice ->
            logD { "Service discovered: $instanceName ($serviceType) from ${srcDevice.deviceName}" }
            // Request TXT record by triggering service discovery again if needed
            // The TXT listener will be called with the full record
        }

        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener)
        logD { "Service discovery listeners configured" }
    }

    /**
     * Start discovering WiFi Direct services.
     * This discovers services advertised by other devices and filters by group code.
     */
    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission()
    fun startServiceDiscovery() {
        if (!hasWifiDirectPermission()) {
            val message = "WiFi Direct permissions not granted for service discovery"
            logE { message }
            eventChannel.trySend(WiFiDirectEvent.Error(message))
            return
        }

        if (isServiceDiscoveryActive) {
            logD { "Service discovery already active" }
            return
        }

        // Clear any previously discovered services
        _discoveredServices.value = emptyList()

        // Set up the listeners first
        setupServiceDiscoveryListeners()

        // Create a service request for DNS-SD services
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        currentServiceRequest = serviceRequest

        // Add the service request
        manager.addServiceRequest(
            channel,
            serviceRequest,
            object : ActionListener {
                override fun onSuccess() {
                    logD { "Service request added successfully" }
                    // Now start the actual discovery
                    discoverServices()
                }

                override fun onFailure(reason: Int) {
                    val message = "Failed to add service request: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    /**
     * Initiate service discovery after service request is added.
     */
    @SuppressLint("MissingPermission") // Permission is checked in caller
    private fun discoverServices() {
        manager.discoverServices(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    isServiceDiscoveryActive = true
                    _isServiceDiscovering.value = true
                    logD { "Service discovery started successfully" }
                    eventChannel.trySend(WiFiDirectEvent.ServiceDiscoveryStarted)
                }

                override fun onFailure(reason: Int) {
                    isServiceDiscoveryActive = false
                    _isServiceDiscovering.value = false
                    val message = "Service discovery failed: ${getErrorMessage(reason)}"
                    logE { message }
                    eventChannel.trySend(WiFiDirectEvent.Error(message))
                }
            },
        )
    }

    /**
     * Stop service discovery.
     */
    fun stopServiceDiscovery() {
        if (!isServiceDiscoveryActive) {
            logD { "Service discovery not active" }
            return
        }

        val serviceRequest = currentServiceRequest
        if (serviceRequest != null) {
            manager.removeServiceRequest(
                channel,
                serviceRequest,
                object : ActionListener {
                    override fun onSuccess() {
                        isServiceDiscoveryActive = false
                        _isServiceDiscovering.value = false
                        currentServiceRequest = null
                        logD { "Service discovery stopped" }
                        eventChannel.trySend(WiFiDirectEvent.ServiceDiscoveryStopped)
                    }

                    override fun onFailure(reason: Int) {
                        // Still mark as inactive to avoid stuck state
                        isServiceDiscoveryActive = false
                        _isServiceDiscovering.value = false
                        currentServiceRequest = null
                        logE { "Failed to stop service discovery: ${getErrorMessage(reason)}" }
                    }
                },
            )
        } else {
            isServiceDiscoveryActive = false
            _isServiceDiscovering.value = false
        }
    }

    /**
     * Clear all service requests.
     */
    fun clearServiceRequests() {
        manager.clearServiceRequests(
            channel,
            object : ActionListener {
                override fun onSuccess() {
                    isServiceDiscoveryActive = false
                    _isServiceDiscovering.value = false
                    currentServiceRequest = null
                    _discoveredServices.value = emptyList()
                    logD { "All service requests cleared" }
                }

                override fun onFailure(reason: Int) {
                    isServiceDiscoveryActive = false
                    _isServiceDiscovering.value = false
                    currentServiceRequest = null
                    logE { "Failed to clear service requests: ${getErrorMessage(reason)}" }
                }
            },
        )
    }

    /**
     * Check if service discovery is currently active.
     */
    fun isServiceDiscoveryActive(): Boolean = isServiceDiscoveryActive

    /**
     * Get discovered services that match the current group code.
     */
    fun getMatchingServices(): List<DiscoveredService> {
        val ourGroupCode = _groupCode.value ?: return emptyList()
        return _discoveredServices.value.filter { it.groupCode == ourGroupCode }
    }

    /**
     * Enable or disable automatic connection to matching peers.
     * When enabled, the manager will automatically attempt to connect
     * to peers with matching group codes when discovered.
     */
    fun setAutoConnectEnabled(enabled: Boolean) {
        autoConnectEnabled = enabled
        logD { "Auto-connect ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if auto-connect is enabled.
     */
    fun isAutoConnectEnabled(): Boolean = autoConnectEnabled

    /**
     * Attempt to auto-connect to a matching service.
     * This is called internally when a MatchingServiceDiscovered event occurs,
     * or can be called externally to trigger connection to a known matching service.
     *
     * @param service The discovered service to connect to
     * @return true if connection attempt was initiated, false if connection was skipped
     */
    @SuppressLint("MissingPermission") // Permission is checked in hasWifiDirectPermission()
    fun autoConnectToMatchingPeer(service: DiscoveredService): Boolean {
        val validationResult = validateAutoConnectConditions(service)
        if (!validationResult.isValid) {
            validationResult.failureReason?.let { reason ->
                eventChannel.trySend(WiFiDirectEvent.AutoConnectionFailed(reason))
            }
            return false
        }

        val device = service.device ?: return false // Should never happen after validation

        isAutoConnecting = true
        lastAutoConnectAttempt = System.currentTimeMillis()

        logD { "Auto-connecting to matching peer: ${device.deviceName} (${device.deviceAddress})" }
        eventChannel.trySend(WiFiDirectEvent.AutoConnectionStarted(service))

        // Initiate the connection
        connectToPeer(device)

        return true
    }

    /**
     * Validates all conditions required for auto-connection.
     * @return ValidationResult indicating if connection should proceed
     */
    @Suppress("ReturnCount") // Complex validation logic requires multiple early returns for clarity
    private fun validateAutoConnectConditions(service: DiscoveredService): AutoConnectValidationResult {
        // Check basic state conditions
        val stateValidation = validateAutoConnectState(service)
        if (!stateValidation.isValid) {
            return stateValidation
        }

        // Check service and group code conditions
        return validateServiceConditions(service)
    }

    /**
     * Validates auto-connect state conditions (enabled, in-progress, cooldown, already connected).
     */
    private fun validateAutoConnectState(service: DiscoveredService): AutoConnectValidationResult {
        if (!autoConnectEnabled) {
            logD { "Auto-connect disabled, skipping connection to ${service.deviceAddress}" }
            return AutoConnectValidationResult(isValid = false, failureReason = null)
        }

        if (isAutoConnecting) {
            logW { "Auto-connection already in progress" }
            return AutoConnectValidationResult(isValid = false, failureReason = null)
        }

        val now = System.currentTimeMillis()
        val isInCooldown = now - lastAutoConnectAttempt < AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS
        if (isInCooldown) {
            val waitTime = (AppConfig.WiFiDirect.CONNECTION_COOLDOWN_MS - (now - lastAutoConnectAttempt)) / 1000
            logW { "Auto-connection attempt too soon, wait $waitTime seconds" }
            return AutoConnectValidationResult(isValid = false, failureReason = null)
        }

        val isAlreadyConnected = _connectionInfo.value?.groupFormed == true
        return if (isAlreadyConnected) {
            logD { "Already connected to a WiFi Direct group, skipping auto-connect" }
            AutoConnectValidationResult(isValid = false, failureReason = null)
        } else {
            AutoConnectValidationResult(isValid = true, failureReason = null)
        }
    }

    /**
     * Validates service conditions (device info, group code match).
     */
    private fun validateServiceConditions(service: DiscoveredService): AutoConnectValidationResult {
        if (service.device == null) {
            logW { "Cannot auto-connect: service has no device info" }
            return AutoConnectValidationResult(isValid = false, failureReason = "No device info available")
        }

        val ourGroupCode = _groupCode.value
        val groupCodeMatches = ourGroupCode != null && service.groupCode == ourGroupCode
        return if (!groupCodeMatches) {
            logW { "Group code mismatch, skipping auto-connect. Ours: $ourGroupCode, theirs: ${service.groupCode}" }
            AutoConnectValidationResult(isValid = false, failureReason = "Group code mismatch")
        } else {
            AutoConnectValidationResult(isValid = true, failureReason = null)
        }
    }

    /**
     * Result of auto-connect validation check.
     */
    private data class AutoConnectValidationResult(
        val isValid: Boolean,
        val failureReason: String?,
    )

    /**
     * Reset auto-connection state.
     * Call this when a connection attempt completes (success or failure).
     */
    fun resetAutoConnectState() {
        isAutoConnecting = false
        logD { "Auto-connect state reset" }
    }

    /**
     * Check if an auto-connection attempt is currently in progress.
     */
    fun isAutoConnecting(): Boolean = isAutoConnecting

    /**
     * Attempt to connect to the first available matching service.
     * Useful for initiating connection when services have already been discovered.
     *
     * @return true if connection attempt was initiated, false otherwise
     */
    fun connectToFirstMatchingService(): Boolean {
        val matchingServices = getMatchingServices()
        if (matchingServices.isEmpty()) {
            logD { "No matching services available for connection" }
            return false
        }

        // Try to connect to the first service with a valid device
        for (service in matchingServices) {
            if (service.device != null) {
                return autoConnectToMatchingPeer(service)
            }
        }

        logW { "No matching services with device info available" }
        return false
    }

    private fun getErrorMessage(reason: Int): String {
        return when (reason) {
            ERROR -> "Internal error"
            P2P_UNSUPPORTED -> "P2P unsupported"
            BUSY -> "Device busy"
            NO_SERVICE_REQUESTS -> "No service requests"
            else -> "Unknown error ($reason)"
        }
    }

    fun getEvents() = eventChannel.receiveAsFlow()
}
