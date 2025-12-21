package com.entercomm.bikeintercom.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.entercomm.bikeintercom.location.RadarData
import com.entercomm.bikeintercom.mesh.MeshGroup
import com.entercomm.bikeintercom.mesh.MeshNetworkService
import com.entercomm.bikeintercom.mesh.MeshTopology
import com.entercomm.bikeintercom.mesh.NetworkStats
import com.entercomm.bikeintercom.mesh.ServiceState
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.ui.components.ClearHistoryDialog
import com.entercomm.bikeintercom.ui.components.CreateGroupDialog
import com.entercomm.bikeintercom.ui.components.DiagnosticsState
import com.entercomm.bikeintercom.ui.components.JoinGroupByCodeDialog
import com.entercomm.bikeintercom.ui.components.JoinGroupDialog
import com.entercomm.bikeintercom.ui.components.RenameGroupDialog
import com.entercomm.bikeintercom.ui.screens.common.AppMode
import com.entercomm.bikeintercom.ui.screens.common.NavigationTab
import com.entercomm.bikeintercom.ui.screens.group.GroupContent
import com.entercomm.bikeintercom.ui.screens.intercom.IntercomContent
import com.entercomm.bikeintercom.ui.screens.radar.RadarContent
import com.entercomm.bikeintercom.ui.screens.settings.SettingsContent
import com.entercomm.bikeintercom.ui.theme.DarkSurface
import com.entercomm.bikeintercom.ui.theme.DarkSurfaceElevated
import com.entercomm.bikeintercom.ui.theme.PitchBlack
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechOrange
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.ui.theme.TextTertiary
import kotlinx.coroutines.delay

/**
 * Main screen with PTT-centric design and cohesive animations
 */
