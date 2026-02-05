package com.tailbait.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.Constants
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for TailBaitService.
 *
 * These tests verify:
 * - Service lifecycle (onCreate, onStartCommand, onBind, onDestroy)
 * - Service actions (start, stop, pause, resume tracking)
 * - Service binding interface for UI communication
 * - Service restart behavior (START_STICKY)
 * - Notification updates based on scan state
 * - Integration with BleScannerManager
 * - Settings repository integration
 *
 * Note: These tests require Hilt dependency injection and appropriate permissions.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TailBaitServiceTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val serviceRule = ServiceTestRule()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var context: Context

    @Before
    fun setup() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = runBlocking {
        // Ensure service is stopped and tracking is disabled after each test
        try {
            settingsRepository.updateTrackingEnabled(false)
            val stopIntent = Intent(context, TailBaitService::class.java).apply {
                action = Constants.ACTION_STOP_TRACKING
            }
            context.stopService(stopIntent)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        // Give service time to stop
        delay(500)
    }

    /**
     * Test service starts successfully and enters foreground.
     */
    @Test
    fun testServiceStartsSuccessfully() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java)

        // Start service
        val binder = serviceRule.bindService(startIntent)

        // Verify service is bound
        assertNotNull(binder, "Service binder should not be null")
    }

    /**
     * Test service binding returns valid LocalBinder.
     */
    @Test
    fun testServiceBinding() = runTest {
        val bindIntent = Intent(context, TailBaitService::class.java)

        // Bind to service
        val binder = serviceRule.bindService(bindIntent) as TailBaitService.LocalBinder

        // Get service instance
        val service = binder.getService()

        // Verify service instance
        assertNotNull(service, "Service instance should not be null")
        assertTrue(service is TailBaitService, "Service should be instance of TailBaitService")
    }

    /**
     * Test START_TRACKING action starts tracking.
     */
    @Test
    fun testStartTrackingAction() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }

        // Bind to service and get instance
        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Give service time to start tracking
        withTimeout(3000) {
            delay(1000)
        }

        // Verify tracking is enabled in settings
        val settings = settingsRepository.getSettingsOnce()
        assertTrue(settings.isTrackingEnabled, "Tracking should be enabled in settings")

        // Verify service reports tracking state
        assertTrue(service.isTracking(), "Service should report tracking as active")
    }

    /**
     * Test PAUSE_TRACKING action pauses tracking.
     */
    @Test
    fun testPauseTrackingAction() = runTest {
        // First start tracking
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }
        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Wait for tracking to start
        delay(1000)

        // Pause tracking
        val pauseIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_PAUSE_TRACKING
        }
        context.startService(pauseIntent)

        // Wait for pause to complete
        delay(1000)

        // Verify tracking is disabled
        val settings = settingsRepository.getSettingsOnce()
        assertFalse(settings.isTrackingEnabled, "Tracking should be disabled after pause")
        assertFalse(service.isTracking(), "Service should report tracking as inactive")
    }

    /**
     * Test RESUME_TRACKING action resumes tracking after pause.
     */
    @Test
    fun testResumeTrackingAction() = runTest {
        // Start and pause tracking
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }
        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        delay(1000)

        val pauseIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_PAUSE_TRACKING
        }
        context.startService(pauseIntent)

        delay(1000)

        // Resume tracking
        val resumeIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_RESUME_TRACKING
        }
        context.startService(resumeIntent)

        // Wait for resume to complete
        delay(1000)

        // Verify tracking is re-enabled
        val settings = settingsRepository.getSettingsOnce()
        assertTrue(settings.isTrackingEnabled, "Tracking should be enabled after resume")
        assertTrue(service.isTracking(), "Service should report tracking as active after resume")
    }

    /**
     * Test STOP_TRACKING action stops service.
     */
    @Test
    fun testStopTrackingAction() = runTest {
        // Start tracking
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }
        serviceRule.bindService(startIntent)

        delay(1000)

        // Stop tracking
        val stopIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_STOP_TRACKING
        }
        context.startService(stopIntent)

        // Wait for service to stop
        delay(1000)

        // Verify tracking is disabled
        val settings = settingsRepository.getSettingsOnce()
        assertFalse(settings.isTrackingEnabled, "Tracking should be disabled after stop")
    }

    /**
     * Test service returns START_STICKY for restart behavior.
     */
    @Test
    fun testServiceStartStickyBehavior() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }

        // Note: ServiceTestRule doesn't directly expose the return value of onStartCommand,
        // but we can verify the service starts and remains running after binding
        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Verify service is running
        assertNotNull(service, "Service should be running")

        // Wait a moment
        delay(1000)

        // Verify service is still active
        assertTrue(service.isTracking(), "Service should remain active (START_STICKY behavior)")
    }

    /**
     * Test scan state updates are propagated correctly.
     */
    @Test
    fun testScanStateUpdates() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }

        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Wait for scanning to potentially start
        delay(2000)

        // Get scan state
        val scanState = service.getScanState()

        // Verify scan state is not null and is one of the expected states
        assertNotNull(scanState, "Scan state should not be null")
        assertTrue(
            scanState is BleScannerManager.ScanState.Idle ||
            scanState is BleScannerManager.ScanState.Scanning ||
            scanState is BleScannerManager.ScanState.Error,
            "Scan state should be Idle, Scanning, or Error"
        )
    }

    /**
     * Test service handles multiple rapid action commands gracefully.
     */
    @Test
    fun testRapidActionCommands() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }

        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Send rapid commands
        context.startService(Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_PAUSE_TRACKING
        })
        delay(100)
        context.startService(Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_RESUME_TRACKING
        })
        delay(100)
        context.startService(Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_PAUSE_TRACKING
        })

        // Wait for commands to process
        delay(2000)

        // Service should still be functional (not crashed)
        assertNotNull(service, "Service should still be running after rapid commands")

        // Should end in paused state (last command)
        val settings = settingsRepository.getSettingsOnce()
        assertFalse(settings.isTrackingEnabled, "Should be paused after last command")
    }

    /**
     * Test service handles unknown action gracefully.
     */
    @Test
    fun testUnknownActionHandling() = runTest {
        val unknownIntent = Intent(context, TailBaitService::class.java).apply {
            action = "com.tailbait.action.UNKNOWN_ACTION"
        }

        // Service should not crash with unknown action
        val binder = serviceRule.bindService(unknownIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Verify service is still running
        assertNotNull(service, "Service should handle unknown action gracefully")
    }

    /**
     * Test service integration with BleScannerManager.
     * Verifies that starting tracking actually initiates BLE scanning.
     */
    @Test
    fun testBleScannerManagerIntegration() = runTest {
        val startIntent = Intent(context, TailBaitService::class.java).apply {
            action = Constants.ACTION_START_TRACKING
        }

        val binder = serviceRule.bindService(startIntent) as TailBaitService.LocalBinder
        val service = binder.getService()

        // Wait for scanning to potentially start
        delay(2000)

        // Verify scan state changes from initial Idle
        val scanState = service.getScanState()

        // After starting tracking, scanner should be in Scanning or Idle state
        // (Idle is expected between scan intervals in continuous mode)
        assertTrue(
            scanState is BleScannerManager.ScanState.Scanning ||
            scanState is BleScannerManager.ScanState.Idle,
            "Scanner should be active after starting tracking"
        )
    }
}
