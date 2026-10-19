package com.tailbait.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.companion.CompanionIngestor
import com.tailbait.companion.CompanionLinkManager
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.CompanionDevice
import com.tailbait.data.repository.CompanionDeviceRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.ui.screens.settings.SettingsViewModel
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TailBaitServiceTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            *buildList {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.toTypedArray(),
        )

    @get:Rule(order = 2)
    val serviceRule = ServiceTestRule()

    private val dispatcher = StandardTestDispatcher()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = MutableStateFlow(AppSettings(isTrackingEnabled = false))
    private val paired = MutableStateFlow<CompanionDevice?>(null)
    private val linkState = MutableStateFlow<CompanionLinkManager.State>(CompanionLinkManager.State.Idle)
    private val scanState = MutableStateFlow<BleScannerManager.ScanState>(BleScannerManager.ScanState.Idle)
    private val locationState = MutableStateFlow<LocationTracker.LocationState>(LocationTracker.LocationState.Idle)
    private val settingsRepository = mockk<SettingsRepository>()
    private val companionRepository = mockk<CompanionDeviceRepository>()
    private val scanner = mockk<BleScannerManager>(relaxed = true)
    private val location = mockk<LocationTracker>(relaxed = true)
    private val link = mockk<CompanionLinkManager>(relaxed = true)
    private val ingestor = mockk<CompanionIngestor>(relaxed = true)
    private val permissionHelper = mockk<PermissionHelper>()
    private lateinit var context: Context
    private lateinit var service: TailBaitService
    private var collector: Job? = null
    private var viewModel: SettingsViewModel? = null
    private var bound = false
    private var startId = 0

    @Before
    fun setup() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        every { settingsRepository.getSettings() } returns settings
        coEvery { settingsRepository.getSettingsOnce() } answers { settings.value }
        coEvery { settingsRepository.updateTrackingEnabled(any()) } answers {
            settings.value = settings.value.copy(isTrackingEnabled = firstArg())
        }
        coEvery { settingsRepository.updateCompanionEnabled(any()) } answers {
            settings.value = settings.value.copy(companionEnabled = firstArg())
        }
        coEvery { settingsRepository.updateScanInterval(any()) } answers {
            settings.value = settings.value.copy(scanIntervalSeconds = firstArg())
        }
        coEvery { settingsRepository.updateSettings(any()) } answers { settings.value = firstArg() }
        every { companionRepository.getFirst() } returns paired
        coEvery { companionRepository.pair(any(), any()) } answers {
            CompanionDevice(address = firstArg(), name = secondArg()).also { paired.value = it }
        }
        every { scanner.scanState } returns scanState
        coEvery { scanner.performManualScan(any()) } coAnswers {
            scanState.value = BleScannerManager.ScanState.Scanning(0)
            try {
                awaitCancellation()
            } finally {
                scanState.value = BleScannerManager.ScanState.Idle
            }
        }
        every { location.locationState } returns locationState
        coEvery { location.startLocationTracking(any()) } answers { Result.success(Unit) }
        every { link.state } returns linkState
        every { link.stop() } answers { linkState.value = CompanionLinkManager.State.Idle }
        every { permissionHelper.hasLocationPermissions() } returns true
        every { permissionHelper.hasBluetoothPermissions() } returns true
        every { ingestor.attach(any(), any()) } answers {
            collector = secondArg<CoroutineScope>().launch { awaitCancellation() }
        }
        coEvery { ingestor.detach() } coAnswers { collector?.cancelAndJoin() }

        // Binding creates the Android service but does not issue a tracking command.
        val binder = serviceRule.bindService(Intent(context, TailBaitService::class.java)) as TailBaitService.LocalBinder
        service = binder.getService()
        bound = true
        onMain {
            Dispatchers.setMain(dispatcher)
            service.settingsRepository = settingsRepository
            service.companionRepository = companionRepository
            service.bleScannerManager = scanner
            service.locationTracker = location
            service.companionLink = link
            service.companionIngestor = ingestor
            service.permissionHelper = permissionHelper
            service.locationRepository = mockk(relaxed = true)
            service.detectionAlgorithm = mockk<DetectionAlgorithm>(relaxed = true)
            service.alertGenerator = mockk(relaxed = true)
            service.lifecycleScope
            drain()
        }
    }

    @After
    fun tearDown() {
        if (bound) {
            onMain {
                command(TailBaitService.ACTION_STOP_TRACKING)
                viewModel?.viewModelScope?.cancel()
                drain()
            }
            destroyService()
        }
        onMain {
            viewModel?.viewModelScope?.cancel()
            drain()
            Dispatchers.resetMain()
        }
        unmockkAll()
    }

    @Test
    fun stickyNullIntentRestoresPersistedEnabledTracking() =
        onMain {
            settings.value = settings.value.copy(isTrackingEnabled = true)

            assertEquals(Service.START_STICKY, command(null))

            assertTrue(service.isTracking())
            coVerify(exactly = 1) { location.startLocationTracking(300) }
            coVerify(exactly = 1) { scanner.performManualScan(any()) }
            coVerify(exactly = 0) { settingsRepository.updateTrackingEnabled(any()) }
            assertNotNull(scheduledScan())
        }

    @Test
    fun stickyNullIntentDoesNotReenableDisabledTracking() =
        onMain {
            command(null)

            assertFalse(service.isTracking())
            assertFalse(settings.value.isTrackingEnabled)
            coVerify(exactly = 0) { location.startLocationTracking(any()) }
            coVerify(exactly = 0) { scanner.performManualScan(any()) }
            coVerify(exactly = 0) { settingsRepository.updateTrackingEnabled(any()) }
            assertNull(scheduledScan())
        }

    @Test
    fun coldAlarmRestoresOnlyEnabledTracking() =
        onMain {
            command(TailBaitService.ACTION_TRIGGER_SCAN)
            assertFalse(service.isTracking())
            coVerify(exactly = 0) { scanner.performManualScan(any()) }

            settings.value = settings.value.copy(isTrackingEnabled = true)
            command(TailBaitService.ACTION_TRIGGER_SCAN)
            assertTrue(service.isTracking())
            coVerify(exactly = 1) { scanner.performManualScan(any()) }
        }

    @Test
    fun restoredTrackingRequiresBothLocationAndBluetoothPermissions() =
        onMain {
            settings.value = settings.value.copy(isTrackingEnabled = true)
            every { permissionHelper.hasLocationPermissions() } returns false
            command(null)
            assertFalse(service.isTracking())
            every { permissionHelper.hasLocationPermissions() } returns true
            every { permissionHelper.hasBluetoothPermissions() } returns false
            command(TailBaitService.ACTION_TRIGGER_SCAN)

            assertFalse(service.isTracking())
            coVerify(exactly = 0) { location.startLocationTracking(any()) }
            coVerify(exactly = 0) { scanner.performManualScan(any()) }
            assertNull(scheduledScan())
        }

    @Test
    fun pauseWaitsForCleanupBeforeQueuedResumeAndReleasesEveryOwner() =
        onMain {
            paired.value = CompanionDevice(address = "AA:BB:CC:DD:EE:FF")
            settings.value = settings.value.copy(companionEnabled = true)
            command(TailBaitService.ACTION_START_TRACKING)
            val oldCollector = collector!!
            val cleanup = CompletableDeferred<Unit>()
            coEvery { scanner.stopAndSaveScanResults() } coAnswers { cleanup.await() }

            command(TailBaitService.ACTION_PAUSE_TRACKING)
            command(TailBaitService.ACTION_RESUME_TRACKING)
            assertFalse(service.isTracking())
            assertNull(scheduledScan())
            assertFalse(wakeLock().isHeld)
            verify { location.stopLocationTracking() }
            verify { link.stop() }
            coVerify(exactly = 1) { location.startLocationTracking(any()) }

            cleanup.complete(Unit)
            drain()
            assertTrue(oldCollector.isCancelled)
            assertTrue(service.isTracking())
            assertTrue(collector!!.isActive)
            coVerify(exactly = 2) { location.startLocationTracking(any()) }
            command(TailBaitService.ACTION_PAUSE_TRACKING)
            assertFalse(service.isTracking())
            assertTrue(collector!!.isCancelled)
            assertEquals(BleScannerManager.ScanState.Idle, service.getScanState())
            assertNull(scheduledScan())
            assertFalse(wakeLock().isHeld)
        }

    @Test
    fun destructionCancelsLocationScanCollectorAlarmAndWakeLock() {
        lateinit var activeCollector: Job
        lateinit var serviceJob: Job
        onMain {
            paired.value = CompanionDevice(address = "AA:BB:CC:DD:EE:FF")
            settings.value = settings.value.copy(companionEnabled = true)
            command(TailBaitService.ACTION_START_TRACKING)
            activeCollector = collector!!
            serviceJob = service.lifecycleScope.coroutineContext[Job]!!
            assertTrue(activeCollector.isActive)
            assertTrue(wakeLock().isHeld)
        }

        destroyService()

        onMain {
            drain()
            assertTrue(serviceJob.isCancelled)
            assertTrue(activeCollector.isCancelled)
            assertFalse(service.isTracking())
            assertEquals(BleScannerManager.ScanState.Idle, service.getScanState())
            verify { location.stopLocationTracking() }
            verify { scanner.stopScanning() }
            verify { link.stop() }
            assertNull(scheduledScan())
            assertFalse(wakeLock().isHeld)
        }
    }

    @Test
    fun liveIntervalReplacesAlarmAndLocationWithoutRestartingOtherRadios() =
        onMain {
            command(TailBaitService.ACTION_START_TRACKING)
            val previousAlarm = scheduledScan()!!
            viewModel = createSettingsViewModel()
            drain()

            viewModel!!.updateScanInterval(10)
            drain()

            coVerify(exactly = 1) { location.startLocationTracking(600) }
            val replacement = scheduledScan()
            assertNotNull(replacement)
            assertNotEquals(previousAlarm, replacement)
            assertTrue(service.isTracking())
            coVerify(exactly = 1) { scanner.performManualScan(any()) }
            viewModel!!.updateThemeMode("DARK")
            viewModel!!.updateAlertSoundEnabled(false)
            drain()
            coVerify(exactly = 2) { location.startLocationTracking(any()) }
            coVerify(exactly = 1) { scanner.performManualScan(any()) }
            assertEquals(replacement, scheduledScan())
        }

    @Test
    fun settingsToggleDrivesServiceAndCompanionDisableDetachesCollector() =
        onMain {
            paired.value = CompanionDevice(address = "AA:BB:CC:DD:EE:FF")
            viewModel = createSettingsViewModel()
            viewModel!!.updateTrackingEnabled(true)
            drain()
            assertTrue(service.isTracking())

            viewModel!!.updateCompanionEnabled(true)
            drain()
            val activeCollector = collector!!
            assertTrue(activeCollector.isActive)
            verify { link.start("AA:BB:CC:DD:EE:FF") }

            viewModel!!.updateCompanionEnabled(false)
            drain()
            assertTrue(activeCollector.isCancelled)
            assertEquals(CompanionLinkManager.State.Idle, linkState.value)
            assertTrue(service.isTracking())
            viewModel!!.updateTrackingEnabled(false)
            drain()
            assertFalse(service.isTracking())
            assertFalse(settings.value.isTrackingEnabled)
            assertNull(scheduledScan())
            assertFalse(wakeLock().isHeld)
        }

    @Test
    fun pairingWhileDisabledCannotCreateIndependentIngestionOrConnection() =
        onMain {
            viewModel = createSettingsViewModel()
            viewModel!!.pairCompanion("AA:BB:CC:DD:EE:FF", "Companion")
            drain()

            assertFalse(service.isTracking())
            verify(exactly = 0) { ingestor.attach(any(), any()) }
            verify(exactly = 0) { link.start(any()) }
            verify { link.stopScanningForCompanions() }
        }

    @Test
    fun phoneFallbackRunsWhileCompanionIsConnectingButNotWhenReady() =
        onMain {
            paired.value = CompanionDevice(address = "AA:BB:CC:DD:EE:FF")
            settings.value = settings.value.copy(companionEnabled = true)
            every { link.start(any()) } answers {
                linkState.value = CompanionLinkManager.State.Connecting(firstArg())
            }
            coEvery { scanner.performManualScan(any()) } returns 0
            command(TailBaitService.ACTION_START_TRACKING)
            coVerify(exactly = 1) { scanner.performManualScan(any()) }

            linkState.value = CompanionLinkManager.State.Connected("AA:BB:CC:DD:EE:FF")
            command(TailBaitService.ACTION_TRIGGER_SCAN)
            coVerify(exactly = 1) { scanner.performManualScan(any()) }
            assertNotNull(scheduledScan())
        }

    private fun createSettingsViewModel(): SettingsViewModel {
        val serviceContext =
            object : ContextWrapper(context) {
                override fun startService(intent: Intent): ComponentName {
                    service.onStartCommand(intent, 0, ++startId)
                    return ComponentName(context, TailBaitService::class.java)
                }

                override fun startForegroundService(intent: Intent): ComponentName = startService(intent)
            }
        return SettingsViewModel(
            serviceContext,
            settingsRepository,
            mockk { every { getAllDevices() } returns flowOf(emptyList()) },
            mockk { every { getAllLocations() } returns flowOf(emptyList()) },
            mockk { every { getAllAlerts() } returns flowOf(emptyList()) },
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            companionRepository,
            ingestor,
            link,
        )
    }

    private fun command(action: String?): Int {
        val result = service.onStartCommand(action?.let { Intent(context, TailBaitService::class.java).setAction(it) }, 0, ++startId)
        drain()
        return result
    }

    private fun drain() = dispatcher.scheduler.runCurrent()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun scheduledScan(): PendingIntent? {
        val intent = Intent(context, TailBaitService::class.java).setAction(TailBaitService.ACTION_TRIGGER_SCAN)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 1004, intent, flags)
        } else {
            PendingIntent.getService(context, 1004, intent, flags)
        }
    }

    private fun wakeLock(): PowerManager.WakeLock =
        TailBaitService::class.java.getDeclaredField("wakeLock").let {
            it.isAccessible = true
            it.get(service) as PowerManager.WakeLock
        }

    private fun destroyService() {
        val destroyed = CountDownLatch(1)
        onMain {
            service.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
                },
            )
        }
        context.stopService(Intent(context, TailBaitService::class.java))
        serviceRule.unbindService()
        bound = false
        assertTrue("Service must deliver ON_DESTROY", destroyed.await(5, TimeUnit.SECONDS))
    }
}
