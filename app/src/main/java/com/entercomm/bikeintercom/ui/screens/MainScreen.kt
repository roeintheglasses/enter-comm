package com.entercomm.bikeintercom.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.location.RadarData
import com.entercomm.bikeintercom.mesh.*
import com.entercomm.bikeintercom.onboarding.ConnectionMode
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.onboarding.UserPreferences
import com.entercomm.bikeintercom.ui.components.*
import com.entercomm.bikeintercom.ui.theme.*
import com.entercomm.bikeintercom.util.AccessibilityManager
import com.entercomm.bikeintercom.util.AccessibilitySettings
import com.entercomm.bikeintercom.util.rememberHapticFeedback
import kotlinx.coroutines.delay

/**
 * App state that drives all animations cohesively
 */
enum class AppMode {
    INITIALIZING,
    STANDBY,
    CONNECTING,
    ACTIVE,
    TRANSMITTING,
}

/**
 * Navigation tabs for the app
 */
enum class NavigationTab {
    INTERCOM,
    GROUP,
    RADAR,
    SETTINGS,
}

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

    // Dialog states
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf<MeshGroup?>(null) }
    var showJoinGroupByCodeDialog by remember { mutableStateOf(false) }

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
                        meshTopology = meshTopology,
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

                    GroupContent(
                        currentGroup = currentGroup,
                        members = members,
                        nickname = nickname,
                        groupCode = formattedGroupCode,
                        availableGroups = groupManager?.getAvailableGroups() ?: emptyList(),
                        isOwner = groupManager?.isOwner() ?: false,
                        localNodeId = meshService?.getMeshNetworkManager()?.let { "" } ?: "",
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
                    )
                }
                NavigationTab.RADAR -> {
                    // Debug log when radar tab is displayed
                    SideEffect {
                        android.util.Log.d("MainScreen", "Rendering RADAR tab: isLocationTracking=$isLocationTracking, meshService=${meshService != null}, locationManager=${locationManager != null}")
                    }
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
}

/**
 * Intercom tab content
 */
@Composable
private fun IntercomContent(
    appMode: AppMode,
    audioLevel: Float,
    serviceState: ServiceState,
    @Suppress("UNUSED_PARAMETER") meshTopology: MeshTopology?,
    onPTTPress: () -> Unit,
    onStartStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack),
    ) {
        // Animated background glow based on app mode
        AnimatedBackgroundGlow(appMode = appMode, audioLevel = audioLevel)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top status section
            StatusHeader(
                appMode = appMode,
                connectedDevices = serviceState.connectedDevices,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(0.15f))

            // Central PTT section - the hero
            PTTHeroSection(
                appMode = appMode,
                audioLevel = audioLevel,
                isRecording = serviceState.isRecording,
                onPTTPress = onPTTPress,
                onStartStop = onStartStop,
                modifier = Modifier.weight(0.5f),
            )

            Spacer(modifier = Modifier.weight(0.1f))

            // Bottom info section
            BottomInfoSection(
                appMode = appMode,
                connectedDevices = serviceState.connectedDevices,
                isRunning = serviceState.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Group tab content
 */
@Composable
private fun GroupContent(
    currentGroup: MeshGroup?,
    members: List<GroupMember>,
    nickname: String,
    groupCode: String?,
    availableGroups: List<MeshGroup>,
    isOwner: Boolean,
    localNodeId: String,
    onCreateGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onJoinGroup: (MeshGroup) -> Unit,
    onJoinGroupByCode: () -> Unit,
    onKickMember: (String) -> Unit,
    onBanMember: (String) -> Unit,
    onChannelChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Group & Channel",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        // Group info card
        GroupInfoCard(
            group = currentGroup,
            memberCount = members.size,
            nickname = nickname,
            groupCode = groupCode,
            onLeaveGroup = onLeaveGroup,
            onCreateGroup = onCreateGroup,
            onJoinGroupByCode = onJoinGroupByCode,
        )

        // Channel selector (if in group and owner)
        if (currentGroup != null && isOwner) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ChannelSelector(
                        currentChannel = currentGroup.channelNumber,
                        onChannelChange = onChannelChange,
                        enabled = isOwner,
                    )
                }
            }
        }

        // Member list
        if (currentGroup != null && members.isNotEmpty()) {
            MemberList(
                members = members,
                localNodeId = localNodeId,
                isOwner = isOwner,
                onKickMember = onKickMember,
                onBanMember = onBanMember,
            )
        }

        // Available groups (if not in a group)
        if (currentGroup == null && availableGroups.isNotEmpty()) {
            AvailableGroupsList(
                groups = availableGroups,
                onJoinGroup = onJoinGroup,
            )
        }
    }
}

