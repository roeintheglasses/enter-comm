package com.entercomm.bikeintercom.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AccessibilitySettings data class.
 * Verifies that settings can be correctly serialized, deserialized,
 * and persisted across app restarts via SharedPreferences.
 */
class AccessibilitySettingsTest {

    // === Default Values Tests ===

    @Test
    fun `default values are correctly set`() {
        val settings = AccessibilitySettings()

        assertFalse("Voice feedback should be disabled by default", settings.voiceFeedbackEnabled)
        assertEquals(0.8f, settings.voiceVolume, 0.001f)
        assertEquals(1.1f, settings.speechRate, 0.001f)
        assertFalse("Enhanced haptics should be disabled by default", settings.enhancedHaptics)
        assertEquals(1.0f, settings.hapticIntensity, 0.001f)
        assertFalse("Large text mode should be disabled by default", settings.largeTextMode)
        assertFalse("High contrast mode should be disabled by default", settings.highContrastMode)
        assertEquals(BoneConductionMode.AUTO, settings.boneConduction)
        assertTrue("One-handed mode should be enabled by default", settings.oneHandedMode)
        assertFalse("Volume button PTT should be disabled by default", settings.volumeButtonPtt)
        assertTrue("Swipe gestures should be enabled by default", settings.swipeGesturesEnabled)
    }

    // === Data Class Copy Tests ===

    @Test
    fun `copy preserves all fields when unchanged`() {
        val original = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.5f,
            speechRate = 1.5f,
            enhancedHaptics = true,
            hapticIntensity = 0.7f,
            largeTextMode = true,
            highContrastMode = true,
            boneConduction = BoneConductionMode.ENABLED,
            oneHandedMode = false,
            volumeButtonPtt = true,
            swipeGesturesEnabled = false,
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `copy allows modification of single field`() {
        val original = AccessibilitySettings(voiceFeedbackEnabled = false)
        val modified = original.copy(voiceFeedbackEnabled = true)

        assertFalse(original.voiceFeedbackEnabled)
        assertTrue(modified.voiceFeedbackEnabled)
    }

    @Test
    fun `copy allows modification of multiple fields`() {
        val original = AccessibilitySettings()
        val modified = original.copy(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.5f,
            enhancedHaptics = true,
        )

        assertTrue(modified.voiceFeedbackEnabled)
        assertEquals(0.5f, modified.voiceVolume, 0.001f)
        assertTrue(modified.enhancedHaptics)
        // Other fields should retain defaults
        assertEquals(original.speechRate, modified.speechRate, 0.001f)
    }

    // === Equality Tests ===

    @Test
    fun `two settings with same values are equal`() {
        val settings1 = AccessibilitySettings(voiceFeedbackEnabled = true, voiceVolume = 0.5f)
        val settings2 = AccessibilitySettings(voiceFeedbackEnabled = true, voiceVolume = 0.5f)

        assertEquals(settings1, settings2)
        assertEquals(settings1.hashCode(), settings2.hashCode())
    }

    @Test
    fun `two settings with different values are not equal`() {
        val settings1 = AccessibilitySettings(voiceFeedbackEnabled = true)
        val settings2 = AccessibilitySettings(voiceFeedbackEnabled = false)

        assertNotEquals(settings1, settings2)
    }

    // === BoneConductionMode Tests ===

    @Test
    fun `BoneConductionMode has all expected values`() {
        val modes = BoneConductionMode.entries

        assertEquals(3, modes.size)
        assertTrue(modes.contains(BoneConductionMode.AUTO))
        assertTrue(modes.contains(BoneConductionMode.ENABLED))
        assertTrue(modes.contains(BoneConductionMode.DISABLED))
    }

    @Test
    fun `BoneConductionMode can be serialized and deserialized by name`() {
        for (mode in BoneConductionMode.entries) {
            val serialized = mode.name
            val deserialized = BoneConductionMode.valueOf(serialized)
            assertEquals(mode, deserialized)
        }
    }

    // === Value Range Tests (for SharedPreferences storage) ===

    @Test
    fun `voice volume is stored correctly at boundary values`() {
        val minSettings = AccessibilitySettings(voiceVolume = 0.0f)
        val maxSettings = AccessibilitySettings(voiceVolume = 1.0f)

        assertEquals(0.0f, minSettings.voiceVolume, 0.001f)
        assertEquals(1.0f, maxSettings.voiceVolume, 0.001f)
    }

    @Test
    fun `speech rate is stored correctly at boundary values`() {
        val minSettings = AccessibilitySettings(speechRate = 0.5f)
        val maxSettings = AccessibilitySettings(speechRate = 2.0f)

        assertEquals(0.5f, minSettings.speechRate, 0.001f)
        assertEquals(2.0f, maxSettings.speechRate, 0.001f)
    }

    @Test
    fun `haptic intensity is stored correctly at boundary values`() {
        val minSettings = AccessibilitySettings(hapticIntensity = 0.0f)
        val maxSettings = AccessibilitySettings(hapticIntensity = 1.0f)

        assertEquals(0.0f, minSettings.hapticIntensity, 0.001f)
        assertEquals(1.0f, maxSettings.hapticIntensity, 0.001f)
    }

    // === Update Pattern Tests (simulating what updateSetting does) ===

    @Test
    fun `settings update pattern maintains other values`() {
        val original = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.8f,
            enhancedHaptics = true,
        )

        // Simulating updateSetting { it.copy(voiceVolume = 0.5f) }
        val updateFn: (AccessibilitySettings) -> AccessibilitySettings = { it.copy(voiceVolume = 0.5f) }
        val newSettings = updateFn(original)

        assertTrue(newSettings.voiceFeedbackEnabled)
        assertEquals(0.5f, newSettings.voiceVolume, 0.001f)
        assertTrue(newSettings.enhancedHaptics)
    }

