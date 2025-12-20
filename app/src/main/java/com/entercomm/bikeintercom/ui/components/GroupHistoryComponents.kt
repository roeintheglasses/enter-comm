package com.entercomm.bikeintercom.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.group.GroupMemory
import com.entercomm.bikeintercom.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A card showing group history with one-tap rejoin.
 */
@Composable
fun GroupHistoryCard(
    groupHistory: List<GroupMemory>,
    onRejoinGroup: (GroupMemory) -> Unit,
    onRenameGroup: (GroupMemory) -> Unit,
    onRemoveGroup: (GroupMemory) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            GroupHistoryHeader(showClear = groupHistory.isNotEmpty(), onClearHistory = onClearHistory)
            Spacer(modifier = Modifier.height(12.dp))
            GroupHistoryContent(groupHistory, onRejoinGroup, onRenameGroup, onRemoveGroup)
        }
    }
}

@Composable
private fun GroupHistoryHeader(showClear: Boolean, onClearHistory: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = TechCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Recent Groups", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        if (showClear) {
            TextButton(onClick = onClearHistory, colors = ButtonDefaults.textButtonColors(contentColor = TechRed)) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun GroupHistoryContent(groupHistory: List<GroupMemory>, onRejoinGroup: (GroupMemory) -> Unit, onRenameGroup: (GroupMemory) -> Unit, onRemoveGroup: (GroupMemory) -> Unit) {
    if (groupHistory.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text("No recent groups", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groupHistory.forEach { group ->
                GroupHistoryItem(group, { onRejoinGroup(group) }, { onRenameGroup(group) }, { onRemoveGroup(group) })
            }
        }
    }
}

@Composable
private fun GroupHistoryItem(group: GroupMemory, onRejoin: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRejoin),
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupHistoryItemInfo(group, Modifier.weight(1f))
            GroupHistoryItemActions(onRejoin, showMenu, { showMenu = it }, onRename, onRemove)
        }
    }
}

@Composable
private fun GroupHistoryItemInfo(group: GroupMemory, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            group.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (group.customName != null) {
                Text(GroupMemory.formatGroupCode(group.groupCode), style = MaterialTheme.typography.labelSmall, color = TechCyan)
            }
            Text(formatRelativeTime(group.lastJoinedAt), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

@Composable
private fun GroupHistoryItemActions(onRejoin: () -> Unit, showMenu: Boolean, onShowMenuChange: (Boolean) -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onRejoin,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = TechGreen.copy(alpha = 0.2f), contentColor = TechGreen),
        ) {
            Icon(Icons.Default.Login, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Join", style = MaterialTheme.typography.labelMedium)
        }
        GroupHistoryItemMenu(showMenu, onShowMenuChange, onRename, onRemove)
    }
}

@Composable
private fun GroupHistoryItemMenu(showMenu: Boolean, onShowMenuChange: (Boolean) -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    Box {
        IconButton(onClick = { onShowMenuChange(true) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, "More options", tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenuChange(false) }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = {
                    onShowMenuChange(false)
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = TechRed) },
                onClick = {
                    onShowMenuChange(false)
                    onRemove()
                },
            )
        }
    }
}

/**
 * Dialog for renaming a group.
 */
@Composable
fun RenameGroupDialog(group: GroupMemory, onDismiss: () -> Unit, onRename: (String?) -> Unit) {
    var name by remember { mutableStateOf(group.customName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Group") },
        text = { RenameGroupDialogContent(group.groupCode, name) { name = it.take(20) } },
        confirmButton = {
            Button(onClick = {
                onRename(name.trim().takeIf { it.isNotEmpty() })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameGroupDialogContent(groupCode: String, name: String, onNameChange: (String) -> Unit) {
    Column {
        Text("Code: ${GroupMemory.formatGroupCode(groupCode)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Custom Name") },
            placeholder = { Text("e.g., Sunday Riders") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("Leave empty to use default code", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

/**
 * Dialog for adjusting per-group volumes.
 */
@Composable
fun GroupVolumeDialog(group: GroupMemory, onDismiss: () -> Unit, onVolumeChange: (Float, Float) -> Unit) {
    var incomingVolume by remember { mutableFloatStateOf(group.incomingVolume) }
    var voiceFeedbackVolume by remember { mutableFloatStateOf(group.voiceFeedbackVolume) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Volume for ${group.displayName}") },
        text = {
            Column {
                SettingsSlider("Incoming Audio", incomingVolume, { incomingVolume = it }, icon = Icons.Default.VolumeUp)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSlider("Voice Feedback", voiceFeedbackVolume, { voiceFeedbackVolume = it }, icon = Icons.Default.RecordVoiceOver)
            }
        },
        confirmButton = {
            Button(onClick = {
                onVolumeChange(incomingVolume, voiceFeedbackVolume)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirmation dialog for clearing history.
 */
@Composable
fun ClearHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = TechOrange) },
        title = { Text("Clear Group History?") },
        text = { Text("This will remove all saved groups and their preferences. This action cannot be undone.") },
        confirmButton = {
            Button(onClick = {
                onConfirm()
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = TechRed)) {
                Text("Clear All")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
