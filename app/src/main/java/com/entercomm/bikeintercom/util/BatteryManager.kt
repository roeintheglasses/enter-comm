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

    /**
     * Get battery-aware discovery interval for mesh network scanning.
     *
     * @param batteryLevel Current battery level (0-100)
     * @return Discovery interval in milliseconds
     *   - 0-20%: 120,000ms (2 minutes) - battery critical
     *   - 21-50%: 60,000ms (1 minute) - battery low
     *   - 51-100%: 30,000ms (30 seconds) - normal operation
     */
    fun getDiscoveryIntervalForBattery(batteryLevel: Int): Long = when (batteryLevel) {
        in 0..20 -> 120_000L // 2 minutes when battery critical
        in 21..50 -> 60_000L // 1 minute when battery low
        else -> 30_000L // 30 seconds normally
    }
}
