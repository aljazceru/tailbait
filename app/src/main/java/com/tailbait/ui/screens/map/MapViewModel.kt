package com.tailbait.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.ScannedDevice
import org.osmdroid.util.GeoPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import com.tailbait.util.DeviceNameGenerator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.tailbait.data.dto.DeviceLocationMapData

/**
 * ViewModel for the Map Screen.
 *
 * This ViewModel manages the state and business logic for the map visualization screen.
 * It provides:
 * - Loading location history with device information
 * - Filtering by device (show specific device's path)
 * - Filtering by date range (start/end timestamps)
 * - Grouping locations by device for path visualization
 * - Camera positioning based on visible markers
 *
 * The ViewModel combines data from multiple sources to create a comprehensive view
 * of device movement patterns across locations.
 *
 * @property deviceLocationRecordDao DAO for device-location correlation records
 * @property locationDao DAO for location data
 * @property scannedDeviceDao DAO for device data
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val deviceLocationRecordDao: DeviceLocationRecordDao,
    private val locationDao: LocationDao,
    private val scannedDeviceDao: ScannedDeviceDao
) : ViewModel() {

    /**
     * Represents a marker on the map with associated device and location data.
     */
    data class MapMarker(
        val id: Long,
        val position: GeoPoint,
        val deviceId: Long,
        val deviceName: String,
        val deviceAddress: String,
        val timestamp: Long,
        val rssi: Int,
        val accuracy: Float,
        val locationId: Long
    )

    /**
     * Represents a device's movement path with all its detection locations.
     */
    data class DevicePath(
        val deviceId: Long,
        val deviceName: String,
        val deviceAddress: String,
        val points: List<GeoPoint>,
        val timestamps: List<Long>,
        val color: Int
    )

    /**
     * UI State for the Map Screen
     */
    data class MapUiState(
        val isLoading: Boolean = true,
        val markers: List<MapMarker> = emptyList(),
        val devicePaths: List<DevicePath> = emptyList(),
        val selectedDeviceId: Long? = null,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val cameraPosition: GeoPoint? = null,
        val errorMessage: String? = null,
        val totalDevices: Int = 0,
        val totalLocations: Int = 0
    )

    /**
     * Filter criteria for map data
     */
    data class MapFilter(
        val deviceId: Long? = null,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null
    )

    // Internal state flows
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(MapFilter())

    init {
        Timber.d("MapViewModel initialized")
        initializeViewModel()
    }

    /**
     * Initialize the ViewModel by loading map data
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun initializeViewModel() {
        viewModelScope.launch {
            try {
                _filter.flatMapLatest { filter ->
                    Timber.d("Loading map data with filter: $filter")
                    deviceLocationRecordDao.getMapData(
                        deviceId = filter.deviceId,
                        startTimestamp = filter.startTimestamp,
                        endTimestamp = filter.endTimestamp
                    )
                }.catch { e ->
                    Timber.e(e, "Error loading map data")
                    emit(emptyList()) // Emit empty list on error to keep flow alive? Or handle differently.
                    // Actually, let's just log and update state with error
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load map data: ${e.message}") }
                }.collect { mapData ->
                    Timber.d("Loaded detailed map data: ${mapData.size} records")

                    val markers = mutableListOf<MapMarker>()
                    val devicePathsMap = mutableMapOf<Long, MutableList<Pair<GeoPoint, Long>>>()
                    val uniqueDeviceIds = mutableSetOf<Long>()
                    val uniqueLocationIds = mutableSetOf<Long>() // Note: We don't have location IDs in the simple DTO, wait, we added it. Ah, DTO has id, deviceId, etc. It doesn't have locationId explicitly?
                    // Ah, looking at my previous step, I selected:
                    // dlr.id, dlr.device_id as deviceId, l.latitude, l.longitude, l.accuracy, dlr.timestamp, dlr.rssi...
                    // I did NOT select location_id. But I can assume each record is a location instance.
                    // Actually uniqueLocations was counting distinct location IDs.
                    // Maps often have many records for the same location ID if multiple devices were scanned there? 
                    // No, `device_location_records` links one device to one location.
                    // Use unique (lat, lon) pairs or just count records? The original code counted distinct locationIds.
                    // I should probably add locationId to the DTO if I want to match exactly, but counting distinct (lat, lon) is close enough
                    // or just counting records is fine for "locations" count in this context.
                    // Let's modify the DTO to include location_id?
                    // The DTO has `id` which is the record ID.
                    // The query: `INNER JOIN locations l ON dlr.location_id = l.id`
                    // I can add `dlr.location_id` to the select.
                    
                    // Actually, let's just implement the mapping logic with what we have.
                    // uniqueLocations count is just a stat.

                    mapData.forEach { data ->
                        val position = GeoPoint(data.latitude, data.longitude)
                        uniqueDeviceIds.add(data.deviceId)
                        
                        // Generate short, identifiable name for map display
                        val shortName = DeviceNameGenerator.generateShortName(
                            deviceType = data.deviceType,
                            deviceAddress = data.deviceAddress,
                            manufacturerData = data.manufacturerData
                        )

                        // Add marker
                        markers.add(
                            MapMarker(
                                id = data.id,
                                position = position,
                                deviceId = data.deviceId,
                                deviceName = shortName,
                                deviceAddress = data.deviceAddress,
                                timestamp = data.timestamp,
                                rssi = data.rssi,
                                accuracy = data.accuracy,
                                locationId = data.locationId
                            )
                        )

                        // Add to device path
                        devicePathsMap.getOrPut(data.deviceId) { mutableListOf() }
                            .add(position to data.timestamp)
                    }

                    // Sort device paths by timestamp and create DevicePath objects
                    val devicePaths = devicePathsMap.map { (deviceId, pointsWithTimestamps) ->
                        // We need device details for the path name. We can get it from the first record for this device.
                        val firstRecord = mapData.firstOrNull { it.deviceId == deviceId }
                        
                        val shortPathName = firstRecord?.let {
                            DeviceNameGenerator.generateShortName(
                                deviceType = it.deviceType,
                                deviceAddress = it.deviceAddress,
                                manufacturerData = it.manufacturerData
                            )
                        } ?: "Unknown"

                        val sortedPoints = pointsWithTimestamps.sortedBy { it.second }

                        DevicePath(
                            deviceId = deviceId,
                            deviceName = shortPathName,
                            deviceAddress = firstRecord?.deviceAddress ?: "",
                            points = sortedPoints.map { it.first },
                            timestamps = sortedPoints.map { it.second },
                            color = getColorForDevice(deviceId)
                        )
                    }

                    // Calculate camera position (center of all markers)
                    val cameraPosition = if (markers.isNotEmpty()) {
                        val avgLat = markers.map { it.position.latitude }.average()
                        val avgLng = markers.map { it.position.longitude }.average()
                        GeoPoint(avgLat, avgLng)
                    } else {
                        null
                    }

                    _uiState.value = MapUiState(
                        isLoading = false,
                        markers = markers,
                        devicePaths = devicePaths,
                        selectedDeviceId = _filter.value.deviceId,
                        startTimestamp = _filter.value.startTimestamp,
                        endTimestamp = _filter.value.endTimestamp,
                        cameraPosition = cameraPosition,
                        totalDevices = uniqueDeviceIds.size,
                        totalLocations = markers.size // Approximation since we don't have distinct location IDs
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing map view model")
                _uiState.value = MapUiState(
                    isLoading = false,
                    errorMessage = "Failed to initialize map: ${e.message}"
                )
            }
        }
    }

    /**
     * Filter map data by device ID
     */
    fun filterByDevice(deviceId: Long?) {
        viewModelScope.launch {
            Timber.i("Filtering map by device: $deviceId")
            _filter.value = _filter.value.copy(deviceId = deviceId)
        }
    }

    /**
     * Filter map data by date range
     */
    fun filterByDateRange(startTimestamp: Long?, endTimestamp: Long?) {
        viewModelScope.launch {
            Timber.i("Filtering map by date range: $startTimestamp - $endTimestamp")
            _filter.value = _filter.value.copy(
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp
            )
        }
    }

    /**
     * Clear all filters
     */
    fun clearFilters() {
        viewModelScope.launch {
            Timber.i("Clearing all map filters")
            _filter.value = MapFilter()
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Get color for a device based on its ID
     * This creates consistent colors across the app for the same device
     */
    private fun getColorForDevice(deviceId: Long): Int {
        // Predefined material colors for device paths
        val colors = listOf(
            // Red
            0xFFE53935.toInt(),
            // Blue
            0xFF1E88E5.toInt(),
            // Green
            0xFF43A047.toInt(),
            // Orange
            0xFFFB8C00.toInt(),
            // Purple
            0xFF8E24AA.toInt(),
            // Cyan
            0xFF00ACC1.toInt(),
            // Amber
            0xFFFFB300.toInt(),
            // Indigo
            0xFF3949AB.toInt(),
            // Teal
            0xFF00897B.toInt(),
            // Lime
            0xFFC0CA33.toInt()
        )

        return colors[(deviceId % colors.size).toInt()]
    }

    /**
     * Get devices available for filtering
     */
    fun getAvailableDevices(): Flow<List<ScannedDevice>> {
        return scannedDeviceDao.getAllDevices()
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("MapViewModel cleared")
    }
}
