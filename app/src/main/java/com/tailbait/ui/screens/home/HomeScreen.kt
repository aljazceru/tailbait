package com.tailbait.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.components.EmptyView
import com.tailbait.ui.components.KeyValueRow
import com.tailbait.ui.components.LoadingView
import com.tailbait.ui.components.SectionTitle
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import com.tailbait.ui.theme.TailBaitTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Home Screen - Main Dashboard
 *
 * This is the main screen of the app that shows:
 * - Current scanning status (Active/Idle/Error)
 * - Total devices found
 * - Start/stop tracking controls
 * - Last scan timestamp
 * - Permission status indicators
 * - Quick actions and navigation
 *
 * The screen is designed with Material Design 3 and supports both light and dark themes.
 *
 * @param onNavigateToDeviceList Callback when user wants to view device list
 * @param onNavigateToSettings Callback when user wants to view settings
 * @param onNavigateToPermissions Callback when user wants to manage permissions
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDeviceList: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToAlerts: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TailBait",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            // Manual scan FAB (shown only when tracking is enabled and not already scanning)
            if (uiState.isTrackingEnabled && uiState.scanState != HomeViewModel.ScanStatus.Scanning) {
                FloatingActionButton(
                    onClick = { viewModel.performManualScan() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = TailBaitShapeTokens.FabShape
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Manual Scan"
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    LoadingView(message = "Loading...")
                }
                else -> {
                    HomeContent(
                        uiState = uiState,
                        onToggleTracking = { viewModel.toggleTracking() },
                        onNavigateToDeviceList = onNavigateToDeviceList,
                        onNavigateToPermissions = onNavigateToPermissions,
                        onNavigateToAlerts = onNavigateToAlerts,
                        onNavigateToMap = onNavigateToMap,
                        onClearError = { viewModel.clearError() }
                    )
                }
            }
        }
    }
}

/**
 * Main content of the Home Screen
 */
@Composable
private fun HomeContent(
    uiState: HomeViewModel.HomeUiState,
    onToggleTracking: () -> Unit,
    onNavigateToDeviceList: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToMap: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TailBaitDimensions.SpacingLG),
        verticalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingLG)
    ) {
        // Error message (if any)
        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ErrorBanner(
                message = uiState.errorMessage ?: "",
                onDismiss = onClearError
            )
        }

        // Permission status card
        if (!uiState.permissionStatus.allEssentialGranted) {
            PermissionStatusCard(
                permissionStatus = uiState.permissionStatus,
                onNavigateToPermissions = onNavigateToPermissions
            )
        }

        // Scanning status card
        ScanningStatusCard(
            scanState = uiState.scanState,
            isTrackingEnabled = uiState.isTrackingEnabled,
            devicesFoundInCurrentScan = uiState.devicesFoundInCurrentScan
        )

        // Tracking control button
        TrackingControlButton(
            isTrackingEnabled = uiState.isTrackingEnabled,
            canEnableTracking = uiState.permissionStatus.allEssentialGranted,
            onToggleTracking = onToggleTracking
        )

        // Statistics card
        StatisticsCard(
            totalDevicesFound = uiState.totalDevicesFound,
            knownDevicesCount = uiState.knownDevicesCount,
            unknownDevicesCount = uiState.unknownDevicesCount,
            lastScanTimestamp = uiState.lastScanTimestamp,
            onViewDevices = onNavigateToDeviceList
        )

        // Quick actions
        QuickActionsSection(
            onNavigateToDeviceList = onNavigateToDeviceList,
            onNavigateToAlerts = onNavigateToAlerts,
            onNavigateToMap = onNavigateToMap,
            totalDevices = uiState.totalDevicesFound,
            knownDevices = uiState.knownDevicesCount,
            unknownDevices = uiState.unknownDevicesCount
        )
    }
}

/**
 * Error banner shown at the top when there's an error
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                )
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingMD))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Permission status card
 */
@Composable
private fun PermissionStatusCard(
    permissionStatus: HomeViewModel.PermissionStatus,
    onNavigateToPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
                )
                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingMD))
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

            // Permission items
            PermissionItem(
                name = "Bluetooth",
                granted = permissionStatus.bluetoothGranted
            )
            PermissionItem(
                name = "Location",
                granted = permissionStatus.locationGranted
            )
            PermissionItem(
                name = "Notifications",
                granted = permissionStatus.notificationGranted
            )
            PermissionItem(
                name = "Background Location (Optional)",
                granted = permissionStatus.backgroundLocationGranted,
                isOptional = true
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

            Button(
                onClick = onNavigateToPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TailBaitDimensions.ButtonHeight),
                shape = TailBaitShapeTokens.ButtonShape
            ) {
                Text(
                    text = "Grant Permissions",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Individual permission item
 */
@Composable
private fun PermissionItem(
    name: String,
    granted: Boolean,
    isOptional: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TailBaitDimensions.SpacingXS),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) {
                MaterialTheme.colorScheme.primary
            } else if (isOptional) {
                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(TailBaitDimensions.IconSizeSmall)
        )
    }
}

