package com.tailbait.ui.screens.whitelist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.ui.theme.TailBaitTheme

/**
 * Dialog for adding a device to the whitelist.
 *
 * This dialog allows users to select a device and configure:
 * - Label (user-friendly name)
 * - Category (OWN, PARTNER, TRUSTED)
 * - Optional notes
 *
 * @param devices List of available devices to whitelist
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when user confirms (deviceId, label, category, notes)
 */
@Composable
fun AddToWhitelistDialog(
    devices: List<ScannedDevice>,
    onDismiss: () -> Unit,
    onConfirm: (deviceId: Long, label: String, category: String, notes: String?) -> Unit
) {
    var selectedDeviceId by remember { mutableStateOf<Long?>(null) }
    var label by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(WhitelistRepository.Category.OWN) }
    var notes by remember { mutableStateOf("") }

    // Pre-fill label when device is selected
    LaunchedEffect(selectedDeviceId) {
        selectedDeviceId?.let { deviceId ->
            val device = devices.find { it.id == deviceId }
            if (label.isBlank()) {
                label = device?.name ?: device?.address ?: ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add to Whitelist")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Device Selection
                Text(
                    text = "Select Device",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                if (devices.isEmpty()) {
                    Text(
                        text = "No devices available. Start tracking to discover devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.selectableGroup()
                    ) {
                        devices.forEach { device ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = (selectedDeviceId == device.id),
                                        onClick = { selectedDeviceId = device.id },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selectedDeviceId == device.id),
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = device.name ?: "Unknown Device",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Only show the rest if a device is selected
                if (selectedDeviceId != null) {
                    Divider()

                    // Label Input
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        placeholder = { Text("e.g., My Phone, Partner's Watch") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Category Selection
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(
                        modifier = Modifier.selectableGroup()
                    ) {
                        listOf(
                            WhitelistRepository.Category.OWN to "My Devices",
                            WhitelistRepository.Category.PARTNER to "Partner's Devices",
                            WhitelistRepository.Category.TRUSTED to "Trusted Devices"
                        ).forEach { (category, displayName) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = (selectedCategory == category),
                                        onClick = { selectedCategory = category },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selectedCategory == category),
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = displayName)
                            }
                        }
                    }

                    // Notes Input
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (Optional)") },
                        placeholder = { Text("Add any additional notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedDeviceId?.let { deviceId ->
                        onConfirm(
                            deviceId,
                            label.ifBlank { "Trusted Device" },
                            selectedCategory,
                            notes.ifBlank { null }
                        )
                    }
                },
                enabled = selectedDeviceId != null && label.isNotBlank()
            ) {
                Text("Add")
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
 * Dialog for editing an existing whitelist entry.
 *
 * Allows users to modify:
 * - Label
 * - Category
 * - Notes
 *
 * @param entry The whitelist entry to edit
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when user confirms (label, category, notes)
 */
@Composable
fun EditWhitelistDialog(
    entry: WhitelistEntry,
    onDismiss: () -> Unit,
    onConfirm: (label: String, category: String, notes: String?) -> Unit
) {
    var label by remember { mutableStateOf(entry.label) }
    var selectedCategory by remember { mutableStateOf(entry.category) }
    var notes by remember { mutableStateOf(entry.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit Whitelist Entry")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Label Input
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g., My Phone, Partner's Watch") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Category Selection
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier.selectableGroup()
                ) {
                    listOf(
                        WhitelistRepository.Category.OWN to "My Devices",
                        WhitelistRepository.Category.PARTNER to "Partner's Devices",
                        WhitelistRepository.Category.TRUSTED to "Trusted Devices"
                    ).forEach { (category, displayName) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (selectedCategory == category),
                                    onClick = { selectedCategory = category },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedCategory == category),
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = displayName)
                        }
                    }
                }

                // Notes Input
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (Optional)") },
                    placeholder = { Text("Add any additional notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        label.ifBlank { "Trusted Device" },
                        selectedCategory,
                        notes.ifBlank { null }
                    )
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
 * Confirmation dialog for deleting a whitelist entry.
 *
 * @param entry The entry with device information to delete
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when user confirms deletion
 */
@Composable
fun DeleteWhitelistConfirmationDialog(
    entry: WhitelistRepository.WhitelistEntryWithDevice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Remove from Whitelist?")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Are you sure you want to remove this device from your whitelist?",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = entry.entry.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = entry.device.name ?: "Unknown Device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = entry.device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This device will be included in stalking detection again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ========== Preview Functions ==========

@Preview(name = "Add to Whitelist Dialog", showBackground = true)
@Composable
private fun AddToWhitelistDialogPreview() {
    TailBaitTheme {
        AddToWhitelistDialog(
            devices = listOf(
                ScannedDevice(
                    id = 1,
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "My Phone",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                ),
                ScannedDevice(
                    id = 2,
                    address = "11:22:33:44:55:66",
                    name = "Partner's Watch",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                )
            ),
            onDismiss = {},
            onConfirm = { _, _, _, _ -> }
        )
    }
}

@Preview(name = "Edit Whitelist Dialog", showBackground = true)
@Composable
private fun EditWhitelistDialogPreview() {
    TailBaitTheme {
        EditWhitelistDialog(
            entry = WhitelistEntry(
                id = 1,
                deviceId = 1,
                label = "My Phone",
                category = WhitelistRepository.Category.OWN,
                notes = "iPhone 15 Pro"
            ),
            onDismiss = {},
            onConfirm = { _, _, _ -> }
        )
    }
}

@Preview(name = "Delete Confirmation Dialog", showBackground = true)
@Composable
private fun DeleteWhitelistConfirmationDialogPreview() {
    TailBaitTheme {
        DeleteWhitelistConfirmationDialog(
            entry = WhitelistRepository.WhitelistEntryWithDevice(
                entry = WhitelistEntry(
                    id = 1,
                    deviceId = 1,
                    label = "My Phone",
                    category = WhitelistRepository.Category.OWN
                ),
                device = ScannedDevice(
                    id = 1,
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "iPhone 15 Pro",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                )
            ),
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview(name = "Add Dialog - Dark Mode", showBackground = true)
@Composable
private fun AddToWhitelistDialogDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        AddToWhitelistDialog(
            devices = listOf(
                ScannedDevice(
                    id = 1,
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "My Phone",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                )
            ),
            onDismiss = {},
            onConfirm = { _, _, _, _ -> }
        )
    }
}
