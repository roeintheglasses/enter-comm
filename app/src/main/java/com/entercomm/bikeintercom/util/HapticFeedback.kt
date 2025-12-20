package com.entercomm.bikeintercom.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Haptic feedback utility for cycling-optimized touch feedback.
 * Provides strong, distinct vibrations that can be felt through gloves.
 * Supports intensity scaling via AccessibilityManager settings.
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
     * Scale amplitude by intensity (0.0 to 1.5+).
     * Returns value clamped to valid amplitude range (1-255).
     */
    private fun scaleAmplitude(baseAmplitude: Int, intensity: Float): Int {
        return (baseAmplitude * intensity).toInt().coerceIn(1, 255)
    }

    /**
     * Scale duration by intensity for older devices.
     */
    private fun scaleDuration(baseDuration: Long, intensity: Float): Long {
        return (baseDuration * intensity).toLong().coerceAtLeast(1)
    }

    /**
     * Heavy tick feedback for important actions like PTT button press.
     * Stronger than standard click for better feedback through cycling gloves.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun heavyClick(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(255, intensity)
            vibrator.vibrate(VibrationEffect.createOneShot(50, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(scaleDuration(50, intensity))
        }
    }

    /**
     * Standard click feedback for buttons and interactive elements.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun click(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(180, intensity)
            vibrator.vibrate(VibrationEffect.createOneShot(20, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(scaleDuration(20, intensity))
        }
    }

    /**
     * Double click feedback for toggle actions.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun doubleClick(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(200, intensity)
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 30, 50, 30),
                    intArrayOf(0, amplitude, 0, amplitude),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                longArrayOf(0, scaleDuration(30, intensity), 50, scaleDuration(30, intensity)),
                -1,
            )
        }
    }

    /**
     * Tick feedback for selection changes and minor interactions.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun tick(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(100, intensity)
            vibrator.vibrate(VibrationEffect.createOneShot(10, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(scaleDuration(10, intensity))
        }
    }

    /**
     * Error feedback for failed actions.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun error(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(255, intensity)
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 50, 100),
                    intArrayOf(0, amplitude, 0, amplitude),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
    }

    /**
     * Success feedback for completed actions.
     * @param intensity Multiplier for haptic strength (default 1.0, range 0.0-1.5+)
     */
    fun success(context: Context, intensity: Float = 1.0f) {
        if (intensity <= 0f) return
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = scaleAmplitude(220, intensity)
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 50, 30, 100),
                    intArrayOf(0, amplitude / 2, 0, amplitude),
                    -1,
                ),
            )
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
 * Supports optional intensity parameter from AccessibilityManager.getHapticIntensity().
 */
class HapticFeedbackHelper(private val context: Context) {
    fun heavyClick(intensity: Float = 1.0f) = HapticFeedback.heavyClick(context, intensity)
    fun click(intensity: Float = 1.0f) = HapticFeedback.click(context, intensity)
    fun doubleClick(intensity: Float = 1.0f) = HapticFeedback.doubleClick(context, intensity)
    fun tick(intensity: Float = 1.0f) = HapticFeedback.tick(context, intensity)
    fun error(intensity: Float = 1.0f) = HapticFeedback.error(context, intensity)
    fun success(intensity: Float = 1.0f) = HapticFeedback.success(context, intensity)
}
