package com.tailbait.ui

import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.BleScannerManager
import com.tailbait.ui.screens.learnmode.LearnModeViewModel
import com.tailbait.util.Constants
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
 * Unit tests for LearnModeViewModel.
 *
 * Tests the Learn Mode functionality including:
 * - Starting/stopping Learn Mode
 * - Device discovery and selection
 * - Timer countdown
 * - Batch whitelist addition
 * - Error handling
 * - Permission checks
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LearnModeViewModelTest {

    // Test dispatcher
    private val testDispatcher = StandardTestDispatcher()

    // Mocked dependencies
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var whitelistRepository: WhitelistRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var bleScannerManager: BleScannerManager
    private lateinit var permissionHelper: PermissionHelper

    // System under test
    private lateinit var viewModel: LearnModeViewModel

    // StateFlows for mocking
    private val bluetoothPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val locationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val backgroundLocationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)
    private val notificationPermissionFlow = MutableStateFlow(PermissionHelper.PermissionState.GRANTED)

    // Controllable flow for isLearnModeActive
    private lateinit var learnModeActiveFlow: MutableStateFlow<Boolean>

    // Fixed timestamps for deterministic tests
    private val baseTimestamp = 1000000000L

    // Test data
    private val testDevice1 = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Test Device 1",
        firstSeen = baseTimestamp,
        lastSeen = baseTimestamp
    )

    private val testDevice2 = ScannedDevice(
        id = 2L,
        address = "11:22:33:44:55:66",
        name = "Test Device 2",
        firstSeen = baseTimestamp,
        lastSeen = baseTimestamp
    )

    private val testSettings = AppSettings(
        id = 1,
        learnModeActive = false,
        learnModeStartedAt = null
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        deviceRepository = mockk(relaxed = true)
        whitelistRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        bleScannerManager = mockk(relaxed = true)
        permissionHelper = mockk(relaxed = true)

        // Initialize controllable flow
        learnModeActiveFlow = MutableStateFlow(false)

        // Setup default mock behaviors
        coEvery { settingsRepository.getSettingsOnce() } returns testSettings
        every { settingsRepository.isLearnModeActive() } returns learnModeActiveFlow
        every { deviceRepository.getAllDevices() } returns flowOf(emptyList())
        every { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        every { permissionHelper.areEssentialPermissionsGranted() } returns true
        every { permissionHelper.checkAllPermissions() } just Runs
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

    private fun createViewModel(): LearnModeViewModel {
        return LearnModeViewModel(
            deviceRepository = deviceRepository,
            whitelistRepository = whitelistRepository,
            settingsRepository = settingsRepository,
            bleScannerManager = bleScannerManager,
            permissionHelper = permissionHelper
        )
    }

    /**
     * Helper to create a ViewModel with learn mode already active and devices discovered.
     * Sets up mocks so that the scanning loop populates discoveredDevices,
     * then stops learn mode to terminate the loops.
     */
    private fun TestScope.createViewModelWithDevices(
        devices: List<ScannedDevice>,
        whitelistedIds: List<Long> = emptyList()
    ): LearnModeViewModel {
        val learnModeStartTime = baseTimestamp - 10000

        every { deviceRepository.getAllDevices() } returns flowOf(devices)
        every { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(whitelistedIds)
        coEvery { settingsRepository.getSettingsOnce() } returns testSettings.copy(
            learnModeActive = true,
            learnModeStartedAt = learnModeStartTime
        )

        val vm = createViewModel()

        // Trigger learn mode to start scanning, which populates discoveredDevices
        learnModeActiveFlow.value = true
        advanceTimeBy(100)

        // Stop the scanning/timer loops so tests can proceed without infinite loops
        learnModeActiveFlow.value = false
        coEvery { settingsRepository.getSettingsOnce() } returns testSettings
        advanceTimeBy(15000) // advance past scan interval to let loops terminate

        return vm
    }

    @Test
    fun `initial state is correct`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActive)
        assertFalse(state.isScanning)
        assertTrue(state.discoveredDevices.isEmpty())
        assertTrue(state.selectedDeviceIds.isEmpty())
        assertEquals(0L, state.timeRemainingMs)
        assertTrue(state.permissionsGranted)
    }

    @Test
    fun `startLearnMode updates state and settings`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { settingsRepository.startLearnMode() } just Runs

        viewModel.startLearnMode()
        // Just advance enough for the coroutine to start and set state, not the full timer
        advanceTimeBy(100)

        coVerify { settingsRepository.startLearnMode() }
        assertTrue(viewModel.uiState.value.isActive)

        // Stop learn mode to clean up loops
        coEvery { settingsRepository.stopLearnMode() } just Runs
        viewModel.stopLearnMode()
        advanceTimeBy(15000)
    }

    @Test
    fun `startLearnMode fails without permissions`() = runTest {
        every { permissionHelper.areEssentialPermissionsGranted() } returns false

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startLearnMode()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isActive)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("permissions"))
    }

    @Test
    fun `stopLearnMode updates state and settings`() = runTest {
        coEvery { settingsRepository.startLearnMode() } just Runs
        coEvery { settingsRepository.stopLearnMode() } just Runs

        viewModel = createViewModel()
        advanceUntilIdle()

        // Start learn mode
        viewModel.startLearnMode()
        advanceTimeBy(100)

        assertTrue(viewModel.uiState.value.isActive)

        // Stop learn mode
        viewModel.stopLearnMode()
        advanceTimeBy(15000)

        coVerify { settingsRepository.stopLearnMode() }
        assertFalse(viewModel.uiState.value.isActive)
        assertEquals(0L, viewModel.uiState.value.timeRemainingMs)
    }

    @Test
    fun `toggleDeviceSelection adds and removes device`() = runTest {
        viewModel = createViewModelWithDevices(listOf(testDevice1))

        // Verify device is discovered
        assertFalse(viewModel.uiState.value.discoveredDevices.isEmpty())

        // Toggle to select
        viewModel.toggleDeviceSelection(testDevice1.id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedDeviceIds.contains(testDevice1.id))

        // Toggle to deselect
        viewModel.toggleDeviceSelection(testDevice1.id)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.selectedDeviceIds.contains(testDevice1.id))
    }

    @Test
    fun `toggleDeviceSelection does not select already whitelisted device`() = runTest {
        viewModel = createViewModelWithDevices(
            devices = listOf(testDevice1),
            whitelistedIds = listOf(testDevice1.id)
        )

        viewModel.toggleDeviceSelection(testDevice1.id)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.selectedDeviceIds.contains(testDevice1.id))
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `updateDeviceLabel updates label and category`() = runTest {
        viewModel = createViewModelWithDevices(listOf(testDevice1))

        val newLabel = "My Custom Device"
        val newCategory = Constants.WHITELIST_CATEGORY_PARTNER

        viewModel.updateDeviceLabel(testDevice1.id, newLabel, newCategory)
        advanceUntilIdle()

        val device = viewModel.uiState.value.discoveredDevices.find { it.device.id == testDevice1.id }
        assertNotNull(device)
        assertEquals(newLabel, device?.label)
        assertEquals(newCategory, device?.category)
    }

    @Test
    fun `showLabelDialog sets deviceToLabel`() = runTest {
        viewModel = createViewModelWithDevices(listOf(testDevice1))

        viewModel.showLabelDialog(testDevice1.id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showLabelDialog)
        assertNotNull(viewModel.uiState.value.deviceToLabel)
        assertEquals(testDevice1.id, viewModel.uiState.value.deviceToLabel?.device?.id)
    }

    @Test
    fun `dismissLabelDialog clears deviceToLabel`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dismissLabelDialog()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showLabelDialog)
        assertNull(viewModel.uiState.value.deviceToLabel)
    }

    @Test
    fun `finishLearnMode with no selection shows error`() = runTest {
        coEvery { settingsRepository.stopLearnMode() } just Runs

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.finishLearnMode()
        advanceTimeBy(15000) // Let stopLearnMode complete (called inside finishLearnMode)

        assertFalse(viewModel.uiState.value.showConfirmationDialog)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `finishLearnMode with selection shows confirmation dialog`() = runTest {
        viewModel = createViewModelWithDevices(listOf(testDevice1))

        // Select a device
        viewModel.toggleDeviceSelection(testDevice1.id)
        advanceUntilIdle()

        viewModel.finishLearnMode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showConfirmationDialog)
        assertEquals(1, viewModel.uiState.value.devicesToAdd.size)
    }

    @Test
    fun `confirmAddToWhitelist adds devices and shows success`() = runTest {
        coEvery { whitelistRepository.addMultipleToWhitelist(any()) } returns listOf(1L, 2L)
        coEvery { settingsRepository.stopLearnMode() } just Runs

        viewModel = createViewModelWithDevices(listOf(testDevice1, testDevice2))

        // Select devices
        viewModel.toggleDeviceSelection(testDevice1.id)
        viewModel.toggleDeviceSelection(testDevice2.id)
        advanceUntilIdle()

        // Finish and confirm
        viewModel.finishLearnMode()
        advanceUntilIdle()

        viewModel.confirmAddToWhitelist()
        advanceTimeBy(15000) // Let stopLearnMode inside confirmAddToWhitelist complete

        coVerify { whitelistRepository.addMultipleToWhitelist(any()) }
        coVerify { settingsRepository.stopLearnMode() }
        assertTrue(viewModel.uiState.value.showSuccessMessage)
        assertEquals(2, viewModel.uiState.value.devicesAddedCount)
    }

    @Test
    fun `dismissConfirmationDialog clears devicesToAdd`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dismissConfirmationDialog()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showConfirmationDialog)
        assertTrue(viewModel.uiState.value.devicesToAdd.isEmpty())
    }

    @Test
    fun `formatTimeRemaining formats correctly`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("5:00", viewModel.formatTimeRemaining(300000L))
        assertEquals("2:30", viewModel.formatTimeRemaining(150000L))
        assertEquals("0:30", viewModel.formatTimeRemaining(30000L))
        assertEquals("0:00", viewModel.formatTimeRemaining(0L))
    }

    @Test
    fun `clearError clears error message`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearError()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `dismissSuccessMessage clears success state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.dismissSuccessMessage()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSuccessMessage)
    }
}