/**
 * Radar tab content
 */
@Composable
private fun RadarContent(radarData: RadarData, isTracking: Boolean, onStartTracking: () -> Unit, onStopTracking: () -> Unit, onRangeChange: () -> Unit) {
    val hasLocation = radarData.localLocation != null
    val peersInRange = radarData.peersInRange()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Header section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    text = "Rider Radar",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isTracking -> "GPS disabled"
                        !hasLocation -> "Acquiring signal..."
                        peersInRange.isEmpty() -> "No riders nearby"
                        else -> "${peersInRange.size} rider${if (peersInRange.size > 1) "s" else ""} in range"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !isTracking -> TextTertiary
                        !hasLocation -> TechOrange
                        peersInRange.isEmpty() -> TextSecondary
                        else -> TechGreen
                    },
                )
            }

            // Status indicator pill
            if (isTracking) {
                Row(
                    modifier = Modifier
                        .background(
                            color = if (hasLocation) TechGreen.copy(alpha = 0.15f) else TechOrange.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!hasLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = TechOrange,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(TechGreen, CircleShape),
                        )
                    }
                    Text(
                        text = if (hasLocation) "LIVE" else "GPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasLocation) TechGreen else TechOrange,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Location info bar (when available)
        AnimatedVisibility(
            visible = hasLocation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            radarData.localLocation?.let { loc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = TechGreen,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = "Your Location",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                            )
                            Text(
                                text = "%.4f, %.4f".format(loc.latitude, loc.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (loc.speed > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = TechCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "${(loc.speed * 3.6).toInt()} km/h",
                                style = MaterialTheme.typography.bodySmall,
                                color = TechCyan,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // Acquiring GPS message
        AnimatedVisibility(
            visible = isTracking && !hasLocation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TechOrange.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = TechOrange,
                )
                Text(
                    text = "Searching for GPS signal...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TechOrange,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Radar view (main content)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            RadarWithPeerList(
                radarData = radarData,
                onRangeChange = onRangeChange,
                modifier = Modifier.fillMaxSize(),
            )

            // Empty state overlay when GPS is off
            if (!isTracking) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PitchBlack.copy(alpha = 0.85f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "GPS Disabled",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable GPS to see nearby riders",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // GPS toggle button at bottom
        Button(
            onClick = {
                if (isTracking) onStopTracking() else onStartTracking()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isTracking && hasLocation -> TechGreen
                    isTracking -> TechOrange
                    else -> DarkSurfaceElevated
                },
                contentColor = when {
                    isTracking -> Color.White
                    else -> TechCyan
                },
            ),
            border = if (!isTracking) BorderStroke(1.5.dp, TechCyan.copy(alpha = 0.6f)) else null,
        ) {
            Icon(
                imageVector = when {
                    isTracking && hasLocation -> Icons.Default.LocationOn
                    isTracking -> Icons.Default.GpsNotFixed
                    else -> Icons.Default.LocationOff
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when {
                    isTracking && hasLocation -> "GPS Active"
                    isTracking -> "Acquiring GPS..."
                    else -> "Enable GPS"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Settings tab content
 */
@Suppress("LongMethod") // LongMethod: pre-existing due to multiple settings sections
@Composable
private fun SettingsContent(meshTopology: MeshTopology?, onboardingManager: OnboardingManager?, accessibilityManager: AccessibilityManager?) {
    val userPrefs by onboardingManager?.userPreferences?.collectAsState()
        ?: remember { mutableStateOf(null) }

    // Observe accessibility settings
    val accessibilitySettings by accessibilityManager?.settings?.collectAsState()
        ?: remember { mutableStateOf(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        // Profile section
        ProfileSettingsCard(userPrefs, onboardingManager)

        // Voice Feedback section
        if (accessibilitySettings != null && accessibilityManager != null) {
            VoiceFeedbackCard(accessibilitySettings!!, accessibilityManager)
        }

        // Haptic Feedback section
        if (accessibilitySettings != null && accessibilityManager != null) {
            HapticFeedbackCard(accessibilitySettings!!, accessibilityManager)
        }

        // Display Accessibility section
        if (accessibilitySettings != null && accessibilityManager != null) {
            DisplayAccessibilityCard(accessibilitySettings!!, accessibilityManager)
        }

        // Network topology section
        NetworkTopologyCard(meshTopology)

        // About section
        AboutCard()
    }
}

/**
 * Profile settings card
 */
@Composable
private fun ProfileSettingsCard(userPrefs: UserPreferences?, onboardingManager: OnboardingManager?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Nickname
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Nickname",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    text = userPrefs?.nickname ?: "Rider",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Group Code
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Group Code",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    text = userPrefs?.currentGroupCode?.let {
                        onboardingManager?.formatGroupCode(it)
                    } ?: "None",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (userPrefs?.currentGroupCode != null) TechCyan else TextTertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Connection Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    text = when (userPrefs?.connectionMode) {
                        ConnectionMode.GROUP_MODE -> "Group Only"
                        ConnectionMode.OPEN_MODE -> "Open Mode"
                        else -> "Group Only"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (userPrefs?.connectionMode) {
                        ConnectionMode.OPEN_MODE -> TechOrange
                        else -> TechGreen
                    },
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Voice Feedback settings card with enable toggle, volume slider, and speech rate slider.
 */
@Composable
private fun VoiceFeedbackCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Voice Feedback",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Enable/Disable toggle
            SettingsToggle(
                label = "Voice Feedback",
                description = "Announce events and status changes",
                checked = settings.voiceFeedbackEnabled,
                onCheckedChange = { enabled ->
                    accessibilityManager.updateSetting { it.copy(voiceFeedbackEnabled = enabled) }
                },
            )

            // Volume slider
            SettingsSlider(
                label = "Volume",
                value = settings.voiceVolume,
                onValueChange = { volume ->
                    accessibilityManager.updateSetting { it.copy(voiceVolume = volume) }
                },
                valueRange = 0f..1f,
                enabled = settings.voiceFeedbackEnabled,
            )

            // Speech rate slider
            SettingsSlider(
                label = "Speech Rate",
                value = settings.speechRate,
                onValueChange = { rate ->
                    accessibilityManager.updateSetting { it.copy(speechRate = rate) }
                },
                valueRange = 0.5f..2f,
                valueFormatter = { "%.1fx".format(it) },
                enabled = settings.voiceFeedbackEnabled,
            )
        }
    }
}

/**
 * Haptic Feedback settings card with enhanced haptics toggle and intensity slider.
 */
@Composable
private fun HapticFeedbackCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Vibration,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Haptic Feedback",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Enhanced haptics toggle
            SettingsToggle(
                label = "Enhanced Haptics",
                description = "Stronger vibration feedback for actions",
                checked = settings.enhancedHaptics,
                onCheckedChange = { enabled ->
                    accessibilityManager.updateSetting { it.copy(enhancedHaptics = enabled) }
                },
            )

            // Haptic intensity slider
            SettingsSlider(
                label = "Intensity",
                value = settings.hapticIntensity,
                onValueChange = { intensity ->
                    accessibilityManager.updateSetting { it.copy(hapticIntensity = intensity) }
                },
                valueRange = 0f..1f,
            )
        }
    }
}

/**
 * Display Accessibility settings card with large text mode and high contrast mode toggles.
 */
@Composable
private fun DisplayAccessibilityCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Display",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Large text mode toggle
            SettingsToggle(
                label = "Large Text Mode",
                description = "Increase text size for better readability",
                checked = settings.largeTextMode,
                onCheckedChange = { enabled ->
                    accessibilityManager.updateSetting { it.copy(largeTextMode = enabled) }
                },
            )

            // High contrast mode toggle
            SettingsToggle(
                label = "High Contrast Mode",
                description = "Enhanced contrast for visibility in bright conditions",
                checked = settings.highContrastMode,
                onCheckedChange = { enabled ->
                    accessibilityManager.updateSetting { it.copy(highContrastMode = enabled) }
                },
            )
        }
    }
}

/**
 * Network topology settings card
 */
@Composable
private fun NetworkTopologyCard(meshTopology: MeshTopology?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Network Topology",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (meshTopology != null && meshTopology.nodes.isNotEmpty()) {
                EnhancedNetworkTopology(
                    topology = meshTopology,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No network connections",
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * About card
 */
@Composable
private fun AboutCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter-Comm v1.0",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text = "WiFi Direct Mesh Intercom",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

/**
 * Animated background glow that responds to app state
 */
@Composable
private fun AnimatedBackgroundGlow(appMode: AppMode, audioLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "bgGlow")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val glowColor by animateColorAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> TechCyan.copy(alpha = 0.05f)
            AppMode.STANDBY -> TechGreen.copy(alpha = 0.03f)
            AppMode.CONNECTING -> TechOrange.copy(alpha = 0.08f)
            AppMode.ACTIVE -> TechGreen.copy(alpha = 0.06f)
            AppMode.TRANSMITTING -> TechRed.copy(alpha = 0.1f + audioLevel * 0.15f)
        },
        animationSpec = tween(500, easing = EaseOutCubic),
        label = "glowColor",
    )

    val glowScale = if (appMode == AppMode.TRANSMITTING) {
        1f + audioLevel * 0.3f
    } else {
        pulseScale
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .scale(glowScale)
            .blur(100.dp),
    ) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.45f),
                radius = size.minDimension * 0.8f,
            ),
        )
    }
}

