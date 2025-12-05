package com.entercomm.bikeintercom.mesh

import android.content.Context
import android.content.SharedPreferences
import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a mesh communication group/channel.
 */
data class MeshGroup(
    val groupId: String,
    val groupName: String,
    val channelNumber: Int,           // 1-10 for different "frequencies"
    val ownerId: String,              // Node ID of group creator
    val maxSize: Int = 10,
    val isPasswordProtected: Boolean = false,
    val passwordHash: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CHANNEL = 1
        const val MAX_CHANNELS = 10
        const val DEFAULT_MAX_SIZE = 10

        /**
         * Create a default open group.
         */
        fun createDefault(ownerId: String, ownerName: String): MeshGroup {
            return MeshGroup(
                groupId = UUID.randomUUID().toString().take(8),
                groupName = "$ownerName's Group",
                channelNumber = DEFAULT_CHANNEL,
                ownerId = ownerId
            )
        }
    }

    /**
     * Check if a password matches.
     */
    fun checkPassword(password: String): Boolean {
        if (!isPasswordProtected) return true
        return hashPassword(password) == passwordHash
    }
}

/**
 * Represents a member in a group.
 */
data class GroupMember(
    val nodeId: String,
    val nickname: String,
    val joinedAt: Long = System.currentTimeMillis(),
    val role: MemberRole = MemberRole.MEMBER,
    val lastSeen: Long = System.currentTimeMillis(),
    val isMuted: Boolean = false
) {
    val isOwner: Boolean
        get() = role == MemberRole.OWNER
}

enum class MemberRole {
    OWNER,    // Group creator, can kick/ban
    MEMBER,   // Regular member
    BANNED    // Banned from group
}

/**
 * Group-related events for UI updates.
 */
sealed class GroupEvent {
    data class MemberJoined(val member: GroupMember) : GroupEvent()
    data class MemberLeft(val nodeId: String, val nickname: String) : GroupEvent()
    data class MemberKicked(val nodeId: String, val nickname: String) : GroupEvent()
    data class MemberBanned(val nodeId: String, val nickname: String) : GroupEvent()
    data class GroupCreated(val group: MeshGroup) : GroupEvent()
    data class GroupJoined(val group: MeshGroup) : GroupEvent()
    data class GroupLeft(val groupId: String) : GroupEvent()
    data class NicknameChanged(val nodeId: String, val newNickname: String) : GroupEvent()
    data class ChannelChanged(val newChannel: Int) : GroupEvent()
    data class Error(val message: String) : GroupEvent()
}

/**
 * Group message types for mesh protocol.
 */
enum class GroupMessageType {
    GROUP_ANNOUNCE,    // Broadcast group existence
    JOIN_REQUEST,      // Request to join group
    JOIN_ACCEPT,       // Accept join request
    JOIN_REJECT,       // Reject join request
    LEAVE,             // Member leaving
    KICK,              // Owner kicking member
    BAN,               // Owner banning member
    NICKNAME_UPDATE,   // Member nickname change
    MEMBER_LIST,       // Full member list sync
    CHANNEL_CHANGE     // Channel number change
}

/**
 * Manages group/channel functionality for the mesh network.
 * Handles group creation, joining, member management, and permissions.
 */
