package com.tailbait.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import android.content.Intent
import com.tailbait.BuildConfig
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.service.DataExportService
import com.tailbait.util.CsvDataExporter
import com.tailbait.util.FileShareHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for Settings Screen.
 *
 * Manages all app settings including:
 * - Scanning configuration (mode, interval, duration)
 * - Alert preferences (thresholds, notifications)
 * - Location settings (accuracy, background tracking)
 * - Data retention policies
 * - Battery optimization
 * - App information
 *
 * Provides state flows for reactive UI updates and handles
 * all settings modifications through the repository layer.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val alertRepository: AlertRepository,
    private val csvDataExporter: CsvDataExporter,
    private val dataExportService: DataExportService,
    private val fileShareHelper: FileShareHelper
) : ViewModel() {

    /**
     * UI State for Settings Screen.
     */
    data class SettingsUiState(
        val isLoading: Boolean = true,
        val settings: AppSettings = AppSettings(),
        val appVersion: String = "",
        val buildNumber: String = "",
        val totalDevicesCount: Int = 0,
        val totalLocationsCount: Int = 0,
        val totalAlertsCount: Int = 0,
        val errorMessage: String? = null,
        val showClearDataDialog: Boolean = false,
        val dataCleared: Boolean = false,
        val isExporting: Boolean = false,
        val exportResult: CsvDataExporter.ExportResult? = null,
        val isDebugExporting: Boolean = false,
        val debugExportFile: File? = null
    )

    // Settings flow from repository
    private val settingsFlow = settingsRepository.getSettings()

    // Combine all data sources into UI state
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsFlow,
        deviceRepository.getAllDevices(),
        locationRepository.getAllLocations(),
        alertRepository.getAllAlerts(),
        _uiState
    ) { settings, devices, locations, alerts, state ->
        state.copy(
            isLoading = false,
            settings = settings,
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            totalDevicesCount = devices.size,
            totalLocationsCount = locations.size,
            totalAlertsCount = alerts.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    // ==================== Scanning Settings ====================

    /**
     * Update tracking enabled state.
     *
     * @param enabled Whether to enable BLE tracking
     */
    fun updateTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(isTrackingEnabled = enabled)
                )
            } catch (e: Exception) {
                showError("Failed to update tracking setting: ${e.message}")
            }
        }
    }



    /**
     * Update scan interval in minutes.
     *
     * @param minutes Interval between scans (1-30 minutes)
     */
    fun updateScanInterval(minutes: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.updateScanInterval(minutes * 60)
            } catch (e: Exception) {
                showError("Failed to update scan interval: ${e.message}")
            }
        }
    }

    /**
     * Update scan duration in seconds.
     *
     * @param seconds Duration of each scan (10-60 seconds)
     */
    fun updateScanDuration(seconds: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.updateScanDuration(seconds)
            } catch (e: Exception) {
                showError("Failed to update scan duration: ${e.message}")
            }
        }
    }

    // ==================== Alert Settings ====================

    /**
     * Update minimum location count threshold for alerts.
     *
     * @param count Minimum number of distinct locations (2-10)
     */
    fun updateAlertThresholdCount(count: Int) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(alertThresholdCount = count)
                )
            } catch (e: Exception) {
                showError("Failed to update alert threshold: ${e.message}")
            }
        }
    }

    /**
     * Update minimum detection distance for alerts.
     *
     * @param distance Distance in meters (50-500)
     */
    fun updateMinDetectionDistance(distance: Double) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(minDetectionDistanceMeters = distance)
                )
            } catch (e: Exception) {
                showError("Failed to update detection distance: ${e.message}")
            }
        }
    }

    /**
     * Update alert notification enabled state.
     *
     * @param enabled Whether to show notifications for alerts
     */
    fun updateAlertNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(alertNotificationEnabled = enabled)
                )
            } catch (e: Exception) {
                showError("Failed to update notification setting: ${e.message}")
            }
        }
    }

    /**
     * Update alert sound enabled state.
     *
     * @param enabled Whether to play sound for alert notifications
     */
    fun updateAlertSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(alertSoundEnabled = enabled)
                )
            } catch (e: Exception) {
                showError("Failed to update sound setting: ${e.message}")
            }
        }
    }

    /**
     * Update alert vibration enabled state.
     *
     * @param enabled Whether to vibrate for alert notifications
     */
    fun updateAlertVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(alertVibrationEnabled = enabled)
                )
            } catch (e: Exception) {
                showError("Failed to update vibration setting: ${e.message}")
            }
        }
    }

    // ==================== Location Settings ====================

    // Location Change Threshold removed as per simplified scanning requirements


    // ==================== Data Retention Settings ====================

    /**
     * Update data retention period.
     *
     * @param days Number of days to retain data (7, 14, 30, 60, 90, or -1 for never)
     */
    fun updateDataRetentionDays(days: Int) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(dataRetentionDays = days)
                )
            } catch (e: Exception) {
                showError("Failed to update data retention: ${e.message}")
            }
        }
    }

    /**
     * Show confirmation dialog for clearing all data.
     */
    fun showClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = true) }
    }

    /**
     * Hide confirmation dialog for clearing all data.
     */
    fun hideClearDataDialog() {
        _uiState.update { it.copy(showClearDataDialog = false) }
    }

    /**
     * Clear all app data (devices, locations, alerts).
     *
     * This is a destructive operation that cannot be undone.
     * Should only be called after user confirmation.
     */
    fun clearAllData() {
        viewModelScope.launch {
            try {
                // Delete all data from repositories
                deviceRepository.deleteAllDevices()
                locationRepository.deleteAllLocations()
                alertRepository.deleteAllAlerts()

                // Update UI state
                _uiState.update {
                    it.copy(
                        showClearDataDialog = false,
                        dataCleared = true
                    )
                }

                // Reset data cleared flag after a short delay
                kotlinx.coroutines.delay(3000)
                _uiState.update { it.copy(dataCleared = false) }
            } catch (e: Exception) {
                showError("Failed to clear data: ${e.message}")
                _uiState.update { it.copy(showClearDataDialog = false) }
            }
        }
    }

    // ==================== Theme Settings ====================

    /**
     * Update theme mode.
     *
     * @param mode One of: "SYSTEM", "LIGHT", "DARK"
     */
    fun updateThemeMode(mode: String) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(themeMode = mode)
                )
            } catch (e: Exception) {
                showError("Failed to update theme: ${e.message}")
            }
        }
    }

    // ==================== Battery Settings ====================

    /**
     * Update battery optimization enabled state.
     *
     * @param enabled Whether to enable battery saving optimizations
     */
    fun updateBatteryOptimizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettingsOnce()
                settingsRepository.updateSettings(
                    currentSettings.copy(batteryOptimizationEnabled = enabled)
                )
            } catch (e: Exception) {
                showError("Failed to update battery optimization: ${e.message}")
            }
        }
    }

    // ==================== CSV Export ====================

    /**
     * Export all data to CSV files
     */
    fun exportDataToCsv() {

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true, exportResult = null) }

                val result = csvDataExporter.exportAllData()

                if (result.success) {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportResult = result
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportResult = result,
                            errorMessage = result.errorMessage ?: "Export failed"
                        )
                    }
                }

                // Clear export result after showing it for a few seconds
                if (result.success) {
                    kotlinx.coroutines.delay(5000)
                    _uiState.update { it.copy(exportResult = null) }
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Export failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Create share intent for exported CSV data
     */
    suspend fun createShareIntent(): Intent? {
        val exportResult = _uiState.value.exportResult
        return if (exportResult != null && exportResult.success) {
            fileShareHelper.createShareIntent(
                exportResult.exportDirectory ?: "",
                exportResult.files
            )
        } else {
            null
        }
    }

    // ==================== Debug Data Export ====================

    /**
     * Export all data to JSON (Debug mode)
     */
    fun exportDebugData() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isDebugExporting = true, debugExportFile = null) }

                val exportFile = dataExportService.exportDebugData()

                _uiState.update {
                    it.copy(
                        isDebugExporting = false,
                        debugExportFile = exportFile
                    )
                }

                // Clear export result after showing it for a few seconds
                kotlinx.coroutines.delay(5000)
                _uiState.update { it.copy(debugExportFile = null) }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDebugExporting = false,
                        errorMessage = "Debug export failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Create share intent for exported debug data
     */
    suspend fun createDebugShareIntent(): Intent? {
        val file = _uiState.value.debugExportFile
        return if (file != null && file.exists()) {
            fileShareHelper.createShareIntent(
                file.parent ?: "",
                listOf(file.absolutePath)
            )
        } else {
            null
        }
    }

    /**
     * Clear export result
     */
    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null, debugExportFile = null) }
    }

    // ==================== Error Handling ====================

    /**
     * Show error message to user.
     *
     * @param message Error message to display
     */
    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ==================== Preferences Management ====================

    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_FIRST_LAUNCH = "first_launch"

        /**
         * Check if this is the first launch of the app.
         *
         * @param context Application context
         * @return True if first launch, false otherwise
         */
        fun isFirstLaunch(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        }

        /**
         * Mark first launch as complete.
         *
         * @param context Application context
         */
        fun markFirstLaunchComplete(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
    }
}
