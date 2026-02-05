package com.tailbait.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.tailbait.data.database.entities.AppSettings
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.theme.TailBaitTheme
import java.io.File

/**
 * Settings Screen.
 *
 * Comprehensive settings screen with sections for:
 * - Scanning configuration (mode, interval, duration)
 * - Alert preferences (thresholds, notifications)
 * - Location settings (accuracy, change threshold)
 * - Data retention policies
 * - Battery optimization
 * - App information (version, build)
 *
 * Uses Material Design 3 with full dark mode support.
 *
 * @param onNavigateBack Callback when back button is clicked
 * @param onNavigateToHelp Callback to navigate to help screen
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHelp: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHelp) {
                        Icon(
                            imageVector = Icons.Filled.Help,
                            contentDescription = "Help"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SettingsContent(
                uiState = uiState,
                viewModel = viewModel
            )

            // Success message when data is cleared
            AnimatedVisibility(
                visible = uiState.dataCleared,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "All data cleared successfully",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }

    // Clear data confirmation dialog
    if (uiState.showClearDataDialog) {
        ConfirmationDialog(
            title = "Clear All Data?",
            message = "This will permanently delete all devices, locations, and alerts. This action cannot be undone.",
            confirmText = "Clear Data",
            dismissText = "Cancel",
            onConfirm = { viewModel.clearAllData() },
            onDismiss = { viewModel.hideClearDataDialog() }
        )
    }

    // Export success snackbar
    if (uiState.exportResult != null && uiState.exportResult?.success == true) {
        LaunchedEffect(uiState.exportResult) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearExportResult()
        }
    }

    // Error snackbar
    if (uiState.errorMessage != null) {
        LaunchedEffect(uiState.errorMessage) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }
}

/**
 * Main settings content with all sections.
 */
@Composable
private fun SettingsContent(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Scanning Settings Section
        ScanningSettingsSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // Alert Settings Section
        AlertSettingsSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // Location Settings Section
        LocationSettingsSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // Data Retention Section
        DataRetentionSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // Battery Optimization Section
        BatteryOptimizationSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // Appearance Section
        AppearanceSection(
            uiState = uiState,
            viewModel = viewModel
        )

        SettingsDivider()

        // App Information Section
        AppInformationSection(
            uiState = uiState
        )

        // Bottom spacing
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Scanning settings section.
 */
@Composable
private fun ScanningSettingsSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Scanning",
        icon = Icons.Outlined.BluetoothSearching
    )

    SettingsToggleItem(
        title = "Enable Tracking",
        description = "Master switch to enable/disable BLE device tracking",
        checked = uiState.settings.isTrackingEnabled,
        onCheckedChange = { viewModel.updateTrackingEnabled(it) },
        icon = Icons.Outlined.PlayCircle
    )

    SettingsSliderItem(
        title = "Scan Interval",
        description = "Time between scans",
        value = (uiState.settings.scanIntervalSeconds / 60).toFloat(),
        onValueChange = { viewModel.updateScanInterval(it.toInt()) },
        valueRange = 1f..30f,
        steps = 28,
        valueLabel = { "${it.toInt()} min" },
        icon = Icons.Outlined.Timer,
        enabled = uiState.settings.isTrackingEnabled
    )

    SettingsSliderItem(
        title = "Scan Duration",
        description = "How long each scan session lasts",
        value = uiState.settings.scanDurationSeconds.toFloat(),
        onValueChange = { viewModel.updateScanDuration(it.toInt()) },
        valueRange = 10f..60f,
        steps = 9,
        valueLabel = { "${it.toInt()} sec" },
        icon = Icons.Outlined.Timelapse
    )
}

/**
 * Alert settings section.
 */
