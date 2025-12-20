package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accessibility settings for the intercom app.
 */
data class AccessibilitySettings(
    val voiceFeedbackEnabled: Boolean = false,
    val voiceVolume: Float = 0.8f,
    val speechRate: Float = 1.1f,
    val enhancedHaptics: Boolean = false,
    val hapticIntensity: Float = 1.0f,
    val largeTextMode: Boolean = false,
    val highContrastMode: Boolean = false,
    val volumeButtonPtt: Boolean = false,
)

/**
 * Manages accessibility features including voice feedback, haptics,
 * and display settings.
 */
class AccessibilityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "accessibility_prefs"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_ENHANCED_HAPTICS = "enhanced_haptics"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_LARGE_TEXT = "large_text"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_VOLUME_PTT = "volume_ptt"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Voice feedback
    val voiceFeedback = VoiceFeedback(context)

    // Settings state
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AccessibilitySettings> = _settings.asStateFlow()

    /**
     * Initialize the accessibility manager.
     */
    fun initialize() {
        voiceFeedback.initialize()
        applySettings(_settings.value)
        logD { "AccessibilityManager initialized" }
    }

    /**
     * Update accessibility settings.
     */
    fun updateSettings(newSettings: AccessibilitySettings) {
        _settings.value = newSettings
        saveSettings(newSettings)
        applySettings(newSettings)
    }

    /**
     * Update a single setting.
     */
    fun updateSetting(update: (AccessibilitySettings) -> AccessibilitySettings) {
        val newSettings = update(_settings.value)
        updateSettings(newSettings)
    }

    /**
     * Get haptic feedback intensity multiplier.
     */
    fun getHapticIntensity(): Float {
        val settings = _settings.value
        return if (settings.enhancedHaptics) {
            settings.hapticIntensity * 1.5f
        } else {
            settings.hapticIntensity
        }
    }

    /**
     * Clean up resources.
     */
    fun shutdown() {
        voiceFeedback.shutdown()
        logD { "AccessibilityManager shutdown" }
    }

    private fun loadSettings(): AccessibilitySettings {
        return AccessibilitySettings(
            voiceFeedbackEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, false),
            voiceVolume = prefs.getFloat(KEY_VOICE_VOLUME, 0.8f),
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, 1.1f),
            enhancedHaptics = prefs.getBoolean(KEY_ENHANCED_HAPTICS, false),
            hapticIntensity = prefs.getFloat(KEY_HAPTIC_INTENSITY, 1.0f),
            largeTextMode = prefs.getBoolean(KEY_LARGE_TEXT, false),
            highContrastMode = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
            volumeButtonPtt = prefs.getBoolean(KEY_VOLUME_PTT, false),
        )
    }

    private fun saveSettings(settings: AccessibilitySettings) {
        prefs.edit().apply {
            putBoolean(KEY_VOICE_ENABLED, settings.voiceFeedbackEnabled)
            putFloat(KEY_VOICE_VOLUME, settings.voiceVolume)
            putFloat(KEY_SPEECH_RATE, settings.speechRate)
            putBoolean(KEY_ENHANCED_HAPTICS, settings.enhancedHaptics)
            putFloat(KEY_HAPTIC_INTENSITY, settings.hapticIntensity)
            putBoolean(KEY_LARGE_TEXT, settings.largeTextMode)
            putBoolean(KEY_HIGH_CONTRAST, settings.highContrastMode)
            putBoolean(KEY_VOLUME_PTT, settings.volumeButtonPtt)
            apply()
        }
    }

    private fun applySettings(settings: AccessibilitySettings) {
        // Apply voice feedback settings
        voiceFeedback.setEnabled(settings.voiceFeedbackEnabled)
        voiceFeedback.setVolume(settings.voiceVolume)
        voiceFeedback.setSpeechRate(settings.speechRate)

        logD { "Applied accessibility settings: $settings" }
    }
}
