package com.tailbait.ui.screens.devicelist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.components.EmptyView
import com.tailbait.ui.components.LoadingView
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import com.tailbait.ui.theme.TailBaitTheme
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device List Screen
 *
 * This screen displays a list of all discovered BLE devices with:
 * - Device details (name, address, detection count, timestamps)
 * - Search functionality
 * - Sorting options (by name, time, detection count)
 * - Pull-to-refresh
 * - Navigation to device details
 *
 * The screen uses LazyColumn for efficient rendering of large device lists
 * and provides a rich filtering/sorting experience.
 *
 * @param onNavigateBack Callback when user wants to go back
 * @param onDeviceClick Callback when user clicks on a device
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onNavigateBack: () -> Unit,
    onDeviceClick: (Long) -> Unit,
    onNavigateToMap: () -> Unit = {},
    onNavigateToMapForDevice: (Long) -> Unit = {},
    viewModel: DeviceListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (searchActive) {
                SearchTopBar(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                    onCloseSearch = {
                        searchActive = false
                        viewModel.clearSearch()
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Devices") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Navigate back"
                            )
                        }
                    },
                    actions = {
                        // Map action
                        IconButton(onClick = onNavigateToMap) {
                            Icon(
                                imageVector = Icons.Outlined.Map,
                                contentDescription = "View on map"
                            )
                        }

                        // Search action
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search devices"
                            )
                        }

                        // Sort action
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Sort,
                                    contentDescription = "Sort devices"
                                )
                            }

                            SortMenuDropdown(
                                expanded = showSortMenu,
                                currentSortOption = uiState.sortOption,
                                onDismiss = { showSortMenu = false },
                                onSortOptionSelected = { option ->
                                    viewModel.updateSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    LoadingView(message = "Loading devices...")
                }
                else -> {
                    DeviceListContent(
                        uiState = uiState,
                        onDeviceClick = onDeviceClick,
                        onRefresh = { viewModel.refreshDevices() },
                        onTagAsKnown = { deviceId, label -> viewModel.tagDeviceAsKnown(deviceId, label) },
                        onTagAsUnknown = { deviceId -> viewModel.tagDeviceAsUnknown(deviceId) },
                        onViewOnMap = onNavigateToMapForDevice
                    )
                }
            }
        }
    }
}

/**
 * Search top bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search devices...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                    unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                    cursorColor = MaterialTheme.colorScheme.onPrimary,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    focusedIndicatorColor = MaterialTheme.colorScheme.onPrimary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        navigationIcon = {
            IconButton(onClick = onCloseSearch) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Close search",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        actions = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    )
}

/**
 * Sort menu dropdown
 */
@Composable
private fun SortMenuDropdown(
    expanded: Boolean,
    currentSortOption: DeviceListViewModel.SortOption,
    onDismiss: () -> Unit,
    onSortOptionSelected: (DeviceListViewModel.SortOption) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DeviceListViewModel.SortOption.values().forEach { option ->
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (option == currentSortOption) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Spacer(modifier = Modifier.width(28.dp))
                        }
                        Text(getSortOptionDisplayName(option))
                    }
                },
                onClick = { onSortOptionSelected(option) }
            )
        }
    }
}

/**
 * Main content with pull-to-refresh
 */
