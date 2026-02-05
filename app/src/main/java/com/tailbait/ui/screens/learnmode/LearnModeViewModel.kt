package com.tailbait.ui.screens.learnmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.BleScannerManager
import com.tailbait.util.Constants
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for Learn Mode screen.
 *
 * Manages the Learn Mode process where users can discover nearby BLE devices
 * and add them to their whitelist in bulk. The Learn Mode runs for a configurable
 * duration (default 5 minutes) and continuously scans for devices.
 *
 * Features:
 * - Start/stop Learn Mode
 * - Continuous device scanning during Learn Mode
 * - Device selection/deselection
 * - Countdown timer
 * - Batch whitelist addition
 * - Device labeling
 * - Duplicate prevention
 *
 * @property deviceRepository Repository for device operations
 * @property whitelistRepository Repository for whitelist operations
 * @property settingsRepository Repository for settings operations
 * @property bleScannerManager BLE scanner manager
 * @property permissionHelper Permission management helper
 */
@HiltViewModel
class LearnModeViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val whitelistRepository: WhitelistRepository,
    private val settingsRepository: SettingsRepository,
    private val bleScannerManager: BleScannerManager,
    private val permissionHelper: PermissionHelper
) : ViewModel() {

    /**
     * UI state for Learn Mode
     */
    data class LearnModeUiState(
        val isActive: Boolean = false,
        val isScanning: Boolean = false,
        val discoveredDevices: List<DeviceSelectionItem> = emptyList(),
        val selectedDeviceIds: Set<Long> = emptySet(),
        val timeRemainingMs: Long = 0,
        val scanProgress: Float = 0f,
        val errorMessage: String? = null,
        val permissionsGranted: Boolean = false,
        val showLabelDialog: Boolean = false,
        val deviceToLabel: DeviceSelectionItem? = null,
        val showConfirmationDialog: Boolean = false,
        val devicesToAdd: List<DeviceSelectionItem> = emptyList(),
        val showSuccessMessage: Boolean = false,
        val devicesAddedCount: Int = 0
    )

    /**
     * Device selection item for UI display
     */
    data class DeviceSelectionItem(
        val device: ScannedDevice,
        val isSelected: Boolean = false,
        val label: String = "",
        val category: String = Constants.WHITELIST_CATEGORY_OWN,
        val isAlreadyWhitelisted: Boolean = false
    )

    // Internal state
    private val _uiState = MutableStateFlow(LearnModeUiState())
    val uiState: StateFlow<LearnModeUiState> = _uiState.asStateFlow()

    // Learn Mode timer job
    private var timerJob: Job? = null
    private var scanJob: Job? = null

    // Learn Mode configuration
    private val learnModeDurationMs = Constants.LEARN_MODE_DEFAULT_DURATION_MS
    private val scanIntervalMs = 10000L // Scan every 10 seconds during Learn Mode

    init {
        Timber.d("LearnModeViewModel initialized")
        checkPermissions()
        observeLearnModeState()
    }

    /**
     * Check if required permissions are granted
     */
    private fun checkPermissions() {
        viewModelScope.launch {
            permissionHelper.checkAllPermissions()
            val granted = permissionHelper.areEssentialPermissionsGranted()
            _uiState.update { it.copy(permissionsGranted = granted) }
        }
    }

    /**
     * Observe Learn Mode state from settings
     */
    private fun observeLearnModeState() {
        viewModelScope.launch {
            settingsRepository.isLearnModeActive().collect { isActive ->
                if (isActive == true && !_uiState.value.isActive) {
                    // Learn Mode was started externally
                    startLearnModeProcess()
                } else if (isActive == false && _uiState.value.isActive) {
                    // Learn Mode was stopped externally
                    stopLearnModeProcess()
                }
            }
        }
    }

    /**
     * Start Learn Mode
     */
    fun startLearnMode() {
        viewModelScope.launch {
            try {
                Timber.i("Starting Learn Mode")

                // Check permissions
                if (!permissionHelper.areEssentialPermissionsGranted()) {
                    _uiState.update {
                        it.copy(errorMessage = "Please grant all required permissions to use Learn Mode")
                    }
                    return@launch
                }

                // Update settings to activate Learn Mode
                settingsRepository.startLearnMode()

                // Start the Learn Mode process
                startLearnModeProcess()

            } catch (e: Exception) {
                Timber.e(e, "Failed to start Learn Mode")
                _uiState.update {
                    it.copy(errorMessage = "Failed to start Learn Mode: ${e.message}")
                }
            }
        }
    }

    /**
     * Internal method to start Learn Mode process
     */
    private fun startLearnModeProcess() {
        Timber.d("Starting Learn Mode process")

        _uiState.update {
            it.copy(
                isActive = true,
                timeRemainingMs = learnModeDurationMs,
                discoveredDevices = emptyList(),
                selectedDeviceIds = emptySet(),
                errorMessage = null
            )
        }

        // Start timer
        startTimer()

        // Start continuous scanning
        startContinuousScanning()
    }

    /**
     * Stop Learn Mode
     */
    fun stopLearnMode() {
        viewModelScope.launch {
            try {
                Timber.i("Stopping Learn Mode")
                settingsRepository.stopLearnMode()
                stopLearnModeProcess()
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop Learn Mode")
                _uiState.update {
                    it.copy(errorMessage = "Failed to stop Learn Mode: ${e.message}")
                }
            }
        }
    }

    /**
     * Internal method to stop Learn Mode process
     */
    private fun stopLearnModeProcess() {
        Timber.d("Stopping Learn Mode process")

        // Cancel timer and scanning
        timerJob?.cancel()
        scanJob?.cancel()

        _uiState.update {
            it.copy(
                isActive = false,
                isScanning = false,
                timeRemainingMs = 0,
                scanProgress = 0f
            )
        }
    }

    /**
     * Start countdown timer
     */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + learnModeDurationMs

            while (System.currentTimeMillis() < endTime) {
                val remaining = endTime - System.currentTimeMillis()
                val progress = 1f - (remaining.toFloat() / learnModeDurationMs.toFloat())

                _uiState.update {
                    it.copy(
                        timeRemainingMs = remaining.coerceAtLeast(0),
                        scanProgress = progress.coerceIn(0f, 1f)
                    )
                }

                delay(1000) // Update every second
            }

            // Timer finished
            Timber.i("Learn Mode timer completed")
            finishLearnMode()
        }
    }

    /**
     * Start continuous scanning during Learn Mode
     */
    private fun startContinuousScanning() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            while (_uiState.value.isActive) {
                try {
                    _uiState.update { it.copy(isScanning = true) }

                    // Perform a manual scan
                    bleScannerManager.performManualScan(
                        scanTriggerType = Constants.SCAN_TRIGGER_MANUAL
                    )

                    // Refresh discovered devices
                    refreshDiscoveredDevices()

                    _uiState.update { it.copy(isScanning = false) }

                    // Wait before next scan
                    delay(scanIntervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Scan error during Learn Mode")
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            errorMessage = "Scan error: ${e.message}"
                        )
                    }
                    delay(5000) // Wait before retrying
                }
            }
        }
    }

    /**
     * Refresh the list of discovered devices
     */
    private suspend fun refreshDiscoveredDevices() {
        try {
            // Get all devices discovered during this session
            val settings = settingsRepository.getSettingsOnce()
            val learnModeStartTime = settings.learnModeStartedAt ?: return

            deviceRepository.getAllDevices().firstOrNull()?.let { devices ->
                // Filter devices discovered since Learn Mode started
                val recentDevices = devices.filter { it.lastSeen >= learnModeStartTime }

                // Check which devices are already whitelisted
                val whitelistedIds = whitelistRepository.getAllWhitelistedDeviceIds()
                    .firstOrNull()?.toSet() ?: emptySet()

                // Build selection items
                val selectionItems = recentDevices.map { device ->
                    val isWhitelisted = whitelistedIds.contains(device.id)
                    val existingItem = _uiState.value.discoveredDevices
                        .find { it.device.id == device.id }

                    DeviceSelectionItem(
                        device = device,
                        isSelected = existingItem?.isSelected ?: false,
                        label = existingItem?.label ?: device.name ?: "Device ${device.address.takeLast(8)}",
                        category = existingItem?.category ?: Constants.WHITELIST_CATEGORY_OWN,
                        isAlreadyWhitelisted = isWhitelisted
                    )
                }

                _uiState.update { it.copy(discoveredDevices = selectionItems) }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh discovered devices")
        }
    }

    /**
     * Toggle device selection
     */
    fun toggleDeviceSelection(deviceId: Long) {
        val currentState = _uiState.value
        val device = currentState.discoveredDevices.find { it.device.id == deviceId } ?: return

        // Don't allow selection of already whitelisted devices
        if (device.isAlreadyWhitelisted) {
            _uiState.update {
                it.copy(errorMessage = "This device is already whitelisted")
            }
            return
        }

        val updatedDevices = currentState.discoveredDevices.map {
            if (it.device.id == deviceId) {
                it.copy(isSelected = !it.isSelected)
            } else {
                it
            }
        }

        val updatedSelectedIds = if (device.isSelected) {
            currentState.selectedDeviceIds - deviceId
        } else {
            currentState.selectedDeviceIds + deviceId
        }

        _uiState.update {
            it.copy(
                discoveredDevices = updatedDevices,
                selectedDeviceIds = updatedSelectedIds
            )
        }
    }

    /**
     * Show device labeling dialog
     */
    fun showLabelDialog(deviceId: Long) {
        val device = _uiState.value.discoveredDevices.find { it.device.id == deviceId } ?: return
        _uiState.update {
            it.copy(
                showLabelDialog = true,
                deviceToLabel = device
            )
        }
    }

    /**
     * Update device label
     */
    fun updateDeviceLabel(deviceId: Long, label: String, category: String) {
        val updatedDevices = _uiState.value.discoveredDevices.map {
            if (it.device.id == deviceId) {
                it.copy(label = label, category = category)
            } else {
                it
            }
        }

        _uiState.update {
            it.copy(
                discoveredDevices = updatedDevices,
                showLabelDialog = false,
                deviceToLabel = null
            )
        }
    }

    /**
     * Dismiss label dialog
     */
    fun dismissLabelDialog() {
        _uiState.update {
            it.copy(
                showLabelDialog = false,
                deviceToLabel = null
            )
        }
    }

    /**
     * Finish Learn Mode and show confirmation dialog
     */
    fun finishLearnMode() {
        val selectedDevices = _uiState.value.discoveredDevices.filter { it.isSelected }

        if (selectedDevices.isEmpty()) {
            // No devices selected, just stop Learn Mode
            stopLearnMode()
            _uiState.update {
                it.copy(errorMessage = "No devices selected to add to whitelist")
            }
        } else {
            // Show confirmation dialog
            _uiState.update {
                it.copy(
                    showConfirmationDialog = true,
                    devicesToAdd = selectedDevices
                )
            }
        }
    }

    /**
     * Confirm and add selected devices to whitelist
     */
    fun confirmAddToWhitelist() {
        viewModelScope.launch {
            try {
                val devicesToAdd = _uiState.value.devicesToAdd

                if (devicesToAdd.isEmpty()) {
                    dismissConfirmationDialog()
                    return@launch
                }

                Timber.i("Adding ${devicesToAdd.size} devices to whitelist")

                // Create whitelist entries
                val entries = devicesToAdd.map { item ->
                    WhitelistEntry(
                        deviceId = item.device.id,
                        label = item.label,
                        category = item.category,
                        addedViaLearnMode = true,
                        notes = "Added via Learn Mode"
                    )
                }

                // Add to whitelist in batch
                whitelistRepository.addMultipleToWhitelist(entries)

                // Stop Learn Mode
                stopLearnMode()

                // Show success message
                _uiState.update {
                    it.copy(
                        showConfirmationDialog = false,
                        showSuccessMessage = true,
                        devicesAddedCount = entries.size,
                        devicesToAdd = emptyList()
                    )
                }

                Timber.i("Successfully added ${entries.size} devices to whitelist")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add devices to whitelist")
                _uiState.update {
                    it.copy(
                        showConfirmationDialog = false,
                        errorMessage = "Failed to add devices to whitelist: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Dismiss confirmation dialog
     */
    fun dismissConfirmationDialog() {
        _uiState.update {
            it.copy(
                showConfirmationDialog = false,
                devicesToAdd = emptyList()
            )
        }
    }

    /**
     * Dismiss success message
     */
    fun dismissSuccessMessage() {
        _uiState.update {
            it.copy(showSuccessMessage = false)
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Format remaining time for display
     */
    fun formatTimeRemaining(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        scanJob?.cancel()
        Timber.d("LearnModeViewModel cleared")
    }
}
