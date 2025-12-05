package com.entercomm.bikeintercom.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection modes for the mesh network.
 */
enum class ConnectionMode {
    GROUP_MODE,  // Only connect with group members (default, private)
    OPEN_MODE    // Connect with everyone in range (for events)
}

/**
 * User preferences and onboarding state.
 */
data class UserPreferences(
    val nickname: String = "Rider",
    val connectionMode: ConnectionMode = ConnectionMode.GROUP_MODE,
    val onboardingCompleted: Boolean = false,
    val currentGroupCode: String? = null
)

/**
 * Manages onboarding flow and user preferences.
 */
class OnboardingManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "entercomm_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_CURRENT_GROUP_CODE = "current_group_code"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _userPreferences = MutableStateFlow(loadPreferences())
    val userPreferences: StateFlow<UserPreferences> = _userPreferences.asStateFlow()

    /**
     * Check if onboarding needs to be shown.
     */
    fun needsOnboarding(): Boolean {
        return !_userPreferences.value.onboardingCompleted
    }

    /**
     * Mark onboarding as completed.
     */
    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        _userPreferences.value = _userPreferences.value.copy(onboardingCompleted = true)
    }

    /**
     * Reset onboarding (for testing or re-setup).
     */
    fun resetOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, false).apply()
        _userPreferences.value = _userPreferences.value.copy(onboardingCompleted = false)
    }

    /**
     * Set user nickname.
     */
    fun setNickname(nickname: String) {
        val sanitized = nickname.trim().take(20).ifEmpty { "Rider" }
        prefs.edit().putString(KEY_NICKNAME, sanitized).apply()
        _userPreferences.value = _userPreferences.value.copy(nickname = sanitized)
    }

    /**
     * Set connection mode.
     */
    fun setConnectionMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
        _userPreferences.value = _userPreferences.value.copy(connectionMode = mode)
    }

    /**
     * Set current group code.
     */
    fun setCurrentGroupCode(code: String?) {
        if (code != null) {
            prefs.edit().putString(KEY_CURRENT_GROUP_CODE, code).apply()
        } else {
            prefs.edit().remove(KEY_CURRENT_GROUP_CODE).apply()
        }
        _userPreferences.value = _userPreferences.value.copy(currentGroupCode = code)
    }

    /**
     * Generate a new shareable group code.
     * Format: XXXX-XX (e.g., "RIDE-4K", "TEAM-7X")
     */
    fun generateGroupCode(): String = GroupCodeUtils.generateGroupCode()

    /**
     * Validate a group code format.
     */
    fun isValidGroupCode(code: String): Boolean = GroupCodeUtils.isValidGroupCode(code)

    /**
     * Normalize a group code (remove dashes, uppercase).
     */
    fun normalizeGroupCode(code: String): String = GroupCodeUtils.normalizeGroupCode(code)

    /**
     * Format a group code for display.
     */
    fun formatGroupCode(code: String): String = GroupCodeUtils.formatGroupCode(code)

    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            nickname = prefs.getString(KEY_NICKNAME, "Rider") ?: "Rider",
            connectionMode = try {
                ConnectionMode.valueOf(prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.GROUP_MODE.name) ?: ConnectionMode.GROUP_MODE.name)
            } catch (e: Exception) {
                ConnectionMode.GROUP_MODE
            },
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            currentGroupCode = prefs.getString(KEY_CURRENT_GROUP_CODE, null)
        )
    }
}
