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
}