/**
 * Scanning status card
 */
@Composable
private fun ScanningStatusCard(
    scanState: HomeViewModel.ScanStatus,
    isTrackingEnabled: Boolean,
    devicesFoundInCurrentScan: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = when (scanState) {
                // Use primary container but we will apply brush background internally if possible or just stick to solid for now
                HomeViewModel.ScanStatus.Scanning -> MaterialTheme.colorScheme.primaryContainer
                HomeViewModel.ScanStatus.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (scanState == HomeViewModel.ScanStatus.Scanning) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status icon
            Icon(
                imageVector = when (scanState) {
                    HomeViewModel.ScanStatus.Scanning -> Icons.Outlined.BluetoothSearching
                    HomeViewModel.ScanStatus.Error -> Icons.Filled.Error
                    else -> if (isTrackingEnabled) Icons.Outlined.Bluetooth else Icons.Outlined.BluetoothDisabled
                },
                contentDescription = null,
                modifier = Modifier.size(TailBaitDimensions.IconSizeXL),
                tint = when (scanState) {
                    HomeViewModel.ScanStatus.Scanning -> MaterialTheme.colorScheme.primary
                    HomeViewModel.ScanStatus.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

            // Status text
            Text(
                text = when (scanState) {
                    HomeViewModel.ScanStatus.Scanning -> "Scanning..."
                    HomeViewModel.ScanStatus.Error -> "Scan Error"
                    else -> if (isTrackingEnabled) "Tracking Active" else "Tracking Inactive"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (scanState) {
                    HomeViewModel.ScanStatus.Scanning -> MaterialTheme.colorScheme.onPrimaryContainer
                    HomeViewModel.ScanStatus.Error -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            // Show devices found in current scan if scanning
            if (scanState == HomeViewModel.ScanStatus.Scanning) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))
                Text(
                    text = "$devicesFoundInCurrentScan devices found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Tracking control button
 */
@Composable
private fun TrackingControlButton(
    isTrackingEnabled: Boolean,
    canEnableTracking: Boolean,
    onToggleTracking: () -> Unit
) {
    Button(
        onClick = onToggleTracking,
        modifier = Modifier
            .fillMaxWidth()
            .height(TailBaitDimensions.ButtonHeight),
        enabled = canEnableTracking || isTrackingEnabled,
        shape = TailBaitShapeTokens.ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isTrackingEnabled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    ) {
        Icon(
            imageVector = if (isTrackingEnabled) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
        )
        Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))
        Text(
            text = if (isTrackingEnabled) "Stop Tracking" else "Start Tracking",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Statistics card
 */
@Composable
private fun StatisticsCard(
    totalDevicesFound: Int,
    knownDevicesCount: Int,
    unknownDevicesCount: Int,
    lastScanTimestamp: Long?,
    onViewDevices: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding)
        ) {
            SectionTitle(
                title = "Statistics"
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

            KeyValueRow(
                label = "Total Devices Found",
                value = totalDevicesFound.toString(),
                valueColor = MaterialTheme.colorScheme.primary
            )

            if (totalDevicesFound > 0) {
                KeyValueRow(
                    label = "Known Devices",
                    value = knownDevicesCount.toString(),
                    valueColor = MaterialTheme.colorScheme.tertiary
                )

                KeyValueRow(
                    label = "Unknown Devices",
                    value = unknownDevicesCount.toString(),
                    valueColor = MaterialTheme.colorScheme.secondary
                )
            }

            KeyValueRow(
                label = "Last Scan",
                value = lastScanTimestamp?.let { formatTimestamp(it) } ?: "Never"
            )

            if (totalDevicesFound > 0) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))
                TextButton(
                    onClick = onViewDevices,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View All Devices")
                    Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingXS))
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(TailBaitDimensions.IconSizeSmall)
                    )
                }
            }
        }
    }
}

/**
 * Quick actions section
 */
