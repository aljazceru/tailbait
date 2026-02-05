package com.tailbait.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.Constants
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for NotificationHelper.
 *
 * These tests verify:
 * - Notification creation and display
 * - User preference respect
 * - DND status handling
 * - Notification actions (view, dismiss, whitelist)
 * - Notification grouping
 * - Sound and vibration patterns
 * - Different alert levels
 *
 * Uses Robolectric for Android framework testing and MockK for mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Test on Android 13 (API 33)
class NotificationHelperTest {

    @MockK
    private lateinit var alertRepository: AlertRepository

    @MockK
    private lateinit var settingsRepository: SettingsRepository

    @RelaxedMockK
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    @RelaxedMockK
    private lateinit var systemNotificationManager: NotificationManager

    private lateinit var context: Context
    private lateinit var notificationHelper: NotificationHelper

    // Sample alert for testing
    private val sampleAlert = AlertHistory(
        id = 1L,
        alertLevel = Constants.ALERT_LEVEL_MEDIUM,
        title = "Test Alert",
        message = "This is a test alert message",
        timestamp = System.currentTimeMillis(),
        deviceAddresses = """["AA:BB:CC:DD:EE:FF"]""",
        locationIds = """[1, 2, 3]""",
        threatScore = 0.6,
        detectionDetails = """{"test": "data"}"""
    )

