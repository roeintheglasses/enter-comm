package com.entercomm.bikeintercom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entercomm.bikeintercom.ui.theme.*
import com.entercomm.bikeintercom.util.BoneConductionMode

/**
 * A toggle switch setting row for boolean accessibility options.
 *
 * @param label The setting name displayed on the left
 * @param description Optional description text shown below the label
 * @param checked Current toggle state
 * @param onCheckedChange Callback when toggle state changes
 * @param icon Optional leading icon
 * @param enabled Whether the toggle is interactive
 * @param modifier Modifier for the row
 */
@Composable
fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) TechCyan else TextTertiary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) TextPrimary else TextTertiary,
                    fontWeight = FontWeight.Medium,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TechGreen,
                checkedTrackColor = TechGreen.copy(alpha = 0.3f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DarkSurfaceElevated,
                disabledCheckedThumbColor = TechGreen.copy(alpha = 0.5f),
                disabledCheckedTrackColor = TechGreen.copy(alpha = 0.15f),
                disabledUncheckedThumbColor = TextTertiary.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = DarkSurfaceElevated.copy(alpha = 0.5f),
            ),
        )
    }
}

/**
 * A slider setting row for float/percentage accessibility options.
 *
 * @param label The setting name displayed above the slider
 * @param value Current slider value
 * @param onValueChange Callback when slider value changes
 * @param valueRange The range of values for the slider
 * @param icon Optional leading icon
 * @param valueFormatter Function to format the display value (e.g., "80%", "1.2x")
 * @param enabled Whether the slider is interactive
 * @param modifier Modifier for the column
 */
@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    icon: ImageVector? = null,
    valueFormatter: (Float) -> String = { "${(it * 100).toInt()}%" },
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) TechCyan else TextTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) TextPrimary else TextTertiary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TechCyan else TextTertiary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = TechGreen,
                activeTrackColor = TechGreen,
                inactiveTrackColor = DarkSurfaceElevated,
                disabledThumbColor = TechGreen.copy(alpha = 0.5f),
                disabledActiveTrackColor = TechGreen.copy(alpha = 0.3f),
                disabledInactiveTrackColor = DarkSurfaceElevated.copy(alpha = 0.5f),
            ),
        )
    }
}

/**
 * A dropdown selector for enum-based accessibility settings.
 *
 * @param label The setting name displayed on the left
 * @param selectedOption Currently selected option
 * @param options List of available options
 * @param onOptionSelected Callback when an option is selected
 * @param optionLabel Function to get display label for an option
 * @param description Optional description or status text (e.g., "Detected: Shokz OpenRun")
 * @param icon Optional leading icon
 * @param enabled Whether the dropdown is interactive
 * @param modifier Modifier for the row
 */
@Composable
fun <T> SettingsDropdown(
    label: String,
    selectedOption: T,
    options: List<T>,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsDropdownLabel(
            label = label,
            description = description,
            icon = icon,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )

        SettingsDropdownSelector(
            selectedOption = selectedOption,
            options = options,
            onOptionSelected = onOptionSelected,
            optionLabel = optionLabel,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsDropdownLabel(label: String, description: String?, icon: ImageVector?, enabled: Boolean, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TechCyan else TextTertiary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TextPrimary else TextTertiary,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TechCyan,
                )
            }
        }
    }
}

@Composable
private fun <T> SettingsDropdownSelector(selectedOption: T, options: List<T>, onOptionSelected: (T) -> Unit, optionLabel: (T) -> String, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (expanded) TechCyan else DarkBorder,
        animationSpec = tween(200),
        label = "dropdownBorder",
    )

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = optionLabel(selectedOption),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TextPrimary else TextTertiary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = if (enabled) TextSecondary else TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurface),
        ) {
            options.forEach { option ->
                SettingsDropdownMenuItem(
                    option = option,
                    isSelected = option == selectedOption,
                    optionLabel = optionLabel,
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> SettingsDropdownMenuItem(option: T, isSelected: Boolean, optionLabel: (T) -> String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = optionLabel(option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) TechGreen else TextPrimary,
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = TechGreen,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/**
 * Helper function to format BoneConductionMode for display.
 */
fun BoneConductionMode.displayName(): String {
    return when (this) {
        BoneConductionMode.AUTO -> "Auto"
        BoneConductionMode.ENABLED -> "Enabled"
        BoneConductionMode.DISABLED -> "Disabled"
    }
}
