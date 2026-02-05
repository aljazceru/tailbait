package com.tailbait.ui.screens.devicedetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.components.*
import com.tailbait.ui.components.createGeoPoint
import com.tailbait.ui.components.createDeviceMarker
import com.tailbait.ui.components.createLocationPath
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device Detail Screen with Location History
 *
 * This screen shows comprehensive information about a specific device including:
 * - Device details (name, address, type, manufacturer)
 * - Complete location history on OpenStreetMap
 * - Timeline of all detections with timestamps
 * - Detection statistics and patterns
 * - Threat analysis and risk assessment
 * - Options to whitelist or manage the device
 *
 * @param onNavigateBack Callback when user wants to go back
 * @param deviceId The ID of the device to display
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    onNavigateBack: () -> Unit,
    deviceId: Long,
    viewModel: DeviceDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.loadDeviceDetail(deviceId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    // View on main map button
                    IconButton(onClick = { viewModel.viewOnMainMap() }) {
                        Icon(
                            imageVector = Icons.Outlined.Map,
                            contentDescription = "View on main map"
                        )
                    }
                    // More options menu
                    Box {
                        IconButton(onClick = { viewModel.showMenu() }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading device details...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            uiState.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            val errorMessage = uiState.errorMessage
                            Text(
                                text = errorMessage ?: "Unknown error",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = { viewModel.retryLoad() }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            uiState.device != null -> {
                DeviceDetailContent(
                    uiState = uiState,
                    modifier = Modifier.padding(paddingValues),
                    onToggleMap = { viewModel.toggleMapVisibility() },
                    onAddToWhitelist = { label, category ->
                        viewModel.addToWhitelist(label, category)
                    },
                    onRemoveFromWhitelist = { viewModel.removeFromWhitelist() },
                    onExportData = { viewModel.exportDeviceData() },
                    onTriggerSound = { viewModel.triggerSound() },
                    onStopSound = { viewModel.stopSound() },
                    onDismissSoundResult = { viewModel.resetSoundTriggerState() }
                )
            }
        }
    }
}

/**
 * Main content for device detail screen
 */
@Composable
private fun DeviceDetailContent(
    uiState: DeviceDetailViewModel.DeviceDetailUiState,
    modifier: Modifier = Modifier,
    onToggleMap: () -> Unit = {},
    onAddToWhitelist: (String, String) -> Unit = { _, _ -> },
    onRemoveFromWhitelist: () -> Unit = {},
    onExportData: () -> Unit = {},
    onTriggerSound: () -> Unit = {},
    onStopSound: () -> Unit = {},
    onDismissSoundResult: () -> Unit = {}
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Device header card
        item {
            DeviceHeaderCard(
                device = uiState.device!!,
                isWhitelisted = uiState.isWhitelisted,
                whitelistCategory = uiState.whitelistCategory,
                onAddToWhitelist = onAddToWhitelist,
                onRemoveFromWhitelist = onRemoveFromWhitelist
            )
        }

        // Sound trigger card (for tracker devices)
        if (uiState.canPlaySound) {
            item {
                SoundTriggerCard(
                    soundTriggerState = uiState.soundTriggerState,
                    onTriggerSound = onTriggerSound,
                    onStopSound = onStopSound,
                    onDismissResult = onDismissSoundResult
                )
            }
        }

        // Map section
        if (uiState.locationHistory.isNotEmpty()) {
            item {
                MapSectionCard(
                    device = uiState.device!!,
                    locationHistory = uiState.locationHistory,
                    showMap = uiState.showMap,
                    onToggleMap = onToggleMap
                )
            }
        }

        // Statistics card
        item {
            DeviceStatsCard(
                device = uiState.device!!,
                locationCount = uiState.locationHistory.size,
                detectionStats = uiState.detectionStats
            )
        }

        // Location history timeline
        if (uiState.locationHistory.isNotEmpty()) {
            item {
                Text(
                    text = "Location History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(uiState.locationHistory) { location ->
                LocationHistoryItem(
                    location = location,
                    device = uiState.device!!,
                    isLast = location == uiState.locationHistory.last()
                )
            }
        }
    }
}

/**
 * Device header information card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceHeaderCard(
    device: DeviceDetailViewModel.DeviceDetail,
    isWhitelisted: Boolean,
    whitelistCategory: String?,
    onAddToWhitelist: (String, String) -> Unit,
    onRemoveFromWhitelist: () -> Unit
) {
    var showWhitelistDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Device name and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isWhitelisted) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "✓ Known",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (device.deviceType != null) {
                        Text(
                            text = device.deviceType,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Show advertised name if it differs from the main name
                    if (device.advertisedName != null && device.advertisedName != device.name) {
                        Text(
                            text = "Advertised as: ${device.advertisedName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (whitelistCategory != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = whitelistCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Whitelist action button
                OutlinedButton(
                    onClick = {
                        if (isWhitelisted) {
                            onRemoveFromWhitelist()
                        } else {
                            showWhitelistDialog = true
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isWhitelisted) {
                            Icons.Filled.Star
                        } else {
                            Icons.Outlined.StarBorder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isWhitelisted) "Remove" else "Add to Known"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Device details grid
            DeviceDetailGrid(device = device)

            Spacer(modifier = Modifier.height(8.dp))

            // Manufacturer data
            device.manufacturerData?.let { manufData ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Business,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Manufacturer: $manufData",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // Whitelist dialog
    if (showWhitelistDialog) {
        WhitelistDialog(
            deviceName = device.name,
            onDismiss = { showWhitelistDialog = false },
            onAddToWhitelist = { label, category ->
                onAddToWhitelist(label, category)
                showWhitelistDialog = false
            }
        )
    }
}

/**
 * Device details grid layout
 */
@Composable
private fun DeviceDetailGrid(device: DeviceDetailViewModel.DeviceDetail) {
    Column {
        // MAC Address
        DeviceDetailRow(
            icon = Icons.Outlined.Wifi,
            label = "MAC Address",
            value = device.address,
            isMonospace = true
        )

        // Advertised Name (if available and different from main name)
        if (device.advertisedName != null) {
            DeviceDetailRow(
                icon = Icons.Outlined.Label,
                label = "Advertised Name",
                value = device.advertisedName
            )
        }

        // Database ID
        DeviceDetailRow(
            icon = Icons.Outlined.Badge,
            label = "Database ID",
            value = device.id.toString(),
            isMonospace = true
        )

        // First Seen
        DeviceDetailRow(
            icon = Icons.Outlined.Schedule,
            label = "First Seen",
            value = formatTimestamp(device.firstSeen)
        )

        // Last Seen
        DeviceDetailRow(
            icon = Icons.Outlined.Update,
            label = "Last Seen",
            value = formatTimestamp(device.lastSeen)
        )

        // Detection Count
        DeviceDetailRow(
            icon = Icons.Outlined.Visibility,
            label = "Detection Count",
            value = "${device.detectionCount} times"
        )
    }
}

/**
 * Individual device detail row
 */
@Composable
private fun DeviceDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Map section with device locations
 */
@Composable
private fun MapSectionCard(
    device: DeviceDetailViewModel.DeviceDetail,
    locationHistory: List<DeviceDetailViewModel.LocationWithDetection>,
    showMap: Boolean,
    onToggleMap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Map header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Locations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onToggleMap
                ) {
                    Icon(
                        imageVector = if (showMap) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showMap) "Hide Map" else "Show Map")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Seen at ${locationHistory.size} location${if (locationHistory.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Map view
            AnimatedVisibility(
                visible = showMap,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                DeviceLocationMap(
                    device = device,
                    locations = locationHistory
                )
            }
        }
    }
}

