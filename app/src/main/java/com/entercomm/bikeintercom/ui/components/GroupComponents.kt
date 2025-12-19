package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.mesh.GroupMember
import com.entercomm.bikeintercom.mesh.MemberRole
import com.entercomm.bikeintercom.mesh.MeshGroup

/**
 * Group info header card showing current group status.
 */
@Composable
fun GroupInfoCard(
    group: MeshGroup?,
    memberCount: Int,
    nickname: String,
    groupCode: String?,
    onLeaveGroup: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroupByCode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Nickname row (read-only now)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Your Nickname",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Show tappable group code badge if available
                if (groupCode != null) {
                    GroupCodeDisplay(groupCode = groupCode)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            if (group != null) {
                // Group info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.groupName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Channel badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "CH ${group.channelNumber}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            // Member count
                            Text(
                                text = "$memberCount/${group.maxSize} members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Password indicator
                            if (group.isPasswordProtected) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Password protected",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = onLeaveGroup,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Leave")
                    }
                }

                // Group code sharing info
                if (groupCode != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share code $groupCode to let others join",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (groupCode != null) {
                // Connected by code only (no full GroupManager group)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Listening on code $groupCode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = onLeaveGroup,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Leave")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share code $groupCode to let others join",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Not connected at all - show create and join buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Not in a group",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = onCreateGroup) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create Group")
                        }
                        OutlinedButton(onClick = onJoinGroupByCode) {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Join Group")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Channel selector component.
 */
@Composable
fun ChannelSelector(currentChannel: Int, onChannelChange: (Int) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Channel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (channel in 1..10) {
                val isSelected = channel == currentChannel
                val backgroundColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    label = "channelColor",
                )
                val contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(backgroundColor)
                        .clickable(enabled = enabled) { onChannelChange(channel) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = channel.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Member list item.
 */
@Composable
fun MemberListItem(member: GroupMember, isLocalUser: Boolean, isOwner: Boolean, onKick: (() -> Unit)? = null, onBan: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            member.role == MemberRole.OWNER -> MaterialTheme.colorScheme.primary
                            isLocalUser -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = member.nickname.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        member.role == MemberRole.OWNER -> MaterialTheme.colorScheme.onPrimary
                        isLocalUser -> MaterialTheme.colorScheme.onTertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = member.nickname,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (member.role == MemberRole.OWNER) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "Owner",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (isLocalUser) {
                        Text(
                            text = "(You)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (member.isMuted) {
                    Text(
                        text = "Muted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Actions for owner
        if (isOwner && !isLocalUser && member.role != MemberRole.OWNER) {
            Row {
                IconButton(onClick = { onKick?.invoke() }) {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = "Kick member",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { onBan?.invoke() }) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "Ban member",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Member list with header.
 */
@Composable
fun MemberList(members: List<GroupMember>, localNodeId: String, isOwner: Boolean, onKickMember: (String) -> Unit, onBanMember: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Members (${members.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column {
            members.forEach { member ->
                MemberListItem(
                    member = member,
                    isLocalUser = member.nodeId == localNodeId,
                    isOwner = isOwner,
                    onKick = { onKickMember(member.nodeId) },
                    onBan = { onBanMember(member.nodeId) },
                )
            }
        }
    }
}

/**
 * Create group dialog.
 */
@Composable
fun CreateGroupDialog(onDismiss: () -> Unit, onCreate: (name: String, channel: Int, password: String?, maxSize: Int) -> Unit) {
    var groupName by remember { mutableStateOf("") }
    var channel by remember { mutableIntStateOf(1) }
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var maxSize by remember { mutableIntStateOf(10) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Group") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(20) },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ChannelSelector(
                    currentChannel = channel,
                    onChannelChange = { channel = it },
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = usePassword,
                        onCheckedChange = { usePassword = it },
                    )
                    Text("Password protect")
                }

                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Max members:")
                    Slider(
                        value = maxSize.toFloat(),
                        onValueChange = { maxSize = it.toInt() },
                        valueRange = 2f..20f,
                        steps = 17,
                        modifier = Modifier.weight(1f),
                    )
                    Text(maxSize.toString())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        groupName.ifBlank { "My Group" },
                        channel,
                        if (usePassword && password.isNotBlank()) password else null,
                        maxSize,
                    )
                    onDismiss()
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Join group dialog.
 */
@Composable
fun JoinGroupDialog(group: MeshGroup, onDismiss: () -> Unit, onJoin: (password: String?) -> Unit) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Group") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = group.groupName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Channel ${group.channelNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (group.isPasswordProtected) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onJoin(if (group.isPasswordProtected) password else null)
                    onDismiss()
                },
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Available groups list for joining.
 */
@Composable
fun AvailableGroupsList(groups: List<MeshGroup>, onJoinGroup: (MeshGroup) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Available Groups",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No groups found nearby",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column {
                groups.forEach { group ->
                    AvailableGroupItem(
                        group = group,
                        onClick = { onJoinGroup(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailableGroupItem(group: MeshGroup, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.groupName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (group.isPasswordProtected) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Password protected",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "Channel ${group.channelNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Join",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Join group by code dialog - allows user to enter a group code manually.
 */
@Composable
fun JoinGroupByCodeDialog(onDismiss: () -> Unit, onJoin: (groupCode: String) -> Unit, isValidCode: (String) -> Boolean) {
    var groupCode by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val isValid = isValidCode(groupCode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Group") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Enter the group code shared by your ride leader",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = groupCode,
                    onValueChange = { groupCode = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(7) },
                    label = { Text("Group Code") },
                    placeholder = { Text("XXXX-XX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (isValid) {
                                onJoin(groupCode)
                                onDismiss()
                            }
                        },
                    ),
                    trailingIcon = {
                        if (groupCode.isNotEmpty()) {
                            Icon(
                                imageVector = if (isValid) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    isError = groupCode.isNotEmpty() && !isValid,
                    supportingText = if (groupCode.isNotEmpty() && !isValid) {
                        { Text("Invalid code format (e.g., ABCD-EF)") }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onJoin(groupCode)
                    onDismiss()
                },
                enabled = isValid,
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
