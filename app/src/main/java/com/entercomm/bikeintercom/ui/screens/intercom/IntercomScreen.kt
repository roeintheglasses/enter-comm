package com.entercomm.bikeintercom.ui.screens.intercom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.ServiceState
import com.entercomm.bikeintercom.ui.components.DiagnosticsBottomSheet
import com.entercomm.bikeintercom.ui.components.DiagnosticsState
import com.entercomm.bikeintercom.ui.components.NetworkDiagnosticsFAB
import com.entercomm.bikeintercom.ui.screens.common.AppMode
import com.entercomm.bikeintercom.ui.theme.PitchBlack

/**
 * Intercom tab content
 */
@Composable
fun IntercomContent(
    appMode: AppMode,
    audioLevel: Float,
    serviceState: ServiceState,
    diagnosticsState: DiagnosticsState,
    showDiagnosticsSheet: Boolean,
    onDiagnosticsClick: () -> Unit,
    onDiagnosticsDismiss: () -> Unit,
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

        // Network diagnostics FAB
        NetworkDiagnosticsFAB(
            onClick = onDiagnosticsClick,
            connectedPeersCount = serviceState.connectedDevices,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    // Diagnostics bottom sheet
    if (showDiagnosticsSheet) {
        DiagnosticsBottomSheet(state = diagnosticsState, onDismiss = onDiagnosticsDismiss)
    }
}
