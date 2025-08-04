package com.entercomm.bikeintercom.mesh

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.entercomm.bikeintercom.R
import com.entercomm.bikeintercom.audio.AudioManager
import com.entercomm.bikeintercom.wifidirect.WiFiDirectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

data class ServiceState(
    val isRunning: Boolean = false,
    val connectedDevices: Int = 0,
    val isRecording: Boolean = false,
    val networkStatus: String = "Disconnected"
)

class MeshNetworkService : Service() {
    
    companion object {
        private const val TAG = "MeshNetworkService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mesh_network_channel"
        
        const val ACTION_START_MESH = "START_MESH"
        const val ACTION_STOP_MESH = "STOP_MESH"
        const val ACTION_START_RECORDING = "START_RECORDING"
        const val ACTION_STOP_RECORDING = "STOP_RECORDING"
        const val ACTION_TOGGLE_MUTE = "TOGGLE_MUTE"
    }
    
    private val binder = MeshNetworkBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Core managers
    private lateinit var wifiDirectManager: WiFiDirectManager
    private lateinit var meshNetworkManager: MeshNetworkManager
    private lateinit var audioManager: AudioManager
    
    // Service state
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private val nodeId = "node-${UUID.randomUUID().toString().take(8)}"
    private val deviceName = "BikeIntercom-${Build.MODEL}"
    
    // Callbacks for UI updates
    var onStateChanged: ((ServiceState) -> Unit)? = null
    var onDeviceDiscovered: ((String, String) -> Unit)? = null
    var onConnectionEstablished: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    inner class MeshNetworkBinder : Binder() {
        fun getService(): MeshNetworkService = this@MeshNetworkService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MeshNetworkService onCreate() called")
        
        try {
            createNotificationChannel()
            Log.d(TAG, "Notification channel created")
            
            // Try to initialize managers, but don't fail the service if it doesn't work
            try {
                initializeManagers()
                Log.d(TAG, "Managers initialized")
                
                setupMeshCallbacks()
                Log.d(TAG, "Mesh callbacks setup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Warning: Manager initialization failed, service will work in limited mode", e)
                // Don't throw here - let the service bind even if managers fail
            }
            
            Log.d(TAG, "MeshNetworkService created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during service creation", e)
            // Only throw for critical errors like notification channel
            throw e
        }
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind() called")
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MESH -> startMeshNetwork()
            ACTION_STOP_MESH -> stopMeshNetwork()
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_MUTE -> toggleMute()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanupManagers()
        scope.cancel()
        Log.d(TAG, "MeshNetworkService destroyed")
    }
    
