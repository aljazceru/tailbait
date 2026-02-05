package com.tailbait.ui.screens.whitelist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.ui.components.EmptyView
import com.tailbait.ui.components.LoadingView
import com.tailbait.ui.components.KeyValueRow
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import com.tailbait.ui.theme.TailBaitTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Whitelist Screen
 *
 * This screen displays and manages the device whitelist with:
 * - List of whitelisted devices with details
 * - Category filtering (OWN, PARTNER, TRUSTED)
 * - Search functionality
 * - Add/Edit/Remove operations
 * - Statistics display
 *
 * The screen provides a comprehensive interface for managing trusted devices
 * that should be excluded from stalking detection.
 *
 * @param onNavigateBack Callback when user wants to go back
 * @param viewModel The ViewModel for this screen (injected by Hilt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onNavigateBack: () -> Unit,
    viewModel: WhitelistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchActive by remember { mutableStateOf(false) }

    // Handle dialogs
    if (uiState.showAddDialog) {
        // Note: Device list would be populated from DeviceRepository in a future enhancement
        // Currently supports manual device ID entry
        AddToWhitelistDialog(
            devices = emptyList(),
            onDismiss = { viewModel.hideAddDialog() },
            onConfirm = { deviceId, label, category, notes ->
                viewModel.addToWhitelist(deviceId, label, category, notes)
            }
        )
    }

    if (uiState.showEditDialog && uiState.editingEntry != null) {
        EditWhitelistDialog(
            entry = uiState.editingEntry!!,
            onDismiss = { viewModel.hideEditDialog() },
            onConfirm = { label, category, notes ->
                viewModel.updateEntry(
                    entryId = uiState.editingEntry!!.id,
                    label = label,
                    category = category,
                    notes = notes
                )
            }
        )
    }

    if (uiState.showDeleteConfirmation && uiState.deletingEntry != null) {
        DeleteWhitelistConfirmationDialog(
            entry = uiState.deletingEntry!!,
            onDismiss = { viewModel.hideDeleteConfirmation() },
            onConfirm = {
                viewModel.removeFromWhitelist(uiState.deletingEntry!!.entry.id)
            }
        )
    }

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error snackbar if there's an error
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    title = { Text("Whitelist") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Navigate back"
                            )
                        }
                    },
                    actions = {
                        // Search action
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search whitelist"
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
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = { Text("Add Device") },
                shape = TailBaitShapeTokens.FabShape
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Statistics Card
            if (!searchActive) {
                WhitelistStatisticsCard(
                    totalCount = uiState.totalCount,
                    ownCount = uiState.ownCount,
                    partnerCount = uiState.partnerCount,
                    trustedCount = uiState.trustedCount
                )
            }

            // Category Filter Chips
            CategoryFilterChips(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.selectCategory(it) },
                ownCount = uiState.ownCount,
                partnerCount = uiState.partnerCount,
                trustedCount = uiState.trustedCount
            )

            // Content
            when {
                uiState.isLoading -> {
                    LoadingView(message = "Loading whitelist...")
                }
                uiState.filteredEntries.isEmpty() -> {
                    EmptyView(
                        icon = Icons.Outlined.Shield,
                        title = if (uiState.searchQuery.isNotBlank()) {
                            "No Results"
                        } else if (uiState.selectedCategory != null) {
                            "No Devices in this Category"
                        } else {
                            "No Whitelisted Devices"
                        },
                        message = if (uiState.searchQuery.isNotBlank()) {
                            "No devices match your search query"
                        } else if (uiState.selectedCategory != null) {
                            "Add devices to this category to see them here"
                        } else {
                            "Add trusted devices to exclude them from stalking detection"
                        },
                        actionButtonText = if (uiState.searchQuery.isBlank() && uiState.selectedCategory == null) {
                            "Add Device"
                        } else null,
                        onActionClick = if (uiState.searchQuery.isBlank() && uiState.selectedCategory == null) {
                            { viewModel.showAddDialog() }
                        } else null
                    )
                }
                else -> {
                    WhitelistContent(
                        entries = uiState.filteredEntries,
                        onEntryClick = { entry ->
                            viewModel.showEditDialog(entry.entry)
                        },
                        onDeleteClick = { entry ->
                            viewModel.showDeleteConfirmation(entry)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Search top bar for whitelist screen.
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
                placeholder = { Text("Search by label, name, or address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = onCloseSearch) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Close search"
                )
            }
        },
        actions = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        }
    )
}

/**
 * Statistics card showing whitelist counts.
 */
@Composable
private fun WhitelistStatisticsCard(
    totalCount: Int,
    ownCount: Int,
    partnerCount: Int,
    trustedCount: Int
) {
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
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            StatisticItem(
                label = "Total",
                count = totalCount,
                icon = Icons.Outlined.Shield
            )
            StatisticItem(
                label = "My Devices",
                count = ownCount,
                icon = Icons.Outlined.Smartphone
            )
            StatisticItem(
                label = "Partner",
                count = partnerCount,
                icon = Icons.Outlined.Favorite
            )
            StatisticItem(
                label = "Trusted",
                count = trustedCount,
                icon = Icons.Outlined.VerifiedUser
            )
        }
    }
}

/**
 * Individual statistic item.
 */
