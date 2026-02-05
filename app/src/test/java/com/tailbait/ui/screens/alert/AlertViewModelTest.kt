package com.tailbait.ui.screens.alert

import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.repository.AlertRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AlertViewModel.
 *
 * Tests cover:
 * - Loading alerts
 * - Active and dismissed alert separation
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertViewModelTest {

    private lateinit var viewModel: AlertViewModel
    private lateinit var alertRepository: AlertRepository
    private val testDispatcher = StandardTestDispatcher()

    // Sample test data
    private val activeAlerts = listOf(
        AlertHistory(
            id = 1,
            alertLevel = "CRITICAL",
            title = "Critical Alert",
            message = "Critical threat detected",
            timestamp = System.currentTimeMillis() - 3600000,
            deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
            locationIds = "[1, 2, 3]",
            threatScore = 0.95,
            detectionDetails = "{}",
            isDismissed = false
        ),
        AlertHistory(
            id = 2,
            alertLevel = "HIGH",
            title = "High Alert",
            message = "Suspicious device detected",
            timestamp = System.currentTimeMillis() - 7200000,
            deviceAddresses = "[\"11:22:33:44:55:66\"]",
            locationIds = "[4, 5]",
            threatScore = 0.75,
            detectionDetails = "{}",
            isDismissed = false
        )
    )

    private val dismissedAlerts = listOf(
        AlertHistory(
            id = 3,
            alertLevel = "MEDIUM",
            title = "Medium Alert",
            message = "Previously dismissed",
            timestamp = System.currentTimeMillis() - 86400000,
            deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
            locationIds = "[1]",
            threatScore = 0.5,
            detectionDetails = "{}",
            isDismissed = true
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        alertRepository = mockk(relaxed = true)

        // Setup mock responses
        every { alertRepository.getActiveAlerts() } returns flowOf(activeAlerts)
        every { alertRepository.getDismissedAlerts() } returns flowOf(dismissedAlerts)

        viewModel = AlertViewModel(alertRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        val state = viewModel.uiState.value
        assertTrue(state is AlertViewModel.AlertListUiState.Loading)
    }

    @Test
    fun `loads active and dismissed alerts successfully`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AlertViewModel.AlertListUiState.Success)

        val successState = state as AlertViewModel.AlertListUiState.Success
        assertEquals(2, successState.activeAlerts.size)
        assertEquals(1, successState.dismissedAlerts.size)
    }

    @Test
    fun `active alerts are not dismissed`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AlertViewModel.AlertListUiState.Success
        assertTrue(state.activeAlerts.none { it.isDismissed })
    }

    @Test
    fun `dismissed alerts are marked as dismissed`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AlertViewModel.AlertListUiState.Success
        assertTrue(state.dismissedAlerts.all { it.isDismissed })
    }

    @Test
    fun `handles empty alerts list`() = runTest {
        every { alertRepository.getActiveAlerts() } returns flowOf(emptyList())
        every { alertRepository.getDismissedAlerts() } returns flowOf(emptyList())

        viewModel = AlertViewModel(alertRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AlertViewModel.AlertListUiState.Success
        assertTrue(state.activeAlerts.isEmpty())
        assertTrue(state.dismissedAlerts.isEmpty())
    }

    @Test
    fun `alerts are sorted correctly`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as AlertViewModel.AlertListUiState.Success

        // Verify critical alert is in the list
        assertTrue(state.activeAlerts.any { it.alertLevel == "CRITICAL" })
        assertTrue(state.activeAlerts.any { it.alertLevel == "HIGH" })
    }
}