/**
 * Top status header with mode indicator
 */
@Composable
private fun StatusHeader(appMode: AppMode, connectedDevices: Int, modifier: Modifier = Modifier) {
    val statusAlpha by animateFloatAsState(
        targetValue = if (appMode == AppMode.INITIALIZING) 0.5f else 1f,
        animationSpec = tween(300),
        label = "statusAlpha",
    )

    Row(
        modifier = modifier.alpha(statusAlpha),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App title with status
        Column {
            Text(
                text = "ENTER-COMM",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(4.dp))

            StatusIndicator(appMode = appMode)
        }

        // Connected devices badge
        AnimatedVisibility(
            visible = appMode == AppMode.ACTIVE || appMode == AppMode.TRANSMITTING,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            DeviceCountBadge(count = connectedDevices)
        }
    }
}

/**
 * Animated status indicator
 */
@Composable
private fun StatusIndicator(appMode: AppMode) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")

    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )

    val statusColor by animateColorAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> TechCyan
            AppMode.STANDBY -> TextTertiary
            AppMode.CONNECTING -> TechOrange
            AppMode.ACTIVE -> TechGreen
            AppMode.TRANSMITTING -> TechRed
        },
        animationSpec = tween(300),
        label = "statusColor",
    )

    val statusText = when (appMode) {
        AppMode.INITIALIZING -> "INITIALIZING"
        AppMode.STANDBY -> "STANDBY"
        AppMode.CONNECTING -> "CONNECTING"
        AppMode.ACTIVE -> "ACTIVE"
        AppMode.TRANSMITTING -> "ON AIR"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (appMode != AppMode.STANDBY) dotScale else 1f)
                .clip(CircleShape)
                .background(statusColor),
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Connected devices badge
 */
