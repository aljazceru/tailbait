package com.tailbait.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Companion Device (ESP32) settings section.
 *
 * - Toggle: connect to the paired companion while tracking runs
 * - Pairing flow: scan for devices advertising the companion service
 * - Status card: connection state, firmware, mode, records received
 * - Controls: mode switch (Carry/Sentinel), forget device
 */
@Composable
fun CompanionDeviceSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel,
) {
    SettingsSection(
        title = "Companion Device",
        icon = Icons.Outlined.Usb,
    )

    SettingsSwitchItem(
        title = "Companion Scanning",
        description =
            "Connect to your ESP32 companion while tracking. " +
                "Adds WiFi + continuous BLE coverage from the companion device.",
        checked = uiState.settings.companionEnabled,
        onCheckedChange = { viewModel.updateCompanionEnabled(it) },
        icon = Icons.Outlined.Bluetooth,
    )

    val companionState by viewModel.companionLinkState.collectAsState()
    val paired by viewModel.pairedCompanion.collectAsState()
    val scanState by viewModel.companionScan.collectAsState()

    paired?.let { device ->
        CompanionStatusCard(
            device = device,
            linkState = companionState,
            recordsBle = viewModel.companionIngestor.counters.ble.get(),
            recordsWifi =
                viewModel.companionIngestor.counters.probe.get() +
                    viewModel.companionIngestor.counters.sta.get() +
                    viewModel.companionIngestor.counters.beacon.get(),
            onSetMode = { viewModel.setCompanionMode(it) },
            onForget = { viewModel.forgetCompanion() },
        )
    } ?: Text(
        text =
            "No companion paired. Flash the tailbait-companion firmware " +
                "onto an ESP32 (see Help ▸ Companion Device), then pair it below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    if (uiState.settings.companionEnabled && paired == null) {
        TextButton(
            onClick = { viewModel.startCompanionScan() },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(if (scanState.isNotEmpty()) "Scanning… (${scanState.size} found)" else "Pair companion device")
        }
    }

    if (scanState.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.stopCompanionScan() },
            title = { Text("Pair companion") },
            text = {
                Column {
                    Text(
                        "Devices advertising the TailBait companion service:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    scanState.forEach { (address, name) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.pairCompanion(address, name) }
                                    .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Usb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(name ?: "TailBait-Companion", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.stopCompanionScan() }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionStatusCard(
    device: com.tailbait.data.database.entities.CompanionDevice,
    linkState: String,
    recordsBle: Long,
    recordsWifi: Long,
    onSetMode: (Int) -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.name ?: "Companion", style = MaterialTheme.typography.titleSmall)
                    Text(
                        device.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    linkState,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        when {
                            linkState.startsWith("Connected") -> MaterialTheme.colorScheme.primary
                            linkState.startsWith("Error") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "fw ${device.firmwareVersion ?: "?"} · mode ${device.mode ?: "?"} · " +
                    "records: $recordsBle BLE / $recordsWifi WiFi (session)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSetMode(1) }) { Text("Carry") }
                TextButton(onClick = { onSetMode(0) }) { Text("Sentinel") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onForget) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Forget")
                }
            }
        }
    }
}