@Composable
private fun StatisticItem(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * Category filter chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterChips(
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    ownCount: Int,
    partnerCount: Int,
    trustedCount: Int
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // All chip
        item {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) },
                label = { Text("All") },
                leadingIcon = if (selectedCategory == null) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else null
            )
        }

        // My Devices chip
        item {
            FilterChip(
                selected = selectedCategory == WhitelistRepository.Category.OWN,
                onClick = { onCategorySelected(WhitelistRepository.Category.OWN) },
                label = { Text("My Devices ($ownCount)") },
                leadingIcon = if (selectedCategory == WhitelistRepository.Category.OWN) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else null
            )
        }

        // Partner chip
        item {
            FilterChip(
                selected = selectedCategory == WhitelistRepository.Category.PARTNER,
                onClick = { onCategorySelected(WhitelistRepository.Category.PARTNER) },
                label = { Text("Partner ($partnerCount)") },
                leadingIcon = if (selectedCategory == WhitelistRepository.Category.PARTNER) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else null
            )
        }

        // Trusted chip
        item {
            FilterChip(
                selected = selectedCategory == WhitelistRepository.Category.TRUSTED,
                onClick = { onCategorySelected(WhitelistRepository.Category.TRUSTED) },
                label = { Text("Trusted ($trustedCount)") },
                leadingIcon = if (selectedCategory == WhitelistRepository.Category.TRUSTED) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else null
            )
        }
    }
}

/**
 * Whitelist content showing the list of entries.
 */
@Composable
private fun WhitelistContent(
    entries: List<WhitelistRepository.WhitelistEntryWithDevice>,
    onEntryClick: (WhitelistRepository.WhitelistEntryWithDevice) -> Unit,
    onDeleteClick: (WhitelistRepository.WhitelistEntryWithDevice) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp) // Space for FAB
    ) {
        items(
            items = entries,
            key = { it.entry.id }
        ) { entry ->
            WhitelistEntryCard(
                entry = entry,
                onClick = { onEntryClick(entry) },
                onDeleteClick = { onDeleteClick(entry) }
            )
        }
    }
}

/**
 * Individual whitelist entry card.
 */
@Composable
private fun WhitelistEntryCard(
    entry: WhitelistRepository.WhitelistEntryWithDevice,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Main content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Label with category badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    CategoryBadge(category = entry.entry.category)
                }

                // Device name
                if (entry.device.name != null) {
                    Text(
                        text = entry.device.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Device address
                Text(
                    text = entry.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Notes (if present)
                if (entry.entry.notes != null) {
                    Text(
                        text = entry.entry.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Added date
                Text(
                    text = "Added: ${formatTimestamp(entry.entry.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Learn mode indicator
                if (entry.entry.addedViaLearnMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Added via Learn Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // Delete button
            IconButton(
                onClick = onDeleteClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove from whitelist"
                )
            }
        }
    }
}

/**
 * Category badge component.
 */
@Composable
private fun CategoryBadge(category: String) {
    val (backgroundColor, textColor, icon) = when (category) {
        WhitelistRepository.Category.OWN -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Outlined.Smartphone
        )
        WhitelistRepository.Category.PARTNER -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Outlined.Favorite
        )
        WhitelistRepository.Category.TRUSTED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Outlined.VerifiedUser
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Outlined.Shield
        )
    }

    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = textColor
            )
            Text(
                text = when (category) {
                    WhitelistRepository.Category.OWN -> "OWN"
                    WhitelistRepository.Category.PARTNER -> "PARTNER"
                    WhitelistRepository.Category.TRUSTED -> "TRUSTED"
                    else -> category
                },
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Format timestamp to human-readable date.
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ========== Preview Functions ==========

@Preview(name = "Whitelist Screen - With Entries", showBackground = true)
@Composable
private fun WhitelistScreenPreview() {
    TailBaitTheme {
        // Preview would need a fake ViewModel
        // WhitelistScreen(onNavigateBack = {})
    }
}

@Preview(name = "Whitelist Entry Card", showBackground = true)
@Composable
private fun WhitelistEntryCardPreview() {
    TailBaitTheme {
        WhitelistEntryCard(
            entry = WhitelistRepository.WhitelistEntryWithDevice(
                entry = WhitelistEntry(
                    id = 1,
                    deviceId = 1,
                    label = "My iPhone",
                    category = WhitelistRepository.Category.OWN,
                    notes = "Personal device - iPhone 15 Pro",
                    addedViaLearnMode = false,
                    createdAt = System.currentTimeMillis()
                ),
                device = ScannedDevice(
                    id = 1,
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "iPhone 15 Pro",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                )
            ),
            onClick = {},
            onDeleteClick = {}
        )
    }
}

@Preview(name = "Statistics Card", showBackground = true)
@Composable
private fun WhitelistStatisticsCardPreview() {
    TailBaitTheme {
        WhitelistStatisticsCard(
            totalCount = 8,
            ownCount = 3,
            partnerCount = 2,
            trustedCount = 3
        )
    }
}

@Preview(name = "Category Badge", showBackground = true)
@Composable
private fun CategoryBadgePreview() {
    TailBaitTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            CategoryBadge(WhitelistRepository.Category.OWN)
            CategoryBadge(WhitelistRepository.Category.PARTNER)
            CategoryBadge(WhitelistRepository.Category.TRUSTED)
        }
    }
}

@Preview(name = "Whitelist Screen - Dark Mode", showBackground = true)
@Composable
private fun WhitelistScreenDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        // Dark mode preview
    }
}