@Composable
private fun DeviceCountBadge(count: Int) {
    Row(
        modifier = Modifier
            .background(DarkSurfaceVariant, RoundedCornerShape(20.dp))
            .border(1.dp, TechGreen.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = TechGreen,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = TechGreen,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Central PTT hero section - the main interaction area
 * Uses fixed size container to prevent layout jumps during transitions
 */
@Composable
private fun PTTHeroSection(appMode: AppMode, audioLevel: Float, isRecording: Boolean, onPTTPress: () -> Unit, onStartStop: () -> Unit, modifier: Modifier = Modifier) {
    // Fixed size container prevents layout jumps
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp), // Fixed height for both states
        contentAlignment = Alignment.Center,
    ) {
        // Crossfade between START and PTT buttons
        Crossfade(
            targetState = appMode == AppMode.STANDBY || appMode == AppMode.INITIALIZING,
            animationSpec = tween(300, easing = EaseInOutCubic),
            label = "heroTransition",
        ) { isStandby ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isStandby) {
                    StartButton(
                        onClick = onStartStop,
                        enabled = appMode != AppMode.INITIALIZING,
                    )
                } else {
                    PTTButton(
                        isRecording = isRecording,
                        audioLevel = audioLevel,
                        onPress = onPTTPress,
                        onDisconnect = onStartStop,
                    )
                }
            }
        }
    }
}

