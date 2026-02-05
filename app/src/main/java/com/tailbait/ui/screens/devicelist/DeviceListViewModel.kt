package com.tailbait.ui.screens.devicelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.util.ManufacturerDataParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Device List Screen.
 *
 * This ViewModel manages the state and business logic for displaying and managing
 * the list of discovered BLE devices. It provides:
 * - List of all discovered devices
 * - Sorting options (by name, RSSI, time)
 * - Filtering options (search, device type)
 * - Pull-to-refresh functionality
 * - Device detail navigation
 *
 * The ViewModel processes raw device data and transforms it into a UI-friendly format
 * with sorting and filtering applied.
 *
 * @property deviceRepository Repository for device data operations
 */
@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val whitelistRepository: WhitelistRepository
) : ViewModel() {

    /**
     * Sort options for device list
     */
    enum class SortOption {
        NAME_ASC,
        NAME_DESC,
        LAST_SEEN_DESC,
        LAST_SEEN_ASC,
        FIRST_SEEN_DESC,
        FIRST_SEEN_ASC,
        DETECTION_COUNT_DESC,
        DETECTION_COUNT_ASC
    }

    /**
     * UI State for Device List Screen
     */
    data class DeviceListUiState(
        val isLoading: Boolean = true,
        val devices: List<DeviceItemUiState> = emptyList(),
        val filteredDevices: List<DeviceItemUiState> = emptyList(),
        val unknownDevices: List<DeviceItemUiState> = emptyList(),
        val knownDevices: List<DeviceItemUiState> = emptyList(),
        val searchQuery: String = "",
        val sortOption: SortOption = SortOption.LAST_SEEN_DESC,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null
    )

    /**
     * UI State for individual device item
     */
    data class DeviceItemUiState(
        val id: Long,
        val address: String,
        val name: String,
        val displayName: String, // The name to show (whitelist label if known, else device name)
        val firstSeen: Long,
        val lastSeen: Long,
        val detectionCount: Int,
        val deviceType: String?,
        val manufacturerName: String?,
        val manufacturerData: String?,
        val isWhitelisted: Boolean = false,
        val whitelistLabel: String? = null,
        val whitelistCategory: String? = null,
        val locationPreview: List<LocationPreview>? = null
    )

    /**
     * Location preview data for device list
     */
    data class LocationPreview(
        val locationId: Long,
        val name: String?,
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )

    // Internal state
    private val _uiState = MutableStateFlow(DeviceListUiState())
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    private val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Sort option state
    private val _sortOption = MutableStateFlow(SortOption.LAST_SEEN_DESC)
    private val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    init {
        Timber.d("DeviceListViewModel initialized")
        observeDevices()
    }

    /**
     * Observe devices from repository and apply transformations
     */
    private fun observeDevices() {
        viewModelScope.launch {
            combine(
                deviceRepository.getAllDevices(),
                whitelistRepository.getAllWhitelistEntries(),
                searchQuery,
                sortOption
            ) { devices, whitelistEntries, query, sort ->
                Timber.d(
                    "Processing ${devices.size} devices with ${whitelistEntries.size} whitelisted, " +
                        "query='$query' and sort=$sort"
                )

                // Create map for efficient lookup: deviceId -> WhitelistEntry
                val whitelistMap = whitelistEntries.associateBy { it.deviceId }

                // Load locations for all devices
                val deviceLocationsMap = devices.associate { device ->
                    device.id to try {
                        locationRepository.getLocationsForDeviceOnce(device.id).map { location ->
                            LocationPreview(
                                locationId = location.id,
                                name = null, // Location entity doesn't have a name
                                timestamp = location.timestamp,
                                latitude = location.latitude,
                                longitude = location.longitude
                            )
                        }
                    } catch (e: Exception) {
                        Timber.i("Loading locations cancelled for device ${device.id}")
                        emptyList()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load locations for device ${device.id}")
                        emptyList()
                    }
                }

                // Map to UI state with whitelist info and locations
                val deviceItems = devices.map { device ->
                    val whitelistEntry = whitelistMap[device.id]
                    val isWhitelisted = whitelistEntry != null
                    val baseName = device.name ?: "Unknown Device"
                    val locations = deviceLocationsMap[device.id] ?: emptyList()

                    device.toUiState().copy(
                        isWhitelisted = isWhitelisted,
                        whitelistLabel = whitelistEntry?.label,
                        whitelistCategory = whitelistEntry?.category,
                        locationPreview = locations.takeIf { it.isNotEmpty() },
                        // For known devices, show the whitelist label; for unknown, show device name
                        displayName = if (isWhitelisted && !whitelistEntry?.label.isNullOrBlank()) {
                            whitelistEntry!!.label
                        } else {
                            baseName
                        }
                    )
                }

                // Apply search filter across all visible fields
                val filtered = if (query.isBlank()) {
                    deviceItems
                } else {
                    deviceItems.filter { device ->
                        device.displayName.contains(query, ignoreCase = true) ||
                        device.name.contains(query, ignoreCase = true) ||
                        device.address.contains(query, ignoreCase = true) ||
                        device.deviceType?.contains(query, ignoreCase = true) == true ||
                        device.manufacturerName?.contains(query, ignoreCase = true) == true ||
                        device.whitelistLabel?.contains(query, ignoreCase = true) == true ||
                        device.whitelistCategory?.contains(query, ignoreCase = true) == true ||
                        (device.isWhitelisted && query.contains("known", ignoreCase = true)) ||
                        (!device.isWhitelisted && query.contains("unknown", ignoreCase = true))
                    }
                }

                // Apply sorting
                val sorted = applySorting(filtered, sort)

                // Split into known and unknown devices
                val (known, unknown) = sorted.partition { it.isWhitelisted }

                DeviceListResult(deviceItems, sorted, unknown, known, query)
            }.catch { e ->
                Timber.e(e, "Error observing devices")
                emit(DeviceListResult(emptyList(), emptyList(), emptyList(), emptyList(), ""))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load devices: ${e.message}"
                )
            }.collect { result ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    devices = result.allDevices,
                    filteredDevices = result.filteredDevices,
                    unknownDevices = result.unknownDevices,
                    knownDevices = result.knownDevices,
                    sortOption = _sortOption.value,
                    isRefreshing = false,
                    errorMessage = null
                )
            }
        }
    }

    /**
     * Data class to hold the result of device list processing
     */
    private data class DeviceListResult(
        val allDevices: List<DeviceItemUiState>,
        val filteredDevices: List<DeviceItemUiState>,
        val unknownDevices: List<DeviceItemUiState>,
        val knownDevices: List<DeviceItemUiState>,
        val query: String
    )

    /**
     * Apply sorting to device list
     */
    private fun applySorting(
        devices: List<DeviceItemUiState>,
        sortOption: SortOption
    ): List<DeviceItemUiState> {
        return when (sortOption) {
            SortOption.NAME_ASC -> devices.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> devices.sortedByDescending { it.name.lowercase() }
            SortOption.LAST_SEEN_DESC -> devices.sortedByDescending { it.lastSeen }
            SortOption.LAST_SEEN_ASC -> devices.sortedBy { it.lastSeen }
            SortOption.FIRST_SEEN_DESC -> devices.sortedByDescending { it.firstSeen }
            SortOption.FIRST_SEEN_ASC -> devices.sortedBy { it.firstSeen }
            SortOption.DETECTION_COUNT_DESC -> devices.sortedByDescending { it.detectionCount }
            SortOption.DETECTION_COUNT_ASC -> devices.sortedBy { it.detectionCount }
        }
    }

    /**
     * Update search query
     */
    fun updateSearchQuery(query: String) {
        Timber.d("Updating search query: $query")
        _searchQuery.value = query
        // Immediately update UI state so TextField stays responsive
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /**
     * Clear search query
     */
    fun clearSearch() {
        Timber.d("Clearing search query")
        _searchQuery.value = ""
        // Immediately update UI state so TextField stays responsive
        _uiState.value = _uiState.value.copy(searchQuery = "")
    }

    /**
     * Update sort option
     */
    fun updateSortOption(option: SortOption) {
        Timber.d("Updating sort option: $option")
        _sortOption.value = option
    }

    /**
     * Refresh device list
     * Note: The list is automatically refreshed via Flow observation,
     * this is mainly for pull-to-refresh UI feedback
     */
    fun refreshDevices() {
        viewModelScope.launch {
            Timber.d("Refreshing device list")
            _uiState.value = _uiState.value.copy(isRefreshing = true)

            // Simulate refresh delay for better UX
            kotlinx.coroutines.delay(500)

            _uiState.value = _uiState.value.copy(isRefreshing = false)
            Timber.d("Device list refreshed")
        }
    }

    /**
     * Add device to whitelist (tag as known)
     */
    fun tagDeviceAsKnown(deviceId: Long, label: String, category: String = WhitelistRepository.Category.TRUSTED) {
        viewModelScope.launch {
            try {
                Timber.d("Adding device $deviceId to whitelist with label: $label")
                whitelistRepository.addToWhitelist(
                    deviceId = deviceId,
                    label = label,
                    category = category,
                    addedViaLearnMode = false
                )
                Timber.i("Device successfully added to whitelist")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add device to whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to tag device as known: ${e.message}"
                )
            }
        }
    }

    /**
     * Remove device from whitelist (tag as unknown)
     */
    fun tagDeviceAsUnknown(deviceId: Long) {
        viewModelScope.launch {
            try {
                Timber.d("Removing device $deviceId from whitelist")
                val removed = whitelistRepository.removeFromWhitelist(deviceId)
                if (removed > 0) {
                    Timber.i("Device successfully removed from whitelist")
                } else {
                    Timber.w("Device was not in whitelist")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove device from whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to tag device as unknown: ${e.message}"
                )
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Get sort option display name
     */
    fun getSortOptionDisplayName(option: SortOption): String {
        return when (option) {
            SortOption.NAME_ASC -> "Name (A-Z)"
            SortOption.NAME_DESC -> "Name (Z-A)"
            SortOption.LAST_SEEN_DESC -> "Last Seen (Newest)"
            SortOption.LAST_SEEN_ASC -> "Last Seen (Oldest)"
            SortOption.FIRST_SEEN_DESC -> "First Seen (Newest)"
            SortOption.FIRST_SEEN_ASC -> "First Seen (Oldest)"
            SortOption.DETECTION_COUNT_DESC -> "Detection Count (High-Low)"
            SortOption.DETECTION_COUNT_ASC -> "Detection Count (Low-High)"
        }
    }

    /**
     * Extension function to convert ScannedDevice entity to UI state
     */
    private fun ScannedDevice.toUiState(): DeviceItemUiState {
        // Use stored manufacturer name from entity, fallback to parsing if not available
        val rawManufName = manufacturerName ?: manufacturerId?.let { id ->
            ManufacturerDataParser.getManufacturerName(id)
        }

        // Clean up manufacturer name - don't show "Unknown (0x...)" format
        val cleanManufName = rawManufName?.takeIf {
            !it.startsWith("Unknown") && it.isNotBlank()
        }

        // Format device type nicely for display
        val displayDeviceType = when {
            // Use device model if it's more specific (e.g., "AirTag", "AirPods Pro")
            !deviceModel.isNullOrBlank() && deviceModel != "UNKNOWN" -> deviceModel
            // Check if device type is meaningful (not "UNKNOWN")
            !deviceType.isNullOrBlank() && deviceType.uppercase() != "UNKNOWN" -> {
                deviceType.lowercase().replaceFirstChar { it.uppercase() }.replace("_", " ")
            }
            else -> null
        }

        val deviceName = name ?: "Unknown Device"
        return DeviceItemUiState(
            id = id,
            address = address,
            name = deviceName,
            displayName = deviceName, // Will be overridden if whitelisted
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            detectionCount = detectionCount,
            deviceType = displayDeviceType,
            manufacturerName = cleanManufName,
            manufacturerData = manufacturerData,
            locationPreview = null // Will be loaded separately
        )
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("DeviceListViewModel cleared")
    }
}
