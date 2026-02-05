package com.tailbait.ui.screens.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.TrackerSoundManager
import com.tailbait.util.ManufacturerDataParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for Device Detail Screen
 *
 * Manages the state and business logic for displaying comprehensive
 * device information including location history and statistics.
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val whitelistRepository: WhitelistRepository,
    private val trackerSoundManager: TrackerSoundManager
) : ViewModel() {

    /**
     * UI State for Device Detail Screen
     */
    data class DeviceDetailUiState(
        val isLoading: Boolean = true,
        val device: DeviceDetail? = null,
        val locationHistory: List<LocationWithDetection> = emptyList(),
        val detectionStats: DetectionStats = DetectionStats(),
        val isWhitelisted: Boolean = false,
        val whitelistCategory: String? = null,
        val showMap: Boolean = true,
        val errorMessage: String? = null,
        // Sound trigger state
        val canPlaySound: Boolean = false,
        val soundTriggerState: SoundTriggerUiState = SoundTriggerUiState.Idle
    )

    /**
     * UI state for sound trigger operation
     */
    sealed class SoundTriggerUiState {
        object Idle : SoundTriggerUiState()
        object Connecting : SoundTriggerUiState()
        object Playing : SoundTriggerUiState()
        data class Success(val message: String) : SoundTriggerUiState()
        data class Error(val message: String) : SoundTriggerUiState()
    }

    /**
     * Enhanced device detail information
     */
    data class DeviceDetail(
        val id: Long,
        val address: String,
        val name: String,
        val advertisedName: String?,  // Local name from BLE advertisement
        val deviceType: String?,
        val deviceModel: String?,
        val manufacturerName: String?,
        val manufacturerData: String?,
        val firstSeen: Long,
        val lastSeen: Long,
        val detectionCount: Int,
        val isTracker: Boolean = false
    )

    /**
     * Location with associated detection information
     */
    data class LocationWithDetection(
        val locationId: Long,
        val name: String?,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val detectionCount: Int,
        val lastSeen: Long,
        val averageRssi: Int?
    )

    /**
     * Detection statistics
     */
    data class DetectionStats(
        val averageDetectionsPerDay: Double = 0.0,
        val mostActiveDay: String = "",
        val peakDetectionHour: Int = 0,
        val totalLocations: Int = 0,
        val coverageRadius: Double = 0.0 // in kilometers
    )

    private val _uiState = MutableStateFlow(DeviceDetailUiState())
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    /**
     * Load device details by ID
     */
    fun loadDeviceDetail(deviceId: Long) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

                // Get device information
                val device = deviceRepository.getDeviceById(deviceId)
                if (device == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Device not found"
                    )
                    return@launch
                }

                // Get location history
                val locations = locationRepository.getLocationsForDevice(deviceId).first()
                val locationWithDetections = locations.map { location ->
                    LocationWithDetection(
                        locationId = location.id,
                        name = null, // Location entity doesn't have a name field
                        latitude = location.latitude,
                        longitude = location.longitude,
                        timestamp = location.timestamp,
                        detectionCount = 1, // Would need to calculate from detection records
                        lastSeen = location.timestamp,
                        averageRssi = null // Would need to calculate from detection records
                    )
                }

                // Check if device is whitelisted
                val whitelistEntry = whitelistRepository.getEntryByDeviceId(deviceId)
                val isWhitelisted = whitelistEntry != null

                // Calculate detection statistics
                val stats = calculateDetectionStats(device, locationWithDetections)

                // Check if this device can have sound triggered
                val canPlaySound = trackerSoundManager.isTrackerWithSoundSupport(
                    deviceType = device.deviceType,
                    manufacturerName = device.manufacturerName,
                    isTracker = device.isTracker
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    device = DeviceDetail(
                        id = device.id,
                        address = device.address,
                        name = device.name ?: "Unknown Device",
                        advertisedName = device.advertisedName,
                        deviceType = device.deviceType,
                        deviceModel = device.deviceModel,
                        manufacturerName = device.manufacturerName,
                        manufacturerData = device.manufacturerData,
                        firstSeen = device.firstSeen,
                        lastSeen = device.lastSeen,
                        detectionCount = device.detectionCount,
                        isTracker = device.isTracker
                    ),
                    locationHistory = locationWithDetections,
                    detectionStats = stats,
                    isWhitelisted = isWhitelisted,
                    whitelistCategory = whitelistEntry?.category,
                    canPlaySound = canPlaySound,
                    errorMessage = null
                )

                Timber.d("Loaded device detail for device $deviceId with ${locations.size} locations")

            } catch (e: Exception) {
                Timber.e(e, "Error loading device detail for device $deviceId")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load device details: ${e.message}"
                )
            }
        }
    }

    /**
     * Calculate detection statistics from device and location data
     */
    private fun calculateDetectionStats(
        device: ScannedDevice,
        locations: List<LocationWithDetection>
    ): DetectionStats {
        val trackingPeriodMs = device.lastSeen - device.firstSeen
        val trackingPeriodDays = trackingPeriodMs / (1000 * 60 * 60 * 24).toDouble()

        val averageDetectionsPerDay = if (trackingPeriodDays > 0) {
            device.detectionCount / trackingPeriodDays
        } else {
            device.detectionCount.toDouble()
        }

        // Calculate coverage radius (maximum distance between any two points)
        val coverageRadius = calculateCoverageRadius(locations)

        return DetectionStats(
            averageDetectionsPerDay = averageDetectionsPerDay,
            mostActiveDay = "", // Would need detailed detection records
            peakDetectionHour = 0, // Would need detailed detection records
            totalLocations = locations.size,
            coverageRadius = coverageRadius
        )
    }

    /**
     * Calculate maximum distance between any two locations to estimate coverage radius
     */
    private fun calculateCoverageRadius(locations: List<LocationWithDetection>): Double {
        if (locations.size < 2) return 0.0

        var maxDistance = 0.0

        for (i in locations.indices) {
            for (j in i + 1 until locations.size) {
                val distance = calculateDistance(
                    locations[i].latitude, locations[i].longitude,
                    locations[j].latitude, locations[j].longitude
                )
                if (distance > maxDistance) {
                    maxDistance = distance
                }
            }
        }

        return maxDistance
    }

    /**
     * Calculate distance between two coordinates in kilometers
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371 // Earth's radius in kilometers

        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        val a = (Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + (Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)))
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    /**
     * Toggle map visibility
     */
    fun toggleMapVisibility() {
        _uiState.value = _uiState.value.copy(
            showMap = !_uiState.value.showMap
        )
    }

    /**
     * Add device to whitelist
     */
    fun addToWhitelist(label: String, category: String) {
        val device = _uiState.value.device ?: return

        viewModelScope.launch {
            try {
                whitelistRepository.addToWhitelist(
                    deviceId = device.id,
                    label = label,
                    category = category,
                    addedViaLearnMode = false
                )

                // Update UI state
                _uiState.value = _uiState.value.copy(
                    isWhitelisted = true,
                    whitelistCategory = category
                )

                Timber.d("Added device ${device.address} to whitelist with label '$label'")

            } catch (e: Exception) {
                Timber.e(e, "Error adding device to whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to add device to whitelist: ${e.message}"
                )
            }
        }
    }

    /**
     * Remove device from whitelist
     */
    fun removeFromWhitelist() {
        val device = _uiState.value.device ?: return

        viewModelScope.launch {
            try {
                whitelistRepository.removeFromWhitelist(device.id)

                // Update UI state
                _uiState.value = _uiState.value.copy(
                    isWhitelisted = false,
                    whitelistCategory = null
                )

                Timber.d("Removed device ${device.address} from whitelist")

            } catch (e: Exception) {
                Timber.e(e, "Error removing device from whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to remove device from whitelist: ${e.message}"
                )
            }
        }
    }

    /**
     * Trigger sound on the tracker device (AirTag, etc.)
     *
     * This connects to the device via BLE GATT and sends a command
     * to trigger the sound. Only works on supported tracker devices.
     */
    fun triggerSound() {
        val device = _uiState.value.device ?: return

        if (!_uiState.value.canPlaySound) {
            _uiState.value = _uiState.value.copy(
                soundTriggerState = SoundTriggerUiState.Error("This device doesn't support sound triggering")
            )
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    soundTriggerState = SoundTriggerUiState.Connecting
                )

                Timber.d("Triggering sound on device: ${device.address}")

                // Observe the tracker sound manager state
                trackerSoundManager.state.collect { state ->
                    when (state) {
                        is TrackerSoundManager.SoundTriggerState.Connecting -> {
                            _uiState.value = _uiState.value.copy(
                                soundTriggerState = SoundTriggerUiState.Connecting
                            )
                        }
                        is TrackerSoundManager.SoundTriggerState.DiscoveringServices,
                        is TrackerSoundManager.SoundTriggerState.TriggeringSound -> {
                            _uiState.value = _uiState.value.copy(
                                soundTriggerState = SoundTriggerUiState.Playing
                            )
                        }
                        is TrackerSoundManager.SoundTriggerState.Success -> {
                            _uiState.value = _uiState.value.copy(
                                soundTriggerState = SoundTriggerUiState.Success(state.message)
                            )
                            return@collect
                        }
                        is TrackerSoundManager.SoundTriggerState.Error -> {
                            _uiState.value = _uiState.value.copy(
                                soundTriggerState = SoundTriggerUiState.Error(state.message)
                            )
                            return@collect
                        }
                        is TrackerSoundManager.SoundTriggerState.Idle -> {
                            // Initial state, continue
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error triggering sound")
                _uiState.value = _uiState.value.copy(
                    soundTriggerState = SoundTriggerUiState.Error(e.message ?: "Unknown error")
                )
            }
        }

        // Start the sound trigger in parallel
        viewModelScope.launch {
            val result = trackerSoundManager.triggerSound(device.address)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    soundTriggerState = SoundTriggerUiState.Error(
                        result.exceptionOrNull()?.message ?: "Failed to trigger sound"
                    )
                )
            }
        }
    }

    /**
     * Stop the sound on the tracker device
     */
    fun stopSound() {
        viewModelScope.launch {
            trackerSoundManager.stopSound()
            _uiState.value = _uiState.value.copy(
                soundTriggerState = SoundTriggerUiState.Idle
            )
        }
    }

    /**
     * Reset sound trigger state to idle
     */
    fun resetSoundTriggerState() {
        trackerSoundManager.resetState()
        _uiState.value = _uiState.value.copy(
            soundTriggerState = SoundTriggerUiState.Idle
        )
    }

    /**
     * View device on main map
     */
    fun viewOnMainMap() {
        // This would navigate to the main map screen and filter by this device
        // Implementation depends on navigation setup
        Timber.d("View device on main map requested")
    }

    /**
     * Show menu (placeholder for future menu options)
     */
    fun showMenu() {
        // Show device options menu
        Timber.d("Device menu requested")
    }

    /**
     * Export device data
     */
    fun exportDeviceData() {
        // Export device data for analysis or sharing
        Timber.d("Export device data requested")
    }

    /**
     * Retry loading device data
     */
    fun retryLoad() {
        val device = _uiState.value.device
        if (device != null) {
            loadDeviceDetail(device.id)
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("DeviceDetailViewModel cleared")
    }
}