@Composable
fun IntercomMainScreen(meshService: MeshNetworkService?, isServiceBound: Boolean, onboardingManager: OnboardingManager? = null) {
    val context = LocalContext.current
    var serviceState by remember { mutableStateOf(ServiceState()) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    var selectedTab by remember { mutableStateOf(NavigationTab.INTERCOM) }

    // Log tab changes
    LaunchedEffect(selectedTab) {
        android.util.Log.d("MainScreen", "Tab changed to: $selectedTab")
    }

    // Group state
    val groupManager = meshService?.getGroupManager()
    val currentGroup by groupManager?.currentGroup?.collectAsState() ?: remember { mutableStateOf(null) }
    val members by groupManager?.members?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val nickname by groupManager?.nickname?.collectAsState() ?: remember { mutableStateOf("Rider") }

    // Radar state
    val locationManager = meshService?.getLocationManager()
    val radarData by locationManager?.radarData?.collectAsState() ?: remember { mutableStateOf(RadarData.EMPTY) }
    val isLocationTracking by locationManager?.isTracking?.collectAsState() ?: remember { mutableStateOf(false) }

    // Accessibility state
    val accessibilityManager = meshService?.getAccessibilityManager()

    // Topology state
    var meshTopology by remember { mutableStateOf<MeshTopology?>(null) }

    // Network diagnostics state
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    val meshNetworkManager = meshService?.getMeshNetworkManager()
    val networkStats by meshNetworkManager?.networkStats?.collectAsState()
        ?: remember { mutableStateOf(NetworkStats()) }
    val connectedNodes by meshNetworkManager?.connectedNodes?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val networkStartTime = remember { System.currentTimeMillis() }
    val diagnosticsState = remember(meshTopology, networkStats, connectedNodes, networkStartTime) {
        DiagnosticsState(meshTopology, networkStats, connectedNodes, networkStartTime)
    }

    // Dialog states
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf<MeshGroup?>(null) }
    var showJoinGroupByCodeDialog by remember { mutableStateOf(false) }
    var showRenameGroupDialog by remember { mutableStateOf<com.entercomm.bikeintercom.group.GroupMemory?>(null) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // Group memory state
    val groupMemoryManager = meshService?.getGroupMemoryManager()
    val groupHistory by groupMemoryManager?.groupHistory?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }

    // Derive app mode from state - this drives all animations
    val appMode by remember(isServiceBound, serviceState) {
        derivedStateOf {
            when {
                !isServiceBound -> AppMode.INITIALIZING
                serviceState.isRecording -> AppMode.TRANSMITTING
                serviceState.isRunning -> AppMode.ACTIVE
                else -> AppMode.STANDBY
            }
        }
    }

    // Collect service state
    LaunchedEffect(meshService) {
        meshService?.serviceState?.collect { state ->
            serviceState = state
        }
    }

    // Update topology periodically
    LaunchedEffect(serviceState.isRunning, meshService) {
        while (serviceState.isRunning) {
            meshTopology = meshService?.getMeshTopology()
            delay(2000)
        }
    }

    // Simulate audio level when transmitting
    LaunchedEffect(serviceState.isRecording) {
        if (serviceState.isRecording) {
            while (serviceState.isRecording) {
                audioLevel = kotlin.random.Random.nextFloat() * 0.8f + 0.2f
                delay(80)
            }
        } else {
            // Animate down smoothly
            while (audioLevel > 0.01f) {
                audioLevel *= 0.85f
                delay(50)
            }
            audioLevel = 0f
        }
    }

    // Main UI with bottom navigation
    Scaffold(
        containerColor = PitchBlack,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = TextPrimary,
            ) {
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.INTERCOM,
                    onClick = { selectedTab = NavigationTab.INTERCOM },
                    icon = { Icon(Icons.Rounded.Mic, contentDescription = "Intercom") },
                    label = { Text("Intercom") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechGreen,
                        selectedTextColor = TechGreen,
                        indicatorColor = DarkSurfaceElevated,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                    ),
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.GROUP,
                    onClick = { selectedTab = NavigationTab.GROUP },
                    icon = { Icon(Icons.Rounded.Group, contentDescription = "Group") },
                    label = { Text("Group") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechCyan,
                        selectedTextColor = TechCyan,
                        indicatorColor = DarkSurfaceElevated,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                    ),
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.RADAR,
                    onClick = { selectedTab = NavigationTab.RADAR },
                    icon = { Icon(Icons.Rounded.MyLocation, contentDescription = "Radar") },
                    label = { Text("Radar") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechOrange,
                        selectedTextColor = TechOrange,
                        indicatorColor = DarkSurfaceElevated,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                    ),
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.SETTINGS,
                    onClick = { selectedTab = NavigationTab.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TextPrimary,
                        selectedTextColor = TextPrimary,
                        indicatorColor = DarkSurfaceElevated,
                        unselectedIconColor = TextTertiary,
                        unselectedTextColor = TextTertiary,
                    ),
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (selectedTab) {
                NavigationTab.INTERCOM -> {
                    IntercomContent(
                        appMode = appMode,
                        audioLevel = audioLevel,
                        serviceState = serviceState,
                        diagnosticsState = diagnosticsState,
                        showDiagnosticsSheet = showDiagnosticsSheet,
                        onDiagnosticsClick = { showDiagnosticsSheet = true },
                        onDiagnosticsDismiss = { showDiagnosticsSheet = false },
                        onPTTPress = {
                            if (serviceState.isRecording) {
                                meshService?.stopRecording()
                            } else {
                                meshService?.startRecording()
                            }
                        },
                        onStartStop = {
                            if (serviceState.isRunning) {
                                meshService?.stopMeshNetwork()
                            } else {
                                meshService?.startMeshNetwork()
                                Toast.makeText(context, "Starting mesh network...", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                NavigationTab.GROUP -> {
                    val userPrefs by onboardingManager?.userPreferences?.collectAsState()
                        ?: remember { mutableStateOf(null) }
                    val formattedGroupCode = userPrefs?.currentGroupCode?.let {
                        onboardingManager?.formatGroupCode(it)
                    }
                    val rawGroupCode = userPrefs?.currentGroupCode

                    // Get current volume preferences for the active group
                    val currentGroupVolumes = rawGroupCode?.let {
                        groupMemoryManager?.getGroupVolumes(it)
                    }
                    val accessibilitySettings by accessibilityManager?.settings?.collectAsState()
                        ?: remember { mutableStateOf(null) }

                    GroupContent(
                        currentGroup = currentGroup,
                        members = members,
                        nickname = nickname,
                        groupCode = formattedGroupCode,
                        availableGroups = groupManager?.getAvailableGroups() ?: emptyList(),
                        isOwner = groupManager?.isOwner() ?: false,
                        localNodeId = meshService?.getMeshNetworkManager()?.let { "" } ?: "",
                        groupHistory = groupHistory,
                        incomingVolume = currentGroupVolumes?.incomingVolume,
                        voiceFeedbackVolume = accessibilitySettings?.voiceVolume,
                        onCreateGroup = { showCreateGroupDialog = true },
                        onLeaveGroup = {
                            // Leave the GroupManager group
                            groupManager?.leaveGroup()
                            // Clear the group code from OnboardingManager and MeshService
                            onboardingManager?.setCurrentGroupCode(null)
                            meshService?.setGroupCode(null)
                            Toast.makeText(context, "Left group", Toast.LENGTH_SHORT).show()
                        },
                        onJoinGroup = { group -> showJoinGroupDialog = group },
                        onJoinGroupByCode = { showJoinGroupByCodeDialog = true },
                        onKickMember = { nodeId -> groupManager?.kickMember(nodeId) },
                        onBanMember = { nodeId -> groupManager?.banMember(nodeId) },
                        onChannelChange = { channel -> groupManager?.changeChannel(channel) },
                        onRejoinGroup = { group ->
                            onboardingManager?.setCurrentGroupCode(group.groupCode)
                            meshService?.setGroupCode(group.groupCode)
                            Toast.makeText(context, "Rejoining ${group.displayName}...", Toast.LENGTH_SHORT).show()
                        },
                        onRenameGroup = { group -> showRenameGroupDialog = group },
                        onRemoveGroup = { group -> groupMemoryManager?.removeGroup(group.groupCode) },
                        onClearHistory = { showClearHistoryDialog = true },
                        onIncomingVolumeChange = { volume ->
                            rawGroupCode?.let { code ->
                                val voiceVol = accessibilitySettings?.voiceVolume ?: 0.8f
                                groupMemoryManager?.setGroupVolumes(code, volume, voiceVol)
                            }
                        },
                        onVoiceFeedbackVolumeChange = { volume ->
                            accessibilityManager?.updateSetting { it.copy(voiceVolume = volume) }
                            rawGroupCode?.let { code ->
                                val incomingVol = currentGroupVolumes?.incomingVolume ?: 0.8f
                                groupMemoryManager?.setGroupVolumes(code, incomingVol, volume)
                            }
                        },
                    )
                }
                NavigationTab.RADAR -> {
                    RadarContent(
                        radarData = radarData,
                        isTracking = isLocationTracking,
                        onStartTracking = {
                            android.util.Log.d("MainScreen", "onStartTracking called, meshService=${meshService != null}")
                            val result = meshService?.startLocationTracking()
                            android.util.Log.d("MainScreen", "startLocationTracking result: $result")
                        },
                        onStopTracking = {
                            android.util.Log.d("MainScreen", "onStopTracking called")
                            meshService?.stopLocationTracking()
                        },
                        onRangeChange = { locationManager?.cycleRadarRange() },
                    )
                }
                NavigationTab.SETTINGS -> {
                    SettingsContent(
                        meshTopology = meshTopology,
                        onboardingManager = onboardingManager,
                        accessibilityManager = accessibilityManager,
                    )
                }
            }
        }
    }

    // Dialogs
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, channel, password, maxSize ->
                // Generate a new group code
                val newGroupCode = onboardingManager?.generateGroupCode()
                if (newGroupCode == null) {
                    Toast.makeText(context, "Failed to create group", Toast.LENGTH_SHORT).show()
                    return@CreateGroupDialog
                }

                // Store in OnboardingManager for persistence
                onboardingManager.setCurrentGroupCode(newGroupCode)

                // Set in MeshService for mesh filtering
                meshService?.setGroupCode(newGroupCode)

                // Create GroupManager group for advanced features (channels, passwords, members)
                groupManager?.createGroup(name, channel, password, maxSize)

                // Show confirmation with the shareable code
                val formattedCode = onboardingManager.formatGroupCode(newGroupCode)
                Toast.makeText(context, "Group created! Code: $formattedCode", Toast.LENGTH_LONG).show()
            },
        )
    }

    showJoinGroupDialog?.let { group ->
        JoinGroupDialog(
            group = group,
            onDismiss = { showJoinGroupDialog = null },
            onJoin = { password ->
                groupManager?.joinGroup(group.groupId, password)
            },
        )
    }

    if (showJoinGroupByCodeDialog) {
        JoinGroupByCodeDialog(
            onDismiss = { showJoinGroupByCodeDialog = false },
            onJoin = { code ->
                val normalizedCode = onboardingManager?.normalizeGroupCode(code) ?: code.replace("-", "")
                // Update both OnboardingManager (for persistence) and MeshService (for active connection)
                onboardingManager?.setCurrentGroupCode(normalizedCode)
                meshService?.setGroupCode(normalizedCode)
                Toast.makeText(context, "Joined group: ${onboardingManager?.formatGroupCode(normalizedCode) ?: normalizedCode}", Toast.LENGTH_SHORT).show()
            },
            isValidCode = { code -> onboardingManager?.isValidGroupCode(code) ?: false },
        )
    }

    // Group history dialogs
    showRenameGroupDialog?.let { group ->
        RenameGroupDialog(
            group = group,
            onDismiss = { showRenameGroupDialog = null },
            onRename = { newName ->
                groupMemoryManager?.renameGroup(group.groupCode, newName)
            },
        )
    }

    if (showClearHistoryDialog) {
        ClearHistoryDialog(
            onDismiss = { showClearHistoryDialog = false },
            onConfirm = {
                groupMemoryManager?.clearHistory()
                Toast.makeText(context, "Group history cleared", Toast.LENGTH_SHORT).show()
            },
        )
    }
}
