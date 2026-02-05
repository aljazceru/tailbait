package com.tailbait.ui.screens.alert

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import org.json.JSONObject

/**
 * ViewModel for the Alert Detail Screen.
 *
 * This ViewModel manages:
 * - Loading alert details
 * - Loading involved devices
 * - Loading location timeline
 * - Parsing threat score breakdown
 * - Alert dismissal and actions
 * - Navigation to device details
 */
@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val alertId: Long = savedStateHandle.get<Long>(NavArgs.ALERT_ID) ?: 0L

    /**
     * UI State for the Alert Detail Screen.
     */
    data class AlertDetailUiState(
        val isLoading: Boolean = true,
        val alert: AlertHistory? = null,
        val involvedDevices: List<DeviceInfo> = emptyList(),
        val locationTimeline: List<LocationInfo> = emptyList(),
        val threatScoreBreakdown: ThreatScoreBreakdown? = null,
        val errorMessage: String? = null
    )

    /**
     * Device information for display.
     */
    data class DeviceInfo(
        val id: Long,
        val address: String,
        val name: String?,
        val deviceType: String?,
        val firstSeen: Long,
        val lastSeen: Long,
        val detectionCount: Int
    )

    /**
     * Location information with timestamp.
     */
    data class LocationInfo(
        val id: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val provider: String
    )

    /**
     * Threat score breakdown details.
     */
    data class ThreatScoreBreakdown(
        val locationScore: Double = 0.0,
        val distanceScore: Double = 0.0,
        val timeScore: Double = 0.0,
        val consistencyScore: Double = 0.0,
        val deviceTypeScore: Double = 0.0,
        val totalScore: Double = 0.0
    )

    private val _errorMessage = MutableStateFlow<String?>(null)

    /**
     * Main UI state combining all data sources.
     */
    val uiState: StateFlow<AlertDetailUiState> = flow {
        try {
            // Load alert
            val alert = alertRepository.getAlertById(alertId)
            if (alert == null) {
                emit(
                    AlertDetailUiState(
                        isLoading = false,
                        errorMessage = "Alert not found"
                    )
                )
                return@flow
            }

            // Parse device addresses
            val deviceAddresses = parseDeviceAddresses(alert.deviceAddresses)

            // Load involved devices
            val devices = deviceAddresses.mapNotNull { address ->
                deviceRepository.getDeviceByAddress(address)?.let { device ->
                    DeviceInfo(
                        id = device.id,
                        address = device.address,
                        name = device.name,
                        deviceType = device.deviceType,
                        firstSeen = device.firstSeen,
                        lastSeen = device.lastSeen,
                        detectionCount = device.detectionCount
                    )
                }
            }

            // Parse location IDs
            val locationIds = parseLocationIds(alert.locationIds)

            // Load location timeline
            val allLocations = locationRepository.getAllLocations().firstOrNull() ?: emptyList()
            val locations = allLocations
                .filter { it.id in locationIds }
                .sortedBy { it.timestamp }
                .map { location ->
                    LocationInfo(
                        id = location.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.timestamp,
                        provider = location.provider
                    )
                }

            // Parse threat score breakdown
            val breakdown = parseThreatScoreBreakdown(alert.detectionDetails, alert.threatScore)

            emit(
                AlertDetailUiState(
                    isLoading = false,
                    alert = alert,
                    involvedDevices = devices,
                    locationTimeline = locations,
                    threatScoreBreakdown = breakdown,
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error loading alert details for alertId: $alertId")
            emit(
                AlertDetailUiState(
                    isLoading = false,
                    errorMessage = "Failed to load alert details: ${e.message}"
                )
            )
        }
    }.combine(_errorMessage) { state, error ->
        state.copy(errorMessage = error ?: state.errorMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AlertDetailUiState()
    )

    /**
     * Dismiss this alert.
     */
    fun dismissAlert() {
        viewModelScope.launch {
            try {
                val success = alertRepository.dismissAlert(alertId)
                if (!success) {
                    _errorMessage.value = "Failed to dismiss alert"
                    Timber.e("Failed to dismiss alert: $alertId")
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error dismissing alert: ${e.message}"
                Timber.e(e, "Error dismissing alert: $alertId")
            }
        }
    }

    /**
     * Delete this alert permanently.
     */
    fun deleteAlert(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                val alert = uiState.value.alert
                if (alert != null) {
                    alertRepository.deleteAlert(alert)
                    onDeleted()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting alert: ${e.message}"
                Timber.e(e, "Error deleting alert: $alertId")
            }
        }
    }

    /**
     * Share alert details.
     */
    fun shareAlert(): String {
        val state = uiState.value
        val alert = state.alert ?: return "No alert data available"

        return buildString {
            appendLine("TailBait - Alert Report")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Alert Level: ${alert.alertLevel}")
            appendLine("Threat Score: ${(alert.threatScore * 100).toInt()}%")
            appendLine("Title: ${alert.title}")
            appendLine("Message: ${alert.message}")
            appendLine()
            appendLine("Involved Devices:")
            state.involvedDevices.forEach { device ->
                appendLine("  - ${device.name ?: "Unknown"} (${device.address})")
            }
            appendLine()
            appendLine("Location Timeline:")
            state.locationTimeline.forEach { location ->
                appendLine("  - ${location.timestamp}: (${location.latitude}, ${location.longitude})")
            }
            appendLine()
            appendLine("Generated by TailBait")
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Parse device addresses from JSON array string.
     */
    private fun parseDeviceAddresses(deviceAddresses: String): List<String> {
        return try {
            val cleaned = deviceAddresses.trim()
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                cleaned.substring(1, cleaned.length - 1)
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing device addresses")
            emptyList()
        }
    }

    /**
     * Parse location IDs from JSON array string.
     */
    private fun parseLocationIds(locationIds: String): List<Long> {
        return try {
            val cleaned = locationIds.trim()
            if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
                cleaned.substring(1, cleaned.length - 1)
                    .split(",")
                    .map { it.trim().toLong() }
                    .filter { it > 0 }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing location IDs")
            emptyList()
        }
    }

    /**
     * Parse threat score breakdown from detection details JSON.
     */
    private fun parseThreatScoreBreakdown(
        detectionDetails: String,
        totalScore: Double
    ): ThreatScoreBreakdown {
        return try {
            val json = JSONObject(detectionDetails)
            ThreatScoreBreakdown(
                locationScore = json.optDouble("locationScore", 0.0),
                distanceScore = json.optDouble("distanceScore", 0.0),
                timeScore = json.optDouble("timeScore", 0.0),
                consistencyScore = json.optDouble("consistencyScore", 0.0),
                deviceTypeScore = json.optDouble("deviceTypeScore", 0.0),
                totalScore = totalScore
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing threat score breakdown, using defaults")
            // Return default breakdown if parsing fails
            ThreatScoreBreakdown(
                locationScore = totalScore * 0.3,
                distanceScore = totalScore * 0.25,
                timeScore = totalScore * 0.2,
                consistencyScore = totalScore * 0.15,
                deviceTypeScore = totalScore * 0.1,
                totalScore = totalScore
            )
        }
    }
}
