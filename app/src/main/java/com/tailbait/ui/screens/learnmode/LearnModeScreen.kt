package com.tailbait.ui.screens.learnmode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.data.database.entities.ScannedDevice

/**
 * Learn Mode screen composable.
 *
 * This screen allows users to discover nearby BLE devices and bulk-add them
 * to their whitelist. It features:
 * - Start/stop Learn Mode
 * - Real-time device discovery
 * - Device selection with checkboxes
 * - Countdown timer with progress indicator
 * - Device labeling
 * - Batch whitelist addition
 *
 * @param onNavigateBack Callback to navigate back
 * @param viewModel ViewModel for Learn Mode (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnModeScreen(
    onNavigateBack: () -> Unit,
    viewModel: LearnModeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Show dialogs
    if (uiState.showLabelDialog && uiState.deviceToLabel != null) {
        DeviceLabelDialog(
            device = uiState.deviceToLabel!!,
            onConfirm = { label, category ->
                viewModel.updateDeviceLabel(
                    uiState.deviceToLabel!!.device.id,
                    label,
                    category
                )
            },
            onDismiss = { viewModel.dismissLabelDialog() }
        )
    }

    if (uiState.showConfirmationDialog) {
        AddToWhitelistConfirmationDialog(
            devicesToAdd = uiState.devicesToAdd,
            onConfirm = { viewModel.confirmAddToWhitelist() },
            onDismiss = { viewModel.dismissConfirmationDialog() }
        )
    }

    // Show success snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.showSuccessMessage) {
        if (uiState.showSuccessMessage) {
            snackbarHostState.showSnackbar(
                message = "${uiState.devicesAddedCount} device(s) added to whitelist",
                duration = SnackbarDuration.Short
            )
            viewModel.dismissSuccessMessage()
            onNavigateBack()
        }
    }

    // Show error snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Learn Mode") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isActive) {
                            viewModel.stopLearnMode()
                        }
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!uiState.permissionsGranted) {
                PermissionRequiredMessage()
            } else if (!uiState.isActive) {
                LearnModeIntro(
                    onStartLearnMode = { viewModel.startLearnMode() }
                )
            } else {
                LearnModeActiveContent(
                    uiState = uiState,
                    onToggleDeviceSelection = { deviceId ->
                        viewModel.toggleDeviceSelection(deviceId)
                    },
                    onLabelDevice = { deviceId ->
                        viewModel.showLabelDialog(deviceId)
                    },
                    onFinishLearnMode = { viewModel.finishLearnMode() },
                    onCancelLearnMode = { viewModel.stopLearnMode() },
                    formatTimeRemaining = { ms ->
                        viewModel.formatTimeRemaining(ms)
                    }
                )
            }
        }
    }
}

/**
 * Permission required message
 */
@Composable
private fun PermissionRequiredMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Learn Mode requires Bluetooth and Location permissions to discover nearby devices.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Learn Mode introduction screen
 */
@Composable
private fun LearnModeIntro(
    onStartLearnMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.School,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "What is Learn Mode?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Learn Mode helps you discover and whitelist your own devices in one go.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                InstructionItem(
                    number = "1",
                    text = "Start Learn Mode for 5 minutes"
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionItem(
                    number = "2",
                    text = "Keep your devices (phone, watch, etc.) nearby"
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionItem(
                    number = "3",
                    text = "Select discovered devices to whitelist"
                )
                Spacer(modifier = Modifier.height(12.dp))
                InstructionItem(
                    number = "4",
                    text = "Finish to add them all at once"
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartLearnMode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Start Learn Mode",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/**
 * Instruction item for intro screen
 */
@Composable
private fun InstructionItem(
    number: String,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Learn Mode active content
 */
@Composable
private fun LearnModeActiveContent(
    uiState: LearnModeViewModel.LearnModeUiState,
    onToggleDeviceSelection: (Long) -> Unit,
    onLabelDevice: (Long) -> Unit,
    onFinishLearnMode: () -> Unit,
    onCancelLearnMode: () -> Unit,
    formatTimeRemaining: (Long) -> String
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Progress and timer section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatTimeRemaining(uiState.timeRemainingMs),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))

                val animatedProgress by animateFloatAsState(
                    targetValue = uiState.scanProgress,
                    label = "scan_progress"
                )
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scanning...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            text = "${uiState.discoveredDevices.size} devices found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Device list section
        if (uiState.discoveredDevices.isEmpty()) {
            EmptyDeviceList()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.discoveredDevices,
                    key = { it.device.id }
                ) { deviceItem ->
                    DeviceSelectionCard(
                        deviceItem = deviceItem,
                        onToggleSelection = { onToggleDeviceSelection(deviceItem.device.id) },
                        onLabelDevice = { onLabelDevice(deviceItem.device.id) }
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancelLearnMode,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onFinishLearnMode,
                modifier = Modifier.weight(1f),
                enabled = uiState.selectedDeviceIds.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Finish (${uiState.selectedDeviceIds.size})")
            }
        }
    }
}

/**
 * Empty device list message
 */
@Composable
private fun EmptyDeviceList() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Searching for devices...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Make sure your devices are nearby and Bluetooth is enabled",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Device selection card
 */
@Composable
private fun DeviceSelectionCard(
    deviceItem: LearnModeViewModel.DeviceSelectionItem,
    onToggleSelection: () -> Unit,
    onLabelDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (deviceItem.isAlreadyWhitelisted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = deviceItem.isSelected,
                onCheckedChange = { if (!deviceItem.isAlreadyWhitelisted) onToggleSelection() },
                enabled = !deviceItem.isAlreadyWhitelisted
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = deviceItem.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = deviceItem.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (deviceItem.isAlreadyWhitelisted) {
                    Text(
                        text = "Already whitelisted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!deviceItem.isAlreadyWhitelisted) {
                IconButton(
                    onClick = onLabelDevice
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LearnModeIntroPreview() {
    MaterialTheme {
        Surface {
            LearnModeIntro(onStartLearnMode = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceSelectionCardPreview() {
    MaterialTheme {
        Surface {
            DeviceSelectionCard(
                deviceItem = LearnModeViewModel.DeviceSelectionItem(
                    device = ScannedDevice(
                        id = 1,
                        address = "AA:BB:CC:DD:EE:FF",
                        name = "My Phone",
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis()
                    ),
                    isSelected = true,
                    label = "My Phone"
                ),
                onToggleSelection = {},
                onLabelDevice = {}
            )
        }
    }
}
