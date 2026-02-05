package com.tailbait.ui.screens.home

import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.BleScannerManager
import com.tailbait.util.PermissionHelper
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for HomeViewModel.
 *
 * Tests the Home screen functionality including:
 * - Initial state loading
 * - Tracking toggle
 * - Manual scan trigger
 * - Permission status monitoring
 * - Error handling
 * - Scan state management
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    // Test dispatcher
    private val testDispatcher = StandardTestDispatcher()

    // Mocked dependencies
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var whitelistRepository: WhitelistRepository
    private lateinit var bleScannerManager: BleScannerManager
    private lateinit var permissionHelper: PermissionHelper

    // StateFlows for mocking
    private val scanStateFlow = MutableStateFlow<BleScannerManager.ScanState>(BleScannerManager.ScanState.Idle)
    private val bluetoothPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val locationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val backgroundLocationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val notificationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)

    // System under test
    private lateinit var viewModel: HomeViewModel

    // Test data
    private val testDevice1 = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Test Device 1",
        firstSeen = System.currentTimeMillis() - 10000,
        lastSeen = System.currentTimeMillis()
    )

    private val testDevice2 = ScannedDevice(
        id = 2L,
        address = "11:22:33:44:55:66",
        name = "Test Device 2",
        firstSeen = System.currentTimeMillis() - 5000,
        lastSeen = System.currentTimeMillis()
    )

    private val testSettings = AppSettings(
        id = 1,
        isTrackingEnabled = false
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        deviceRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        whitelistRepository = mockk(relaxed = true)
        bleScannerManager = mockk(relaxed = true)
        permissionHelper = mockk(relaxed = true)

        // Reset state flows
        scanStateFlow.value = BleScannerManager.ScanState.Idle
        bluetoothPermissionFlow.value = PermissionHelper.PermissionState.GRANTED
        locationPermissionFlow.value = PermissionHelper.PermissionState.GRANTED
        backgroundLocationPermissionFlow.value = PermissionHelper.PermissionState.GRANTED
        notificationPermissionFlow.value = PermissionHelper.PermissionState.GRANTED

        // Setup default mock behaviors
        coEvery { settingsRepository.getSettings() } returns flowOf(testSettings)
        coEvery { deviceRepository.getAllDevices() } returns flowOf(emptyList())
        every { bleScannerManager.scanState } returns scanStateFlow
        coEvery { permissionHelper.checkAllPermissions() } just Runs
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns true
        every { permissionHelper.bluetoothPermissionsState } returns bluetoothPermissionFlow
        every { permissionHelper.locationPermissionsState } returns locationPermissionFlow
        every { permissionHelper.backgroundLocationPermissionState } returns backgroundLocationPermissionFlow
        every { permissionHelper.notificationPermissionState } returns notificationPermissionFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            deviceRepository = deviceRepository,
            settingsRepository = settingsRepository,
            whitelistRepository = whitelistRepository,
            bleScannerManager = bleScannerManager,
            permissionHelper = permissionHelper
        )
    }

    @Test
    fun `initial state is loading`() = runTest {
        viewModel = createViewModel()

        // State starts as loading before data is collected
        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
    }

    @Test
    fun `initial state loads correctly with no devices`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isTrackingEnabled)
        assertEquals(HomeViewModel.ScanStatus.Idle, state.scanState)
        assertEquals(0, state.totalDevicesFound)
        assertEquals(0, state.devicesFoundInCurrentScan)
        assertNull(state.lastScanTimestamp)
        assertTrue(state.permissionStatus.allEssentialGranted)
        assertNull(state.errorMessage)
    }

    @Test
    fun `state loads with devices correctly`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(listOf(testDevice1, testDevice2))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.totalDevicesFound)
        assertEquals(testDevice2.lastSeen, state.lastScanTimestamp) // Most recent
    }

    @Test
    fun `toggleTracking enables tracking when disabled`() = runTest {
        coEvery { settingsRepository.updateTrackingEnabled(any()) } just Runs

        viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state: tracking disabled
        assertFalse(viewModel.uiState.value.isTrackingEnabled)

        // Toggle tracking
        viewModel.toggleTracking()
        advanceUntilIdle()

        // Verify that updateTrackingEnabled was called with true
        coVerify { settingsRepository.updateTrackingEnabled(true) }
    }

    @Test
    fun `toggleTracking disables tracking when enabled`() = runTest {
        val enabledSettings = testSettings.copy(isTrackingEnabled = true)
        coEvery { settingsRepository.getSettings() } returns flowOf(enabledSettings)
        coEvery { settingsRepository.updateTrackingEnabled(any()) } just Runs

        viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state: tracking enabled
        assertTrue(viewModel.uiState.value.isTrackingEnabled)

        // Toggle tracking
        viewModel.toggleTracking()
        advanceUntilIdle()

        // Verify that updateTrackingEnabled was called with false
        coVerify { settingsRepository.updateTrackingEnabled(false) }
    }

    @Test
    fun `toggleTracking fails without permissions`() = runTest {
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTracking()
        advanceUntilIdle()

        // Should not enable tracking
        coVerify(exactly = 0) { settingsRepository.updateTrackingEnabled(true) }

        // Should show error
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("permissions"))
    }

    @Test
    fun `performManualScan triggers scan successfully`() = runTest {
        coEvery { bleScannerManager.performManualScan() } returns 5

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.performManualScan()
        advanceUntilIdle()

        coVerify { bleScannerManager.performManualScan() }
    }

    @Test
    fun `performManualScan fails without permissions`() = runTest {
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.performManualScan()
        advanceUntilIdle()

        // Should not trigger scan
        coVerify(exactly = 0) { bleScannerManager.performManualScan() }

        // Should show error
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("permissions"))
    }

    @Test
    fun `scan state updates correctly when scanning`() = runTest {
        scanStateFlow.value = BleScannerManager.ScanState.Scanning(devicesFound = 3)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HomeViewModel.ScanStatus.Scanning, state.scanState)
        assertEquals(3, state.devicesFoundInCurrentScan)
    }

    @Test
    fun `scan state updates correctly on error`() = runTest {
        val errorMessage = "Bluetooth adapter not available"
        scanStateFlow.value = BleScannerManager.ScanState.Error(message = errorMessage)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HomeViewModel.ScanStatus.Error, state.scanState)
        assertEquals(errorMessage, state.errorMessage)
    }

    @Test
    fun `permission status reflects missing bluetooth permission`() = runTest {
        bluetoothPermissionFlow.value = PermissionHelper.PermissionState.DENIED
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.permissionStatus.bluetoothGranted)
        assertFalse(state.permissionStatus.allEssentialGranted)
    }

    @Test
    fun `permission status reflects missing location permission`() = runTest {
        locationPermissionFlow.value = PermissionHelper.PermissionState.DENIED
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.permissionStatus.locationGranted)
        assertFalse(state.permissionStatus.allEssentialGranted)
    }

    @Test
    fun `refreshPermissionStatus triggers permission check`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshPermissionStatus()
        advanceUntilIdle()

        // Should call checkAllPermissions at least twice (once in init, once in refresh)
        coVerify(atLeast = 2) { permissionHelper.checkAllPermissions() }
    }

    @Test
    fun `clearError clears error message`() = runTest {
        val errorMessage = "Test error"
        scanStateFlow.value = BleScannerManager.ScanState.Error(message = errorMessage)

        viewModel = createViewModel()
        advanceUntilIdle()

        // Error should be present
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.clearError()

        // Error should be cleared
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `getScanStatusText returns correct text for Idle`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Idle", viewModel.getScanStatusText())
    }

    @Test
    fun `getScanStatusText returns correct text for Scanning`() = runTest {
        scanStateFlow.value = BleScannerManager.ScanState.Scanning(devicesFound = 5)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Scanning...", viewModel.getScanStatusText())
    }

    @Test
    fun `getScanStatusText returns correct text for Error`() = runTest {
        scanStateFlow.value = BleScannerManager.ScanState.Error(message = "Error")

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Error", viewModel.getScanStatusText())
    }

    @Test
    fun `getPermissionStatusText returns correct text when all granted`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("All permissions granted", viewModel.getPermissionStatusText())
    }

    @Test
    fun `getPermissionStatusText returns correct text when missing bluetooth`() = runTest {
        bluetoothPermissionFlow.value = PermissionHelper.PermissionState.DENIED
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.getPermissionStatusText().contains("Bluetooth"))
    }

    @Test
    fun `canEnableTracking returns true when all permissions granted`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.canEnableTracking())
    }

    @Test
    fun `canEnableTracking returns false when permissions missing`() = runTest {
        bluetoothPermissionFlow.value = PermissionHelper.PermissionState.DENIED
        coEvery { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.canEnableTracking())
    }

    // Note: onCleared() is protected and cannot be tested directly.
    // ViewModel cleanup is tested implicitly through other tests.
}
