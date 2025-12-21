package com.entercomm.bikeintercom.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.MeshTopology
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.ui.theme.PitchBlack
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.util.AccessibilityManager
import kotlinx.coroutines.launch

/**
 * Settings tab content
 */
@Suppress("LongMethod") // LongMethod: pre-existing due to multiple settings sections
@Composable
fun SettingsContent(
    meshTopology: MeshTopology?,
    onboardingManager: OnboardingManager?,
    accessibilityManager: AccessibilityManager?,
) {
    val context = LocalContext.current
    val userPrefs by onboardingManager?.userPreferences?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf(null) }

    // Observe accessibility settings
    val accessibilitySettings by accessibilityManager?.settings?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf(null) }

    // Snackbar state for restart notification
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
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
                DisplayAccessibilityCard(
                    settings = accessibilitySettings!!,
                    accessibilityManager = accessibilityManager,
                    onRestartRequired = {
                        coroutineScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Restart required to apply changes",
                                actionLabel = "Restart Now",
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                restartApp(context)
                            }
                        }
                    },
                )
            }

            // Riding Mode section
            if (accessibilitySettings != null && accessibilityManager != null) {
                RidingModeCard(accessibilitySettings!!, accessibilityManager)
            }

            // Network topology section
            NetworkTopologyCard(meshTopology)

            // About section
            AboutCard()
        }

        // Snackbar host at the bottom
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Restart the app by relaunching the main activity.
 */
private fun restartApp(context: Context) {
    val packageManager = context.packageManager
    val intent = packageManager.getLaunchIntentForPackage(context.packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    if (context is Activity) {
        context.finish()
    }
    Runtime.getRuntime().exit(0)
}
