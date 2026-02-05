package com.tailbait.ui.screens.map

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.ui.components.*
import com.tailbait.ui.components.createGeoPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Map Screen - Device Location Visualization
 *
 * This screen displays a Google Maps view showing:
 * - All detection locations as markers
 * - Device movement paths (polylines connecting locations)
 * - Marker clustering for performance (10+ markers)
 * - Filter by device and date range
 * - Click marker to see device details
 *
 * The screen is designed with Material Design 3 and supports both light and dark themes.
 * It uses Google Maps SDK and maps-compose library for Jetpack Compose integration.
 *
 * @param onNavigateBack Callback when back button is clicked
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onNavigateBack: () -> Unit,
    initialDeviceId: Long? = null,
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val availableDevices by viewModel.getAvailableDevices().collectAsState(initial = emptyList())

    // Apply initial device filter if provided
    LaunchedEffect(initialDeviceId) {
        if (initialDeviceId != null) {
            viewModel.filterByDevice(initialDeviceId)
        }
    }

    var showFilterSheet by remember { mutableStateOf(false) }
    var selectedMarker by remember { mutableStateOf<MapViewModel.MapMarker?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Device Locations")
                        if (uiState.totalDevices > 0) {
                            Text(
                                text = "${uiState.totalDevices} devices, ${uiState.totalLocations} locations",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    // Filter button
                    IconButton(onClick = { showFilterSheet = true }) {
                        Badge(
                            containerColor = if (uiState.selectedDeviceId != null ||
                                uiState.startTimestamp != null ||
                                uiState.endTimestamp != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FilterList,
                                contentDescription = "Filter"
                            )
                        }
                    }

                    // Clear filters button (visible when filters are active)
                    if (uiState.selectedDeviceId != null ||
                        uiState.startTimestamp != null ||
                        uiState.endTimestamp != null) {
                        IconButton(onClick = { viewModel.clearFilters() }) {
                            Icon(
                                imageVector = Icons.Filled.FilterListOff,
                                contentDescription = "Clear filters"
                            )
                        }
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
            when {
                uiState.isLoading -> {
                    LoadingView(message = "Loading map data...")
                }
                uiState.markers.isEmpty() -> {
                    EmptyView(
                        icon = Icons.Outlined.LocationOff,
                        title = "No Locations Found",
                        message = if (uiState.selectedDeviceId != null ||
                            uiState.startTimestamp != null ||
                            uiState.endTimestamp != null) {
                            "No locations found with the current filters.\nTry adjusting your filter criteria."
                        } else {
                            "No device locations have been recorded yet.\nStart tracking to see locations on the map."
                        }
                    )
                }
                else -> {
                    MapContent(
                        uiState = uiState,
                        selectedMarker = selectedMarker,
                        onMarkerClick = { marker ->
                            selectedMarker = marker
                        },
                        onMapClick = {
                            selectedMarker = null
                        }
                    )
                }
            }

            // Error message (if any)
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Selected marker info card
            AnimatedVisibility(
                visible = selectedMarker != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                selectedMarker?.let { marker ->
                    MarkerInfoCard(
                        marker = marker,
                        onDismiss = { selectedMarker = null }
                    )
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            currentDeviceId = uiState.selectedDeviceId,
            currentStartTimestamp = uiState.startTimestamp,
            currentEndTimestamp = uiState.endTimestamp,
            availableDevices = availableDevices,
            onDismiss = { showFilterSheet = false },
            onApplyFilters = { deviceId, startTimestamp, endTimestamp ->
                viewModel.filterByDevice(deviceId)
                viewModel.filterByDateRange(startTimestamp, endTimestamp)
                showFilterSheet = false
            }
        )
    }
}

/**
 * Main map content with markers and polylines
 */
@Composable
private fun MapContent(
    uiState: MapViewModel.MapUiState,
    selectedMarker: MapViewModel.MapMarker?,
    onMarkerClick: (MapViewModel.MapMarker) -> Unit,
    onMapClick: () -> Unit
) {

    // Convert UI state markers to OpenStreetMap markers
    val osmMarkers = uiState.markers.map { marker ->
        MapMarker(
            position = marker.position, // Already GeoPoint
            title = marker.deviceName, // Short name like "Tracker•A3F9"
            description = "Device: ${marker.deviceName}\nAddress: ${marker.deviceAddress}\nRSSI: ${marker.rssi} dBm\nTimestamp: ${formatTimestamp(marker.timestamp)}",
            id = marker.deviceAddress
        )
    }

    // Convert UI state paths to OpenStreetMap paths
    val osmPaths = uiState.devicePaths.map { path ->
        if (path.points.isNotEmpty()) {
            MapPath(
                points = path.points, // Already List<GeoPoint>
                title = "Device Path",
                color = path.color,
                width = 5f
            )
        } else {
            null
        }
    }.filterNotNull()

    // Set initial camera position
    val initialPosition = uiState.cameraPosition ?: createGeoPoint(40.7128, -74.0060) // Default to New York

    OpenStreetMap(
        modifier = Modifier.fillMaxSize(),
        initialPosition = initialPosition,
        initialZoom = 18.0,
        markers = osmMarkers,
        paths = osmPaths,
        onMarkerClick = { clickedMarker ->
            // Find the corresponding UI marker and trigger click
            val uiMarker = uiState.markers.find {
                it.deviceAddress == clickedMarker.id
            }
            uiMarker?.let { onMarkerClick(it) }
        },
        onMapClick = { onMapClick() }
    )
}

/**
 * Marker info card shown at the bottom when a marker is selected
 */
@Composable
private fun MarkerInfoCard(
    marker: MapViewModel.MapMarker,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = marker.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = marker.deviceAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Location details
            InfoRow(
                label = "Location",
                value = String.format(
                    "%.6f, %.6f",
                    marker.position.latitude,
                    marker.position.longitude
                )
            )
            InfoRow(
                label = "Signal Strength",
                value = "${marker.rssi} dBm"
            )
            InfoRow(
                label = "Accuracy",
                value = "±${marker.accuracy.toInt()}m"
            )
            InfoRow(
                label = "Detected",
                value = formatTimestamp(marker.timestamp)
            )
        }
    }
}

/**
 * Info row for displaying key-value pairs
 */
@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Filter bottom sheet for selecting device and date range
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    currentDeviceId: Long?,
    currentStartTimestamp: Long?,
    currentEndTimestamp: Long?,
    availableDevices: List<ScannedDevice>,
    onDismiss: () -> Unit,
    onApplyFilters: (deviceId: Long?, startTimestamp: Long?, endTimestamp: Long?) -> Unit
) {
    var selectedDeviceId by remember { mutableStateOf(currentDeviceId) }
    var startTimestamp by remember { mutableStateOf(currentStartTimestamp) }
    var endTimestamp by remember { mutableStateOf(currentEndTimestamp) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Filter Map Data",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device filter section
            Text(
                text = "Filter by Device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // All devices option
            FilterChip(
                selected = selectedDeviceId == null,
                onClick = { selectedDeviceId = null },
                label = { Text("All Devices") },
                leadingIcon = {
                    Icon(
                        imageVector = if (selectedDeviceId == null) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Device list
            if (availableDevices.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(availableDevices) { device ->
                        FilterChip(
                            selected = selectedDeviceId == device.id,
                            onClick = { selectedDeviceId = device.id },
                            label = {
                                Column {
                                    Text(
                                        text = device.name ?: "Unknown Device",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (selectedDeviceId == device.id) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Outlined.Circle
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "No devices available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Date range filter section
            Text(
                text = "Filter by Date Range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Quick date range options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = startTimestamp == null && endTimestamp == null,
                    onClick = {
                        startTimestamp = null
                        endTimestamp = null
                    },
                    label = { Text("All Time") },
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = false,
                    onClick = {
                        val now = System.currentTimeMillis()
                        // Last 24 hours
                        startTimestamp = now - (24 * 60 * 60 * 1000)
                        endTimestamp = now
                    },
                    label = { Text("Last 24h") },
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = false,
                    onClick = {
                        val now = System.currentTimeMillis()
                        // Last 7 days
                        startTimestamp = now - (7 * 24 * 60 * 60 * 1000)
                        endTimestamp = now
                    },
                    label = { Text("Last 7d") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        onApplyFilters(selectedDeviceId, startTimestamp, endTimestamp)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply Filters")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Format timestamp to human-readable string
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Check if color scheme is light
 */
private val ColorScheme.isLight: Boolean
    get() = this.background.luminance() > 0.5f

/**
 * Calculate luminance of a color
 */
private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}
