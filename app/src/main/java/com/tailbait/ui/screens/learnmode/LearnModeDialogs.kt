package com.tailbait.ui.screens.learnmode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.util.Constants

/**
 * Dialog for editing device label and category.
 *
 * Allows users to assign a friendly name and category to a device
 * before adding it to the whitelist.
 *
 * @param device Device selection item to label
 * @param onConfirm Callback when user confirms with (label, category)
 * @param onDismiss Callback when user dismisses dialog
 */
@Composable
fun DeviceLabelDialog(
    device: LearnModeViewModel.DeviceSelectionItem,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(device.label) }
    var selectedCategory by remember { mutableStateOf(device.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = "Label Device",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Device address
                Text(
                    text = device.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Label input
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Device Name") },
                    placeholder = { Text("e.g., My Phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Label,
                            contentDescription = null
                        )
                    }
                )

                // Category selection
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                Column(
                    modifier = Modifier.selectableGroup()
                ) {
                    CategoryOption(
                        label = "Own Device",
                        description = "Your personal devices",
                        icon = Icons.Default.Person,
                        category = Constants.WHITELIST_CATEGORY_OWN,
                        selectedCategory = selectedCategory,
                        onSelect = { selectedCategory = it }
                    )
                    CategoryOption(
                        label = "Partner Device",
                        description = "Your partner's devices",
                        icon = Icons.Default.Favorite,
                        category = Constants.WHITELIST_CATEGORY_PARTNER,
                        selectedCategory = selectedCategory,
                        onSelect = { selectedCategory = it }
                    )
                    CategoryOption(
                        label = "Trusted Device",
                        description = "Other trusted devices",
                        icon = Icons.Default.Security,
                        category = Constants.WHITELIST_CATEGORY_TRUSTED,
                        selectedCategory = selectedCategory,
                        onSelect = { selectedCategory = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.isNotBlank()) {
                        onConfirm(label.trim(), selectedCategory)
                    }
                },
                enabled = label.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Category option for device labeling
 */
@Composable
private fun CategoryOption(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    category: String,
    selectedCategory: String,
    onSelect: (String) -> Unit
) {
    Surface(
        onClick = { onSelect(category) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selectedCategory == category) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectedCategory == category,
                onClick = { onSelect(category) }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Confirmation dialog before adding devices to whitelist.
 *
 * Shows a summary of devices to be added and asks for confirmation.
 *
 * @param devicesToAdd List of devices to add to whitelist
 * @param onConfirm Callback when user confirms
 * @param onDismiss Callback when user dismisses
 */
@Composable
fun AddToWhitelistConfirmationDialog(
    devicesToAdd: List<LearnModeViewModel.DeviceSelectionItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Add to Whitelist?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "You're about to add ${devicesToAdd.size} device(s) to your whitelist:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        devicesToAdd.forEach { device ->
                            DeviceSummaryRow(device)
                        }
                    }
                }

                Text(
                    text = "These devices will be excluded from stalking detection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add to Whitelist")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Device summary row for confirmation dialog
 */
@Composable
private fun DeviceSummaryRow(device: LearnModeViewModel.DeviceSelectionItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (device.category) {
                Constants.WHITELIST_CATEGORY_OWN -> Icons.Default.Person
                Constants.WHITELIST_CATEGORY_PARTNER -> Icons.Default.Favorite
                Constants.WHITELIST_CATEGORY_TRUSTED -> Icons.Default.Security
                else -> Icons.Default.BluetoothConnected
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = device.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = device.device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = device.category,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceLabelDialogPreview() {
    MaterialTheme {
        DeviceLabelDialog(
            device = LearnModeViewModel.DeviceSelectionItem(
                device = ScannedDevice(
                    id = 1,
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "Device",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                ),
                label = "My Phone",
                category = Constants.WHITELIST_CATEGORY_OWN
            ),
            onConfirm = { _, _ -> },
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AddToWhitelistConfirmationDialogPreview() {
    MaterialTheme {
        AddToWhitelistConfirmationDialog(
            devicesToAdd = listOf(
                LearnModeViewModel.DeviceSelectionItem(
                    device = ScannedDevice(
                        id = 1,
                        address = "AA:BB:CC:DD:EE:FF",
                        name = "Phone",
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis()
                    ),
                    label = "My Phone",
                    category = Constants.WHITELIST_CATEGORY_OWN
                ),
                LearnModeViewModel.DeviceSelectionItem(
                    device = ScannedDevice(
                        id = 2,
                        address = "11:22:33:44:55:66",
                        name = "Watch",
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis()
                    ),
                    label = "My Watch",
                    category = Constants.WHITELIST_CATEGORY_OWN
                )
            ),
            onConfirm = {},
            onDismiss = {}
        )
    }
}
