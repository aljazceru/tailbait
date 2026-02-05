package com.tailbait.ui.screens.home

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.BleScannerManager
import com.tailbait.service.TailBaitService
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Home Screen.
 *
 * This ViewModel manages the state and business logic for the main dashboard screen.
 * It provides:
 * - Scanning status (Active/Idle/Error)
 * - Device count (total devices found)
 * - Start/stop tracking controls
 * - Last scan timestamp
 * - Permission status indicators
 * - Navigation to other screens
 *
 * The ViewModel observes multiple data sources and combines them into a single
 * UI state that the Home Screen can display.
 *
 * @property deviceRepository Repository for device data
 * @property settingsRepository Repository for app settings
 * @property bleScannerManager BLE scanner manager for scan operations
 * @property permissionHelper Helper for permission management
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val whitelistRepository: WhitelistRepository,
    private val bleScannerManager: BleScannerManager,
    private val permissionHelper: PermissionHelper,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * UI State for the Home Screen
     */
    data class HomeUiState(
        val isLoading: Boolean = true,
        val isTrackingEnabled: Boolean = false,
        val scanState: ScanStatus = ScanStatus.Idle,
        val totalDevicesFound: Int = 0,
        val knownDevicesCount: Int = 0,
        val unknownDevicesCount: Int = 0,
        val devicesFoundInCurrentScan: Int = 0,
        val lastScanTimestamp: Long? = null,
        val permissionStatus: PermissionStatus = PermissionStatus(),
        val errorMessage: String? = null
    )

    /**
     * Scan status enum
     */
    enum class ScanStatus {
        Idle,
        Scanning,
        Error
    }

    /**
     * Permission status data class
     */
    data class PermissionStatus(
        val bluetoothGranted: Boolean = false,
        val locationGranted: Boolean = false,
        val backgroundLocationGranted: Boolean = false,
        val notificationGranted: Boolean = false,
        val allEssentialGranted: Boolean = false
    )

    // Internal state flows
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        Timber.d("HomeViewModel initialized")
        initializeViewModel()
    }

    /**
     * Initialize the ViewModel by observing data sources
     */
    @OptIn(FlowPreview::class)
    private fun initializeViewModel() {
        viewModelScope.launch {
            // Check permissions initially on IO dispatcher
            withContext(Dispatchers.IO) {
                permissionHelper.checkAllPermissions()
            }

            // Simplified combine using transformLatest to observe all flows
            // Use flowOn(Dispatchers.IO) to move database operations off main thread
            combine(
                settingsRepository.getSettings(),
                bleScannerManager.scanState,
                deviceRepository.getAllDevices(),
                whitelistRepository.getAllWhitelistedDeviceIds(),
                bleScannerManager.lastScanTime
            ) { settings, scanState, devices, whitelistedIds, lastScanTime ->
                // Calculate known/unknown counts
                val whitelistedIdSet = whitelistedIds.toSet()
                val knownCount = devices.count { whitelistedIdSet.contains(it.id) }
                val unknownCount = devices.size - knownCount

                mapOf(
                    "settings" to settings,
                    "scanState" to scanState,
                    "devices" to devices,
                    "knownCount" to knownCount,
                    "unknownCount" to unknownCount,
                    "lastScanTime" to lastScanTime
                )
            }
            .flowOn(Dispatchers.IO) // Process database operations on IO dispatcher
            .map { data ->
                // Extract data from map
                @Suppress("UNCHECKED_CAST")
                val settings = data["settings"] as com.tailbait.data.database.entities.AppSettings
                @Suppress("UNCHECKED_CAST")
                val scanState = data["scanState"] as BleScannerManager.ScanState
                @Suppress("UNCHECKED_CAST")
                val devices = data["devices"] as List<com.tailbait.data.database.entities.ScannedDevice>
                val knownCount = data["knownCount"] as Int
                val unknownCount = data["unknownCount"] as Int
                val managerLastScanTime = data["lastScanTime"] as Long

                // Get current permission states
                val bluetoothPerm = permissionHelper.bluetoothPermissionsState.value
                val locationPerm = permissionHelper.locationPermissionsState.value
                val backgroundLocationPerm = permissionHelper.backgroundLocationPermissionState.value
                val notificationPerm = permissionHelper.notificationPermissionState.value

                // Map scanner state to UI state
                val (status, devicesInScan, error) = when (scanState) {
                    is BleScannerManager.ScanState.Idle -> Triple(ScanStatus.Idle, 0, null)
                    is BleScannerManager.ScanState.Scanning -> Triple(
                        ScanStatus.Scanning,
                        scanState.devicesFound,
                        null
                    )
                    is BleScannerManager.ScanState.Processing -> Triple(
                        ScanStatus.Scanning, // Show as scanning while processing DB operations
                        0,
                        null
                    )
                    is BleScannerManager.ScanState.Error -> Triple(
                        ScanStatus.Error,
                        0,
                        scanState.message
                    )
                }

                // Build permission status
                val permissionStatus = PermissionStatus(
                    bluetoothGranted = bluetoothPerm == PermissionHelper.PermissionState.GRANTED,
                    locationGranted = locationPerm == PermissionHelper.PermissionState.GRANTED,
                    backgroundLocationGranted = backgroundLocationPerm == PermissionHelper.PermissionState.GRANTED,
                    notificationGranted = notificationPerm == PermissionHelper.PermissionState.GRANTED,
                    allEssentialGranted = permissionHelper.areEssentialPermissionsGranted()
                )

                // Calculate last scan timestamp (prefer manager time, fallback to max lastSeen)
                val derivedLastScan = devices.maxOfOrNull { it.lastSeen }
                val lastScanTimestamp = if (managerLastScanTime > 0) managerLastScanTime else derivedLastScan

                HomeUiState(
                    isLoading = false,
                    isTrackingEnabled = settings.isTrackingEnabled,
                    scanState = status,
                    totalDevicesFound = devices.size,
                    knownDevicesCount = knownCount,
                    unknownDevicesCount = unknownCount,
                    devicesFoundInCurrentScan = devicesInScan,
                    lastScanTimestamp = lastScanTimestamp,
                    permissionStatus = permissionStatus,
                    errorMessage = error
                )
            }
            .distinctUntilChanged() // Prevent duplicate emissions
            .debounce(100) // Debounce to prevent rapid UI updates that cause hover event crash
            .catch { e ->
                Timber.e(e, "Error observing data sources")
                emit(
                    HomeUiState(
                        isLoading = false,
                        errorMessage = "Failed to load data: ${e.message}"
                    )
                )
            }
            .collect { state ->
                _uiState.value = state
            }
        }
    }

    // Track if a toggle is already in progress to prevent rapid clicks
    private var isToggleInProgress = false

    /**
     * Toggle tracking on/off
     */
    fun toggleTracking() {
        // Prevent rapid clicks that could cause hover event issues
        if (isToggleInProgress) {
            Timber.d("Toggle already in progress, ignoring")
            return
        }

        viewModelScope.launch {
            isToggleInProgress = true
            try {
                val currentState = _uiState.value
                val newState = !currentState.isTrackingEnabled

                Timber.i("Toggling tracking: $newState")

                // Check if we have essential permissions before enabling tracking
                if (newState && !permissionHelper.areEssentialPermissionsGranted()) {
                    Timber.w("Cannot enable tracking: essential permissions not granted")
                    _uiState.value = currentState.copy(
                        errorMessage = "Please grant all required permissions to enable tracking"
                    )
                    return@launch
                }

                // Update settings on IO dispatcher
                withContext(Dispatchers.IO) {
                    settingsRepository.updateTrackingEnabled(newState)
                }

                // Start or stop the foreground service for continuous scanning
                if (newState) {
                    startTrackingService()
                } else {
                    stopTrackingService()
                }

                Timber.i("Tracking ${if (newState) "enabled" else "disabled"}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle tracking")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to toggle tracking: ${e.message}"
                )
            } finally {
                isToggleInProgress = false
            }
        }
    }

    /**
     * Start the foreground tracking service for continuous BLE scanning
     */
    private fun startTrackingService() {
        try {
            val intent = Intent(context, TailBaitService::class.java).apply {
                action = TailBaitService.ACTION_START_TRACKING
            }
            ContextCompat.startForegroundService(context, intent)
            Timber.i("Started tracking service")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start tracking service")
        }
    }

    /**
     * Stop the foreground tracking service
     */
    private fun stopTrackingService() {
        try {
            val intent = Intent(context, TailBaitService::class.java).apply {
                action = TailBaitService.ACTION_STOP_TRACKING
            }
            context.startService(intent)
            Timber.i("Stopped tracking service")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop tracking service")
        }
    }

    /**
     * Perform a manual scan
     */
    fun performManualScan() {
        viewModelScope.launch {
            try {
                Timber.i("Starting manual scan")

                // Check permissions
                if (!permissionHelper.areEssentialPermissionsGranted()) {
                    Timber.w("Cannot perform manual scan: essential permissions not granted")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Please grant all required permissions to perform a manual scan"
                    )
                    return@launch
                }

                val devicesFound = bleScannerManager.performManualScan()
                Timber.i("Manual scan completed. Found $devicesFound devices")
            } catch (e: Exception) {
                Timber.e(e, "Manual scan failed")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Manual scan failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh permission status
     */
    fun refreshPermissionStatus() {
        viewModelScope.launch {
            Timber.d("Refreshing permission status")
            permissionHelper.checkAllPermissions()
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Get formatted scan status text
     */
    fun getScanStatusText(): String {
        return when (_uiState.value.scanState) {
            ScanStatus.Idle -> "Idle"
            ScanStatus.Scanning -> "Scanning..."
            ScanStatus.Error -> "Error"
        }
    }

    /**
     * Get formatted permission status text
     */
    fun getPermissionStatusText(): String {
        val permStatus = _uiState.value.permissionStatus
        return when {
            permStatus.allEssentialGranted -> "All permissions granted"
            permStatus.bluetoothGranted && permStatus.locationGranted -> "Missing notification permission"
            permStatus.bluetoothGranted -> "Missing location permission"
            permStatus.locationGranted -> "Missing Bluetooth permission"
            else -> "Missing required permissions"
        }
    }

    /**
     * Check if tracking can be enabled
     */
    fun canEnableTracking(): Boolean {
        return _uiState.value.permissionStatus.allEssentialGranted
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("HomeViewModel cleared")
    }
}
