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
import android.os.Build
import androidx.core.content.ContextCompat
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

sealed class WiFiDirectEvent {
    object WiFiP2pEnabled : WiFiDirectEvent()
    object WiFiP2pDisabled : WiFiDirectEvent()
    data class PeersChanged(val peers: List<WifiP2pDevice>) : WiFiDirectEvent()
    data class ConnectionChanged(val info: WifiP2pInfo?) : WiFiDirectEvent()
    data class DeviceChanged(val device: WifiP2pDevice?) : WiFiDirectEvent()
    data class GroupInfoChanged(val clients: List<PeerDevice>, val isGroupOwner: Boolean) : WiFiDirectEvent()
    data class Error(val message: String) : WiFiDirectEvent()
}

class WiFiDirectManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
) {

    companion object {
        private const val SERVICE_TYPE = "_entercomm._tcp"
        private const val SERVICE_INSTANCE = "EnterCommBikeIntercom"
    }

    private var isReceiverRegistered = false

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