class GroupManager(
    private val context: Context,
    private val localNodeId: String,
    private var localNickname: String
) {
    companion object {
        private const val PREFS_NAME = "group_prefs"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LAST_GROUP_ID = "last_group_id"
        private const val KEY_BANNED_NODES = "banned_nodes"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Current group state
    private val _currentGroup = MutableStateFlow<MeshGroup?>(null)
    val currentGroup: StateFlow<MeshGroup?> = _currentGroup.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _nickname = MutableStateFlow(loadNickname())
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _events = MutableStateFlow<GroupEvent?>(null)
    val events: StateFlow<GroupEvent?> = _events.asStateFlow()

    // Internal storage
    private val memberMap = ConcurrentHashMap<String, GroupMember>()
    // Use Collections.newSetFromMap for API 21+ compatibility (newKeySet requires API 24)
    private val bannedNodes: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val availableGroups = ConcurrentHashMap<String, MeshGroup>()

    // Callback for sending messages
    var sendGroupMessage: ((GroupMessageType, String, ByteArray) -> Unit)? = null

    init {
        localNickname = _nickname.value
        loadBannedNodes()
    }

    /**
     * Create a new group.
     */
    fun createGroup(
        name: String,
        channel: Int = MeshGroup.DEFAULT_CHANNEL,
        password: String? = null,
        maxSize: Int = MeshGroup.DEFAULT_MAX_SIZE
    ): MeshGroup {
        val group = MeshGroup(
            groupId = UUID.randomUUID().toString().take(8),
            groupName = name.take(20),
            channelNumber = channel.coerceIn(1, MeshGroup.MAX_CHANNELS),
            ownerId = localNodeId,
            maxSize = maxSize.coerceIn(2, 20),
            isPasswordProtected = password != null,
            passwordHash = password?.let { hashPassword(it) }
        )

        _currentGroup.value = group
        memberMap.clear()

        // Add ourselves as owner
        val ownerMember = GroupMember(
            nodeId = localNodeId,
            nickname = _nickname.value,
            role = MemberRole.OWNER
        )
        memberMap[localNodeId] = ownerMember
        updateMembersList()

        saveLastGroupId(group.groupId)
        emitEvent(GroupEvent.GroupCreated(group))

        // Announce group to network
        announceGroup()

        logD { "Created group: ${group.groupName} (${group.groupId}) on channel ${group.channelNumber}" }
        return group
    }

    /**
     * Join an existing group.
     */
    fun joinGroup(groupId: String, password: String? = null): Boolean {
        val group = availableGroups[groupId]
        if (group == null) {
            emitEvent(GroupEvent.Error("Group not found"))
            return false
        }

        // Check if banned
        if (bannedNodes.contains(localNodeId)) {
            emitEvent(GroupEvent.Error("You are banned from this group"))
            return false
        }

        // Check password
        if (group.isPasswordProtected && !group.checkPassword(password ?: "")) {
            emitEvent(GroupEvent.Error("Incorrect password"))
            return false
        }

        // Send join request
        val payload = "$localNodeId|${_nickname.value}|${password ?: ""}"
        sendGroupMessage?.invoke(GroupMessageType.JOIN_REQUEST, group.ownerId, payload.toByteArray())

        logD { "Sent join request to group: ${group.groupName}" }
        return true
    }

    /**
     * Leave current group.
     */
    fun leaveGroup() {
        val group = _currentGroup.value ?: return

        // Notify other members
        val payload = "$localNodeId|${_nickname.value}"
        memberMap.keys.forEach { memberId ->
            if (memberId != localNodeId) {
                sendGroupMessage?.invoke(GroupMessageType.LEAVE, memberId, payload.toByteArray())
            }
        }

        val groupId = group.groupId
        _currentGroup.value = null
        memberMap.clear()
        updateMembersList()

        emitEvent(GroupEvent.GroupLeft(groupId))
        logD { "Left group: ${group.groupName}" }
    }

    /**
     * Kick a member from the group (owner only).
     */
    fun kickMember(nodeId: String): Boolean {
        val group = _currentGroup.value ?: return false

        if (group.ownerId != localNodeId) {
            emitEvent(GroupEvent.Error("Only the owner can kick members"))
            return false
        }

        if (nodeId == localNodeId) {
            emitEvent(GroupEvent.Error("Cannot kick yourself"))
            return false
        }

        val member = memberMap[nodeId] ?: return false

        // Send kick message
        sendGroupMessage?.invoke(GroupMessageType.KICK, nodeId, group.groupId.toByteArray())

        // Remove from local list
        memberMap.remove(nodeId)
        updateMembersList()

        emitEvent(GroupEvent.MemberKicked(nodeId, member.nickname))
        logD { "Kicked member: ${member.nickname}" }
        return true
    }

    /**
     * Ban a member from the group (owner only).
     */
    fun banMember(nodeId: String): Boolean {
        val group = _currentGroup.value ?: return false

        if (group.ownerId != localNodeId) {
            emitEvent(GroupEvent.Error("Only the owner can ban members"))
            return false
        }

        if (nodeId == localNodeId) {
            emitEvent(GroupEvent.Error("Cannot ban yourself"))
            return false
        }

        val member = memberMap[nodeId]

        // Add to banned list
        bannedNodes.add(nodeId)
        saveBannedNodes()

        // Send ban message
        sendGroupMessage?.invoke(GroupMessageType.BAN, nodeId, group.groupId.toByteArray())

        // Remove from local list
        memberMap.remove(nodeId)
        updateMembersList()

        emitEvent(GroupEvent.MemberBanned(nodeId, member?.nickname ?: nodeId))
        logD { "Banned member: ${member?.nickname ?: nodeId}" }
        return true
    }

    /**
     * Unban a member.
     */
    fun unbanMember(nodeId: String) {
        bannedNodes.remove(nodeId)
        saveBannedNodes()
        logD { "Unbanned member: $nodeId" }
    }

    /**
     * Set local nickname.
     */
    fun setNickname(name: String) {
        val newNickname = name.take(15).trim()
        if (newNickname.isEmpty()) return

        _nickname.value = newNickname
        localNickname = newNickname
        saveNickname(newNickname)

        // Update in member list
        memberMap[localNodeId]?.let {
            memberMap[localNodeId] = it.copy(nickname = newNickname)
            updateMembersList()
        }

        // Broadcast nickname change
        val payload = "$localNodeId|$newNickname"
        memberMap.keys.forEach { memberId ->
            if (memberId != localNodeId) {
                sendGroupMessage?.invoke(GroupMessageType.NICKNAME_UPDATE, memberId, payload.toByteArray())
            }
        }

        emitEvent(GroupEvent.NicknameChanged(localNodeId, newNickname))
        logD { "Nickname changed to: $newNickname" }
    }

    /**
     * Change channel (owner only).
     */
    fun changeChannel(newChannel: Int): Boolean {
        val group = _currentGroup.value ?: return false

        if (group.ownerId != localNodeId) {
            emitEvent(GroupEvent.Error("Only the owner can change channel"))
            return false
        }

        val channel = newChannel.coerceIn(1, MeshGroup.MAX_CHANNELS)
        _currentGroup.value = group.copy(channelNumber = channel)

        // Broadcast channel change
        val payload = "$localNodeId|$channel"
        memberMap.keys.forEach { memberId ->
            if (memberId != localNodeId) {
                sendGroupMessage?.invoke(GroupMessageType.CHANNEL_CHANGE, memberId, payload.toByteArray())
            }
        }

        emitEvent(GroupEvent.ChannelChanged(channel))
        logD { "Channel changed to: $channel" }
        return true
    }

    /**
     * Process incoming group message.
     */
    fun processGroupMessage(type: GroupMessageType, senderId: String, payload: ByteArray) {
        val data = String(payload)
        logD { "Processing group message: $type from $senderId" }

        when (type) {
            GroupMessageType.GROUP_ANNOUNCE -> handleGroupAnnounce(senderId, data)
            GroupMessageType.JOIN_REQUEST -> handleJoinRequest(senderId, data)
            GroupMessageType.JOIN_ACCEPT -> handleJoinAccept(senderId, data)
            GroupMessageType.JOIN_REJECT -> handleJoinReject(senderId, data)
            GroupMessageType.LEAVE -> handleLeave(senderId, data)
            GroupMessageType.KICK -> handleKick(senderId, data)
            GroupMessageType.BAN -> handleBan(senderId, data)
            GroupMessageType.NICKNAME_UPDATE -> handleNicknameUpdate(senderId, data)
            GroupMessageType.MEMBER_LIST -> handleMemberList(senderId, data)
            GroupMessageType.CHANNEL_CHANGE -> handleChannelChange(senderId, data)
        }
    }

    /**
     * Announce current group to network.
     */
    fun announceGroup() {
        val group = _currentGroup.value ?: return
        val payload = serializeGroupAnnounce(group)
        // Broadcast to all (destination = "broadcast")
        sendGroupMessage?.invoke(GroupMessageType.GROUP_ANNOUNCE, "broadcast", payload.toByteArray())
    }

    /**
     * Get list of available groups discovered on the network.
     */
    fun getAvailableGroups(): List<MeshGroup> {
        return availableGroups.values.toList()
    }

    /**
     * Check if we're the owner of current group.
     */
    fun isOwner(): Boolean {
        val group = _currentGroup.value ?: return false
        return group.ownerId == localNodeId
    }

    /**
     * Get current channel number.
     */
    fun getCurrentChannel(): Int {
        return _currentGroup.value?.channelNumber ?: MeshGroup.DEFAULT_CHANNEL
    }

    /**
     * Check if audio should be filtered by channel.
     * Returns true if the source is on the same channel.
     */
    fun shouldReceiveAudio(sourceNodeId: String): Boolean {
        // If no group, receive all
        if (_currentGroup.value == null) return true

        // If member of our group, receive
        return memberMap.containsKey(sourceNodeId)
    }

    // Message handlers

    private fun handleGroupAnnounce(senderId: String, data: String) {
        val group = deserializeGroupAnnounce(data) ?: return
        availableGroups[group.groupId] = group
        logD { "Discovered group: ${group.groupName} from $senderId" }
    }

    private fun handleJoinRequest(@Suppress("UNUSED_PARAMETER") senderId: String, data: String) {
        val parts = data.split("|")
        if (parts.size < 2) return

        val requesterId = parts[0]
        val nickname = parts[1]
        val password = parts.getOrNull(2) ?: ""

        val group = _currentGroup.value ?: return
        if (group.ownerId != localNodeId) return  // Not owner

        // Check if banned
        if (bannedNodes.contains(requesterId)) {
            sendGroupMessage?.invoke(GroupMessageType.JOIN_REJECT, requesterId, "banned".toByteArray())
            return
        }

        // Check password
        if (group.isPasswordProtected && !group.checkPassword(password)) {
            sendGroupMessage?.invoke(GroupMessageType.JOIN_REJECT, requesterId, "password".toByteArray())
            return
        }

        // Check size
        if (memberMap.size >= group.maxSize) {
            sendGroupMessage?.invoke(GroupMessageType.JOIN_REJECT, requesterId, "full".toByteArray())
            return
        }

        // Accept
        val newMember = GroupMember(nodeId = requesterId, nickname = nickname)
        memberMap[requesterId] = newMember
        updateMembersList()

        // Send accept with group info
        val response = serializeGroupAnnounce(group)
        sendGroupMessage?.invoke(GroupMessageType.JOIN_ACCEPT, requesterId, response.toByteArray())

        // Send member list to new member
        sendMemberList(requesterId)

        // Notify others
        memberMap.keys.filter { it != localNodeId && it != requesterId }.forEach { memberId ->
            sendMemberList(memberId)
        }

        emitEvent(GroupEvent.MemberJoined(newMember))
        logD { "Accepted join request from: $nickname" }
    }

    private fun handleJoinAccept(@Suppress("UNUSED_PARAMETER") senderId: String, data: String) {
        val group = deserializeGroupAnnounce(data) ?: return

        _currentGroup.value = group
        memberMap.clear()

        // Add ourselves
        val selfMember = GroupMember(nodeId = localNodeId, nickname = _nickname.value)
        memberMap[localNodeId] = selfMember

        // Add owner
        val ownerMember = GroupMember(nodeId = group.ownerId, nickname = "Owner", role = MemberRole.OWNER)
        memberMap[group.ownerId] = ownerMember

        updateMembersList()
        saveLastGroupId(group.groupId)

        emitEvent(GroupEvent.GroupJoined(group))
        logD { "Joined group: ${group.groupName}" }
    }

    private fun handleJoinReject(@Suppress("UNUSED_PARAMETER") senderId: String, data: String) {
        val reason = when (data) {
            "banned" -> "You are banned from this group"
            "password" -> "Incorrect password"
            "full" -> "Group is full"
            else -> "Join request rejected"
        }
        emitEvent(GroupEvent.Error(reason))
        logD { "Join rejected: $reason" }
    }

    private fun handleLeave(senderId: String, data: String) {
        val parts = data.split("|")
        val nodeId = parts.getOrNull(0) ?: senderId
        val nickname = parts.getOrNull(1) ?: nodeId

        memberMap.remove(nodeId)
        updateMembersList()

        emitEvent(GroupEvent.MemberLeft(nodeId, nickname))
        logD { "Member left: $nickname" }
    }

    private fun handleKick(senderId: String, @Suppress("UNUSED_PARAMETER") data: String) {
        // We got kicked
        val group = _currentGroup.value
        if (group != null && senderId == group.ownerId) {
            _currentGroup.value = null
            memberMap.clear()
            updateMembersList()
            emitEvent(GroupEvent.Error("You were kicked from the group"))
            logD { "We were kicked from group" }
        }
    }

    private fun handleBan(senderId: String, @Suppress("UNUSED_PARAMETER") data: String) {
        // We got banned
        val group = _currentGroup.value
        if (group != null && senderId == group.ownerId) {
            _currentGroup.value = null
            memberMap.clear()
            updateMembersList()
            emitEvent(GroupEvent.Error("You were banned from the group"))
            logD { "We were banned from group" }
        }
    }

    private fun handleNicknameUpdate(@Suppress("UNUSED_PARAMETER") senderId: String, data: String) {
        val parts = data.split("|")
        if (parts.size < 2) return

        val nodeId = parts[0]
        val newNickname = parts[1]

        memberMap[nodeId]?.let {
            memberMap[nodeId] = it.copy(nickname = newNickname)
            updateMembersList()
        }

        emitEvent(GroupEvent.NicknameChanged(nodeId, newNickname))
        logD { "Nickname updated: $newNickname" }
    }

    private fun handleMemberList(@Suppress("UNUSED_PARAMETER") senderId: String, data: String) {
        // Format: nodeId1:nickname1:role1;nodeId2:nickname2:role2;...
        data.split(";").filter { it.isNotEmpty() }.forEach { memberData ->
            val parts = memberData.split(":")
            if (parts.size >= 2) {
                val nodeId = parts[0]
                val nickname = parts[1]
                val role = parts.getOrNull(2)?.let { MemberRole.valueOf(it) } ?: MemberRole.MEMBER

                if (!memberMap.containsKey(nodeId)) {
                    memberMap[nodeId] = GroupMember(nodeId = nodeId, nickname = nickname, role = role)
                }
            }
        }
        updateMembersList()
    }

    private fun handleChannelChange(senderId: String, data: String) {
        val parts = data.split("|")
        if (parts.size < 2) return

        val newChannel = parts[1].toIntOrNull() ?: return
        val group = _currentGroup.value ?: return

        if (senderId == group.ownerId) {
            _currentGroup.value = group.copy(channelNumber = newChannel)
            emitEvent(GroupEvent.ChannelChanged(newChannel))
            logD { "Channel changed to: $newChannel" }
        }
    }

    // Helper functions

    private fun sendMemberList(targetId: String) {
        val memberListData = memberMap.values.joinToString(";") {
            "${it.nodeId}:${it.nickname}:${it.role}"
        }
        sendGroupMessage?.invoke(GroupMessageType.MEMBER_LIST, targetId, memberListData.toByteArray())
    }

    private fun serializeGroupAnnounce(group: MeshGroup): String {
        return "${group.groupId}|${group.groupName}|${group.channelNumber}|${group.ownerId}|${group.maxSize}|${group.isPasswordProtected}"
    }

    private fun deserializeGroupAnnounce(data: String): MeshGroup? {
        val parts = data.split("|")
        if (parts.size < 6) return null

        return try {
            MeshGroup(
                groupId = parts[0],
                groupName = parts[1],
                channelNumber = parts[2].toInt(),
                ownerId = parts[3],
                maxSize = parts[4].toInt(),
                isPasswordProtected = parts[5].toBoolean()
            )
        } catch (e: Exception) {
            logE({ "Failed to deserialize group announce" }, e)
            null
        }
    }

    private fun updateMembersList() {
        _members.value = memberMap.values.toList().sortedBy {
            when (it.role) {
                MemberRole.OWNER -> 0
                MemberRole.MEMBER -> 1
                MemberRole.BANNED -> 2
            }
        }
    }

    private fun emitEvent(event: GroupEvent) {
        _events.value = event
    }

    private fun loadNickname(): String {
        return prefs.getString(KEY_NICKNAME, "Rider") ?: "Rider"
    }

    private fun saveNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    private fun saveLastGroupId(groupId: String) {
        prefs.edit().putString(KEY_LAST_GROUP_ID, groupId).apply()
    }

    private fun loadBannedNodes() {
        val banned = prefs.getStringSet(KEY_BANNED_NODES, emptySet()) ?: emptySet()
        bannedNodes.addAll(banned)
    }

    private fun saveBannedNodes() {
        prefs.edit().putStringSet(KEY_BANNED_NODES, bannedNodes.toSet()).apply()
    }
}

/**
 * Hash a password for storage.
 */
private fun hashPassword(password: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