    private fun initializeManagers() {
        try {
            Log.d(TAG, "Starting manager initialization...")
            
            // Initialize WiFi Direct Manager
            try {
                Log.d(TAG, "Initializing WiFi Direct Manager...")
                val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                if (wifiP2pManager == null) {
                    Log.e(TAG, "WiFi P2P Manager not available")
                    return
                }
                
                val channel = wifiP2pManager.initialize(this, mainLooper, null)
                if (channel == null) {
                    Log.e(TAG, "Failed to initialize WiFi P2P channel")
                    return
                }
                
                wifiDirectManager = WiFiDirectManager(this, wifiP2pManager, channel)
                Log.d(TAG, "WiFi Direct Manager initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WiFi Direct Manager", e)
                return
            }
            
            // Initialize Mesh Network Manager
            try {
                Log.d(TAG, "Initializing Mesh Network Manager...")
                meshNetworkManager = MeshNetworkManager(nodeId, deviceName)
                Log.d(TAG, "Mesh Network Manager initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Mesh Network Manager", e)
                return
            }
            
            // Initialize Audio Manager
            try {
                Log.d(TAG, "Initializing Audio Manager...")
                audioManager = AudioManager(this) { audioData ->
                    // Send audio data through mesh network
                    try {
                        meshNetworkManager.sendAudioData(audioData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending audio data", e)
                    }
                }
                
                // Initialize audio manager
                audioManager.initialize()
                Log.d(TAG, "Audio Manager initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Audio Manager", e)
                // Continue without audio manager - the app should still work for basic mesh networking
            }
            
            Log.d(TAG, "Manager initialization completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during manager initialization", e)
            onError?.invoke("Failed to initialize: ${e.message}")
            // Don't throw here - let the service continue with limited functionality
        }
    }
    
    private fun setupMeshCallbacks() {
        // Audio data callback
        meshNetworkManager.onAudioDataReceived = { audioData, sourceId ->
            audioManager.playAudioData(audioData, sourceId)
        }
        
        // Control message callback
        meshNetworkManager.onControlMessageReceived = { message, sourceId ->
            handleControlMessage(message, sourceId)
        }
        
        // Monitor state changes
        scope.launch {
            meshNetworkManager.connectedNodes.collect { nodes ->
                updateServiceState {
                    copy(
                        connectedDevices = nodes.size,
                        networkStatus = if (nodes.isNotEmpty()) "Connected (${nodes.size} devices)" else "Searching..."
                    )
                }
            }
        }
        
        scope.launch {
            audioManager.isRecording.collect { recording ->
                updateServiceState {
                    copy(isRecording = recording)
                }
            }
        }
        
        scope.launch {
            meshNetworkManager.isActive.collect { active ->
                updateServiceState {
                    copy(
                        isRunning = active,
                        networkStatus = if (active) "Active" else "Stopped"
                    )
                }
            }
        }
    }
    
    fun startMeshNetwork() {
        scope.launch {
            try {
                Log.d(TAG, "Starting mesh network...")
                
                // Check if managers are initialized
                if (!::wifiDirectManager.isInitialized || !::meshNetworkManager.isInitialized) {
                    Log.e(TAG, "Managers not initialized, cannot start mesh network")
                    onError?.invoke("Service not properly initialized")
                    return@launch
                }
                
                // Start foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                
                // Initialize WiFi Direct
                wifiDirectManager.initialize()
                
                // Start mesh network
                meshNetworkManager.startMeshNetwork()
                
                // Start device discovery
                wifiDirectManager.startDiscovery()
                
                // Monitor WiFi Direct events (with restart capability)
                launch { monitorWiFiDirectEvents() }
                
                updateServiceState {
                    copy(
                        isRunning = true,
                        networkStatus = "Starting..."
                    )
                }
                
                Log.d(TAG, "Mesh network started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh network", e)
                onError?.invoke("Failed to start mesh network: ${e.message}")
            }
        }
    }
    
    // Helper function for retry logic
    private suspend fun <T> retry(
        maxAttempts: Int = 3,
        delayMs: Long = 1000,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1}/$maxAttempts failed", e)
                if (attempt < maxAttempts - 1) {
                    delay(delayMs)
                }
            }
        }
        throw lastException ?: Exception("All retry attempts failed")
    }
    
    fun stopMeshNetwork() {
        scope.launch {
            try {
                Log.d(TAG, "Stopping mesh network...")
                
                // Stop audio recording
                if (::audioManager.isInitialized) {
                    audioManager.stopRecording()
                }
                
                // Stop mesh network
                if (::meshNetworkManager.isInitialized) {
                    meshNetworkManager.stopMeshNetwork()
                }
                
                // Stop WiFi Direct
                if (::wifiDirectManager.isInitialized) {
                    wifiDirectManager.stopDiscovery()
                    wifiDirectManager.disconnect()
                }
                
                updateServiceState {
                    ServiceState() // Reset to default state
                }
                
                // Stop foreground service
                stopForeground(true)
                
                Log.d(TAG, "Mesh network stopped")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mesh network", e)
            }
        }
    }
    
    fun startRecording() {
        if (_serviceState.value.isRunning && ::audioManager.isInitialized) {
            audioManager.startRecording()
            Log.d(TAG, "Audio recording started")
        } else {
            val message = if (!_serviceState.value.isRunning) "Cannot start recording: Mesh network not active" else "Audio manager not initialized"
            onError?.invoke(message)
            Log.w(TAG, message)
        }
    }
    
    fun stopRecording() {
        if (::audioManager.isInitialized) {
            audioManager.stopRecording()
            Log.d(TAG, "Audio recording stopped")
        } else {
            Log.w(TAG, "Audio manager not initialized, cannot stop recording")
        }
    }
    
    fun toggleMute() {
        if (::audioManager.isInitialized) {
            val currentlyMuted = audioManager.isMuted.value
            audioManager.setMuted(!currentlyMuted)
            Log.d(TAG, "Audio ${if (!currentlyMuted) "muted" else "unmuted"}")
        } else {
            Log.w(TAG, "Audio manager not initialized, cannot toggle mute")
        }
    }
    
    private var isConnecting = false
    private var lastConnectionAttempt = 0L
    private val CONNECTION_COOLDOWN = 5000L // 5 seconds
    
    fun connectToDevice(deviceAddress: String) {
        // Prevent multiple simultaneous connection attempts
        val now = System.currentTimeMillis()
        if (isConnecting) {
            Log.w(TAG, "Connection already in progress, ignoring duplicate request")
            onError?.invoke("Connection already in progress")
            return
        }
        
        if (now - lastConnectionAttempt < CONNECTION_COOLDOWN) {
            Log.w(TAG, "Connection attempt too soon, please wait ${(CONNECTION_COOLDOWN - (now - lastConnectionAttempt)) / 1000} seconds")
            onError?.invoke("Please wait before trying again")
            return
        }
        
        scope.launch {
            try {
                isConnecting = true
                lastConnectionAttempt = now
                
                Log.d(TAG, "Starting connection to device $deviceAddress")
                
                // Check if we're already connected to this device
                val connectionInfo = wifiDirectManager.connectionInfo.value
                if (connectionInfo?.groupFormed == true) {
                    Log.w(TAG, "Already connected to a WiFi Direct group, cannot connect to another device")
                    onError?.invoke("Already connected to another device")
                    return@launch
                }
                
                // First, find the device in the peers list and initiate WiFi Direct connection
                val availablePeers = wifiDirectManager.getAvailablePeers()
                val targetDevice = availablePeers.find { it.deviceAddress == deviceAddress }
                
                if (targetDevice != null) {
                    Log.d(TAG, "Initiating WiFi Direct connection to ${targetDevice.deviceName} (${targetDevice.deviceAddress})")
                    wifiDirectManager.connectToPeer(targetDevice)
                    
                    // Set a timeout to reset the connecting flag
                    launch {
                        delay(30000) // 30 second timeout
                        if (isConnecting) {
                            Log.w(TAG, "Connection timeout, resetting connection state")
                            isConnecting = false
                            onError?.invoke("Connection timeout")
                        }
                    }
                } else {
                    Log.e(TAG, "Device $deviceAddress not found in available peers")
                    onError?.invoke("Device not available for connection")
                    isConnecting = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to device", e)
                onError?.invoke("Failed to connect: ${e.message}")
                isConnecting = false
            }
        }
    }
    
    private suspend fun monitorWiFiDirectEvents() {
        try {
            Log.d(TAG, "Starting WiFi Direct event monitoring...")
            wifiDirectManager.getEvents().collect { event ->
                try {
                    Log.d(TAG, "Received WiFi Direct event: ${event::class.simpleName}")
                    when (event) {
                        is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.PeersChanged -> {
                            Log.d(TAG, "Peers changed: ${event.peers.size} peers discovered")
                            event.peers.forEach { peer ->
                                Log.d(TAG, "Discovered peer: ${peer.deviceName} (${peer.deviceAddress})")
                                onDeviceDiscovered?.invoke(peer.deviceName, peer.deviceAddress)
                            }
                        }
                        is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.ConnectionChanged -> {
                            Log.d(TAG, "WiFi Direct connection changed: ${event.info}")
                            event.info?.let { info ->
                                Log.d(TAG, "Group formed: ${info.groupFormed}, Is group owner: ${info.isGroupOwner}, Group owner address: ${info.groupOwnerAddress?.hostAddress}")
                                if (info.groupFormed) {
                                    // Reset connecting flag when connection is successful
                                    isConnecting = false
                                    
                                    val groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                                    if (!info.isGroupOwner) {
                                        // We're a client, connect to group owner
                                        val groupOwnerIP = groupOwnerAddress ?: "192.168.49.1"
                                        Log.d(TAG, "CLIENT: Attempting to connect to group owner mesh network at $groupOwnerIP")
                                        
                                        // Add retry logic for mesh network connection
                                        retry(maxAttempts = 3, delayMs = 2000) {
                                            Log.d(TAG, "CLIENT: Adding direct connection to mesh network at $groupOwnerIP")
                                            meshNetworkManager.addDirectConnection(groupOwnerIP, MeshNetworkManager.DISCOVERY_PORT)
                                            
                                            // Verify connection after a short delay
                                            delay(3000)
                                            val connectedNodes = meshNetworkManager.connectedNodes.value
                                            if (connectedNodes.isEmpty()) {
                                                Log.w(TAG, "CLIENT: No mesh nodes connected after 3 seconds, retrying...")
                                                throw Exception("Mesh connection not established")
                                            } else {
                                                Log.d(TAG, "CLIENT: Mesh connection established successfully, ${connectedNodes.size} nodes connected")
                                            }
                                        }
                                        
                                        onConnectionEstablished?.invoke(groupOwnerIP)
                                    } else {
                                        // We're the group owner
                                        val ourIP = groupOwnerAddress ?: "192.168.49.1"
                                        Log.d(TAG, "GROUP OWNER: We are group owner at $ourIP, mesh network ready for client connections")
                                        Log.d(TAG, "GROUP OWNER: Mesh network is listening on port ${MeshNetworkManager.DISCOVERY_PORT} for client discovery messages")
                                        wifiDirectManager.requestGroupInfo()
                                    }
                                } else {
                                    Log.d(TAG, "WiFi Direct group disbanded")
                                    // Reset connecting flag when group is disbanded
                                    isConnecting = false
                                }
                            }
                        }
                        is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.GroupInfoChanged -> {
                            Log.d(TAG, "Group info changed: ${event.clients.size} clients, isGroupOwner: ${event.isGroupOwner}")
                            if (event.isGroupOwner) {
                                Log.d(TAG, "GROUP OWNER: Waiting for mesh network discovery from ${event.clients.size} clients")
                                event.clients.forEach { client ->
                                    Log.d(TAG, "GROUP OWNER: Client connected: ${client.deviceName} (${client.deviceAddress})")
                                }
                            }
                        }
                        is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.Error -> {
                            Log.e(TAG, "WiFi Direct error: ${event.message}")
                            onError?.invoke("WiFi Direct error: ${event.message}")
                        }
                        else -> {
                            Log.d(TAG, "Unhandled WiFi Direct event: ${event::class.simpleName}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing WiFi Direct event: ${event::class.simpleName}", e)
                    // Don't let event processing errors stop the monitoring
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in WiFi Direct event monitoring", e)
            onError?.invoke("WiFi Direct monitoring failed: ${e.message}")
            // Try to restart monitoring after a delay
            delay(5000)
            if (_serviceState.value.isRunning) {
                Log.d(TAG, "Attempting to restart WiFi Direct event monitoring...")
                scope.launch { monitorWiFiDirectEvents() }
            }
        }
    }
    
    private fun handleControlMessage(message: String, sourceId: String) {
        // Handle control messages like mute requests, etc.
        when (message) {
            "mute_request" -> {
                // Handle mute request from another device
                Log.d(TAG, "Received mute request from $sourceId")
            }
            "status_request" -> {
                // Send status back to requesting device
                Log.d(TAG, "Received status request from $sourceId")
            }
        }
    }
    
    private fun updateServiceState(update: ServiceState.() -> ServiceState) {
        val newState = _serviceState.value.update()
        _serviceState.value = newState
        onStateChanged?.invoke(newState)
        
        // Update notification
        if (newState.isRunning) {
            val notification = createNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun cleanupManagers() {
        try {
            if (::audioManager.isInitialized) {
                audioManager.cleanup()
                Log.d(TAG, "Audio manager cleaned up")
            }
            if (::meshNetworkManager.isInitialized) {
                meshNetworkManager.stopMeshNetwork()
                Log.d(TAG, "Mesh network manager cleaned up")
            }
            if (::wifiDirectManager.isInitialized) {
                wifiDirectManager.cleanup()
                Log.d(TAG, "WiFi Direct manager cleaned up")
            }
            Log.d(TAG, "All managers cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bike Intercom",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mesh network communication for bike intercom"
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val state = _serviceState.value
        
        val stopIntent = Intent(this, MeshNetworkService::class.java).apply {
            action = ACTION_STOP_MESH
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val muteIntent = Intent(this, MeshNetworkService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val muteText = if (::audioManager.isInitialized && audioManager.isMuted.value) "Unmute" else "Mute"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Intercom Active")
            .setContentText("${state.networkStatus} • ${if (state.isRecording) "Recording" else "Standby"}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_mic_off,
                muteText,
                mutePendingIntent
            )
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
}