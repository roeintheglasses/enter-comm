package com.entercomm.bikeintercom.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.R
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.mesh.MeshNetworkService
import com.entercomm.bikeintercom.mesh.ServiceState
import com.entercomm.bikeintercom.util.logD

/**
 * Handles all notification-related operations for the mesh service.
 * Extracted from MeshNetworkService to follow Single Responsibility Principle.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.Service.NOTIFICATION_CHANNEL_ID,
                "Enter-Comm",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mesh network communication"
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
            logD { "Notification channel created" }
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun createNotification(state: ServiceState, isMuted: Boolean): Notification {
        val stopIntent = Intent(context, MeshNetworkService::class.java).apply {
            action = MeshNetworkService.ACTION_STOP_MESH
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteIntent = Intent(context, MeshNetworkService::class.java).apply {
            action = MeshNetworkService.ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            context,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteText = if (isMuted) "Unmute" else "Mute"

        return NotificationCompat.Builder(context, AppConfig.Service.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Enter-Comm Active")
            .setContentText("${state.networkStatus} • ${if (state.isRecording) "Recording" else "Standby"}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_mic_off, muteText, mutePendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }

    @SuppressLint("NotificationPermission")
    fun updateNotification(state: ServiceState, isMuted: Boolean) {
        if (state.isRunning && hasNotificationPermission()) {
            val notification = createNotification(state, isMuted)
            notificationManager.notify(AppConfig.Service.NOTIFICATION_ID, notification)
        }
    }
}
