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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App status and instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Enter-Comm Bike Intercom",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val statusText = when {
                        !isServiceBound -> "Connecting to service..."
                        !serviceState.isRunning -> "Ready to start. Tap 'Start' to begin mesh network."
                        else -> "Mesh network active: ${serviceState.networkStatus}"
                    }
                    
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceState.isRunning) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (serviceState.isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (serviceState.isRunning) "Stop" else "Start")
                }
                
                if (serviceState.isRunning) {
                    Button(
                        onClick = {
                            if (serviceState.isRecording) {
                                meshService?.stopRecording()
                            } else {
                                meshService?.startRecording()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serviceState.isRecording) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            imageVector = if (serviceState.isRecording) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (serviceState.isRecording) "Stop Recording" else "Start Recording")
                    }
                }
            }
            
            // Quick Actions
            if (serviceState.isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Quick Actions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { meshService?.toggleMute() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mute")
                            }
                            
                            OutlinedButton(
                                onClick = { 
                                    // Create group (become group owner)
                                    // This would call wifiDirectManager.createGroup()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Create Group")
                            }
                        }
                    }
                }
            }
            
            // Network Status
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Network Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // Status indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier.fillMaxSize(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        serviceState.isRunning && serviceState.connectedDevices > 0 -> Color.Green
                                        serviceState.isRunning -> Color.Yellow
                                        else -> Color.Gray
                                    }
                                )
                            ) {}
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Status:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (serviceState.isRunning) "Active" else "Inactive",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        Column {
                            Text(
                                text = "Connected:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${serviceState.connectedDevices} devices",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        Column {
                            Text(
                                text = "Recording:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (serviceState.isRecording) "Yes" else "No",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // Discovered Devices (when not connected)
            if (!serviceState.isRunning || serviceState.connectedDevices == 0) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Nearby Devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (discoveredDevices.isEmpty()) {
                            Text(
                                text = if (serviceState.isRunning) "Searching for devices..." else "Start the network to discover devices",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(discoveredDevices) { (deviceName, deviceAddress) ->
                                    Card(
                                        onClick = {
                                            meshService?.connectToDevice(deviceAddress)
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Settings,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = deviceName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = deviceAddress,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                Icons.Default.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}