@Composable
private fun AlertSettingsSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Alerts",
        icon = Icons.Outlined.Notifications
    )

    SettingsSliderItem(
        title = "Minimum Locations",
        description = "Minimum distinct locations to trigger alert",
        value = uiState.settings.alertThresholdCount.toFloat(),
        onValueChange = { viewModel.updateAlertThresholdCount(it.toInt()) },
        valueRange = 2f..10f,
        steps = 7,
        valueLabel = { "${it.toInt()} locations" },
        icon = Icons.Outlined.LocationOn
    )

    SettingsSliderItem(
        title = "Minimum Distance",
        description = "Minimum distance between detections",
        value = uiState.settings.minDetectionDistanceMeters.toFloat(),
        onValueChange = { viewModel.updateMinDetectionDistance(it.toDouble()) },
        valueRange = 50f..500f,
        steps = 8,
        valueLabel = { "${it.toInt()} m" },
        icon = Icons.Outlined.SocialDistance
    )

    SettingsSwitchItem(
        title = "Notifications",
        description = "Show push notifications for alerts",
        checked = uiState.settings.alertNotificationEnabled,
        onCheckedChange = { viewModel.updateAlertNotificationEnabled(it) },
        icon = Icons.Outlined.NotificationsActive
    )

    SettingsSwitchItem(
        title = "Sound",
        description = "Play sound for alert notifications",
        checked = uiState.settings.alertSoundEnabled,
        onCheckedChange = { viewModel.updateAlertSoundEnabled(it) },
        icon = Icons.Outlined.VolumeUp,
        enabled = uiState.settings.alertNotificationEnabled
    )

    SettingsSwitchItem(
        title = "Vibration",
        description = "Vibrate for alert notifications",
        checked = uiState.settings.alertVibrationEnabled,
        onCheckedChange = { viewModel.updateAlertVibrationEnabled(it) },
        icon = Icons.Outlined.Vibration,
        enabled = uiState.settings.alertNotificationEnabled
    )
}

/**
 * Location settings section.
 */
@Composable
private fun LocationSettingsSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Location",
        icon = Icons.Outlined.MyLocation
    )



    SettingsActionItem(
        title = "Background Location",
        description = "Allow location tracking in background (managed by system)",
        onClick = { /* System setting - opens Android settings */ },
        icon = Icons.Outlined.LocationSearching,
        trailingIcon = Icons.Default.OpenInNew
    )
}

/**
 * Data retention section.
 */