/**
 * Animated START button
 */
@Composable
private fun StartButton(onClick: () -> Unit, enabled: Boolean) {
    val haptic = rememberHapticFeedback()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "startScale",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "startPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    val buttonAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(300),
        label = "buttonAlpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.alpha(buttonAlpha),
    ) {
        // Pulsing outer ring
        if (enabled) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(ringScale)
                    .border(
                        width = 2.dp,
                        color = TechGreen.copy(alpha = ringAlpha),
                        shape = CircleShape,
                    ),
            )
        }

        // Main button
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TechGreen.copy(alpha = 0.2f),
                            TechGreenDark.copy(alpha = 0.1f),
                            Color.Transparent,
                        ),
                    ),
                )
                .border(3.dp, TechGreen, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                ) {
                    haptic.heavyClick()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Start",
                    tint = TechGreen,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "START",
                    style = MaterialTheme.typography.titleMedium,
                    color = TechGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

/**
 * Audio level ring animation for PTT button
 */
@Composable
private fun AudioLevelRing(index: Int, audioLevel: Float, infiniteTransition: InfiniteTransition) {
    val delay = index * 200
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f + index * 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = delay, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "audioRing$index",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = delay, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "audioRingAlpha$index",
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(ringScale * (1f + audioLevel * 0.2f))
            .border(width = 2.dp, color = TechRed.copy(alpha = ringAlpha * audioLevel), shape = CircleShape),
    )
}

/**
 * Rotating accent ring around PTT button
 */