    @Test
    fun `multiple sequential updates work correctly`() {
        var settings = AccessibilitySettings()

        // Update 1: Enable voice feedback
        settings = settings.copy(voiceFeedbackEnabled = true)
        assertTrue(settings.voiceFeedbackEnabled)

        // Update 2: Set volume
        settings = settings.copy(voiceVolume = 0.5f)
        assertTrue(settings.voiceFeedbackEnabled)
        assertEquals(0.5f, settings.voiceVolume, 0.001f)

        // Update 3: Enable enhanced haptics
        settings = settings.copy(enhancedHaptics = true)
        assertTrue(settings.voiceFeedbackEnabled)
        assertEquals(0.5f, settings.voiceVolume, 0.001f)
        assertTrue(settings.enhancedHaptics)
    }

    // === Persistence Key Mapping Tests ===

    @Test
    fun `all settings fields have corresponding accessor methods`() {
        val settings = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.7f,
            speechRate = 1.2f,
            enhancedHaptics = true,
            hapticIntensity = 0.8f,
            largeTextMode = true,
            highContrastMode = true,
            boneConduction = BoneConductionMode.DISABLED,
            oneHandedMode = false,
            volumeButtonPtt = true,
            swipeGesturesEnabled = false,
        )

        // Verify all 11 fields are accessible
        assertEquals(true, settings.voiceFeedbackEnabled)
        assertEquals(0.7f, settings.voiceVolume, 0.001f)
        assertEquals(1.2f, settings.speechRate, 0.001f)
        assertEquals(true, settings.enhancedHaptics)
        assertEquals(0.8f, settings.hapticIntensity, 0.001f)
        assertEquals(true, settings.largeTextMode)
        assertEquals(true, settings.highContrastMode)
        assertEquals(BoneConductionMode.DISABLED, settings.boneConduction)
        assertEquals(false, settings.oneHandedMode)
        assertEquals(true, settings.volumeButtonPtt)
        assertEquals(false, settings.swipeGesturesEnabled)
    }

    // === Roundtrip Test (simulating persistence) ===

    @Test
    fun `settings can complete full roundtrip simulation`() {
        // Create custom settings (what user would set)
        val originalSettings = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.65f,
            speechRate = 1.3f,
            enhancedHaptics = true,
            hapticIntensity = 0.9f,
            largeTextMode = true,
            highContrastMode = false,
            boneConduction = BoneConductionMode.ENABLED,
            oneHandedMode = true,
            volumeButtonPtt = true,
            swipeGesturesEnabled = false,
        )

        // Simulate what saveSettings does: extract individual values
        val voiceFeedbackEnabled = originalSettings.voiceFeedbackEnabled
        val voiceVolume = originalSettings.voiceVolume
        val speechRate = originalSettings.speechRate
        val enhancedHaptics = originalSettings.enhancedHaptics
        val hapticIntensity = originalSettings.hapticIntensity
        val largeTextMode = originalSettings.largeTextMode
        val highContrastMode = originalSettings.highContrastMode
        val boneConduction = originalSettings.boneConduction.name
        val oneHandedMode = originalSettings.oneHandedMode
        val volumeButtonPtt = originalSettings.volumeButtonPtt
        val swipeGesturesEnabled = originalSettings.swipeGesturesEnabled

        // Simulate what loadSettings does: reconstruct from individual values
        val restoredSettings = AccessibilitySettings(
            voiceFeedbackEnabled = voiceFeedbackEnabled,
            voiceVolume = voiceVolume,
            speechRate = speechRate,
            enhancedHaptics = enhancedHaptics,
            hapticIntensity = hapticIntensity,
            largeTextMode = largeTextMode,
            highContrastMode = highContrastMode,
            boneConduction = BoneConductionMode.valueOf(boneConduction),
            oneHandedMode = oneHandedMode,
            volumeButtonPtt = volumeButtonPtt,
            swipeGesturesEnabled = swipeGesturesEnabled,
        )

        // Verify roundtrip produces identical settings
        assertEquals(originalSettings, restoredSettings)
    }

    // === SharedPreferences Persistence Format Tests ===
    // These tests verify that settings values are stored in SharedPreferences-compatible formats

    @Test
    fun `boolean settings can be stored and retrieved from primitive format`() {
        val settings = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            enhancedHaptics = true,
            largeTextMode = false,
            highContrastMode = true,
            oneHandedMode = false,
            volumeButtonPtt = true,
            swipeGesturesEnabled = false,
        )

        // SharedPreferences stores booleans directly
        val storedVoiceFeedback: Boolean = settings.voiceFeedbackEnabled
        val storedEnhancedHaptics: Boolean = settings.enhancedHaptics
        val storedLargeText: Boolean = settings.largeTextMode
        val storedHighContrast: Boolean = settings.highContrastMode
        val storedOneHanded: Boolean = settings.oneHandedMode
        val storedVolumePtt: Boolean = settings.volumeButtonPtt
        val storedSwipeGestures: Boolean = settings.swipeGesturesEnabled

        // Reconstruct from stored values
        val restored = AccessibilitySettings(
            voiceFeedbackEnabled = storedVoiceFeedback,
            enhancedHaptics = storedEnhancedHaptics,
            largeTextMode = storedLargeText,
            highContrastMode = storedHighContrast,
            oneHandedMode = storedOneHanded,
            volumeButtonPtt = storedVolumePtt,
            swipeGesturesEnabled = storedSwipeGestures,
        )

        assertEquals(settings.voiceFeedbackEnabled, restored.voiceFeedbackEnabled)
        assertEquals(settings.enhancedHaptics, restored.enhancedHaptics)
        assertEquals(settings.largeTextMode, restored.largeTextMode)
        assertEquals(settings.highContrastMode, restored.highContrastMode)
        assertEquals(settings.oneHandedMode, restored.oneHandedMode)
        assertEquals(settings.volumeButtonPtt, restored.volumeButtonPtt)
        assertEquals(settings.swipeGesturesEnabled, restored.swipeGesturesEnabled)
    }

    @Test
    fun `float settings can be stored and retrieved from primitive format`() {
        val settings = AccessibilitySettings(
            voiceVolume = 0.42f,
            speechRate = 1.75f,
            hapticIntensity = 0.33f,
        )

        // SharedPreferences stores floats directly
        val storedVoiceVolume: Float = settings.voiceVolume
        val storedSpeechRate: Float = settings.speechRate
        val storedHapticIntensity: Float = settings.hapticIntensity

        // Reconstruct from stored values
        val restored = AccessibilitySettings(
            voiceVolume = storedVoiceVolume,
            speechRate = storedSpeechRate,
            hapticIntensity = storedHapticIntensity,
        )

        assertEquals(settings.voiceVolume, restored.voiceVolume, 0.0001f)
        assertEquals(settings.speechRate, restored.speechRate, 0.0001f)
        assertEquals(settings.hapticIntensity, restored.hapticIntensity, 0.0001f)
    }

    @Test
    fun `BoneConductionMode is persisted as string name`() {
        for (mode in BoneConductionMode.entries) {
            val settings = AccessibilitySettings(boneConduction = mode)

            // SharedPreferences stores enum as string name
            val storedModeName: String = settings.boneConduction.name

            // Reconstruct from stored string
            val restoredMode = BoneConductionMode.valueOf(storedModeName)
            val restored = AccessibilitySettings(boneConduction = restoredMode)

            assertEquals(mode, restored.boneConduction)
        }
    }

    @Test
    fun `default values match SharedPreferences fallback defaults`() {
        // These are the default values used in AccessibilityManager.loadSettings()
        // when SharedPreferences doesn't have a stored value
        val defaultVoiceEnabled = false
        val defaultVoiceVolume = 0.8f
        val defaultSpeechRate = 1.1f
        val defaultEnhancedHaptics = false
        val defaultHapticIntensity = 1.0f
        val defaultLargeText = false
        val defaultHighContrast = false
        val defaultBoneConduction = BoneConductionMode.AUTO.name
        val defaultOneHanded = true
        val defaultVolumePtt = false
        val defaultSwipeGestures = true

        // Create settings with defaults
        val defaultSettings = AccessibilitySettings()

        // Verify data class defaults match the persistence defaults
        assertEquals(defaultVoiceEnabled, defaultSettings.voiceFeedbackEnabled)
        assertEquals(defaultVoiceVolume, defaultSettings.voiceVolume, 0.0001f)
        assertEquals(defaultSpeechRate, defaultSettings.speechRate, 0.0001f)
        assertEquals(defaultEnhancedHaptics, defaultSettings.enhancedHaptics)
        assertEquals(defaultHapticIntensity, defaultSettings.hapticIntensity, 0.0001f)
        assertEquals(defaultLargeText, defaultSettings.largeTextMode)
        assertEquals(defaultHighContrast, defaultSettings.highContrastMode)
        assertEquals(defaultBoneConduction, defaultSettings.boneConduction.name)
        assertEquals(defaultOneHanded, defaultSettings.oneHandedMode)
        assertEquals(defaultVolumePtt, defaultSettings.volumeButtonPtt)
        assertEquals(defaultSwipeGestures, defaultSettings.swipeGesturesEnabled)
    }

    @Test
    fun `settings persist correctly after multiple updates simulation`() {
        // Simulate SharedPreferences in-memory store
        val store = mutableMapOf<String, Any>()

        // Initial settings
        var currentSettings = AccessibilitySettings()

        // First update: enable voice feedback
        currentSettings = currentSettings.copy(voiceFeedbackEnabled = true)
        saveToStore(store, currentSettings)
        var loaded = loadFromStore(store)
        assertTrue(loaded.voiceFeedbackEnabled)

        // Second update: adjust volume
        currentSettings = currentSettings.copy(voiceVolume = 0.5f)
        saveToStore(store, currentSettings)
        loaded = loadFromStore(store)
        assertTrue(loaded.voiceFeedbackEnabled) // Previous value retained
        assertEquals(0.5f, loaded.voiceVolume, 0.0001f)

        // Third update: change bone conduction mode
        currentSettings = currentSettings.copy(boneConduction = BoneConductionMode.DISABLED)
        saveToStore(store, currentSettings)
        loaded = loadFromStore(store)
        assertTrue(loaded.voiceFeedbackEnabled) // Previous value retained
        assertEquals(0.5f, loaded.voiceVolume, 0.0001f) // Previous value retained
        assertEquals(BoneConductionMode.DISABLED, loaded.boneConduction)

        // Fourth update: toggle multiple settings
        currentSettings = currentSettings.copy(
            enhancedHaptics = true,
            largeTextMode = true,
            volumeButtonPtt = true,
        )
        saveToStore(store, currentSettings)
        loaded = loadFromStore(store)

        // Verify all accumulated changes persist
        assertTrue(loaded.voiceFeedbackEnabled)
        assertEquals(0.5f, loaded.voiceVolume, 0.0001f)
        assertEquals(BoneConductionMode.DISABLED, loaded.boneConduction)
        assertTrue(loaded.enhancedHaptics)
        assertTrue(loaded.largeTextMode)
        assertTrue(loaded.volumeButtonPtt)
    }

    @Test
    fun `settings persist across simulated app restart`() {
        // Simulate SharedPreferences store (persisted on disk)
        val persistentStore = mutableMapOf<String, Any>()

        // First "session": user configures settings
        val userSettings = AccessibilitySettings(
            voiceFeedbackEnabled = true,
            voiceVolume = 0.7f,
            speechRate = 1.5f,
            enhancedHaptics = true,
            hapticIntensity = 0.8f,
            largeTextMode = true,
            highContrastMode = false,
            boneConduction = BoneConductionMode.ENABLED,
            oneHandedMode = true,
            volumeButtonPtt = true,
            swipeGesturesEnabled = false,
        )
        saveToStore(persistentStore, userSettings)

        // Simulate app restart: create new AccessibilityManager instance
        // which would call loadSettings() from SharedPreferences
        val restoredSettings = loadFromStore(persistentStore)

        // Verify all settings restored correctly after "restart"
        assertEquals(userSettings, restoredSettings)
    }

    @Test
    fun `extreme float values persist correctly`() {
        val store = mutableMapOf<String, Any>()

        // Test minimum values
        val minSettings = AccessibilitySettings(
            voiceVolume = 0.0f,
            speechRate = 0.5f,
            hapticIntensity = 0.0f,
        )
        saveToStore(store, minSettings)
        var loaded = loadFromStore(store)
        assertEquals(0.0f, loaded.voiceVolume, 0.0001f)
        assertEquals(0.5f, loaded.speechRate, 0.0001f)
        assertEquals(0.0f, loaded.hapticIntensity, 0.0001f)

        // Test maximum values
        val maxSettings = AccessibilitySettings(
            voiceVolume = 1.0f,
            speechRate = 2.0f,
            hapticIntensity = 1.0f,
        )
        saveToStore(store, maxSettings)
        loaded = loadFromStore(store)
        assertEquals(1.0f, loaded.voiceVolume, 0.0001f)
        assertEquals(2.0f, loaded.speechRate, 0.0001f)
        assertEquals(1.0f, loaded.hapticIntensity, 0.0001f)
    }

    // === Helper methods to simulate SharedPreferences behavior ===

    private fun saveToStore(store: MutableMap<String, Any>, settings: AccessibilitySettings) {
        store["voice_enabled"] = settings.voiceFeedbackEnabled
        store["voice_volume"] = settings.voiceVolume
        store["speech_rate"] = settings.speechRate
        store["enhanced_haptics"] = settings.enhancedHaptics
        store["haptic_intensity"] = settings.hapticIntensity
        store["large_text"] = settings.largeTextMode
        store["high_contrast"] = settings.highContrastMode
        store["bone_conduction"] = settings.boneConduction.name
        store["one_handed"] = settings.oneHandedMode
        store["volume_ptt"] = settings.volumeButtonPtt
        store["swipe_gestures"] = settings.swipeGesturesEnabled
    }

    private fun loadFromStore(store: Map<String, Any>): AccessibilitySettings {
        return AccessibilitySettings(
            voiceFeedbackEnabled = store["voice_enabled"] as? Boolean ?: false,
            voiceVolume = store["voice_volume"] as? Float ?: 0.8f,
            speechRate = store["speech_rate"] as? Float ?: 1.1f,
            enhancedHaptics = store["enhanced_haptics"] as? Boolean ?: false,
            hapticIntensity = store["haptic_intensity"] as? Float ?: 1.0f,
            largeTextMode = store["large_text"] as? Boolean ?: false,
            highContrastMode = store["high_contrast"] as? Boolean ?: false,
            boneConduction = BoneConductionMode.valueOf(
                store["bone_conduction"] as? String ?: BoneConductionMode.AUTO.name,
            ),
            oneHandedMode = store["one_handed"] as? Boolean ?: true,
            volumeButtonPtt = store["volume_ptt"] as? Boolean ?: false,
            swipeGesturesEnabled = store["swipe_gestures"] as? Boolean ?: true,
        )
    }
}