@Composable
private fun DataRetentionSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Data & Storage",
        icon = Icons.Outlined.Storage
    )

    SettingsDropdownItem(
        title = "Auto-Delete Old Data",
        description = "Automatically delete data older than selected period",
        selectedOption = when (uiState.settings.dataRetentionDays) {
            7 -> "7 days"
            14 -> "14 days"
            30 -> "30 days"
            60 -> "60 days"
            90 -> "90 days"
            -1 -> "Never"
            else -> "30 days"
        },
        options = listOf("7 days", "14 days", "30 days", "60 days", "90 days", "Never"),
        onOptionSelected = { option ->
            val days = when (option) {
                "7 days" -> 7
                "14 days" -> 14
                "30 days" -> 30
                "60 days" -> 60
                "90 days" -> 90
                "Never" -> -1
                else -> 30
            }
            viewModel.updateDataRetentionDays(days)
        },
        icon = Icons.Outlined.AutoDelete
    )

    // Data statistics
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Current Data Usage",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DataStatItem(
                    label = "Devices",
                    value = uiState.totalDevicesCount.toString()
                )
                DataStatItem(
                    label = "Locations",
                    value = uiState.totalLocationsCount.toString()
                )
                DataStatItem(
                    label = "Alerts",
                    value = uiState.totalAlertsCount.toString()
                )
            }
        }
    }

    // CSV Export section
        SettingsActionItem(
            title = "Export Data to CSV",
            description = "Export all devices, locations, and alerts for analysis",
            onClick = { viewModel.exportDataToCsv() },
            icon = Icons.Default.CloudDownload,
            trailingIcon = null,
            enabled = !uiState.isExporting && !uiState.isDebugExporting
        )

        SettingsActionItem(
            title = "Export Debug Data (JSON/ZIP)",
            description = "Export full database state for debugging",
            onClick = { viewModel.exportDebugData() },
            icon = Icons.Default.BugReport,
            trailingIcon = null,
            enabled = !uiState.isExporting && !uiState.isDebugExporting
        )

        // Show export progress (Shared for CSV and Debug)
        if (uiState.isExporting || uiState.isDebugExporting) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (uiState.isDebugExporting) "Exporting Debug Data..." else "Exporting CSV Data...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (uiState.isDebugExporting) "Creating ZIP with full database state" else "Creating CSV files with all device and location data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Show CSV export success result
        uiState.exportResult?.let { result ->
            if (result.success) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Export Successful!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Exported ${result.devicesExported} devices, ${result.locationsExported} locations, ${result.deviceLocationRecordsExported} records, and ${result.alertsExported} alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Files saved to: ${File(result.exportDirectory ?: "").name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Share button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            val scope = rememberCoroutineScope()
                            val context = LocalContext.current

                            TextButton(
                                onClick = {
                                    // Create and launch share intent
                                    scope.launch {
                                        val shareIntent = viewModel.createShareIntent()
                                        shareIntent?.let { intent ->
                                            context.startActivity(
                                                Intent.createChooser(intent, "Share BLE Tracker Data")
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Share Files")
                            }
                        }
                    }
                }
            }
        }

        // Show Debug export success result
        uiState.debugExportFile?.let { file ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Debug Export Successful!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Size: ${file.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Share button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val scope = rememberCoroutineScope()
                        val context = LocalContext.current

                        TextButton(
                            onClick = {
                                scope.launch {
                                    val shareIntent = viewModel.createDebugShareIntent()
                                    shareIntent?.let { intent ->
                                        context.startActivity(
                                            Intent.createChooser(intent, "Share Debug Data")
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share ZIP")
                        }
                    }
                }
            }
        }

    SettingsActionItem(
        title = "Clear All Data",
        description = "Permanently delete all devices, locations, and alerts",
        onClick = { viewModel.showClearDataDialog() },
        icon = Icons.Outlined.DeleteForever,
        trailingIcon = null
    )
}

/**
 * Data statistics item.
 */
@Composable
private fun DataStatItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Battery optimization section.
 */
@Composable
private fun BatteryOptimizationSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Battery",
        icon = Icons.Outlined.BatteryChargingFull
    )

    SettingsSwitchItem(
        title = "Battery Optimization",
        description = "Reduce scan frequency and duration to save battery",
        checked = uiState.settings.batteryOptimizationEnabled,
        onCheckedChange = { viewModel.updateBatteryOptimizationEnabled(it) },
        icon = Icons.Outlined.BatterySaver
    )
}

/**
 * Appearance section for theme settings.
 */
@Composable
private fun AppearanceSection(
    uiState: SettingsViewModel.SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsSection(
        title = "Appearance",
        icon = Icons.Outlined.Palette
    )

    SettingsDropdownItem(
        title = "Theme",
        description = "Choose app appearance",
        selectedOption = when (uiState.settings.themeMode) {
            "LIGHT" -> "Light"
            "DARK" -> "Dark"
            else -> "System Default"
        },
        options = listOf("System Default", "Light", "Dark"),
        onOptionSelected = { option ->
            val mode = when (option) {
                "Light" -> "LIGHT"
                "Dark" -> "DARK"
                else -> "SYSTEM"
            }
            viewModel.updateThemeMode(mode)
        },
        icon = Icons.Outlined.DarkMode
    )
}

/**
 * Settings toggle item for boolean settings.
 */
@Composable
private fun SettingsToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * App information section.
 */
@Composable
private fun AppInformationSection(
    uiState: SettingsViewModel.SettingsUiState
) {
    SettingsSection(
        title = "About",
        icon = Icons.Outlined.Info
    )

    SettingsActionItem(
        title = "Version",
        description = uiState.appVersion,
        onClick = { /* No action */ },
        icon = Icons.Outlined.AppSettingsAlt,
        trailingIcon = null,
        enabled = false
    )

    SettingsActionItem(
        title = "Build Number",
        description = uiState.buildNumber,
        onClick = { /* No action */ },
        icon = Icons.Outlined.Tag,
        trailingIcon = null,
        enabled = false
    )

    SettingsActionItem(
        title = "Open Source Licenses",
        description = "View licenses for open source software",
        onClick = { },
        icon = Icons.Outlined.Description,
        enabled = false
    )
}