@Composable
private fun RotatingAccentRing(color: Color, rotation: Float) {
    Canvas(modifier = Modifier.size(180.dp).rotate(rotation)) {
        val strokeWidth = 3.dp.toPx()
        drawArc(color.copy(alpha = 0.5f), 0f, 60f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawArc(color.copy(alpha = 0.5f), 180f, 60f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}

/**
 * Core circular PTT button with mic icon
 */
@Composable
private fun PTTButtonCore(isRecording: Boolean, buttonColor: Color, scale: Float, interactionSource: MutableInteractionSource, onPress: () -> Unit) {
    val haptic = rememberHapticFeedback()
    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    listOf(buttonColor.copy(alpha = 0.25f), buttonColor.copy(alpha = 0.1f), Color.Transparent),
                ),
            )
            .border(4.dp, buttonColor, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null) {
                haptic.heavyClick()
                onPress()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isRecording) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = if (isRecording) "Stop" else "Talk",
                tint = buttonColor,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRecording) "RELEASE" else "PUSH",
                style = MaterialTheme.typography.titleSmall,
                color = buttonColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = if (isRecording) "TO STOP" else "TO TALK",
                style = MaterialTheme.typography.labelSmall,
                color = buttonColor.copy(alpha = 0.7f),
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * End session button
 */
@Composable
private fun EndSessionButton(onDisconnect: () -> Unit) {
    val haptic = rememberHapticFeedback()
    OutlinedButton(
        onClick = {
            haptic.error()
            onDisconnect()
        },
        modifier = Modifier.height(52.dp).widthIn(min = 160.dp),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.5.dp, TechRed.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TechRed),
    ) {
        Icon(Icons.Rounded.CallEnd, null, Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text("End Session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}

/**
 * Main PTT (Push-to-Talk) button with audio visualization
 */
@Composable
private fun PTTButton(isRecording: Boolean, audioLevel: Float, onPress: () -> Unit, onDisconnect: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val infiniteTransition = rememberInfiniteTransition(label = "pttAnim")

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isRecording -> 1.02f + audioLevel * 0.05f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "pttScale",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) TechRed else TechGreen,
        animationSpec = tween(200),
        label = "pttColor",
    )
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringRotation",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (isRecording) {
                repeat(3) { index -> AudioLevelRing(index, audioLevel, infiniteTransition) }
            }
            RotatingAccentRing(buttonColor, ringRotation)
            PTTButtonCore(isRecording, buttonColor, scale, interactionSource, onPress)
        }
        Spacer(modifier = Modifier.height(40.dp))
        EndSessionButton(onDisconnect)
    }
}

/**
 * Bottom info section with network details
 */
@Composable
private fun BottomInfoSection(appMode: AppMode, connectedDevices: Int, isRunning: Boolean, modifier: Modifier = Modifier) {
    val contentAlpha by animateFloatAsState(
        targetValue = when (appMode) {
            AppMode.INITIALIZING -> 0.3f
            AppMode.STANDBY -> 0.5f
            else -> 1f
        },
        animationSpec = tween(300),
        label = "bottomAlpha",
    )

    Column(
        modifier = modifier.alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Network status cards
        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoCard(
                    icon = Icons.Rounded.Wifi,
                    label = "NETWORK",
                    value = if (isRunning) "MESH" else "OFF",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f),
                )
                InfoCard(
                    icon = Icons.Rounded.Group,
                    label = "RIDERS",
                    value = "$connectedDevices",
                    isActive = connectedDevices > 0,
                    modifier = Modifier.weight(1f),
                )
                InfoCard(
                    icon = Icons.Rounded.SignalCellularAlt,
                    label = "SIGNAL",
                    value = if (isRunning) "GOOD" else "--",
                    isActive = isRunning,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Help text for standby mode
        AnimatedVisibility(
            visible = !isRunning,
            enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
            exit = fadeOut(),
        ) {
            Text(
                text = "Tap START to create mesh network\nand connect with nearby riders",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * Info card for bottom section
 */
@Composable
private fun InfoCard(icon: ImageVector, label: String, value: String, isActive: Boolean, modifier: Modifier = Modifier) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) TechGreen.copy(alpha = 0.3f) else DarkBorder,
        animationSpec = tween(300),
        label = "borderColor",
    )

    val iconColor by animateColorAsState(
        targetValue = if (isActive) TechGreen else TextTertiary,
        animationSpec = tween(300),
        label = "iconColor",
    )

    Column(
        modifier = modifier
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (isActive) TextPrimary else TextTertiary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            letterSpacing = 0.5.sp,
        )
    }
}
