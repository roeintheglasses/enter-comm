package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.ui.theme.DarkSurfaceVariant
import com.entercomm.bikeintercom.ui.theme.TechCyan
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TextPrimary
import com.entercomm.bikeintercom.ui.theme.TextSecondary
import com.entercomm.bikeintercom.util.ClipboardUtils
import com.entercomm.bikeintercom.util.ShareUtils
import com.entercomm.bikeintercom.util.rememberHapticFeedback
import kotlinx.coroutines.delay

/**
 * Clipboard label for group code copy operations.
 */
private const val CLIPBOARD_LABEL = "Group Code"

/**
 * Duration to show the checkmark feedback after copying.
 */
private const val COPY_FEEDBACK_DURATION_MS = 1500L

/**
 * A reusable composable that displays a group code in a styled badge with tap-to-copy functionality.
 *
 * Features:
 * - Displays group code in a visually distinct badge
 * - Tapping copies the code to clipboard
 * - Shows animated checkmark feedback on successful copy
 * - Includes subtle copy icon indicator
 * - Uses theme colors for consistent styling
 *
 * @param groupCode The group code to display
 * @param modifier Modifier for the composable
 * @param onCopied Optional callback invoked after the code is copied successfully
 */
@Composable
fun GroupCodeDisplay(groupCode: String, modifier: Modifier = Modifier, onCopied: (() -> Unit)? = null) {
    val context = LocalContext.current
    val haptic = rememberHapticFeedback()
    var showCopySuccess by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Reset the copy success state after a delay
    LaunchedEffect(showCopySuccess) {
        if (showCopySuccess) {
            delay(COPY_FEEDBACK_DURATION_MS)
            showCopySuccess = false
        }
    }

    // Animate scale on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "codeScale",
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                val success = ClipboardUtils.copyToClipboard(
                    context = context,
                    text = groupCode,
                    label = CLIPBOARD_LABEL,
                )
                if (success) {
                    haptic.click()
                    showCopySuccess = true
                    onCopied?.invoke()
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = if (showCopySuccess) {
            TechGreen.copy(alpha = 0.2f)
        } else {
            DarkSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = groupCode,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (showCopySuccess) TechGreen else TechCyan,
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Animated icon that switches between copy and checkmark
            AnimatedContent(
                targetState = showCopySuccess,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200)))
                        .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200)))
                },
                label = "copyIconAnimation",
            ) { isCopied ->
                Icon(
                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (isCopied) "Copied" else "Tap to copy",
                    modifier = Modifier.size(14.dp),
                    tint = if (isCopied) TechGreen else TextPrimary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * A composable row that displays a group code with copy and share action buttons.
 *
 * This component is designed to replace the static "Share code X to let others join" text
 * with interactive copy and share functionality.
 *
 * Features:
 * - Displays shareable group code with descriptive text
 * - Copy button with animated checkmark feedback on success
 * - Share button that opens the Android share sheet
 * - Consistent styling with app theme
 *
 * @param groupCode The group code to display and share
 * @param modifier Modifier for the composable
 * @param onCopied Optional callback invoked after the code is copied successfully
 * @param onShared Optional callback invoked after the share sheet is opened
 */
@Composable
fun GroupCodeShareRow(groupCode: String, modifier: Modifier = Modifier, onCopied: (() -> Unit)? = null, onShared: (() -> Unit)? = null) {
    val context = LocalContext.current
    val haptic = rememberHapticFeedback()
    var showCopySuccess by remember { mutableStateOf(false) }

    // Reset the copy success state after a delay
    LaunchedEffect(showCopySuccess) {
        if (showCopySuccess) {
            delay(COPY_FEEDBACK_DURATION_MS)
            showCopySuccess = false
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: Share icon and text with code
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = TextSecondary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Share code ",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = groupCode,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = TechCyan,
            )
            Text(
                text = " to let others join",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        // Right side: Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Copy button with animated feedback
            IconButton(
                onClick = {
                    val success = ClipboardUtils.copyToClipboard(
                        context = context,
                        text = groupCode,
                        label = CLIPBOARD_LABEL,
                    )
                    if (success) {
                        haptic.click()
                        showCopySuccess = true
                        onCopied?.invoke()
                    }
                },
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = DarkSurfaceVariant,
                ),
            ) {
                AnimatedContent(
                    targetState = showCopySuccess,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200)))
                            .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200)))
                    },
                    label = "copyButtonAnimation",
                ) { isCopied ->
                    Icon(
                        imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (isCopied) "Copied" else "Copy code",
                        modifier = Modifier.size(16.dp),
                        tint = if (isCopied) TechGreen else TextPrimary,
                    )
                }
            }

            // Share button
            IconButton(
                onClick = {
                    val success = ShareUtils.shareGroupCode(
                        context = context,
                        groupCode = groupCode,
                    )
                    if (success) {
                        haptic.click()
                        onShared?.invoke()
                    }
                },
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = DarkSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share code",
                    modifier = Modifier.size(16.dp),
                    tint = TextPrimary,
                )
            }
        }
    }
}
