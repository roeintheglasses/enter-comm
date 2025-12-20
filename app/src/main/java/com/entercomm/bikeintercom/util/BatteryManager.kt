package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager as AndroidBatteryManager

/**
 * Centralized battery level utility.
 * - Provides battery level detection for battery-aware features
 * - Handles errors gracefully with sensible defaults
 * - Supports test mode for unit testing
 */
object BatteryManager {

    // Test mode - disables actual Android battery queries for unit tests
    var isTestMode: Boolean = false

    // Battery level to return in test mode (defaults to 100)
    var testBatteryLevel: Int = 100

    /**
     * Get current battery level (0-100).
     *
     * @param context Android context for accessing battery status
     * @return Battery percentage 0-100, or 100 on error (assume full to avoid over-aggressive power saving)
     */
    @Suppress("TooGenericExceptionCaught")
    fun getBatteryLevel(context: Context): Int {
        // In test mode, return the configured test battery level
        if (isTestMode) {
            return testBatteryLevel.coerceIn(0, 100)
        }

        return try {
            val batteryStatus = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = batteryStatus?.getIntExtra(AndroidBatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(AndroidBatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) {
                level * 100 / scale
            } else {
                100 // Default to full if unknown
            }
        } catch (e: Exception) {
            Logger.w("BatteryManager", { "Failed to get battery level: ${e.message}" })
            100 // Default to full on error
        }
    }
}
