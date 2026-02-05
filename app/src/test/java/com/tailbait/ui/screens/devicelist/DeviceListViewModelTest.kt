package com.tailbait.ui.screens.devicelist

import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.WhitelistRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DeviceListViewModel.
 *
 * Tests the Device List screen functionality including:
 * - Initial state loading
 * - Device list display
 * - Sorting options
 * - Search filtering
 * - Pull-to-refresh
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModelTest {

    // Test dispatcher
    private val testDispatcher = StandardTestDispatcher()

    // Mocked dependencies
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var whitelistRepository: WhitelistRepository

    // System under test
    private lateinit var viewModel: DeviceListViewModel

    // Test data
    private val now = System.currentTimeMillis()

    private val testDevice1 = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Apple Watch",
        firstSeen = now - 20000,
        lastSeen = now - 5000,
        detectionCount = 10,
        deviceType = "WATCH"
    )

    private val testDevice2 = ScannedDevice(
        id = 2L,
        address = "11:22:33:44:55:66",
        name = "Samsung Phone",
        firstSeen = now - 15000,
        lastSeen = now - 2000,
        detectionCount = 5,
        deviceType = "PHONE"
    )

    private val testDevice3 = ScannedDevice(
        id = 3L,
        address = "AA:11:BB:22:CC:33",
        name = "Bluetooth Headphones",
        firstSeen = now - 10000,
        lastSeen = now - 1000,
        detectionCount = 15,
        deviceType = "HEADPHONES"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        deviceRepository = mockk(relaxed = true)
        locationRepository = mockk(relaxed = true)
        whitelistRepository = mockk(relaxed = true)

        // Setup default mock behaviors
        coEvery { deviceRepository.getAllDevices() } returns flowOf(emptyList())
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): DeviceListViewModel {
        return DeviceListViewModel(
            deviceRepository = deviceRepository,
            locationRepository = locationRepository,
            whitelistRepository = whitelistRepository
        )
    }

    @Test
    fun `initial state is loading`() = runTest {
        viewModel = createViewModel()

        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
    }

    @Test
    fun `initial state loads correctly with no devices`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.devices.isEmpty())
        assertTrue(state.filteredDevices.isEmpty())
        assertEquals("", state.searchQuery)
        assertEquals(DeviceListViewModel.SortOption.LAST_SEEN_DESC, state.sortOption)
        assertNull(state.errorMessage)
    }

    @Test
    fun `state loads with devices correctly`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(3, state.devices.size)
        assertEquals(3, state.filteredDevices.size)
    }

    @Test
    fun `devices are sorted by last seen descending by default`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Default sort is LAST_SEEN_DESC, so device3 (most recent) should be first
        assertEquals(testDevice3.id, state.filteredDevices[0].id)
        assertEquals(testDevice2.id, state.filteredDevices[1].id)
        assertEquals(testDevice1.id, state.filteredDevices[2].id)
    }

    @Test
    fun `updateSortOption sorts by name ascending`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSortOption(DeviceListViewModel.SortOption.NAME_ASC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Sorted alphabetically: Apple Watch, Bluetooth Headphones, Samsung Phone
        assertEquals("Apple Watch", state.filteredDevices[0].name)
        assertEquals("Bluetooth Headphones", state.filteredDevices[1].name)
        assertEquals("Samsung Phone", state.filteredDevices[2].name)
    }

    @Test
    fun `updateSortOption sorts by name descending`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSortOption(DeviceListViewModel.SortOption.NAME_DESC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Reverse alphabetically
        assertEquals("Samsung Phone", state.filteredDevices[0].name)
        assertEquals("Bluetooth Headphones", state.filteredDevices[1].name)
        assertEquals("Apple Watch", state.filteredDevices[2].name)
    }

    @Test
    fun `updateSortOption sorts by detection count descending`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSortOption(DeviceListViewModel.SortOption.DETECTION_COUNT_DESC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Sorted by count: device3 (15), device1 (10), device2 (5)
        assertEquals(15, state.filteredDevices[0].detectionCount)
        assertEquals(10, state.filteredDevices[1].detectionCount)
        assertEquals(5, state.filteredDevices[2].detectionCount)
    }

    @Test
    fun `updateSortOption sorts by detection count ascending`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSortOption(DeviceListViewModel.SortOption.DETECTION_COUNT_ASC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Sorted by count: device2 (5), device1 (10), device3 (15)
        assertEquals(5, state.filteredDevices[0].detectionCount)
        assertEquals(10, state.filteredDevices[1].detectionCount)
        assertEquals(15, state.filteredDevices[2].detectionCount)
    }

    @Test
    fun `updateSortOption sorts by first seen descending`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSortOption(DeviceListViewModel.SortOption.FIRST_SEEN_DESC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Most recently first seen: device3, device2, device1
        assertEquals(testDevice3.id, state.filteredDevices[0].id)
        assertEquals(testDevice2.id, state.filteredDevices[1].id)
        assertEquals(testDevice1.id, state.filteredDevices[2].id)
    }

    @Test
    fun `updateSearchQuery filters by device name`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearchQuery("Apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.filteredDevices.size)
        assertEquals("Apple Watch", state.filteredDevices[0].name)
    }

    @Test
    fun `updateSearchQuery filters by device address`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearchQuery("11:22")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should match testDevice2 (11:22:33:44:55:66) and testDevice3 (AA:11:BB:22:CC:33)
        assertEquals(2, state.filteredDevices.size)
    }

    @Test
    fun `updateSearchQuery filters by device type`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearchQuery("PHONE")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.filteredDevices.size)
        assertEquals("Samsung Phone", state.filteredDevices[0].name)
    }

    @Test
    fun `updateSearchQuery is case insensitive`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearchQuery("apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.filteredDevices.size)
        assertEquals("Apple Watch", state.filteredDevices[0].name)
    }

    @Test
    fun `updateSearchQuery with empty string shows all devices`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Apply a filter first
        viewModel.updateSearchQuery("Apple")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredDevices.size)

        // Clear the filter
        viewModel.updateSearchQuery("")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.filteredDevices.size)
    }

    @Test
    fun `clearSearch clears search query`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSearchQuery("Apple")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredDevices.size)

        viewModel.clearSearch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
        assertEquals(3, state.filteredDevices.size)
    }

    @Test
    fun `refreshDevices sets isRefreshing temporarily`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshing)

        viewModel.refreshDevices()
        // Don't advance time yet, should be refreshing
        assertTrue(viewModel.uiState.value.isRefreshing)

        advanceUntilIdle()
        // After completion, should not be refreshing
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `clearError clears error message`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearError()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `getSortOptionDisplayName returns correct names`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Name (A-Z)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.NAME_ASC))
        assertEquals("Name (Z-A)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.NAME_DESC))
        assertEquals("Last Seen (Newest)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.LAST_SEEN_DESC))
        assertEquals("Last Seen (Oldest)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.LAST_SEEN_ASC))
        assertEquals("First Seen (Newest)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.FIRST_SEEN_DESC))
        assertEquals("First Seen (Oldest)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.FIRST_SEEN_ASC))
        assertEquals("Detection Count (High-Low)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.DETECTION_COUNT_DESC))
        assertEquals("Detection Count (Low-High)", viewModel.getSortOptionDisplayName(DeviceListViewModel.SortOption.DETECTION_COUNT_ASC))
    }

    @Test
    fun `devices with null names are displayed as Unknown Device`() = runTest {
        val deviceWithoutName = ScannedDevice(
            id = 4L,
            address = "FF:FF:FF:FF:FF:FF",
            name = null,
            firstSeen = now,
            lastSeen = now,
            detectionCount = 1
        )

        coEvery { deviceRepository.getAllDevices() } returns flowOf(listOf(deviceWithoutName))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.devices.size)
        assertEquals("Unknown Device", state.devices[0].name)
    }

    @Test
    fun `search and sort work together correctly`() = runTest {
        coEvery { deviceRepository.getAllDevices() } returns flowOf(
            listOf(testDevice1, testDevice2, testDevice3)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Search for devices with "e" in the name (all three have it)
        viewModel.updateSearchQuery("e")
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.filteredDevices.size)

        // Now sort by name ascending
        viewModel.updateSortOption(DeviceListViewModel.SortOption.NAME_ASC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should still have 3 devices, sorted alphabetically
        assertEquals(3, state.filteredDevices.size)
        assertEquals("Apple Watch", state.filteredDevices[0].name)
        assertEquals("Bluetooth Headphones", state.filteredDevices[1].name)
        assertEquals("Samsung Phone", state.filteredDevices[2].name)
    }

    // Note: onCleared() is protected and cannot be tested directly.
    // ViewModel cleanup is tested implicitly through other tests.
}