@Composable
private fun DeviceListContent(
    uiState: DeviceListViewModel.DeviceListUiState,
    onDeviceClick: (Long) -> Unit,
    onRefresh: () -> Unit,
    onTagAsKnown: (deviceId: Long, label: String) -> Unit,
    onTagAsUnknown: (deviceId: Long) -> Unit,
    onViewOnMap: (deviceId: Long) -> Unit
) {
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = uiState.isRefreshing)

    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            when {
                uiState.filteredDevices.isEmpty() && uiState.searchQuery.isNotEmpty() -> {
                    item {
                        EmptyView(
                            icon = Icons.Outlined.Search,
                            title = "No Results",
                            message = "No devices match your search query.\nTry different keywords."
                        )
                    }
                }
                uiState.devices.isEmpty() -> {
                    item {
                        EmptyView(
                            icon = Icons.Outlined.DevicesOther,
                            title = "No Devices Found",
                            message = "Start tracking to discover nearby Bluetooth devices.\n\n" +
                                "Devices will appear here as they are detected."
                        )
                    }
                }
                else -> {
                    // Summary header
                    item {
                        SummaryHeader(
                            totalDevices = uiState.devices.size,
                            filteredDevices = uiState.filteredDevices.size,
                            unknownCount = uiState.unknownDevices.size,
                            knownCount = uiState.knownDevices.size,
                            searchActive = uiState.searchQuery.isNotEmpty()
                        )
                    }

                    // Unknown Devices Section
                    if (uiState.unknownDevices.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Unknown Devices",
                                count = uiState.unknownDevices.size,
                                icon = Icons.Outlined.DevicesOther
                            )
                        }

                        items(
                            items = uiState.unknownDevices,
                            key = { "unknown_${it.id}" }
                        ) { device ->
                            DeviceListItem(
                                device = device,
                                onClick = { onDeviceClick(device.id) },
                                onTagAsKnown = { label -> onTagAsKnown(device.id, label) },
                                onTagAsUnknown = { onTagAsUnknown(device.id) },
                                onViewOnMap = { onViewOnMap(device.id) }
                            )
                        }
                    }

                    // Known Devices Section
                    if (uiState.knownDevices.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Known Devices",
                                count = uiState.knownDevices.size,
                                icon = Icons.Filled.Star
                            )
                        }

                        items(
                            items = uiState.knownDevices,
                            key = { "known_${it.id}" }
                        ) { device ->
                            DeviceListItem(
                                device = device,
                                onClick = { onDeviceClick(device.id) },
                                onTagAsKnown = { label -> onTagAsKnown(device.id, label) },
                                onTagAsUnknown = { onTagAsUnknown(device.id) },
                                onViewOnMap = { onViewOnMap(device.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Summary header showing device count
 */
@Composable
private fun SummaryHeader(
    totalDevices: Int,
    filteredDevices: Int,
    unknownCount: Int,
    knownCount: Int,
    searchActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (searchActive) {
                    "Showing $filteredDevices of $totalDevices devices"
                } else {
                    "$totalDevices ${if (totalDevices == 1) "device" else "devices"} found"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!searchActive && (unknownCount > 0 || knownCount > 0)) {
                Text(
                    text = "$unknownCount unknown • $knownCount known",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Section header for device categories
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Individual device list item
 */
@Composable
private fun DeviceListItem(
    device: DeviceListViewModel.DeviceItemUiState,
    onClick: () -> Unit,
    onTagAsKnown: (String) -> Unit,
    onTagAsUnknown: () -> Unit,
    onViewOnMap: () -> Unit
) {
    var showTagDialog by remember { mutableStateOf(false) }
    var tagLabel by remember { mutableStateOf(device.displayName) }

    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Device name and type
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
                            text = device.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Whitelist status indicator
                        if (device.isWhitelisted) {
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

                    // Display device type and manufacturer name
                    val deviceInfo = listOfNotNull(
                        device.deviceType,
                        device.manufacturerName
                    ).joinToString(" • ")

                    if (deviceInfo.isNotEmpty()) {
                        Text(
                            text = deviceInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row {
                    // Tag/Untag button
                    IconButton(
                        onClick = {
                            if (device.isWhitelisted) {
                                onTagAsUnknown()
                            } else {
                                tagLabel = device.displayName
                                showTagDialog = true
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (device.isWhitelisted) {
                                Icons.Filled.Star
                            } else {
                                Icons.Outlined.StarBorder
                            },
                            contentDescription = if (device.isWhitelisted) {
                                "Remove from known devices"
                            } else {
                                "Mark as known device"
                            },
                            tint = if (device.isWhitelisted) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Detection count badge
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${device.detectionCount}×",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    // Expand/Collapse button
                    IconButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showDetails) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = if (showDetails) "Show less" else "Show more",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // MAC address
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "First: ${formatTimestamp(device.firstSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Update,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Last: ${formatTimestamp(device.lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded details section
            if (showDetails) {
                Spacer(modifier = Modifier.height(12.dp))

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Whitelist category badge (if whitelisted)
                if (device.whitelistCategory != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = device.whitelistCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Location preview (locations where device was spotted)
                device.locationPreview?.let { locations ->
                    if (locations.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Spotted at ${locations.size} location${if (locations.size != 1) "s" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                locations.take(3).forEach { location ->
                                    val locationText = formatCoordinates(location.latitude, location.longitude)
                                    Text(
                                        text = "• $locationText — ${formatTimestamp(location.timestamp)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (locations.size > 3) {
                                    Text(
                                        text = "• ... and ${locations.size - 3} more",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // View on map button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = onViewOnMap,
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Map,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "View on Map",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                // Show message if no locations recorded
                if (device.locationPreview.isNullOrEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "No location data recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Tagging dialog
        if (showTagDialog) {
            AlertDialog(
                onDismissRequest = { showTagDialog = false },
                title = { Text("Tag Device as Known") },
                text = {
                    Column {
                        Text("Add this device to your known devices list to prevent alerts:")
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = tagLabel,
                            onValueChange = { tagLabel = it },
                            label = { Text("Device Label") },
                            placeholder = { Text("e.g., \"My Phone\", \"Sarah's Watch\"") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onTagAsKnown(tagLabel)
                            showTagDialog = false
                        },
                        enabled = tagLabel.isNotBlank()
                    ) {
                        Text("Add to Known")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTagDialog = false }) {
                        Text("Cancel")
                    }
                }
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
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

/**
 * Format coordinates to human-readable string
 */
private fun formatCoordinates(latitude: Double, longitude: Double): String {
    val latDir = if (latitude >= 0) "N" else "S"
    val lonDir = if (longitude >= 0) "E" else "W"
    return String.format(
        Locale.US,
        "%.4f°%s, %.4f°%s",
        kotlin.math.abs(latitude),
        latDir,
        kotlin.math.abs(longitude),
        lonDir
    )
}

/**
 * Get sort option display name
 */
private fun getSortOptionDisplayName(option: DeviceListViewModel.SortOption): String {
    return when (option) {
        DeviceListViewModel.SortOption.NAME_ASC -> "Name (A-Z)"
        DeviceListViewModel.SortOption.NAME_DESC -> "Name (Z-A)"
        DeviceListViewModel.SortOption.LAST_SEEN_DESC -> "Last Seen (Newest)"
        DeviceListViewModel.SortOption.LAST_SEEN_ASC -> "Last Seen (Oldest)"
        DeviceListViewModel.SortOption.FIRST_SEEN_DESC -> "First Seen (Newest)"
        DeviceListViewModel.SortOption.FIRST_SEEN_ASC -> "First Seen (Oldest)"
        DeviceListViewModel.SortOption.DETECTION_COUNT_DESC -> "Detection Count (High-Low)"
        DeviceListViewModel.SortOption.DETECTION_COUNT_ASC -> "Detection Count (Low-High)"
    }
}

// ==================== Previews ====================

@Preview(name = "Device List - With Devices", showBackground = true)
@Composable
private fun DeviceListScreenWithDevicesPreview() {
    val knownDevice = DeviceListViewModel.DeviceItemUiState(
        id = 1,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Unknown Device",
        displayName = "My AirPods Pro",
        firstSeen = System.currentTimeMillis() - 3600_000,
        lastSeen = System.currentTimeMillis() - 60_000,
        detectionCount = 15,
        deviceType = "Headphones",
        manufacturerName = "Apple",
        manufacturerData = null,
        isWhitelisted = true,
        whitelistLabel = "My AirPods Pro"
    )
    val unknownDevice = DeviceListViewModel.DeviceItemUiState(
        id = 2,
        address = "11:22:33:44:55:66",
        name = "Unknown Device",
        displayName = "Unknown Device",
        firstSeen = System.currentTimeMillis() - 7200_000,
        lastSeen = System.currentTimeMillis() - 120_000,
        detectionCount = 8,
        deviceType = "Tracker",
        manufacturerName = "Apple",
        manufacturerData = null,
        isWhitelisted = false
    )
    TailBaitTheme {
        DeviceListContent(
            uiState = DeviceListViewModel.DeviceListUiState(
                isLoading = false,
                devices = listOf(knownDevice, unknownDevice),
                filteredDevices = listOf(unknownDevice, knownDevice),
                unknownDevices = listOf(unknownDevice),
                knownDevices = listOf(knownDevice)
            ),
            onDeviceClick = {},
            onRefresh = {},
            onTagAsKnown = { _, _ -> },
            onTagAsUnknown = { },
            onViewOnMap = { }
        )
    }
}

@Preview(name = "Device List - Empty", showBackground = true)
@Composable
private fun DeviceListScreenEmptyPreview() {
    TailBaitTheme {
        DeviceListContent(
            uiState = DeviceListViewModel.DeviceListUiState(
                isLoading = false,
                devices = emptyList(),
                filteredDevices = emptyList()
            ),
            onDeviceClick = {},
            onRefresh = {},
            onTagAsKnown = { _, _ -> },
            onTagAsUnknown = { },
            onViewOnMap = { }
        )
    }
}

@Preview(name = "Device List - Dark Mode", showBackground = true)
@Composable
private fun DeviceListScreenDarkPreview() {
    val device = DeviceListViewModel.DeviceItemUiState(
        id = 1,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Unknown Device",
        displayName = "Unknown Device",
        firstSeen = System.currentTimeMillis() - 3600_000,
        lastSeen = System.currentTimeMillis() - 60_000,
        detectionCount = 15,
        deviceType = "Headphones",
        manufacturerName = "Apple",
        manufacturerData = null
    )
    TailBaitTheme(darkTheme = true) {
        DeviceListContent(
            uiState = DeviceListViewModel.DeviceListUiState(
                isLoading = false,
                devices = listOf(device),
                filteredDevices = listOf(device),
                unknownDevices = listOf(device),
                knownDevices = emptyList()
            ),
            onDeviceClick = {},
            onRefresh = {},
            onTagAsKnown = { _, _ -> },
            onTagAsUnknown = { },
            onViewOnMap = { }
        )
    }
}