/**
 * Map showing device location history
 */
@Composable
private fun DeviceLocationMap(
    device: DeviceDetailViewModel.DeviceDetail,
    locations: List<DeviceDetailViewModel.LocationWithDetection>
) {
    if (locations.isEmpty()) return

    // Create markers for each location
    val markers = locations.mapIndexed { index, location ->
        MapMarker(
            position = createGeoPoint(location.latitude, location.longitude),
            title = location.name ?: "Location ${index + 1}",
            description = "Detections: ${location.detectionCount}\nLast: ${formatTimestamp(location.lastSeen)}",
            id = "location_${location.locationId}"
        )
    }

    // Create path showing movement
    val pathPoints = locations.map { location ->
        createGeoPoint(location.latitude, location.longitude)
    }

    val paths = if (pathPoints.size >= 2) {
        listOf(
            createLocationPath(
                points = pathPoints,
                title = "${device.name} Movement Path"
            )
        )
    } else {
        emptyList()
    }

    // Calculate center position
    val centerPosition = if (locations.isNotEmpty()) {
        createGeoPoint(
            locations.map { it.latitude }.average().toDouble(),
            locations.map { it.longitude }.average().toDouble()
        )
    } else {
        createGeoPoint(40.7128, -74.0060)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        OpenStreetMap(
            modifier = Modifier.fillMaxSize(),
            initialPosition = centerPosition,
            initialZoom = 14.0,
            markers = markers,
            paths = paths
        )
    }
}

