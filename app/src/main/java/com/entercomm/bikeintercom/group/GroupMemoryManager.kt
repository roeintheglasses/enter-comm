package com.entercomm.bikeintercom.group

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Manages persistent storage of group history with per-group preferences.
 * Uses SharedPreferences with JSON serialization.
 */
class GroupMemoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _groupHistory = MutableStateFlow<List<GroupMemory>>(emptyList())
    val groupHistory: StateFlow<List<GroupMemory>> = _groupHistory.asStateFlow()

    init {
        _groupHistory.value = loadGroupHistory()
    }

    /**
     * Record a group join. Creates new entry or updates existing.
     * Maintains max 5 groups, removing oldest if needed.
     */
    fun recordGroupJoin(groupCode: String) {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        val currentHistory = _groupHistory.value.toMutableList()

        val existingIndex = currentHistory.indexOfFirst {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }

        val newEntry = if (existingIndex >= 0) {
            val existing = currentHistory.removeAt(existingIndex)
            existing.copy(
                lastJoinedAt = System.currentTimeMillis(),
                joinCount = existing.joinCount + 1,
            )
        } else {
            GroupMemory(
                groupCode = normalized,
                customName = null,
                incomingVolume = GroupMemory.DEFAULT_INCOMING_VOLUME,
                voiceFeedbackVolume = GroupMemory.DEFAULT_VOICE_FEEDBACK_VOLUME,
                lastJoinedAt = System.currentTimeMillis(),
                joinCount = 1,
            )
        }

        currentHistory.add(0, newEntry)

        while (currentHistory.size > GroupMemory.MAX_HISTORY_SIZE) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }

        _groupHistory.value = currentHistory
        saveGroupHistory(currentHistory)
    }

    /**
     * Rename a group in history.
     */
    fun renameGroup(groupCode: String, newName: String?) {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        val currentHistory = _groupHistory.value.toMutableList()

        val index = currentHistory.indexOfFirst {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }

        if (index >= 0) {
            currentHistory[index] = currentHistory[index].copy(
                customName = newName?.trim()?.takeIf { it.isNotEmpty() },
            )
            _groupHistory.value = currentHistory
            saveGroupHistory(currentHistory)
        }
    }

    /**
     * Update volume preferences for a group.
     */
    fun setGroupVolumes(groupCode: String, incomingVolume: Float, voiceFeedbackVolume: Float) {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        val currentHistory = _groupHistory.value.toMutableList()

        val index = currentHistory.indexOfFirst {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }

        if (index >= 0) {
            currentHistory[index] = currentHistory[index].copy(
                incomingVolume = incomingVolume.coerceIn(0f, 1f),
                voiceFeedbackVolume = voiceFeedbackVolume.coerceIn(0f, 1f),
            )
            _groupHistory.value = currentHistory
            saveGroupHistory(currentHistory)
        }
    }

    /**
     * Get the saved volume preferences for a group.
     */
    fun getGroupVolumes(groupCode: String): GroupVolumes {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        val group = _groupHistory.value.find {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }
        return GroupVolumes(
            incomingVolume = group?.incomingVolume ?: GroupMemory.DEFAULT_INCOMING_VOLUME,
            voiceFeedbackVolume = group?.voiceFeedbackVolume ?: GroupMemory.DEFAULT_VOICE_FEEDBACK_VOLUME,
        )
    }

    /**
     * Remove a specific group from history.
     */
    fun removeGroup(groupCode: String) {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        val currentHistory = _groupHistory.value.filterNot {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }

        _groupHistory.value = currentHistory
        saveGroupHistory(currentHistory)
    }

    /**
     * Clear all group history (privacy feature).
     */
    fun clearHistory() {
        _groupHistory.value = emptyList()
        prefs.edit().remove(KEY_GROUP_HISTORY).apply()
    }

    /**
     * Check if a group exists in history.
     */
    fun hasGroup(groupCode: String): Boolean {
        val normalized = GroupMemory.normalizeGroupCode(groupCode)
        return _groupHistory.value.any {
            GroupMemory.normalizeGroupCode(it.groupCode) == normalized
        }
    }

    private fun loadGroupHistory(): List<GroupMemory> {
        val json = prefs.getString(KEY_GROUP_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                deserializeGroupMemory(array.getJSONObject(i))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to load group history", e)
            emptyList()
        }
    }

    private fun saveGroupHistory(history: List<GroupMemory>) {
        val array = JSONArray()
        history.forEach { group ->
            array.put(serializeGroupMemory(group))
        }
        prefs.edit().putString(KEY_GROUP_HISTORY, array.toString()).apply()
    }

    private fun serializeGroupMemory(group: GroupMemory): JSONObject {
        return JSONObject().apply {
            put("groupCode", group.groupCode)
            put("customName", group.customName)
            put("incomingVolume", group.incomingVolume.toDouble())
            put("voiceFeedbackVolume", group.voiceFeedbackVolume.toDouble())
            put("lastJoinedAt", group.lastJoinedAt)
            put("joinCount", group.joinCount)
        }
    }

    private fun deserializeGroupMemory(json: JSONObject): GroupMemory? {
        return try {
            GroupMemory(
                groupCode = json.getString("groupCode"),
                customName = json.optString("customName", "")
                    .takeIf { it.isNotEmpty() && it != "null" },
                incomingVolume = json.optDouble(
                    "incomingVolume",
                    GroupMemory.DEFAULT_INCOMING_VOLUME.toDouble(),
                ).toFloat(),
                voiceFeedbackVolume = json.optDouble(
                    "voiceFeedbackVolume",
                    GroupMemory.DEFAULT_VOICE_FEEDBACK_VOLUME.toDouble(),
                ).toFloat(),
                lastJoinedAt = json.optLong("lastJoinedAt", 0L),
                joinCount = json.optInt("joinCount", 1),
            )
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to deserialize group memory", e)
            null
        }
    }

    companion object {
        private const val TAG = "GroupMemoryManager"
        private const val PREFS_NAME = "group_memory_prefs"
        private const val KEY_GROUP_HISTORY = "group_history"
    }
}
