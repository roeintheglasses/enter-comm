package com.entercomm.bikeintercom.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.entercomm.bikeintercom.config.AppConfig

/**
 * Haptic feedback utility for cycling-optimized touch feedback.
 * Provides strong, distinct vibrations that can be felt through gloves.
 */
object HapticFeedback {

    /**
     * Get the system vibrator service
     */
    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Heavy tick feedback for important actions like PTT button press.
     * Stronger than standard click for better feedback through cycling gloves.
     */
    fun heavyClick(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /**
     * Standard click feedback for buttons and interactive elements.
     */
    fun click(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
    }

    /**
     * Double click feedback for toggle actions.
     */
    fun doubleClick(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 30, 50, 30), -1)
        }
    }

    /**
     * Tick feedback for selection changes and minor interactions.
     */
    fun tick(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    /**
     * Error feedback for failed actions.
     */
    fun error(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
    }

    /**
     * Success feedback for completed actions.
     */
    fun success(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 30, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 50, 30, 100), -1)
        }
    }
}

/**
 * Composable remember function for haptic feedback.
 */
@Composable
fun rememberHapticFeedback(): HapticFeedbackHelper {
    val context = LocalContext.current
    return remember { HapticFeedbackHelper(context) }
}

/**
 * Helper class for haptic feedback in Composables.
 */
class HapticFeedbackHelper(private val context: Context) {
    fun heavyClick() = HapticFeedback.heavyClick(context)
    fun click() = HapticFeedback.click(context)
    fun doubleClick() = HapticFeedback.doubleClick(context)
    fun tick() = HapticFeedback.tick(context)
    fun error() = HapticFeedback.error(context)
    fun success() = HapticFeedback.success(context)
}
