package com.tailbait.ui.screens.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing permission request state and logic
 */
@HiltViewModel
class PermissionRequestViewModel @Inject constructor(
    val permissionHelper: PermissionHelper
) : ViewModel() {

    // Observe permission states
    val bluetoothPermissionsState: StateFlow<PermissionHelper.PermissionState> =
        permissionHelper.bluetoothPermissionsState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PermissionHelper.PermissionState.UNKNOWN
        )

    val locationPermissionsState: StateFlow<PermissionHelper.PermissionState> =
        permissionHelper.locationPermissionsState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PermissionHelper.PermissionState.UNKNOWN
        )

    val backgroundLocationPermissionState: StateFlow<PermissionHelper.PermissionState> =
        permissionHelper.backgroundLocationPermissionState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PermissionHelper.PermissionState.UNKNOWN
        )

    val notificationPermissionState: StateFlow<PermissionHelper.PermissionState> =
        permissionHelper.notificationPermissionState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PermissionHelper.PermissionState.UNKNOWN
        )

    val allPermissionsGranted: StateFlow<Boolean> =
        permissionHelper.allPermissionsGrantedState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        // Check initial permission states
        checkAllPermissions()
    }

    /**
     * Check all permission states
     */
    fun checkAllPermissions() {
        viewModelScope.launch {
            permissionHelper.checkAllPermissions()
        }
    }

    /**
     * Update permission states after requesting permissions
     */
    fun onPermissionResult() {
        viewModelScope.launch {
            permissionHelper.updatePermissionStates()
        }
    }

    /**
     * Check if essential permissions are granted (excluding background location)
     */
    fun areEssentialPermissionsGranted(): Boolean {
        return permissionHelper.areEssentialPermissionsGranted()
    }

    /**
     * Check if all permissions including optional ones are granted
     */
    fun areAllPermissionsGranted(): Boolean {
        return permissionHelper.areAllPermissionsGranted()
    }
}