@Composable
private fun QuickActionsSection(
    onNavigateToDeviceList: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToMap: () -> Unit,
    totalDevices: Int,
    knownDevices: Int = 0,
    unknownDevices: Int = 0
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle(title = "Quick Actions")

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingMD)
        ) {
            QuickActionCard(
                title = "Devices",
                subtitle = if (totalDevices > 0) {
                    "$totalDevices total • $knownDevices known • $unknownDevices unknown"
                } else {
                    "No devices found"
                },
                icon = Icons.Outlined.Devices,
                onClick = onNavigateToDeviceList,
                modifier = Modifier.weight(1f)
            )

            QuickActionCard(
                title = "Alerts",
                subtitle = "View alerts",
                icon = Icons.Outlined.NotificationsNone,
                onClick = onNavigateToAlerts,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingMD)
        ) {
            QuickActionCard(
                title = "Map",
                subtitle = "View locations",
                icon = Icons.Outlined.Map,
                onClick = onNavigateToMap,
                modifier = Modifier.weight(1f)
            )

            // Placeholder for future quick action
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Quick action card
 */
@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() },
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(TailBaitDimensions.IconSizeLarge),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Format timestamp to human-readable string
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000} minutes ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

// ==================== Previews ====================

@Preview(name = "Home Screen - Idle", showBackground = true)
@Composable
private fun HomeScreenIdlePreview() {
    TailBaitTheme {
        HomeContent(
            uiState = HomeViewModel.HomeUiState(
                isLoading = false,
                isTrackingEnabled = false,
                scanState = HomeViewModel.ScanStatus.Idle,
                totalDevicesFound = 0,
                permissionStatus = HomeViewModel.PermissionStatus(
                    bluetoothGranted = true,
                    locationGranted = true,
                    notificationGranted = true,
                    allEssentialGranted = true
                )
            ),
            onToggleTracking = {},
            onNavigateToDeviceList = {},
            onNavigateToPermissions = {},
            onNavigateToAlerts = {},
            onNavigateToMap = {},
            onClearError = {}
        )
    }
}

@Preview(name = "Home Screen - Scanning", showBackground = true)
@Composable
private fun HomeScreenScanningPreview() {
    TailBaitTheme {
        HomeContent(
            uiState = HomeViewModel.HomeUiState(
                isLoading = false,
                isTrackingEnabled = true,
                scanState = HomeViewModel.ScanStatus.Scanning,
                totalDevicesFound = 15,
                devicesFoundInCurrentScan = 5,
                lastScanTimestamp = System.currentTimeMillis() - 300_000,
                permissionStatus = HomeViewModel.PermissionStatus(
                    bluetoothGranted = true,
                    locationGranted = true,
                    notificationGranted = true,
                    backgroundLocationGranted = true,
                    allEssentialGranted = true
                )
            ),
            onToggleTracking = {},
            onNavigateToDeviceList = {},
            onNavigateToPermissions = {},
            onNavigateToAlerts = {},
            onNavigateToMap = {},
            onClearError = {}
        )
    }
}

@Preview(name = "Home Screen - Missing Permissions", showBackground = true)
@Composable
private fun HomeScreenMissingPermissionsPreview() {
    TailBaitTheme {
        HomeContent(
            uiState = HomeViewModel.HomeUiState(
                isLoading = false,
                isTrackingEnabled = false,
                scanState = HomeViewModel.ScanStatus.Idle,
                totalDevicesFound = 0,
                permissionStatus = HomeViewModel.PermissionStatus(
                    bluetoothGranted = true,
                    locationGranted = false,
                    notificationGranted = false,
                    allEssentialGranted = false
                )
            ),
            onToggleTracking = {},
            onNavigateToDeviceList = {},
            onNavigateToPermissions = {},
            onNavigateToAlerts = {},
            onNavigateToMap = {},
            onClearError = {}
        )
    }
}

@Preview(name = "Home Screen - Dark Mode", showBackground = true)
@Composable
private fun HomeScreenDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        HomeContent(
            uiState = HomeViewModel.HomeUiState(
                isLoading = false,
                isTrackingEnabled = true,
                scanState = HomeViewModel.ScanStatus.Idle,
                totalDevicesFound = 23,
                lastScanTimestamp = System.currentTimeMillis() - 120_000,
                permissionStatus = HomeViewModel.PermissionStatus(
                    bluetoothGranted = true,
                    locationGranted = true,
                    notificationGranted = true,
                    backgroundLocationGranted = true,
                    allEssentialGranted = true
                )
            ),
            onToggleTracking = {},
            onNavigateToDeviceList = {},
            onNavigateToPermissions = {},
            onNavigateToAlerts = {},
            onNavigateToMap = {},
            onClearError = {}
        )
    }
}
