package com.entercomm.bikeintercom.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.group.GroupMemory
import com.entercomm.bikeintercom.mesh.GroupMember
import com.entercomm.bikeintercom.mesh.MeshGroup
import com.entercomm.bikeintercom.ui.components.AvailableGroupsList
import com.entercomm.bikeintercom.ui.components.ChannelSelector
import com.entercomm.bikeintercom.ui.components.GroupHistoryCard
import com.entercomm.bikeintercom.ui.components.GroupInfoCard
import com.entercomm.bikeintercom.ui.components.MemberList
import com.entercomm.bikeintercom.ui.theme.DarkSurface
import com.entercomm.bikeintercom.ui.theme.PitchBlack
import com.entercomm.bikeintercom.ui.theme.TextPrimary

/**
 * Group tab content
 */
@Composable
fun GroupContent(
    currentGroup: MeshGroup?,
    members: List<GroupMember>,
    nickname: String,
    groupCode: String?,
    availableGroups: List<MeshGroup>,
    isOwner: Boolean,
    localNodeId: String,
    groupHistory: List<GroupMemory>,
    incomingVolume: Float?,
    voiceFeedbackVolume: Float?,
    onCreateGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onJoinGroup: (MeshGroup) -> Unit,
    onJoinGroupByCode: () -> Unit,
    onKickMember: (String) -> Unit,
    onBanMember: (String) -> Unit,
    onChannelChange: (Int) -> Unit,
    onRejoinGroup: (GroupMemory) -> Unit,
    onRenameGroup: (GroupMemory) -> Unit,
    onRemoveGroup: (GroupMemory) -> Unit,
    onClearHistory: () -> Unit,
    onIncomingVolumeChange: (Float) -> Unit,
    onVoiceFeedbackVolumeChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Group & Channel",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        GroupInfoCard(
            currentGroup,
            members.size,
            nickname,
            groupCode,
            onLeaveGroup,
            onCreateGroup,
            onJoinGroupByCode,
            incomingVolume,
            voiceFeedbackVolume,
            onIncomingVolumeChange,
            onVoiceFeedbackVolumeChange,
        )
        GroupHistorySection(
            currentGroup,
            groupCode,
            groupHistory,
            onRejoinGroup,
            onRenameGroup,
            onRemoveGroup,
            onClearHistory,
        )
        GroupChannelSection(currentGroup, isOwner, onChannelChange)
        GroupMemberSection(currentGroup, members, localNodeId, isOwner, onKickMember, onBanMember)
        GroupAvailableSection(currentGroup, availableGroups, onJoinGroup)
    }
}

@Composable
private fun GroupHistorySection(
    currentGroup: MeshGroup?,
    groupCode: String?,
    groupHistory: List<GroupMemory>,
    onRejoinGroup: (GroupMemory) -> Unit,
    onRenameGroup: (GroupMemory) -> Unit,
    onRemoveGroup: (GroupMemory) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (currentGroup == null && groupCode == null && groupHistory.isNotEmpty()) {
        GroupHistoryCard(groupHistory, onRejoinGroup, onRenameGroup, onRemoveGroup, onClearHistory)
    }
}

@Composable
private fun GroupChannelSection(currentGroup: MeshGroup?, isOwner: Boolean, onChannelChange: (Int) -> Unit) {
    if (currentGroup != null && isOwner) {
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                ChannelSelector(currentGroup.channelNumber, onChannelChange, isOwner)
            }
        }
    }
}

@Composable
private fun GroupMemberSection(currentGroup: MeshGroup?, members: List<GroupMember>, localNodeId: String, isOwner: Boolean, onKickMember: (String) -> Unit, onBanMember: (String) -> Unit) {
    if (currentGroup != null && members.isNotEmpty()) {
        MemberList(members, localNodeId, isOwner, onKickMember, onBanMember)
    }
}

@Composable
private fun GroupAvailableSection(currentGroup: MeshGroup?, availableGroups: List<MeshGroup>, onJoinGroup: (MeshGroup) -> Unit) {
    if (currentGroup == null && availableGroups.isNotEmpty()) {
        AvailableGroupsList(availableGroups, onJoinGroup)
    }
}
