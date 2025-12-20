package com.entercomm.bikeintercom.group

/**
 * Represents a saved group in the user's group history.
 */
data class GroupMemory(
    val groupCode: String,
    val customName: String?,
    val incomingVolume: Float,
    val voiceFeedbackVolume: Float,
    val lastJoinedAt: Long,
    val joinCount: Int,
) {
    /**
     * Display name: custom name if set, otherwise formatted group code.
     */
    val displayName: String
        get() = customName ?: formatGroupCode(groupCode)

    companion object {
        const val DEFAULT_INCOMING_VOLUME = 0.8f
        const val DEFAULT_VOICE_FEEDBACK_VOLUME = 0.8f
        const val MAX_HISTORY_SIZE = 5

        /**
         * Format group code for display (XXX-XXX).
         */
        fun formatGroupCode(code: String): String {
            val normalized = code.uppercase().replace("-", "").replace(" ", "")
            return if (normalized.length >= 4) {
                "${normalized.take(3)}-${normalized.drop(3)}"
            } else {
                normalized
            }
        }

        /**
         * Normalize group code for comparison.
         */
        fun normalizeGroupCode(code: String): String {
            return code.uppercase().replace("-", "").replace(" ", "")
        }
    }
}

/**
 * Volume preferences for a group.
 */
data class GroupVolumes(
    val incomingVolume: Float,
    val voiceFeedbackVolume: Float,
)
