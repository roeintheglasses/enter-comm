package com.entercomm.bikeintercom.onboarding

/**
 * Utility object for group code generation, validation, and formatting.
 * Extracted for testability without Android dependencies.
 */
object GroupCodeUtils {

    // Group code characters (easy to read, no ambiguous chars like 0/O, 1/I/L)
    const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6

    /**
     * Generate a new shareable group code.
     * Returns raw 6-character code (e.g., "RIDE4K", "TEAM7X").
     * Use formatGroupCode() for display (e.g., "RIDE-4K").
     */
    fun generateGroupCode(): String {
        return buildString {
            repeat(CODE_LENGTH) {
                append(CODE_CHARS.random())
            }
        }
    }

    /**
     * Validate a group code format.
     */
    fun isValidGroupCode(code: String): Boolean {
        val normalized = normalizeGroupCode(code)
        if (normalized.length != CODE_LENGTH) return false
        return normalized.all { it in CODE_CHARS }
    }

    /**
     * Normalize a group code (remove dashes, spaces, uppercase).
     */
    fun normalizeGroupCode(code: String): String {
        return code.uppercase().replace("-", "").replace(" ", "")
    }

    /**
     * Format a group code for display (XXXX-XX format).
     */
    fun formatGroupCode(code: String): String {
        val normalized = normalizeGroupCode(code)
        return if (normalized.length >= 4) {
            "${normalized.substring(0, 4)}-${normalized.substring(4)}"
        } else {
            normalized
        }
    }
}