/**
 * Device statistics card
 */
@Composable
private fun DeviceStatsCard(
    device: DeviceDetailViewModel.DeviceDetail,
    locationCount: Int,
    detectionStats: DeviceDetailViewModel.DetectionStats
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Detection Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    value = device.detectionCount.toString(),
                    label = "Total Detections"
                )
                StatItem(
                    value = locationCount.toString(),
                    label = "Locations"
                )
                StatItem(
                    value = formatDuration(device.lastSeen - device.firstSeen),
                    label = "Tracking Period"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Average detections per location
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Analytics,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Average detections per location: ${if (locationCount > 0) String.format("%.1f", device.detectionCount.toFloat() / locationCount) else "0"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Individual stat item
 */
@Composable
private fun StatItem(
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Location history timeline item
 */
@Composable
private fun LocationHistoryItem(
    location: DeviceDetailViewModel.LocationWithDetection,
    device: DeviceDetailViewModel.DeviceDetail,
    isLast: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Location name and detections
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = location.name ?: "Unknown Location",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Detections: ${location.detectionCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Location coordinates
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${String.format("%.6f", location.latitude)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format("%.6f", location.longitude)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Detection timeline
            Text(
                text = "Last seen: ${formatTimestamp(location.lastSeen)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (location.averageRssi != null) {
                Text(
                    text = "Average signal: ${location.averageRssi.toInt()} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (!isLast) {
        Divider(
            modifier = Modifier.padding(horizontal = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Whitelist dialog for adding device to known list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhitelistDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onAddToWhitelist: (String, String) -> Unit
) {
    var label by remember { mutableStateOf(deviceName) }
    var category by remember { mutableStateOf("OWN") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Known Devices") },
        text = {
            Column {
                Text("Add '$deviceName' to your known devices list to prevent alerts:")

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Device Label") },
                    placeholder = { Text("e.g., \"My Phone\", \"Partner's Watch\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Category:",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = category == "OWN",
                        onClick = { category = "OWN" },
                        label = { Text("Own") }
                    )
                    FilterChip(
                        selected = category == "PARTNER",
                        onClick = { category = "PARTNER" },
                        label = { Text("Partner") }
                    )
                    FilterChip(
                        selected = category == "TRUSTED",
                        onClick = { category = "TRUSTED" },
                        label = { Text("Trusted") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAddToWhitelist(label, category) },
                enabled = label.isNotBlank()
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
 * Sound trigger card for tracker devices
 */
@Composable
private fun SoundTriggerCard(
    soundTriggerState: DeviceDetailViewModel.SoundTriggerUiState,
    onTriggerSound: () -> Unit,
    onStopSound: () -> Unit,
    onDismissResult: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Play Sound",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tracker badge
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "Tracker",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Make this device play a sound to help locate it. This works with AirTags and compatible trackers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // State-dependent content
            when (soundTriggerState) {
                is DeviceDetailViewModel.SoundTriggerUiState.Idle -> {
                    Button(
                        onClick = onTriggerSound,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play Sound on Device")
                    }
                }

                is DeviceDetailViewModel.SoundTriggerUiState.Connecting -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Connecting to device...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is DeviceDetailViewModel.SoundTriggerUiState.Playing -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Triggering sound...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onStopSound
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                is DeviceDetailViewModel.SoundTriggerUiState.Success -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = soundTriggerState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = onDismissResult) {
                                Text("Done")
                            }
                        }
                    }
                }

                is DeviceDetailViewModel.SoundTriggerUiState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = soundTriggerState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = onDismissResult) {
                                    Text("Dismiss")
                                }
                                Button(onClick = onTriggerSound) {
                                    Text("Try Again")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info text
            Text(
                text = "Note: Sound may not play if the device is not nearby, has low battery, or is connected to its owner's phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Utility functions
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
