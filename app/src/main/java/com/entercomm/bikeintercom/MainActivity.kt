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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.mesh.MeshNetworkService
import com.entercomm.bikeintercom.onboarding.ConnectionMode
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.ui.screens.IntercomMainScreen
import com.entercomm.bikeintercom.ui.screens.OnboardingScreen
import com.entercomm.bikeintercom.ui.theme.EnterCommTheme

class MainActivity : ComponentActivity() {

    private var meshService: MeshNetworkService? = null
    private var isServiceBound by mutableStateOf(false)
    private lateinit var onboardingManager: OnboardingManager
    private var showOnboarding by mutableStateOf(true)
    
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

                    // Apply saved group settings from OnboardingManager
                    applyGroupSettingsToService()

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

    /**
     * Apply group settings from OnboardingManager to MeshNetworkService.
     */
    private fun applyGroupSettingsToService() {
        val prefs = onboardingManager.userPreferences.value

        // Set group code
        meshService?.setGroupCode(prefs.currentGroupCode)
        Log.d("MainActivity", "Applied group code: ${prefs.currentGroupCode}")

        // Set group mode (GROUP_MODE = true, OPEN_MODE = false)
        val groupModeEnabled = prefs.connectionMode == ConnectionMode.GROUP_MODE
        meshService?.setGroupModeEnabled(groupModeEnabled)
        Log.d("MainActivity", "Applied group mode enabled: $groupModeEnabled")

        // Set nickname to group manager
        meshService?.getGroupManager()?.setNickname(prefs.nickname)
        Log.d("MainActivity", "Applied nickname: ${prefs.nickname}")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate() started")

        // Initialize onboarding manager
        onboardingManager = OnboardingManager(this)
        showOnboarding = onboardingManager.needsOnboarding()
        Log.d("MainActivity", "Onboarding needed: $showOnboarding")

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
                    if (showOnboarding) {
                        OnboardingScreen(
                            onboardingManager = onboardingManager,
                            onComplete = { groupCode, isCreator ->
                                Log.d("MainActivity", "Onboarding complete: groupCode=$groupCode, isCreator=$isCreator")
                                showOnboarding = false

                                // Store group code for mesh filtering (must be done before applying settings)
                                if (groupCode != null) {
                                    onboardingManager.setCurrentGroupCode(groupCode)
                                }

                                // Apply all settings to service immediately
                                applyGroupSettingsToService()

                                // Create group if this user is the creator
                                if (groupCode != null && isCreator) {
                                    val prefs = onboardingManager.userPreferences.value
                                    meshService?.getGroupManager()?.createGroup(
                                        name = "${prefs.nickname}'s Group",
                                        channel = 1,
                                        password = null,
                                        maxSize = 10
                                    )
                                }
                            }
                        )
                    } else {
                        IntercomMainScreen(
                            meshService = meshService,
                            isServiceBound = isServiceBound,
                            onboardingManager = onboardingManager
                        )
                    }
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

            // Use coroutine for non-blocking delay and service binding
            lifecycleScope.launch {
                delay(100) // Non-blocking delay to let service initialize

                // Bind to service
                val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d("MainActivity", "Service binding attempted: $bound")

                if (!bound) {
                    Log.e("MainActivity", "Failed to bind to service")
                    Toast.makeText(this@MainActivity, "Failed to bind to service", Toast.LENGTH_LONG).show()
                } else {
                    // Set a timeout to detect if service connection fails
                    delay(3000) // Wait 3 seconds
                    if (!isServiceBound) {
                        Log.w("MainActivity", "Service connection timeout")
                        Toast.makeText(this@MainActivity, "Service connection timeout. Check logs for errors.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing service", e)
            Toast.makeText(this, "Service initialization error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
