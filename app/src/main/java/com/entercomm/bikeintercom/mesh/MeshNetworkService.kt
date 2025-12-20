package com.entercomm.bikeintercom.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.audio.AudioManager
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.location.LocationManager
import com.entercomm.bikeintercom.service.ConnectionCoordinator
import com.entercomm.bikeintercom.service.ConnectionEvent
import com.entercomm.bikeintercom.service.NotificationHelper
import com.entercomm.bikeintercom.util.AccessibilityManager
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
    private lateinit var accessibilityManager: AccessibilityManager

    // Extracted components
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var connectionCoordinator: ConnectionCoordinator

    // Service state
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    // Track periodic scan job to prevent multiple concurrent scan loops
    private var periodicScanJob: Job? = null

    // Wake locks for reliable WiFi scanning
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        logD { "onCreate() called" }

        try {
            // Initialize notification helper (handles channel creation)
            notificationHelper = NotificationHelper(this)
            logD { "Notification helper initialized" }

            // Initialize accessibility manager
            accessibilityManager = AccessibilityManager(this)
            accessibilityManager.initialize()
            logD { "Accessibility manager initialized" }

            // Try to initialize managers, but don't fail the service if it doesn't work
            try {
                initializeManagers()
                logD { "Managers initialized" }

                // Only initialize connection coordinator if managers are ready
                if (::wifiDirectManager.isInitialized && ::meshNetworkManager.isInitialized) {
                    connectionCoordinator = ConnectionCoordinator(
                        wifiDirectManager,
                        meshNetworkManager,
                        scope,
                    )
                    setupConnectionCoordinatorCallbacks()
                    logD { "Connection coordinator initialized" }

                    setupMeshCallbacks()
                    logD { "Mesh callbacks setup complete" }
                } else {
                    logW { "Managers not initialized, skipping coordinator setup" }
                }
            } catch (e: Exception) {
                logE({ "Warning: Manager initialization failed" }, e)
            }

            // Initialize wake locks for reliable scanning
            initializeWakeLocks()

            logD { "MeshNetworkService created successfully" }
        } catch (e: Exception) {
            logE({ "Critical error during service creation" }, e)
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
            logD { "Starting manager initialization..." }

            // Initialize WiFi Direct Manager
            try {
                logD { "Initializing WiFi Direct Manager..." }
                val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                if (wifiP2pManager == null) {
                    logE { "WiFi P2P Manager not available" }
                    return
                }

                val channel = wifiP2pManager.initialize(this, mainLooper, null)
                if (channel == null) {
                    logE { "Failed to initialize WiFi P2P channel" }
                    return
                }

                wifiDirectManager = WiFiDirectManager(this, wifiP2pManager, channel)
                logD { "WiFi Direct Manager initialized successfully" }
            } catch (e: Exception) {
                logE({ "Failed to initialize WiFi Direct Manager" }, e)
                return
            }

            // Initialize Mesh Network Manager
            try {
                logD { "Initializing Mesh Network Manager..." }
                meshNetworkManager = MeshNetworkManager(nodeId, deviceName)
                logD { "Mesh Network Manager initialized successfully" }
            } catch (e: Exception) {
                logE({ "Failed to initialize Mesh Network Manager" }, e)
                return
            }

            // Initialize Audio Manager
            try {
                logD { "Initializing Audio Manager..." }
                audioManager = AudioManager(this) { buffer, offset, length ->
                    try {
                        // Copy the pooled buffer slice for async network send
                        val audioData = buffer.copyOfRange(offset, offset + length)
                        meshNetworkManager.sendAudioData(audioData)
                    } catch (e: Exception) {
                        logE({ "Error sending audio data" }, e)
                    }
                }
                audioManager.initialize()
                logD { "Audio Manager initialized successfully" }
            } catch (e: Exception) {
                logE({ "Failed to initialize Audio Manager" }, e)
            }

            // Initialize Group Manager
            try {
                logD { "Initializing Group Manager..." }
                groupManager = GroupManager(this, nodeId, deviceName)
                // Connect group manager to mesh network
                groupManager.sendGroupMessage = { type, destination, payload ->
                    meshNetworkManager.sendGroupMessage(type, destination, payload)
                }
                // Handle incoming group messages
                meshNetworkManager.onGroupMessageReceived = { type, senderId, payload ->
                    groupManager.processGroupMessage(type, senderId, payload)
                }
                // Sync peer discovery with group membership
                meshNetworkManager.onPeerDiscovered = { peerNodeId, peerNickname ->
                    groupManager.addDiscoveredPeer(peerNodeId, peerNickname)
                    // Voice announcement for rider joined
                    val totalCount = meshNetworkManager.connectedNodes.value.size
                    accessibilityManager.voiceFeedback.announceRiderJoined(peerNickname, totalCount)
                }
                // Sync peer disconnection with group membership
                meshNetworkManager.onPeerDisconnected = { peerNodeId ->
                    // Get nickname before removing (for announcement)
                    val nickname = groupManager.members.value
                        .find { it.nodeId == peerNodeId }?.nickname
                    groupManager.removeDisconnectedPeer(peerNodeId)
                    // Voice announcement for rider left
                    val totalCount = meshNetworkManager.connectedNodes.value.size
                    accessibilityManager.voiceFeedback.announceRiderLeft(nickname, totalCount)
                }
                // Sync the user's nickname to mesh network for discovery messages
                meshNetworkManager.setNickname(groupManager.nickname.value)
                logD { "Group Manager initialized successfully" }
            } catch (e: Exception) {
                logE({ "Failed to initialize Group Manager" }, e)
            }

            // Initialize Location Manager
            try {
                logD { "Initializing Location Manager..." }
                locationManager = LocationManager(this)
                // Use nickname from GroupManager if available, otherwise fall back to deviceName
                val nickname = if (::groupManager.isInitialized) groupManager.nickname.value else deviceName
                locationManager.initialize(nodeId, nickname)
                // Connect location manager to mesh network
                locationManager.sendLocationMessage = { type, destination, payload ->
                    meshNetworkManager.sendLocationMessage(type, destination, payload)
                }
                // Handle incoming location messages
                meshNetworkManager.onLocationMessageReceived = { type, senderId, payload ->
                    locationManager.processLocationMessage(type, senderId, payload)
                }
                logD { "Location Manager initialized successfully" }
            } catch (e: Exception) {
                logE({ "Failed to initialize Location Manager" }, e)
            }

            logD { "Manager initialization completed" }
        } catch (e: Exception) {
            logE({ "Critical error during manager initialization" }, e)
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
                    is ConnectionEvent.ServiceDiscovered -> {
                        // Service discovered (may or may not match our group code)
                        logD { "Service discovered: ${event.service.deviceAddress}" }
                    }
                    is ConnectionEvent.MatchingServiceDiscovered -> {
                        // Matching service found - notify as device discovered
                        val service = event.service
                        val deviceName = service.device?.deviceName ?: service.instanceName
                        onDeviceDiscovered?.invoke(deviceName, service.deviceAddress)
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
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

                // Start foreground service with permission check for Android 13+
                val isMuted = if (::audioManager.isInitialized) audioManager.isMuted.value else false
                val notification = notificationHelper.createNotification(_serviceState.value, isMuted)

                // Check POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasNotificationPermission = ContextCompat.checkSelfPermission(
                        this@MeshNetworkService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasNotificationPermission) {
                        logW { "POST_NOTIFICATIONS permission not granted - notification may not show" }
                    }
                }

                try {
                    startForeground(AppConfig.Service.NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    logE({ "Failed to start foreground service" }, e)
                    // Continue anyway - service can still function without notification on some devices
                }

                // Initialize WiFi Direct
                wifiDirectManager.initialize()

                // DON'T start mesh network immediately - wait for WiFi Direct P2P group formation
                // The ConnectionCoordinator will start mesh when a P2P group forms
                // This ensures mesh traffic goes over the P2P interface, not regular WiFi

                // Check if we're already in a P2P group (e.g., resuming after brief disconnect)
                val currentConnection = wifiDirectManager.connectionInfo.value
                if (currentConnection?.groupFormed == true) {
                    logD { "Already in P2P group, starting mesh network on P2P interface" }
                    val p2pInterface = meshNetworkManager.getWiFiDirectInterfaceAddress()
                    if (p2pInterface != null) {
                        meshNetworkManager.startMeshNetworkOnInterface(p2pInterface.first)
                    } else {
                        logW { "P2P group exists but interface not found, starting mesh on all interfaces" }
                        meshNetworkManager.startMeshNetwork()
                    }
                } else {
                    logD { "No P2P group yet, mesh will start when group forms" }
                }

                // Start device discovery and monitoring via connection coordinator
                // Pass current group code to enable WiFi Direct service discovery with group filtering
                val currentGroupCode = meshNetworkManager.getGroupCode()
                connectionCoordinator.startDiscovery(currentGroupCode)
                connectionCoordinator.startMonitoring()

                // Enable auto-connect to matching peers with same group code
                connectionCoordinator.setAutoConnectEnabled(true)
                logD { "WiFi Direct service discovery started with group code: $currentGroupCode" }

                // Cancel any existing scan job before starting a new one
                periodicScanJob?.cancel()

                // Acquire WiFi lock for mesh network operation
                acquireScanLocks()

                // Start periodic device scanning as fallback
                // Only scan within P2P subnet when mesh is active on P2P interface
                periodicScanJob = launch {
                    delay(AppConfig.Service.INITIAL_SCAN_DELAY_MS)

                    // Only scan if mesh is running and no devices connected
                    if (meshNetworkManager.isActive.value && _serviceState.value.connectedDevices == 0) {
                        logD { "No devices found, starting network scan within mesh network..." }
                        performScanWithWakeLock()
                    }

                    // Periodic scanning - only when mesh is active
                    while (isActive && _serviceState.value.isRunning) {
                        delay(AppConfig.Service.PERIODIC_SCAN_INTERVAL_MS)
                        if (_serviceState.value.isRunning &&
                            meshNetworkManager.isActive.value &&
                            _serviceState.value.connectedDevices == 0
                        ) {
                            logD { "No devices connected, performing periodic scan..." }
                            performScanWithWakeLock()
                        }
                    }
                }

                updateServiceState {
                    copy(
                        isRunning = true,
                        networkStatus = "Discovering peers...",
                    )
                }

                // Voice announcement for mesh network started
                accessibilityManager.voiceFeedback.announceMeshStarting()

                logD { "WiFi Direct discovery started, waiting for P2P group formation..." }
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

                // Cancel periodic scan job
                periodicScanJob?.cancel()
                periodicScanJob = null

                // Release wake locks
                releaseAllLocks()

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

                // Stop WiFi Direct and cleanup services
                if (::wifiDirectManager.isInitialized) {
                    wifiDirectManager.stopServiceDiscovery()
                    wifiDirectManager.clearLocalServices()
                    wifiDirectManager.clearServiceRequests()
                    wifiDirectManager.stopDiscovery()
                    wifiDirectManager.disconnect()
                }

                updateServiceState {
                    ServiceState() // Reset to default state
                }

                ServiceCompat.stopForeground(this@MeshNetworkService, ServiceCompat.STOP_FOREGROUND_REMOVE)

                // Voice announcement for mesh network stopped
                accessibilityManager.voiceFeedback.announceMeshStopped()

                logD { "Mesh network stopped" }
            } catch (e: Exception) {
                logE({ "Error stopping mesh network" }, e)
            }
        }
    }

    fun startRecording() {
        if (_serviceState.value.isRunning && ::audioManager.isInitialized) {
            audioManager.startRecording()
            accessibilityManager.voiceFeedback.announceRecordingState(true)
            logD { "Audio recording started" }
        } else {
            val message = if (!_serviceState.value.isRunning) "Cannot start recording: Mesh network not active" else "Audio manager not initialized"
            onError?.invoke(message)
            accessibilityManager.voiceFeedback.announceError(message)
            logW { message }
        }
    }

    fun stopRecording() {
        if (::audioManager.isInitialized) {
            audioManager.stopRecording()
            accessibilityManager.voiceFeedback.announceRecordingState(false)
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
            scope.launch {
                performScanWithWakeLock()
            }
        } else {
            val message = if (!_serviceState.value.isRunning) "Cannot scan: Mesh network not active" else "Mesh network manager not initialized"
            onError?.invoke(message)
            logW { message }
        }
    }

    /**
     * Perform a network scan with wake lock protection.
     * Ensures the device stays awake during the scan operation.
     */
    private suspend fun performScanWithWakeLock() {
        try {
            acquireScanLocks()
            meshNetworkManager.scanAndConnectToAvailableDevices()
        } finally {
            releaseScanLocks()
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
     * Get the accessibility manager for accessibility settings.
     */
    fun getAccessibilityManager(): AccessibilityManager? {
        return if (::accessibilityManager.isInitialized) accessibilityManager else null
    }

    /**
     * Set the group code for mesh filtering.
     * Only nodes with matching group codes will connect.
     */
    fun setGroupCode(code: String?) {
        if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.setGroupCode(code)
            logD { "Group code set to: $code" }

            // Propagate group code to WiFiDirectManager for service discovery filtering
            if (::wifiDirectManager.isInitialized) {
                wifiDirectManager.setGroupCode(code)
                logD { "Group code propagated to WiFiDirectManager: $code" }
            }

            // Create/update group in GroupManager when using group code
            if (code != null && ::groupManager.isInitialized) {
                groupManager.joinByCode(code)
            } else if (code == null && ::groupManager.isInitialized) {
                groupManager.leaveGroupByCode()
            }
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
     * Update the user's nickname for mesh network discovery and location sharing.
     * This should be called when the user changes their nickname.
     */
    fun setNickname(nickname: String) {
        if (::meshNetworkManager.isInitialized) {
            meshNetworkManager.setNickname(nickname)
            logD { "Nickname updated to: $nickname" }
        }
        if (::groupManager.isInitialized) {
            groupManager.setNickname(nickname)
        }
        if (::locationManager.isInitialized) {
            locationManager.updateNickname(nickname)
        }
    }

    /**
     * Start location tracking.
     */
    fun startLocationTracking(): Boolean {
        logD { "startLocationTracking() called" }
        return if (::locationManager.isInitialized) {
            logD { "LocationManager is initialized, starting tracking..." }
            val result = locationManager.startTracking()
            logD { "startTracking() returned: $result" }
            result
        } else {
            logE { "LocationManager is NOT initialized!" }
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

    /**
     * Initialize wake locks for reliable WiFi scanning.
     * Prevents device from sleeping during scan operations.
     */
    @SuppressLint("WakelockTimeout")
    private fun initializeWakeLocks() {
        try {
            // WiFi lock to keep WiFi active
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "EnterComm:WifiScan",
            )
            logD { "WiFi lock initialized" }

            // Partial wake lock to keep CPU active during scans
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EnterComm:NetworkScan",
            )
            logD { "Wake lock initialized" }
        } catch (e: Exception) {
            logE({ "Failed to initialize wake locks" }, e)
        }
    }

    /**
     * Acquire wake locks before WiFi scanning.
     * Call this before starting scan operations.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireScanLocks() {
        try {
            wifiLock?.let { lock ->
                if (!lock.isHeld) {
                    lock.acquire()
                    logD { "WiFi lock acquired" }
                }
            }
            wakeLock?.let { lock ->
                if (!lock.isHeld) {
                    // Acquire with timeout to prevent indefinite hold
                    lock.acquire(AppConfig.Service.PERIODIC_SCAN_INTERVAL_MS)
                    logD { "Wake lock acquired" }
                }
            }
        } catch (e: Exception) {
            logE({ "Failed to acquire scan locks" }, e)
        }
    }

    /**
     * Release wake locks after WiFi scanning completes.
     * Call this after scan operations complete.
     */
    private fun releaseScanLocks() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    logD { "Wake lock released" }
                }
            }
            // Note: WiFi lock is kept during mesh network operation
            // and only released on cleanup
        } catch (e: Exception) {
            logE({ "Failed to release scan locks" }, e)
        }
    }

    /**
     * Release all wake locks.
     */
    private fun releaseAllLocks() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
            wakeLock = null

            wifiLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
            wifiLock = null

            logD { "All locks released" }
        } catch (e: Exception) {
            logE({ "Error releasing locks" }, e)
        }
    }

    private fun cleanupManagers() {
        try {
            // Release wake locks first
            releaseAllLocks()

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
            if (::accessibilityManager.isInitialized) {
                accessibilityManager.shutdown()
                logD { "Accessibility manager cleaned up" }
            }
            logD { "All managers cleaned up" }
        } catch (e: Exception) {
            logE({ "Error during cleanup" }, e)
        }
    }
}
