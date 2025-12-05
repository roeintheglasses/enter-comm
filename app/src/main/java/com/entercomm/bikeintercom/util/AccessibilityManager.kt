package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
    val boneConduction: BoneConductionMode = BoneConductionMode.AUTO,
    val oneHandedMode: Boolean = true,
    val volumeButtonPtt: Boolean = false,
    val swipeGesturesEnabled: Boolean = true,
)

enum class BoneConductionMode {
    AUTO, // Detect automatically
    ENABLED, // Always use bone conduction optimizations
    DISABLED, // Never use bone conduction optimizations
}

/**
 * Manages accessibility features including voice feedback, haptics,
 * bone conduction detection, and one-handed operation modes.
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
        private const val KEY_BONE_CONDUCTION = "bone_conduction"
        private const val KEY_ONE_HANDED = "one_handed"
        private const val KEY_VOLUME_PTT = "volume_ptt"
        private const val KEY_SWIPE_GESTURES = "swipe_gestures"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Voice feedback
    val voiceFeedback = VoiceFeedback(context)

    // Settings state
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AccessibilitySettings> = _settings.asStateFlow()

    // Bone conduction detection
    private val _isBoneConductionDetected = MutableStateFlow(false)
    val isBoneConductionDetected: StateFlow<Boolean> = _isBoneConductionDetected.asStateFlow()

    /**
     * Initialize the accessibility manager.
     */
    fun initialize() {
        voiceFeedback.initialize()
        applySettings(_settings.value)
        detectBoneConductionHeadset()
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
     * Check if bone conduction optimizations should be active.
     */
    fun shouldUseBoneConductionOptimizations(): Boolean {
        return when (_settings.value.boneConduction) {
            BoneConductionMode.ENABLED -> true
            BoneConductionMode.DISABLED -> false
            BoneConductionMode.AUTO -> _isBoneConductionDetected.value
        }
    }

    /**
     * Detect if a bone conduction headset is connected.
     */
    fun detectBoneConductionHeadset(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                // Check for bone conduction or similar headsets
                // TYPE_HEARING_AID, TYPE_BLE_HEADSET, or check product name
                if (device.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    isBoneConductionDevice(device)
                ) {
                    _isBoneConductionDetected.value = true
                    logD { "Bone conduction headset detected: ${device.productName}" }
                    return true
                }
            }
        }
        _isBoneConductionDetected.value = false
        return false
    }

    private fun isBoneConductionDevice(device: AudioDeviceInfo): Boolean {
        // productName requires API 23+
        val productName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            device.productName?.toString()?.lowercase() ?: ""
        } else {
            ""
        }
        val boneConductionKeywords = listOf(
            "bone", "shokz", "aftershokz", "openrun", "opencomm",
            "aeropex", "trekz", "titanium", "air", "conduction",
        )
        return boneConductionKeywords.any { productName.contains(it) }
    }

    /**
     * Get audio processing parameters optimized for bone conduction.
     * Bone conduction headsets work better with:
     * - Boosted mid frequencies (voice clarity)
     * - Reduced bass (doesn't translate well)
     * - Higher overall volume
     */
    fun getBoneConductionAudioParams(): BoneConductionParams {
        return if (shouldUseBoneConductionOptimizations()) {
            BoneConductionParams(
                bassReduction = 0.5f, // Reduce bass by 50%
                midBoost = 1.3f, // Boost mids by 30%
                trebleBoost = 1.1f, // Slight treble boost
                volumeMultiplier = 1.2f, // 20% volume increase
                agcTargetLevel = 0.4f, // Higher AGC target
            )
        } else {
            BoneConductionParams() // Default params
        }
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
            boneConduction = BoneConductionMode.valueOf(
                prefs.getString(KEY_BONE_CONDUCTION, BoneConductionMode.AUTO.name)
                    ?: BoneConductionMode.AUTO.name,
            ),
            oneHandedMode = prefs.getBoolean(KEY_ONE_HANDED, true),
            volumeButtonPtt = prefs.getBoolean(KEY_VOLUME_PTT, false),
            swipeGesturesEnabled = prefs.getBoolean(KEY_SWIPE_GESTURES, true),
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
            putString(KEY_BONE_CONDUCTION, settings.boneConduction.name)
            putBoolean(KEY_ONE_HANDED, settings.oneHandedMode)
            putBoolean(KEY_VOLUME_PTT, settings.volumeButtonPtt)
            putBoolean(KEY_SWIPE_GESTURES, settings.swipeGesturesEnabled)
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

/**
 * Audio processing parameters for bone conduction optimization.
 */
data class BoneConductionParams(
    val bassReduction: Float = 1.0f,
    val midBoost: Float = 1.0f,
    val trebleBoost: Float = 1.0f,
    val volumeMultiplier: Float = 1.0f,
    val agcTargetLevel: Float = 0.3f,
)
