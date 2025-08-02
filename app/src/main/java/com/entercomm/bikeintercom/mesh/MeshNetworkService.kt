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
        Log.d(TAG, "MeshNetworkService created")
        
        createNotificationChannel()
        initializeManagers()
        setupMeshCallbacks()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
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
            // Initialize WiFi Direct Manager
            val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val channel = wifiP2pManager.initialize(this, mainLooper, null)
            wifiDirectManager = WiFiDirectManager(this, wifiP2pManager, channel)
            
            // Initialize Mesh Network Manager
            meshNetworkManager = MeshNetworkManager(nodeId, deviceName)
            
            // Initialize Audio Manager
            audioManager = AudioManager(this) { audioData ->
                // Send audio data through mesh network
                meshNetworkManager.sendAudioData(audioData)
            }
            
            // Initialize audio manager
            audioManager.initialize()
            
            Log.d(TAG, "All managers initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize managers", e)
            onError?.invoke("Failed to initialize: ${e.message}")
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
                
                // Start foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                
                // Initialize WiFi Direct
                wifiDirectManager.initialize()
                
                // Start mesh network
                meshNetworkManager.startMeshNetwork()
                
                // Start device discovery
                wifiDirectManager.startDiscovery()
                
                // Monitor WiFi Direct events
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
    
    fun stopMeshNetwork() {
        scope.launch {
            try {
                Log.d(TAG, "Stopping mesh network...")
                
                // Stop audio recording
                audioManager.stopRecording()
                
                // Stop mesh network
                meshNetworkManager.stopMeshNetwork()
                
                // Stop WiFi Direct
                wifiDirectManager.stopDiscovery()
                wifiDirectManager.disconnect()
                
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
        if (_serviceState.value.isRunning) {
            audioManager.startRecording()
            Log.d(TAG, "Audio recording started")
        } else {
            onError?.invoke("Cannot start recording: Mesh network not active")
        }
    }
    
    fun stopRecording() {
        audioManager.stopRecording()
        Log.d(TAG, "Audio recording stopped")
    }
    
    fun toggleMute() {
        val currentlyMuted = audioManager.isMuted.value
        audioManager.setMuted(!currentlyMuted)
        Log.d(TAG, "Audio ${if (!currentlyMuted) "muted" else "unmuted"}")
    }
    
    fun connectToDevice(deviceAddress: String) {
        // This would be called from UI when user selects a device to connect to
        scope.launch {
            try {
                // Add as direct connection to mesh network
                meshNetworkManager.addDirectConnection(deviceAddress)
                Log.d(TAG, "Connecting to device: $deviceAddress")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to device", e)
                onError?.invoke("Failed to connect: ${e.message}")
            }
        }
    }
    
    private suspend fun monitorWiFiDirectEvents() {
        for (event in wifiDirectManager.getEvents()) {
            when (event) {
                is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.PeersChanged -> {
                    event.peers.forEach { peer ->
                        onDeviceDiscovered?.invoke(peer.deviceName, peer.deviceAddress)
                    }
                }
                is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.ConnectionChanged -> {
                    event.info?.let { info ->
                        if (info.groupFormed) {
                            val groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            if (groupOwnerAddress != null && !info.isGroupOwner) {
                                // We're a client, connect to group owner
                                meshNetworkManager.addDirectConnection(groupOwnerAddress)
                                onConnectionEstablished?.invoke(groupOwnerAddress)
                            }
                        }
                    }
                }
                is com.entercomm.bikeintercom.wifidirect.WiFiDirectEvent.Error -> {
                    onError?.invoke("WiFi Direct error: ${event.message}")
                }
                else -> {
                    // Handle other events as needed
                }
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
            audioManager.cleanup()
            meshNetworkManager.stopMeshNetwork()
            wifiDirectManager.cleanup()
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike Intercom Active")
            .setContentText("${state.networkStatus} • ${if (state.isRecording) "Recording" else "Standby"}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_mic_off,
                if (audioManager.isMuted.value) "Unmute" else "Mute",
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