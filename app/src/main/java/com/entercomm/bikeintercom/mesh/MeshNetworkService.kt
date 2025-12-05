package com.entercomm.bikeintercom.mesh

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.entercomm.bikeintercom.audio.AudioManager
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.location.LocationManager
import com.entercomm.bikeintercom.service.ConnectionCoordinator
import com.entercomm.bikeintercom.service.ConnectionEvent
import com.entercomm.bikeintercom.service.NotificationHelper
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
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
    val networkStatus: String = "Disconnected",
)

class MeshNetworkService : Service() {

    companion object {
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
    private lateinit var groupManager: GroupManager
    private lateinit var locationManager: LocationManager

    // Extracted components
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var connectionCoordinator: ConnectionCoordinator

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
        android.util.Log.d("MeshNetworkService", "onCreate() called")

        try {
            // Initialize notification helper (handles channel creation)
            notificationHelper = NotificationHelper(this)
            android.util.Log.d("MeshNetworkService", "Notification helper initialized")

            // Try to initialize managers, but don't fail the service if it doesn't work
            try {
                initializeManagers()
                android.util.Log.d("MeshNetworkService", "Managers initialized")

                // Only initialize connection coordinator if managers are ready
                if (::wifiDirectManager.isInitialized && ::meshNetworkManager.isInitialized) {
                    connectionCoordinator = ConnectionCoordinator(
                        wifiDirectManager,
                        meshNetworkManager,
                        scope,
                    )
                    setupConnectionCoordinatorCallbacks()
                    android.util.Log.d("MeshNetworkService", "Connection coordinator initialized")

                    setupMeshCallbacks()
                    android.util.Log.d("MeshNetworkService", "Mesh callbacks setup complete")
                } else {
                    android.util.Log.w("MeshNetworkService", "Managers not initialized, skipping coordinator setup")
                }
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Warning: Manager initialization failed", e)
            }

            android.util.Log.d("MeshNetworkService", "MeshNetworkService created successfully")
        } catch (e: Exception) {
            android.util.Log.e("MeshNetworkService", "Critical error during service creation", e)
            throw e
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        logD { "onBind() called" }
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
        if (::connectionCoordinator.isInitialized) {
            connectionCoordinator.stopMonitoring()
        }
        cleanupManagers()
        scope.cancel()
        logD { "MeshNetworkService destroyed" }
    }

    private fun initializeManagers() {
        try {
            android.util.Log.d("MeshNetworkService", "Starting manager initialization...")

            // Initialize WiFi Direct Manager
            try {
                android.util.Log.d("MeshNetworkService", "Initializing WiFi Direct Manager...")
                val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                if (wifiP2pManager == null) {
                    android.util.Log.e("MeshNetworkService", "WiFi P2P Manager not available")
                    return
                }

                val channel = wifiP2pManager.initialize(this, mainLooper, null)
                if (channel == null) {
                    android.util.Log.e("MeshNetworkService", "Failed to initialize WiFi P2P channel")
                    return
                }

                wifiDirectManager = WiFiDirectManager(this, wifiP2pManager, channel)
                android.util.Log.d("MeshNetworkService", "WiFi Direct Manager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Failed to initialize WiFi Direct Manager", e)
                return
            }

            // Initialize Mesh Network Manager
            try {
                android.util.Log.d("MeshNetworkService", "Initializing Mesh Network Manager...")
                meshNetworkManager = MeshNetworkManager(nodeId, deviceName)
                android.util.Log.d("MeshNetworkService", "Mesh Network Manager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Failed to initialize Mesh Network Manager", e)
                return
            }

            // Initialize Audio Manager
            try {
                android.util.Log.d("MeshNetworkService", "Initializing Audio Manager...")
                audioManager = AudioManager(this) { audioData ->
                    try {
                        meshNetworkManager.sendAudioData(audioData)
                    } catch (e: Exception) {
                        android.util.Log.e("MeshNetworkService", "Error sending audio data", e)
                    }
                }
                audioManager.initialize()
                android.util.Log.d("MeshNetworkService", "Audio Manager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Failed to initialize Audio Manager", e)
            }

            // Initialize Group Manager
            try {
                android.util.Log.d("MeshNetworkService", "Initializing Group Manager...")
                groupManager = GroupManager(this, nodeId, deviceName)
                // Connect group manager to mesh network
                groupManager.sendGroupMessage = { type, destination, payload ->
                    meshNetworkManager.sendGroupMessage(type, destination, payload)
                }
                // Handle incoming group messages
                meshNetworkManager.onGroupMessageReceived = { type, senderId, payload ->
                    groupManager.processGroupMessage(type, senderId, payload)
                }
                android.util.Log.d("MeshNetworkService", "Group Manager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Failed to initialize Group Manager", e)
            }

            // Initialize Location Manager
            try {
                android.util.Log.d("MeshNetworkService", "Initializing Location Manager...")
                locationManager = LocationManager(this)
                locationManager.initialize(nodeId, deviceName)
                // Connect location manager to mesh network
                locationManager.sendLocationMessage = { type, destination, payload ->
                    meshNetworkManager.sendLocationMessage(type, destination, payload)
                }
                // Handle incoming location messages
                meshNetworkManager.onLocationMessageReceived = { type, senderId, payload ->
                    locationManager.processLocationMessage(type, senderId, payload)
                }
                android.util.Log.d("MeshNetworkService", "Location Manager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MeshNetworkService", "Failed to initialize Location Manager", e)
            }

            android.util.Log.d("MeshNetworkService", "Manager initialization completed")
        } catch (e: Exception) {
            android.util.Log.e("MeshNetworkService", "Critical error during manager initialization", e)
            onError?.invoke("Failed to initialize: ${e.message}")
        }
    }

    private fun setupConnectionCoordinatorCallbacks() {
        scope.launch {
            connectionCoordinator.events.collect { event ->
                when (event) {
                    is ConnectionEvent.DeviceDiscovered -> {
                        onDeviceDiscovered?.invoke(event.deviceName, event.deviceAddress)
                    }
                    is ConnectionEvent.ConnectionEstablished -> {
                        onConnectionEstablished?.invoke(event.address)
                    }
                    is ConnectionEvent.ConnectionFailed -> {
                        onError?.invoke(event.reason)
                    }
                    is ConnectionEvent.StateChanged -> {
                        // State changes are handled through connectionState flow
                    }
                }
            }
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
                        networkStatus = if (nodes.isNotEmpty()) "Connected (${nodes.size} devices)" else "Searching...",
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
                        networkStatus = if (active) "Active" else "Stopped",
                    )
                }
            }
        }
    }

    fun startMeshNetwork() {
        scope.launch {
            try {
                logD { "Starting mesh network..." }

                // Check if managers are initialized
                if (!::wifiDirectManager.isInitialized || !::meshNetworkManager.isInitialized) {
                    logE { "Managers not initialized, cannot start mesh network" }
                    onError?.invoke("Service not properly initialized")
                    return@launch
                }

                // Start foreground service
                val isMuted = if (::audioManager.isInitialized) audioManager.isMuted.value else false
                val notification = notificationHelper.createNotification(_serviceState.value, isMuted)
                startForeground(AppConfig.Service.NOTIFICATION_ID, notification)

                // Initialize WiFi Direct
                wifiDirectManager.initialize()

                // Start mesh network
                meshNetworkManager.startMeshNetwork()

                // Start device discovery and monitoring via connection coordinator
                connectionCoordinator.startDiscovery()
                connectionCoordinator.startMonitoring()

                // Start automatic device scanning after a short delay
                launch {
                    delay(AppConfig.Service.INITIAL_SCAN_DELAY_MS)

                    if (_serviceState.value.connectedDevices == 0) {
                        logD { "No devices found via WiFi Direct, starting network scan..." }
                        meshNetworkManager.scanAndConnectToAvailableDevices()
                    } else {
                        logD { "Devices already found via WiFi Direct, skipping network scan" }
                    }

                    // Periodic scanning
                    while (_serviceState.value.isRunning) {
                        delay(AppConfig.Service.PERIODIC_SCAN_INTERVAL_MS)
                        if (_serviceState.value.isRunning && _serviceState.value.connectedDevices == 0) {
                            logD { "No devices connected, performing periodic scan..." }
                            meshNetworkManager.scanAndConnectToAvailableDevices()
                        }
                    }
                }

                updateServiceState {
                    copy(
                        isRunning = true,
                        networkStatus = "Starting...",
                    )
                }

                logD { "Mesh network started successfully" }
            } catch (e: Exception) {
                logE({ "Failed to start mesh network" }, e)
                onError?.invoke("Failed to start mesh network: ${e.message}")
            }
        }
    }

    // Helper function for retry logic
    private suspend fun <T> retry(maxAttempts: Int = AppConfig.Service.RETRY_MAX_ATTEMPTS, delayMs: Long = AppConfig.Service.RETRY_DELAY_MS, block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                logW({ "Attempt ${attempt + 1}/$maxAttempts failed" }, e)
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
                logD { "Stopping mesh network..." }

                // Stop connection coordinator monitoring
                if (::connectionCoordinator.isInitialized) {
                    connectionCoordinator.stopMonitoring()
                    connectionCoordinator.disconnect()
                }

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

                ServiceCompat.stopForeground(this@MeshNetworkService, ServiceCompat.STOP_FOREGROUND_REMOVE)

                logD { "Mesh network stopped" }
            } catch (e: Exception) {
                logE({ "Error stopping mesh network" }, e)
            }
        }
    }

    fun startRecording() {
        if (_serviceState.value.isRunning && ::audioManager.isInitialized) {
            audioManager.startRecording()
            logD { "Audio recording started" }
        } else {
            val message = if (!_serviceState.value.isRunning) "Cannot start recording: Mesh network not active" else "Audio manager not initialized"
            onError?.invoke(message)
            logW { message }
        }
    }

    fun stopRecording() {
        if (::audioManager.isInitialized) {
            audioManager.stopRecording()
            logD { "Audio recording stopped" }
        } else {
            logW { "Audio manager not initialized, cannot stop recording" }
        }
    }

    fun toggleMute() {
        if (::audioManager.isInitialized) {
            val currentlyMuted = audioManager.isMuted.value
            audioManager.setMuted(!currentlyMuted)
            logD { "Audio ${if (!currentlyMuted) "muted" else "unmuted"}" }
        } else {
            logW { "Audio manager not initialized, cannot toggle mute" }
        }
    }

    fun scanForDevices() {
        if (_serviceState.value.isRunning && ::meshNetworkManager.isInitialized) {
            logD { "Starting network scan for available devices..." }
            meshNetworkManager.scanAndConnectToAvailableDevices()
        } else {
            val message = if (!_serviceState.value.isRunning) "Cannot scan: Mesh network not active" else "Mesh network manager not initialized"
            onError?.invoke(message)
            logW { message }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        if (!::connectionCoordinator.isInitialized) {
            onError?.invoke("Service not properly initialized")
            return
        }

        connectionCoordinator.connectToDevice(deviceAddress)
            .onError { message, _ ->
                onError?.invoke(message)
            }
    }

    private fun handleControlMessage(message: String, sourceId: String) {
        when (message) {
            "mute_request" -> logD { "Received mute request from $sourceId" }
            "status_request" -> logD { "Received status request from $sourceId" }
        }
    }

    /**
     * Get the group manager for UI access.
     */
    fun getGroupManager(): GroupManager? {
        return if (::groupManager.isInitialized) groupManager else null
    }

    /**
     * Get the mesh network manager for topology access.
     */
    fun getMeshNetworkManager(): MeshNetworkManager? {
        return if (::meshNetworkManager.isInitialized) meshNetworkManager else null
    }

    /**
     * Get current mesh topology for visualization.
     */
    fun getMeshTopology(): MeshTopology? {
        return if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.getMeshTopology()
        } else {
            null
        }
    }

    /**
     * Get the location manager for radar access.
     */
    fun getLocationManager(): LocationManager? {
        return if (::locationManager.isInitialized) locationManager else null
    }

    /**
     * Set the group code for mesh filtering.
     * Only nodes with matching group codes will connect.
     */
    fun setGroupCode(code: String?) {
        if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.setGroupCode(code)
            logD { "Group code set to: $code" }
        } else {
            logW { "Cannot set group code - mesh network manager not initialized" }
        }
    }

    /**
     * Get the current group code.
     */
    fun getGroupCode(): String? {
        return if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.getGroupCode()
        } else {
            null
        }
    }

    /**
     * Enable or disable group mode filtering.
     * When enabled (default), only connects with nodes that have matching group codes.
     * When disabled (open mode), connects with all nearby nodes.
     */
    fun setGroupModeEnabled(enabled: Boolean) {
        if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.setGroupModeEnabled(enabled)
            logD { "Group mode ${if (enabled) "enabled" else "disabled"}" }
        } else {
            logW { "Cannot set group mode - mesh network manager not initialized" }
        }
    }

    /**
     * Check if group mode is enabled.
     */
    fun isGroupModeEnabled(): Boolean {
        return if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.isGroupModeEnabled()
        } else {
            true // Default to group mode for safety
        }
    }

    /**
     * Start location tracking.
     */
    fun startLocationTracking(): Boolean {
        android.util.Log.d("MeshNetworkService", "startLocationTracking() called")
        return if (::locationManager.isInitialized) {
            android.util.Log.d("MeshNetworkService", "LocationManager is initialized, starting tracking...")
            val result = locationManager.startTracking()
            android.util.Log.d("MeshNetworkService", "startTracking() returned: $result")
            result
        } else {
            android.util.Log.e("MeshNetworkService", "LocationManager is NOT initialized!")
            false
        }
    }

    /**
     * Stop location tracking.
     */
    fun stopLocationTracking() {
        if (::locationManager.isInitialized) {
            locationManager.stopTracking()
        }
    }

    private fun updateServiceState(update: ServiceState.() -> ServiceState) {
        val newState = _serviceState.value.update()
        _serviceState.value = newState
        onStateChanged?.invoke(newState)

        // Update notification via NotificationHelper
        if (::notificationHelper.isInitialized) {
            val isMuted = if (::audioManager.isInitialized) audioManager.isMuted.value else false
            notificationHelper.updateNotification(newState, isMuted)
        }
    }

    private fun cleanupManagers() {
        try {
            if (::locationManager.isInitialized) {
                locationManager.stopTracking()
                logD { "Location manager cleaned up" }
            }
            if (::audioManager.isInitialized) {
                audioManager.cleanup()
                logD { "Audio manager cleaned up" }
            }
            if (::meshNetworkManager.isInitialized) {
                meshNetworkManager.stopMeshNetwork()
                logD { "Mesh network manager cleaned up" }
            }
            if (::wifiDirectManager.isInitialized) {
                wifiDirectManager.cleanup()
                logD { "WiFi Direct manager cleaned up" }
            }
            logD { "All managers cleaned up" }
        } catch (e: Exception) {
            logE({ "Error during cleanup" }, e)
        }
    }
}
