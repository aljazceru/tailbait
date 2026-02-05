package com.tailbait.ui.screens.settings

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.CsvDataExporter
import com.tailbait.util.FileShareHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Unit tests for SettingsViewModel.
 *
 * Tests settings management, data statistics, and user preference handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // Mock dependencies
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var csvDataExporter: CsvDataExporter
    private lateinit var fileShareHelper: FileShareHelper

    // Test data
    private lateinit var testSettings: AppSettings
    private lateinit var testDevices: List<ScannedDevice>
    private lateinit var testLocations: List<Location>
    private lateinit var testAlerts: List<AlertHistory>

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        locationRepository = mockk(relaxed = true)
        alertRepository = mockk(relaxed = true)
        csvDataExporter = mockk(relaxed = true)
        fileShareHelper = mockk(relaxed = true)

        // Create test data
        testSettings = AppSettings(
            id = 1,
            isTrackingEnabled = false,
            trackingMode = "PERIODIC",
            scanIntervalSeconds = 300,
            scanDurationSeconds = 30,
            locationChangeThresholdMeters = 50.0,
            minDetectionDistanceMeters = 100.0,
            alertThresholdCount = 3,
            alertNotificationEnabled = true,
            alertSoundEnabled = true,
            alertVibrationEnabled = true,
            dataRetentionDays = 30,
            batteryOptimizationEnabled = true
        )

        testDevices = listOf(
            ScannedDevice(
                id = 1L,
                address = "AA:BB:CC:DD:EE:01",
                name = "Device 1",
                firstSeen = 1000L,
                lastSeen = 2000L,
                detectionCount = 3
            ),
            ScannedDevice(
                id = 2L,
                address = "AA:BB:CC:DD:EE:02",
                name = "Device 2",
                firstSeen = 1500L,
                lastSeen = 2500L,
                detectionCount = 2
            )
        )

        testLocations = listOf(
            Location(
                id = 1L,
                latitude = 40.7128,
                longitude = -74.0060,
                accuracy = 10f,
                timestamp = 1000L,
                provider = "GPS"
            ),
            Location(
                id = 2L,
                latitude = 40.7589,
                longitude = -73.9851,
                accuracy = 15f,
                timestamp = 1500L,
                provider = "GPS"
            ),
            Location(
                id = 3L,
                latitude = 40.7489,
                longitude = -73.9680,
                accuracy = 12f,
                timestamp = 2000L,
                provider = "GPS"
            )
        )

        testAlerts = listOf(
            AlertHistory(
                id = 1L,
                alertLevel = "HIGH",
                title = "Test Alert 1",
                message = "Device detected",
                timestamp = 1000L,
                deviceAddresses = "[\"AA:BB:CC:DD:EE:01\"]",
                locationIds = "[1,2]",
                threatScore = 0.8,
                detectionDetails = "{}",
                isDismissed = false
            )
        )

        // Setup mock responses
        every { settingsRepository.getSettings() } returns flowOf(testSettings)
        coEvery { settingsRepository.getSettingsOnce() } returns testSettings
        coEvery { settingsRepository.updateSettings(any()) } returns Unit
        coEvery { settingsRepository.updateScanInterval(any()) } returns Unit
        coEvery { settingsRepository.updateScanDuration(any()) } returns Unit

        every { deviceRepository.getAllDevices() } returns flowOf(testDevices)
        every { locationRepository.getAllLocations() } returns flowOf(testLocations)
        every { alertRepository.getAllAlerts() } returns flowOf(testAlerts)

        coEvery { deviceRepository.deleteAllDevices() } returns Unit
        coEvery { locationRepository.deleteAllLocations() } returns Unit
        coEvery { alertRepository.deleteAllAlerts() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)

        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
    }

    @Test
    fun `loads settings and statistics successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(testSettings, state.settings)
        assertEquals(2, state.totalDevicesCount)
        assertEquals(3, state.totalLocationsCount)
        assertEquals(1, state.totalAlertsCount)
        assertNotNull(state.appVersion)
        assertNotNull(state.buildNumber)
    }

    @Test
    fun `updateTrackingMode updates mode successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateTrackingMode("CONTINUOUS")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { it.trackingMode == "CONTINUOUS" }
            )
        }
    }

    @Test
    fun `updateScanInterval converts minutes to seconds`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateScanInterval(10) // 10 minutes
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateScanInterval(600) // 600 seconds
        }
    }

    @Test
    fun `updateScanDuration updates duration successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateScanDuration(45)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateScanDuration(45)
        }
    }

    @Test
    fun `updateAlertThresholdCount updates count successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateAlertThresholdCount(5)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { it.alertThresholdCount == 5 }
            )
        }
    }

    @Test
    fun `updateMinDetectionDistance updates distance successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateMinDetectionDistance(200.0)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { it.minDetectionDistanceMeters == 200.0 }
            )
        }
    }

    @Test
    fun `updateAlertNotificationEnabled updates notification setting`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateAlertNotificationEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { !it.alertNotificationEnabled }
            )
        }
    }

    @Test
    fun `updateAlertSoundEnabled updates sound setting`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateAlertSoundEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { !it.alertSoundEnabled }
            )
        }
    }

    @Test
    fun `updateAlertVibrationEnabled updates vibration setting`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateAlertVibrationEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { !it.alertVibrationEnabled }
            )
        }
    }

    @Test
    fun `updateLocationChangeThreshold updates threshold successfully`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateLocationChangeThreshold(75.0)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { it.locationChangeThresholdMeters == 75.0 }
            )
        }
    }

    @Test
    fun `updateDataRetentionDays updates retention period`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateDataRetentionDays(60)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { it.dataRetentionDays == 60 }
            )
        }
    }

    @Test
    fun `updateBatteryOptimizationEnabled updates battery setting`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateBatteryOptimizationEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            settingsRepository.updateSettings(
                match { !it.batteryOptimizationEnabled }
            )
        }
    }

    @Test
    fun `showClearDataDialog sets dialog visible`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showClearDataDialog()

        assertTrue(viewModel.uiState.value.showClearDataDialog)
    }

    @Test
    fun `hideClearDataDialog hides dialog`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showClearDataDialog()
        assertTrue(viewModel.uiState.value.showClearDataDialog)

        viewModel.hideClearDataDialog()
        assertFalse(viewModel.uiState.value.showClearDataDialog)
    }

    @Test
    fun `clearAllData deletes all data from repositories`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearAllData()
        testDispatcher.scheduler.advanceTimeBy(100)

        coVerify { deviceRepository.deleteAllDevices() }
        coVerify { locationRepository.deleteAllLocations() }
        coVerify { alertRepository.deleteAllAlerts() }
    }

    @Test
    fun `clearAllData sets dataCleared flag temporarily`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearAllData()
        testDispatcher.scheduler.advanceTimeBy(100)

        // dataCleared should be true immediately after clearing
        assertTrue(viewModel.uiState.value.dataCleared)

        // After 3 seconds, it should be reset to false
        testDispatcher.scheduler.advanceTimeBy(3100)
        assertFalse(viewModel.uiState.value.dataCleared)
    }

    @Test
    fun `clearAllData hides dialog after completion`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showClearDataDialog()
        assertTrue(viewModel.uiState.value.showClearDataDialog)

        viewModel.clearAllData()
        testDispatcher.scheduler.advanceTimeBy(100)

        assertFalse(viewModel.uiState.value.showClearDataDialog)
    }

    @Test
    fun `error is shown when settings update fails`() = runTest {
        // Setup mock to throw exception
        coEvery { settingsRepository.getSettingsOnce() } throws RuntimeException("Database error")

        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateTrackingMode("CONTINUOUS")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Failed"))
    }

    @Test
    fun `clearError removes error message`() = runTest {
        coEvery { settingsRepository.getSettingsOnce() } throws RuntimeException("Error")

        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateTrackingMode("CONTINUOUS")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify error exists
        assertNotNull(viewModel.uiState.value.errorMessage)

        // Clear error
        viewModel.clearError()

        // Verify error is cleared
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `error is shown when clearAllData fails`() = runTest {
        // Setup mock to throw exception
        coEvery { deviceRepository.deleteAllDevices() } throws RuntimeException("Delete failed")

        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearAllData()
        testDispatcher.scheduler.advanceTimeBy(100)

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Failed to clear data"))
    }

    @Test
    fun `statistics show correct counts`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Should have 2 devices", 2, state.totalDevicesCount)
        assertEquals("Should have 3 locations", 3, state.totalLocationsCount)
        assertEquals("Should have 1 alert", 1, state.totalAlertsCount)
    }

    @Test
    fun `empty data shows zero counts`() = runTest {
        // Setup mocks to return empty lists
        every { deviceRepository.getAllDevices() } returns flowOf(emptyList())
        every { locationRepository.getAllLocations() } returns flowOf(emptyList())
        every { alertRepository.getAllAlerts() } returns flowOf(emptyList())

        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.totalDevicesCount)
        assertEquals(0, state.totalLocationsCount)
        assertEquals(0, state.totalAlertsCount)
    }

    @Test
    fun `app version and build number are populated`() = runTest {
        viewModel = SettingsViewModel(context, settingsRepository, deviceRepository, locationRepository, alertRepository, csvDataExporter, fileShareHelper)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.appVersion)
        assertNotNull(state.buildNumber)
        assertTrue(state.appVersion.isNotEmpty())
        assertTrue(state.buildNumber.isNotEmpty())
    }
}