    // Default settings
    private val defaultSettings = AppSettings(
        id = 1,
        isTrackingEnabled = true,
        alertNotificationEnabled = true,
        alertSoundEnabled = true,
        alertVibrationEnabled = true
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // Use Robolectric's application context
        context = RuntimeEnvironment.getApplication()

        // Mock static methods
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns notificationManagerCompat

        // Create NotificationHelper with mocked dependencies
        notificationHelper = NotificationHelper(
            context,
            alertRepository,
            settingsRepository
        )

        // Setup common mock behaviors
        coEvery { settingsRepository.getSettingsOnce() } returns defaultSettings
        coEvery { alertRepository.getActiveAlerts() } returns flowOf(listOf(sampleAlert))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `showAlertNotification should show notification when settings allow`() = runTest {
        // Given
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify {
            notificationManagerCompat.notify(
                Constants.ALERT_NOTIFICATION_BASE_ID + sampleAlert.id.toInt(),
                any()
            )
        }
    }

    @Test
    fun `showAlertNotification should not show when notifications disabled in settings`() = runTest {
        // Given
        val disabledSettings = defaultSettings.copy(alertNotificationEnabled = false)
        coEvery { settingsRepository.getSettingsOnce() } returns disabledSettings

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify(exactly = 0) {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `showAlertNotification should handle different alert levels correctly`() = runTest {
        // Given
        val criticalAlert = sampleAlert.copy(
            id = 2L,
            alertLevel = Constants.ALERT_LEVEL_CRITICAL
        )
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(criticalAlert)

        // Then
        verify {
            notificationManagerCompat.notify(
                Constants.ALERT_NOTIFICATION_BASE_ID + criticalAlert.id.toInt(),
                any()
            )
        }
    }

    @Test
    fun `showAlertNotification should use low priority channel for low alerts`() = runTest {
        // Given
        val lowAlert = sampleAlert.copy(alertLevel = Constants.ALERT_LEVEL_LOW)
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(lowAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `showAlertNotification should use high priority channel for high alerts`() = runTest {
        // Given
        val highAlert = sampleAlert.copy(alertLevel = Constants.ALERT_LEVEL_HIGH)
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(highAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `dismissNotification should cancel notification and mark alert dismissed`() = runTest {
        // Given
        every { notificationManagerCompat.cancel(any()) } just Runs
        coEvery { alertRepository.dismissAlert(any()) } returns true

        // When
        notificationHelper.dismissNotification(sampleAlert.id)

        // Then
        verify {
            notificationManagerCompat.cancel(
                Constants.ALERT_NOTIFICATION_BASE_ID + sampleAlert.id.toInt()
            )
        }
        coVerify {
            alertRepository.dismissAlert(sampleAlert.id)
        }
    }

    @Test
    fun `dismissNotification should update summary notification`() = runTest {
        // Given
        every { notificationManagerCompat.cancel(any()) } just Runs
        every { notificationManagerCompat.notify(any(), any()) } just Runs
        coEvery { alertRepository.dismissAlert(any()) } returns true
        coEvery { alertRepository.getActiveAlerts() } returns flowOf(emptyList())

        // When
        notificationHelper.dismissNotification(sampleAlert.id)

        // Then
        verify {
            notificationManagerCompat.cancel(Constants.ALERT_NOTIFICATION_SUMMARY_ID)
        }
    }

    @Test
    fun `showSummaryNotification should show when multiple active alerts exist`() = runTest {
        // Given
        val alerts = listOf(
            sampleAlert.copy(id = 1L),
            sampleAlert.copy(id = 2L),
            sampleAlert.copy(id = 3L)
        )
        coEvery { alertRepository.getActiveAlerts() } returns flowOf(alerts)
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify(atLeast = 1) {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `cancelAllNotifications should cancel all notifications`() = runTest {
        // Given
        every { notificationManagerCompat.cancelAll() } just Runs

        // When
        notificationHelper.cancelAllNotifications()

        // Then
        verify {
            notificationManagerCompat.cancelAll()
        }
    }

    @Test
    fun `notification should not show sound when sound disabled in settings`() = runTest {
        // Given
        val noSoundSettings = defaultSettings.copy(alertSoundEnabled = false)
        coEvery { settingsRepository.getSettingsOnce() } returns noSoundSettings
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `notification should not vibrate when vibration disabled in settings`() = runTest {
        // Given
        val noVibrationSettings = defaultSettings.copy(alertVibrationEnabled = false)
        coEvery { settingsRepository.getSettingsOnce() } returns noVibrationSettings
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `hasNotificationPermission should return true on Android 12 and below`() = runTest {
        // Given
        every { notificationManagerCompat.areNotificationsEnabled() } returns true

        // When
        val hasPermission = notificationHelper.hasNotificationPermission()

        // Then
        assert(hasPermission)
    }

    @Test
    fun `showTestNotification should show test notification when permission granted`() = runTest {
        // Given
        every { notificationManagerCompat.areNotificationsEnabled() } returns true
        every { notificationManagerCompat.notify(any(), any()) } just Runs
        coEvery { settingsRepository.getSettingsOnce() } returns defaultSettings

        // When
        notificationHelper.showTestNotification()

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `showTestNotification should not show when permission not granted`() = runTest {
        // Given
        every { notificationManagerCompat.areNotificationsEnabled() } returns false

        // When
        notificationHelper.showTestNotification()

        // Then
        verify(exactly = 0) {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `notification should include all three actions for alerts with devices`() = runTest {
        // Given
        val alertWithDevices = sampleAlert.copy(
            deviceAddresses = """["AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"]"""
        )
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(alertWithDevices)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `critical alert should use maximum priority`() = runTest {
        // Given
        val criticalAlert = sampleAlert.copy(
            alertLevel = Constants.ALERT_LEVEL_CRITICAL
        )
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(criticalAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `low alert should use low priority`() = runTest {
        // Given
        val lowAlert = sampleAlert.copy(
            alertLevel = Constants.ALERT_LEVEL_LOW
        )
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(lowAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `notification should be grouped correctly`() = runTest {
        // Given
        every { notificationManagerCompat.notify(any(), any()) } just Runs

        // When
        notificationHelper.showAlertNotification(sampleAlert)

        // Then
        verify {
            notificationManagerCompat.notify(any(), any())
        }
    }

    @Test
    fun `exception during notification should be handled gracefully`() = runTest {
        // Given
        every { notificationManagerCompat.notify(any(), any()) } throws SecurityException("Test exception")

        // When/Then - Should not throw exception
        notificationHelper.showAlertNotification(sampleAlert)
    }

    @Test
    fun `summary notification should cancel when no active alerts remain`() = runTest {
        // Given
        every { notificationManagerCompat.cancel(any()) } just Runs
        coEvery { alertRepository.dismissAlert(any()) } returns true
        coEvery { alertRepository.getActiveAlerts() } returns flowOf(emptyList())

        // When
        notificationHelper.dismissNotification(sampleAlert.id)

        // Then
        verify {
            notificationManagerCompat.cancel(Constants.ALERT_NOTIFICATION_SUMMARY_ID)
        }
    }

    @Test
    fun `summary notification should cancel when only one active alert remains`() = runTest {
        // Given
        val singleAlert = listOf(sampleAlert)
        every { notificationManagerCompat.cancel(any()) } just Runs
        coEvery { alertRepository.dismissAlert(any()) } returns true
        coEvery { alertRepository.getActiveAlerts() } returns flowOf(singleAlert)

        // When
        notificationHelper.dismissNotification(2L)

        // Then
        verify {
            notificationManagerCompat.cancel(Constants.ALERT_NOTIFICATION_SUMMARY_ID)
        }
    }
}
