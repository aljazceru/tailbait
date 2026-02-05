package com.tailbait.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import kotlin.math.roundToInt

/**
 * Settings section header with title and optional icon.
 *
 * This component is used to visually separate different settings sections
 * and provide context about what the following settings control.
 *
 * @param title The section title text
 * @param icon Optional icon to display before the title
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = TailBaitDimensions.ContentPaddingHorizontal,
                vertical = TailBaitDimensions.ListItemPaddingVertical
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(TailBaitDimensions.IconSizeDefault)
            )
            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Settings item with a switch toggle.
 *
 * This component displays a setting that can be toggled on or off,
 * with an optional description text.
 *
 * @param title The setting title
 * @param description Optional description text
 * @param checked Current state of the switch
 * @param onCheckedChange Callback when switch is toggled
 * @param icon Optional icon to display
 * @param enabled Whether the setting is enabled for interaction
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SettingsSwitchItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TailBaitDimensions.ListItemPaddingHorizontal,
                    vertical = TailBaitDimensions.ListItemPaddingVertical
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                )
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

/**
 * Settings item with a slider for numeric values.
 *
 * Displays a setting with a slider control, showing the current value
 * and optional unit label.
 *
 * @param title The setting title
 * @param description Optional description text
 * @param value Current slider value
 * @param onValueChange Callback when slider value changes
 * @param valueRange The range of valid values for the slider
 * @param steps Number of steps between min and max (0 for continuous)
 * @param valueLabel Function to format the value for display
 * @param icon Optional icon to display
 * @param enabled Whether the setting is enabled for interaction
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SettingsSliderItem(
    title: String,
    description: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0,
    valueLabel: (Float) -> String = { it.roundToInt().toString() },
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TailBaitDimensions.ListItemPaddingHorizontal,
                    vertical = TailBaitDimensions.ListItemPaddingVertical
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                    )
                    Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                    if (description != null) {
                        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))

                Text(
                    text = valueLabel(value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Settings item with multiple choice radio buttons.
 *
 * Displays a setting with a dropdown menu or radio button group
 * for selecting one option from multiple choices.
 *
 * @param title The setting title
 * @param description Optional description text
 * @param selectedOption Currently selected option
 * @param options List of available options
 * @param onOptionSelected Callback when an option is selected
 * @param icon Optional icon to display
 * @param enabled Whether the setting is enabled for interaction
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SettingsDropdownItem(
    title: String,
    description: String? = null,
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { expanded = true },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TailBaitDimensions.ListItemPaddingHorizontal,
                    vertical = TailBaitDimensions.ListItemPaddingVertical
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                )
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedOption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        },
                        leadingIcon = if (option == selectedOption) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

/**
 * Settings item with a clickable action.
 *
 * Displays a setting that performs an action when clicked,
 * typically used for navigation or triggering dialogs.
 *
 * @param title The setting title
 * @param description Optional description text
 * @param onClick Callback when item is clicked
 * @param icon Optional icon to display
 * @param trailingIcon Optional trailing icon (default: arrow forward)
 * @param enabled Whether the setting is enabled for interaction
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SettingsActionItem(
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = Icons.Default.ChevronRight,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TailBaitDimensions.ListItemPaddingHorizontal,
                    vertical = TailBaitDimensions.ListItemPaddingVertical
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                )
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }

            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

/**
 * Settings divider for visual separation.
 *
 * A thin horizontal line to separate settings items or sections.
 */
@Composable
fun SettingsDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = TailBaitDimensions.ContentPaddingHorizontal),
        thickness = TailBaitDimensions.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Confirmation dialog for destructive actions.
 *
 * Displays a dialog asking for user confirmation before
 * performing a potentially destructive action.
 *
 * @param title Dialog title
 * @param message Dialog message
 * @param confirmText Confirm button text (default: "Confirm")
 * @param dismissText Dismiss button text (default: "Cancel")
 * @param onConfirm Callback when confirmed
 * @param onDismiss Callback when dismissed
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(TailBaitDimensions.IconSizeLarge)
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
        shape = TailBaitShapeTokens.DialogShape
    )
}
