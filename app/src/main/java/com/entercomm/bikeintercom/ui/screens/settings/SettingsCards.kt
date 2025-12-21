package com.entercomm.bikeintercom.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.MeshTopology
import com.entercomm.bikeintercom.onboarding.ConnectionMode
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.onboarding.UserPreferences
import com.entercomm.bikeintercom.ui.components.EnhancedNetworkTopology
import com.entercomm.bikeintercom.ui.components.SettingsSlider
import com.entercomm.bikeintercom.ui.components.SettingsToggle
import com.entercomm.bikeintercom.ui.theme.DarkSurface
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechOrange
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.ui.theme.TextSecondary
import com.entercomm.bikeintercom.ui.theme.TextTertiary
import com.entercomm.bikeintercom.util.AccessibilityManager
import com.entercomm.bikeintercom.util.AccessibilitySettings

@Composable
private fun ProfileInfoRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ProfileSettingsCard(userPrefs: UserPreferences?, onboardingManager: OnboardingManager?) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, null, tint = TechCyan, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Profile", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            ProfileInfoRow("Nickname", userPrefs?.nickname ?: "Rider")
            Spacer(modifier = Modifier.height(8.dp))
            ProfileInfoRow(
                label = "Group Code",
                value = userPrefs?.currentGroupCode?.let { onboardingManager?.formatGroupCode(it) } ?: "None",
                valueColor = if (userPrefs?.currentGroupCode != null) TechCyan else TextTertiary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProfileInfoRow(
                label = "Connection Mode",
                value = when (userPrefs?.connectionMode) {
                    ConnectionMode.GROUP_MODE -> "Group Only"
                    ConnectionMode.OPEN_MODE -> "Open Mode"
                    else -> "Group Only"
                },
                valueColor = if (userPrefs?.connectionMode == ConnectionMode.OPEN_MODE) TechOrange else TechGreen,
            )
        }
    }
}

/**
 * Voice Feedback settings card with enable toggle, volume slider, and speech rate slider.
 */
@Composable
fun VoiceFeedbackCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
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
fun HapticFeedbackCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
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
fun DisplayAccessibilityCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager, onRestartRequired: () -> Unit = {}) {
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
                    onRestartRequired()
                },
            )

            // High contrast mode toggle
            SettingsToggle(
                label = "High Contrast Mode",
                description = "Enhanced contrast for visibility in bright conditions",
                checked = settings.highContrastMode,
                onCheckedChange = { enabled ->
                    accessibilityManager.updateSetting { it.copy(highContrastMode = enabled) }
                    onRestartRequired()
                },
            )
        }
    }
}

/**
 * Riding Mode settings card with volume PTT.
 */
@Composable
fun RidingModeCard(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            RidingModeCardHeader()
            Spacer(modifier = Modifier.height(12.dp))
            RidingModeCardContent(settings, accessibilityManager)
        }
    }
}

@Composable
private fun RidingModeCardHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.TwoWheeler,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Riding Mode",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RidingModeCardContent(settings: AccessibilitySettings, accessibilityManager: AccessibilityManager) {
    // Volume button PTT toggle
    SettingsToggle(
        label = "Volume Button PTT",
        description = "Use volume buttons for push-to-talk",
        checked = settings.volumeButtonPtt,
        onCheckedChange = { enabled ->
            accessibilityManager.updateSetting { it.copy(volumeButtonPtt = enabled) }
        },
    )
}

/**
 * Network topology settings card
 */
@Composable
fun NetworkTopologyCard(meshTopology: MeshTopology?) {
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
fun AboutCard() {
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
