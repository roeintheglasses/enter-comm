package com.entercomm.bikeintercom

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.ui.components.*
import com.entercomm.bikeintercom.ui.theme.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.mesh.MeshNetworkService
import com.entercomm.bikeintercom.mesh.ServiceState
import com.entercomm.bikeintercom.ui.theme.EnterCommTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
    private var meshService: MeshNetworkService? = null
    private var isServiceBound = false
    
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Permission result received: $permissions")
        
        // Check critical permissions (exclude POST_NOTIFICATIONS as it's optional)
        val criticalPermissions = permissions.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        val allCriticalGranted = criticalPermissions.values.all { it }
        val allGranted = permissions.values.all { it }
        
        Log.d("MainActivity", "All permissions granted: $allGranted")
        Log.d("MainActivity", "All critical permissions granted: $allCriticalGranted")
        
        if (allCriticalGranted) {
            if (!allGranted) {
                Log.d("MainActivity", "Non-critical permissions denied, but proceeding normally")
                Toast.makeText(this, "App ready! (Some optional permissions denied)", Toast.LENGTH_SHORT).show()
            } else {
                Log.d("MainActivity", "All permissions granted!")
                Toast.makeText(this, "All permissions granted! App ready.", Toast.LENGTH_SHORT).show()
            }
            initializeService()
        } else {
            Log.w("MainActivity", "Critical permissions denied: $permissions")
            Toast.makeText(this, "Critical permissions required for mesh networking", Toast.LENGTH_LONG).show()
            
            // Still try to initialize service for debugging
            Log.d("MainActivity", "Attempting to initialize service with missing critical permissions")
            initializeService()
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as? MeshNetworkService.MeshNetworkBinder
                if (binder != null) {
                    meshService = binder.getService()
                    isServiceBound = true
                    Log.d("MainActivity", "Service connected successfully")
                    Toast.makeText(this@MainActivity, "Mesh service connected - Ready to start!", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("MainActivity", "Failed to get service binder")
                    Toast.makeText(this@MainActivity, "Failed to connect to service", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error connecting to service", e)
                Toast.makeText(this@MainActivity, "Service connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            meshService = null
            isServiceBound = false
            Toast.makeText(this@MainActivity, "Service disconnected", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate() started")
        
        // Check permissions
        if (hasAllPermissions()) {
            Log.d("MainActivity", "All critical permissions granted, initializing service")
            Toast.makeText(this, "All permissions ready! Starting app...", Toast.LENGTH_SHORT).show()
            initializeService()
        } else {
            Log.d("MainActivity", "Missing critical permissions, requesting them")
            requestPermissions()
        }
        
        Log.d("MainActivity", "Setting up UI content")
        setContent {
            EnterCommTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        val permissionStatus = requiredPermissions.associateWith { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        Log.d("MainActivity", "Permission status: $permissionStatus")
        
        // Check critical permissions (exclude POST_NOTIFICATIONS as it's optional)
        val criticalPermissions = permissionStatus.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        val allCriticalGranted = criticalPermissions.values.all { it }
        val allGranted = permissionStatus.values.all { it }
        
        Log.d("MainActivity", "All permissions granted: $allGranted")
        Log.d("MainActivity", "All critical permissions granted: $allCriticalGranted")
        
        return allCriticalGranted
    }
    
    private fun requestPermissions() {
        Log.d("MainActivity", "Requesting permissions: ${requiredPermissions.toList()}")
        permissionLauncher.launch(requiredPermissions)
    }
    
    private fun initializeService() {
        Log.d("MainActivity", "Initializing service...")
        try {
            val intent = Intent(this, MeshNetworkService::class.java)
            Log.d("MainActivity", "Created intent for service: ${intent.component}")
            
            // Start the service first to ensure it's created
            val startResult = startService(intent)
            Log.d("MainActivity", "Start service result: $startResult")
            
            // Add a small delay to ensure service is ready
            Thread.sleep(100)
            
            // Then bind to it
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d("MainActivity", "Service binding attempted: $bound")
            
            if (!bound) {
                Log.e("MainActivity", "Failed to bind to service")
                Toast.makeText(this, "Failed to bind to service", Toast.LENGTH_LONG).show()
            } else {
                // Set a timeout to detect if service connection fails
                Thread {
                    Thread.sleep(3000) // Wait 3 seconds
                    if (!isServiceBound) {
                        Log.w("MainActivity", "Service connection timeout")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Service connection timeout. Check logs for errors.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing service", e)
            Toast.makeText(this, "Service initialization error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    @Composable
    private fun MainScreen() {
        var serviceState by remember { mutableStateOf(ServiceState()) }
        var discoveredDevices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var audioLevel by remember { mutableStateOf(0f) }
        
        // Collect service state from the service
        LaunchedEffect(meshService) {
            meshService?.let { service ->
                service.serviceState.collect { state ->
                    serviceState = state
                }
            }
        }
        
        // Set up service callbacks for real-time updates
        LaunchedEffect(meshService) {
            meshService?.let { service ->
                service.onStateChanged = { state ->
                    serviceState = state
                    // Clear discovered devices when network stops
                    if (!state.isRunning) {
                        discoveredDevices = emptyList()
                    }
                }
                
                service.onDeviceDiscovered = { deviceName, deviceAddress ->
                    // Avoid duplicates by checking if device already exists
                    val devicePair = deviceName to deviceAddress
                    if (!discoveredDevices.contains(devicePair)) {
                        discoveredDevices = discoveredDevices + devicePair
                    }
                }
                
                service.onConnectionEstablished = { address ->
                    // Clear discovered devices since we're now connected
                    discoveredDevices = emptyList()
                    Log.d("MainActivity", "Connection established to $address")
                }
                
                service.onError = { message ->
                    // Handle error feedback
                }
            }
        }
        
        // Simulate audio level for demonstration
        LaunchedEffect(serviceState.isRecording) {
            if (serviceState.isRecording) {
                while (serviceState.isRecording) {
                    audioLevel = kotlin.random.Random.nextFloat() * 0.9f + 0.1f
                    kotlinx.coroutines.delay(100)
                }
            } else {
                audioLevel = 0f
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Status Card
            TechnicalStatusCard(
                title = "ENTER-COMM INTERCOM",
                status = when {
                    !isServiceBound -> "INITIALIZING SYSTEM..."
                    !serviceState.isRunning -> "STANDBY - READY FOR OPERATION"
                    else -> "MESH NETWORK OPERATIONAL - ${serviceState.networkStatus.uppercase()}"
                },
                isActive = serviceState.isRunning,
                isError = !isServiceBound,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (serviceState.isRunning && serviceState.connectedDevices > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NETWORK TOPOLOGY",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                        NetworkTopology(
                            connectedDevices = serviceState.connectedDevices,
                            modifier = Modifier.size(60.dp)
                        )
                    }
                }
            }
            
            // Main Control Area
            if (serviceState.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // PTT Button
                    PTTButton(
                        isRecording = serviceState.isRecording,
                        onClick = {
                            if (serviceState.isRecording) {
                                meshService?.stopRecording()
                            } else {
                                meshService?.startRecording()
                            }
                        },
                        enabled = serviceState.isRunning
                    )
                    
                    // Audio Level Meter
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "AUDIO LEVEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AudioLevelMeter(
                            level = audioLevel,
                            isRecording = serviceState.isRecording,
                            modifier = Modifier.size(width = 80.dp, height = 100.dp)
                        )
                    }
                }
            }
            
            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TechnicalButton(
                    text = if (serviceState.isRunning) "STOP" else "START",
                    onClick = {
                        if (serviceState.isRunning) {
                            meshService?.stopMeshNetwork()
                        } else {
                            if (meshService != null) {
                                Log.d("MainActivity", "Starting mesh network...")
                                Toast.makeText(this@MainActivity, "Starting mesh network...", Toast.LENGTH_SHORT).show()
                                meshService?.startMeshNetwork()
                            } else {
                                Log.w("MainActivity", "Service not connected")
                                Toast.makeText(this@MainActivity, "Service not connected. Please wait...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    icon = if (serviceState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    isActive = serviceState.isRunning,
                    buttonType = if (serviceState.isRunning) TechnicalButtonType.DANGER else TechnicalButtonType.PRIMARY,
                    enabled = isServiceBound
                )
                
                if (serviceState.isRunning) {
                    TechnicalButton(
                        text = "MUTE",
                        onClick = { meshService?.toggleMute() },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.VolumeOff,
                        buttonType = TechnicalButtonType.SECONDARY
                    )
                }
            }
            
            // Network Status Panel
            TechnicalStatusCard(
                title = "NETWORK STATUS",
                status = buildString {
                    append("STATUS: ${if (serviceState.isRunning) "ACTIVE" else "INACTIVE"}")
                    append(" | NODES: ${serviceState.connectedDevices}")
                    append(" | REC: ${if (serviceState.isRecording) "ON" else "OFF"}")
                },
                isActive = serviceState.isRunning && serviceState.connectedDevices > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (serviceState.isRunning) "ONLINE" else "OFFLINE",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (serviceState.isRunning) TechGreen else TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${serviceState.connectedDevices}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (serviceState.connectedDevices > 0) TechGreen else TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "CONNECTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (serviceState.isRecording) "REC" else "---",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (serviceState.isRecording) TechRed else TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "RECORDING",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            
            // Device Discovery/Connection Panel
            if (!serviceState.isRunning || serviceState.connectedDevices == 0) {
                TechnicalStatusCard(
                    title = "DEVICE DISCOVERY",
                    status = if (serviceState.isRunning) "SCANNING FOR NEARBY DEVICES..." else "START NETWORK TO DISCOVER DEVICES",
                    isActive = discoveredDevices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            items(discoveredDevices) { (deviceName, deviceAddress) ->
                                DeviceCard(
                                    deviceName = deviceName,
                                    deviceAddress = deviceAddress,
                                    isConnected = false,
                                    signalStrength = kotlin.random.Random.nextInt(50, 101),
                                    onClick = {
                                        meshService?.connectToDevice(deviceAddress)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Connected Devices Panel
                TechnicalStatusCard(
                    title = "CONNECTED DEVICES",
                    status = "MESH NETWORK ACTIVE - ${serviceState.connectedDevices} NODES CONNECTED",
                    isActive = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // This would be populated with actual connected device data
                    repeat(serviceState.connectedDevices) { index ->
                        DeviceCard(
                            deviceName = "BIKE-COMM-${1000 + index}",
                            deviceAddress = "00:11:22:33:44:${55 + index}",
                            isConnected = true,
                            signalStrength = kotlin.random.Random.nextInt(70, 101),
                            onClick = { }
                        )
                        if (index < serviceState.connectedDevices - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}