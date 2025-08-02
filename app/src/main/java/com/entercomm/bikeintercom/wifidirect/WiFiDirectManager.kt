package com.entercomm.bikeintercom.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class PeerDevice(
    val deviceName: String,
    val deviceAddress: String,
    val isGroupOwner: Boolean = false,
    val ipAddress: String? = null,
    val isConnected: Boolean = false
)

sealed class WiFiDirectEvent {
    object WiFiP2pEnabled : WiFiDirectEvent()
    object WiFiP2pDisabled : WiFiDirectEvent()
    data class PeersChanged(val peers: List<WifiP2pDevice>) : WiFiDirectEvent()
    data class ConnectionChanged(val info: WifiP2pInfo?) : WiFiDirectEvent()
    data class DeviceChanged(val device: WifiP2pDevice?) : WiFiDirectEvent()
    data class Error(val message: String) : WiFiDirectEvent()
}

class WiFiDirectManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: Channel
) {
    
    companion object {
        private const val TAG = "WiFiDirectManager"
        private const val SERVICE_TYPE = "_entercomm._tcp"
        private const val SERVICE_INSTANCE = "EnterCommBikeIntercom"
    }
    
    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()
    
    private val eventChannel = Channel<WiFiDirectEvent>(Channel.UNLIMITED)
    
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        eventChannel.trySend(WiFiDirectEvent.WiFiP2pEnabled)
                        Log.d(TAG, "WiFi P2P enabled")
                    } else {
                        eventChannel.trySend(WiFiDirectEvent.WiFiP2pDisabled)
                        Log.d(TAG, "WiFi P2P disabled")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers ->
                        eventChannel.trySend(WiFiDirectEvent.PeersChanged(peers.deviceList.toList()))
                        Log.d(TAG, "Peers changed: ${peers.deviceList.size} devices found")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    _connectionInfo.value = info
                    eventChannel.trySend(WiFiDirectEvent.ConnectionChanged(info))
                    
                    if (info?.groupFormed == true) {
                        Log.d(TAG, "Group formed. Group Owner: ${info.isGroupOwner}")
                        requestGroupInfo()
                    }
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    eventChannel.trySend(WiFiDirectEvent.DeviceChanged(device))
                    Log.d(TAG, "This device changed: ${device?.deviceName}")
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
        context.registerReceiver(receiver, intentFilter)
        Log.d(TAG, "WiFiDirectManager initialized")
    }
    
    fun cleanup() {
        try {
            context.unregisterReceiver(receiver)
            stopDiscovery()
            disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    fun startDiscovery() {
        manager.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = true
                Log.d(TAG, "Discovery started successfully")
            }
            
            override fun onFailure(reason: Int) {
                _isDiscovering.value = false
                val message = "Discovery failed: ${getErrorMessage(reason)}"
                Log.e(TAG, message)
                eventChannel.trySend(WiFiDirectEvent.Error(message))
            }
        })
    }
    
    fun stopDiscovery() {
        manager.stopPeerDiscovery(channel, object : ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = false
                Log.d(TAG, "Discovery stopped successfully")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to stop discovery: ${getErrorMessage(reason)}")
            }
        })
    }
    
    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        
        manager.connect(channel, config, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connecting to ${device.deviceName}")
            }
            
            override fun onFailure(reason: Int) {
                val message = "Connection failed: ${getErrorMessage(reason)}"
                Log.e(TAG, message)
                eventChannel.trySend(WiFiDirectEvent.Error(message))
            }
        })
    }
    
    fun disconnect() {
        manager.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed successfully")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove group: ${getErrorMessage(reason)}")
            }
        })
    }
    
    fun createGroup() {
        manager.createGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group created successfully")
            }
            
            override fun onFailure(reason: Int) {
                val message = "Failed to create group: ${getErrorMessage(reason)}"
                Log.e(TAG, message)
                eventChannel.trySend(WiFiDirectEvent.Error(message))
            }
        })
    }
    
    private fun requestGroupInfo() {
        manager.requestGroupInfo(channel) { group ->
            if (group != null) {
                val peers = group.clientList.map { client ->
                    PeerDevice(
                        deviceName = client.deviceName,
                        deviceAddress = client.deviceAddress,
                        isConnected = true
                    )
                }.toMutableList()
                
                // Add group owner if we're not the group owner
                val connectionInfo = _connectionInfo.value
                if (connectionInfo?.isGroupOwner == false) {
                    peers.add(0, PeerDevice(
                        deviceName = "Group Owner",
                        deviceAddress = "",
                        isGroupOwner = true,
                        ipAddress = connectionInfo.groupOwnerAddress?.hostAddress,
                        isConnected = true
                    ))
                }
                
                _connectedPeers.value = peers
                Log.d(TAG, "Group info updated: ${peers.size} connected peers")
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
    
    suspend fun getEvents(): kotlinx.coroutines.channels.ReceiveChannel<WiFiDirectEvent> {
        return eventChannel
    }
}