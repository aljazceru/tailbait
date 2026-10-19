package com.tailbait.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.repository.PermissionRaceLocationManager
import com.tailbait.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.time.Duration
import android.location.Location as AndroidLocation

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [PermissionRaceLocationManager::class])
class LocationTrackerTest {
    private lateinit var context: Application
    private lateinit var locationManager: LocationManager
    private lateinit var providers: PermissionRaceLocationManager
    private val settingsRepository = mockk<SettingsRepository>()
    private lateinit var tracker: LocationTracker
    private var intervalSeconds = 300

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        grantLocation()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        providers = Shadow.extract(locationManager)
        providers.setProviderEnabled("fused", true)
        providers.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        coEvery { settingsRepository.getSettingsOnce() } answers {
            AppSettings(scanIntervalSeconds = intervalSeconds)
        }
        tracker = LocationTracker(context, settingsRepository)
    }

    @After
    fun tearDown() {
        tracker.stopLocationTracking()
        unmockkAll()
    }

    @Test
    fun startRequiresPreciseLocationPermission() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            val result = tracker.startLocationTracking()

            assertTrue(result.exceptionOrNull() is SecurityException)
            assertFalse(tracker.isTracking())
            assertNoRequests()
            assertTrue(tracker.locationState.value is LocationTracker.LocationState.Error)
            assertNull(tracker.getCurrentLocation())
            assertNull(tracker.getLastKnownLocation())
        }

    @Test
    fun duplicateStartKeepsOneNativeRegistrationAndDeliversLocationUpdates() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            tracker.startLocationTracking().getOrThrow()
            val request = providers.getLocationRequests("fused").single()
            assertEquals(300_000L, request.intervalMillis)
            assertEquals(60_000L, request.minUpdateIntervalMillis)
            assertEquals(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, request.quality)
            assertEquals(LocationTracker.LocationState.Tracking, tracker.locationState.value)
            assertTrue(providers.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
            assertTrue(providers.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())

            val location = location(10f)
            deliver(location)

            assertPublished(location)
            assertTrue(tracker.isTracking())
        }

    @Test
    fun intervalChangeReplacesNativeRequestAndRejectsQueuedOldCallbacks() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            providers.simulateLocation(location(5f))
            intervalSeconds = 600

            tracker.startLocationTracking().getOrThrow()
            assertEquals(600_000L, providers.getLocationRequests("fused").single().intervalMillis)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LocationTracker.LocationState.Tracking, tracker.locationState.value)

            val current = location(20f)
            deliver(current)
            assertPublished(current)
        }

    @Test
    fun explicitRuntimeIntervalOverridesPersistedSettings() =
        runTest {
            tracker.startLocationTracking(120).getOrThrow()
            assertEquals(120_000L, providers.getLocationRequests("fused").single().intervalMillis)

            tracker.startLocationTracking(900).getOrThrow()

            assertEquals(900_000L, providers.getLocationRequests("fused").single().intervalMillis)
        }

    @Test
    fun stopRemovesNativeRegistrationAndRejectsQueuedAndSubsequentLocations() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            providers.simulateLocation(location(5f))

            tracker.stopLocationTracking()
            assertNoRequests()
            shadowOf(Looper.getMainLooper()).idle()
            deliver(location(10f))

            assertFalse(tracker.isTracking())
            assertEquals(LocationTracker.LocationState.Idle, tracker.locationState.value)
            tracker.startLocationTracking().getOrThrow()
            assertEquals(1, providers.getLocationRequests("fused").size)
        }

    @Test
    fun stopDuringSettingsReadPreventsLateRegistration() =
        runTest {
            val settings = CompletableDeferred<AppSettings>()
            coEvery { settingsRepository.getSettingsOnce() } coAnswers { settings.await() }
            val start = async { tracker.startLocationTracking() }
            runCurrent()

            tracker.stopLocationTracking()
            settings.complete(AppSettings())

            assertTrue(start.await().exceptionOrNull() is CancellationException)
            assertNoRequests()
            assertEquals(LocationTracker.LocationState.Idle, tracker.locationState.value)
        }

    @Test
    fun cancellationDuringSettingsReadNeverRegisters() =
        runTest {
            val settings = CompletableDeferred<AppSettings>()
            coEvery { settingsRepository.getSettingsOnce() } coAnswers { settings.await() }
            val start = async { tracker.startLocationTracking() }
            runCurrent()

            start.cancelAndJoin()
            settings.complete(AppSettings())
            runCurrent()

            assertNoRequests()
            assertFalse(tracker.isTracking())
        }

    @Test
    fun permissionRevokedDuringNativeRegistrationClearsStateAndAllowsRetry() =
        runTest {
            providers.revokeOnRegistration = true

            val result = tracker.startLocationTracking()

            assertTrue(result.exceptionOrNull() is SecurityException)
            assertFalse(tracker.isTracking())
            assertEquals(LocationTracker.LocationState.Error("Permission revoked"), tracker.locationState.value)
            assertNoRequests()
            deliver(location(10f))
            assertEquals(LocationTracker.LocationState.Error("Permission revoked"), tracker.locationState.value)

            grantLocation()
            tracker.startLocationTracking().getOrThrow()
            val current = location(20f)
            deliver(current)
            assertPublished(current)
        }

    @Test
    fun permissionRevokedWhileTrackingRemovesNativeRegistration() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            deliver(location(10f))

            assertNoRequests()
            assertFalse(tracker.isTracking())
            assertEquals(LocationTracker.LocationState.Error("Location permission revoked"), tracker.locationState.value)
        }

    @Test
    fun inaccurateNonfiniteAndStaleCallbacksCannotPublishLocations() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            for (accuracy in listOf(201f, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
                deliver(location(accuracy))
                assertEquals(LocationTracker.LocationState.Tracking, tracker.locationState.value)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            }
            tracker.stopLocationTracking()
            tracker.startLocationTracking().getOrThrow()
            deliver(location(10f).apply { time -= 120_001L })
            assertEquals(LocationTracker.LocationState.Tracking, tracker.locationState.value)

            val boundary = location(200f)
            deliver(boundary)
            assertPublished(boundary)
        }

    @Test
    fun missingFusedProviderUsesEnabledNetworkForBatteryConsciousTracking() =
        runTest {
            providers.removeProvider("fused")

            tracker.startLocationTracking().getOrThrow()

            val request = providers.getLocationRequests(LocationManager.NETWORK_PROVIDER).single()
            assertEquals(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, request.quality)
            assertTrue(providers.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
            val location = location(20f, LocationManager.NETWORK_PROVIDER)
            deliver(location)
            assertPublished(location)
        }

    @Test
    fun fusedTrackingWithoutEnabledNetworkRequestsHighAccuracyAndReceivesFixes() =
        runTest {
            providers.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

            tracker.startLocationTracking().getOrThrow()

            val request = providers.getLocationRequests("fused").single()
            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, request.quality)
            assertEquals(300_000L, request.intervalMillis)
            val location = location(10f)
            deliver(location)
            assertPublished(location)
        }

    @Test
    fun repeatedStartReplacesDisabledProviderOrChangedQualityWithoutDuplicatingRequests() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            providers.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

            tracker.startLocationTracking().getOrThrow()

            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, providers.getLocationRequests("fused").single().quality)
            providers.setProviderEnabled("fused", false)
            tracker.startLocationTracking().getOrThrow()
            assertTrue(providers.getLocationRequests("fused").isEmpty())
            assertEquals(1, providers.getLocationRequests(LocationManager.GPS_PROVIDER).size)
            val location = location(10f, LocationManager.GPS_PROVIDER)
            deliver(location)
            assertPublished(location)

            providers.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
            assertTrue(tracker.startLocationTracking().isFailure)
            assertFalse(tracker.isTracking())
            assertNoRequests()
        }

    @Test
    fun GPSOnlyDevicesReceiveContinuousAndOneShotFixes() =
        runTest {
            providers.removeProvider("fused")
            providers.removeProvider(LocationManager.NETWORK_PROVIDER)
            tracker.startLocationTracking().getOrThrow()
            val continuous = providers.getLocationRequests(LocationManager.GPS_PROVIDER).single()
            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, continuous.quality)
            assertEquals(300_000L, continuous.intervalMillis)
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            assertEquals(2, providers.getLocationRequests(LocationManager.GPS_PROVIDER).size)
            val location = location(10f, LocationManager.GPS_PROVIDER)

            deliver(location)
            runCurrent()

            assertPublished(location)
            assertEquals(LocationManager.GPS_PROVIDER, request.await()!!.provider)
            assertEquals(1, providers.getLocationRequests(LocationManager.GPS_PROVIDER).size)
            tracker.stopLocationTracking()
            assertNoRequests()
        }

    @Test
    @Config(sdk = [26])
    fun API26CompatibilityRegistrationDeliversAndStops() =
        runTest {
            providers.removeProvider("fused")
            providers.removeProvider(LocationManager.NETWORK_PROVIDER)
            tracker.startLocationTracking().getOrThrow()
            assertEquals(300_000L, providers.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            val location = location(10f, LocationManager.GPS_PROVIDER)

            deliver(location)

            assertPublished(location)
            tracker.stopLocationTracking()
            assertNoRequests()
        }

    @Test
    fun absentOrDisabledActiveProvidersNeverRegisterPassiveRequests() =
        runTest {
            for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                providers.setProviderEnabled(provider, false)
            }
            assertTrue(tracker.startLocationTracking().isFailure)
            assertNull(tracker.getCurrentLocation())
            assertFalse(tracker.isTracking())
            assertNoRequests()

            for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                providers.removeProvider(provider)
            }
            assertTrue(tracker.startLocationTracking().isFailure)
            assertNull(tracker.getCurrentLocation())
            assertNoRequests()
        }

    @Test
    fun disabledProviderReportsUnavailabilityAndStopStillRemovesItsRequest() =
        runTest {
            tracker.startLocationTracking().getOrThrow()

            providers.setProviderEnabled("fused", false)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(LocationTracker.LocationState.Error("Location unavailable"), tracker.locationState.value)
            tracker.stopLocationTracking()
            assertNoRequests()
        }

    @Test
    @Config(sdk = [26, 33])
    fun cancellingOneShotLocationRemovesNativeRequestAndIgnoresLateResult() =
        runTest {
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLocationRequests("fused").size)

            request.cancelAndJoin()
            assertNoRequests()
            deliver(location(10f))

            assertTrue(request.isCancelled)
        }

    @Test
    @Config(sdk = [26, 33])
    fun stopCancelsPendingOneShotAsWellAsContinuousTracking() =
        runTest {
            tracker.startLocationTracking().getOrThrow()
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            assertEquals(2, providers.getLocationRequests("fused").size)

            tracker.stopLocationTracking()
            assertNoRequests()
            deliver(location(10f))
            runCurrent()

            assertTrue(request.isCancelled)
            assertEquals(LocationTracker.LocationState.Idle, tracker.locationState.value)
        }

    @Test
    fun stopRejectsOneShotCompletedBeforeItsCoroutineResumes() =
        runTest {
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            deliver(location(10f))

            tracker.stopLocationTracking()
            runCurrent()

            assertTrue(request.isCancelled)
            assertNoRequests()
        }

    @Test
    @Config(sdk = [26, 33])
    fun oneShotTimesOutAndRemovesItsNativeRegistration() =
        runTest {
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLocationRequests("fused").size)

            advanceTimeBy(30_000L)
            runCurrent()

            assertNull(request.await())
            assertNoRequests()
        }

    @Test
    fun oneShotHighAccuracyRequestPreservesProviderCoordinatesTimeAndAltitude() =
        runTest {
            providers.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            val registration = providers.getLocationRequests("fused").single()
            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, registration.quality)
            assertEquals(1, registration.maxUpdates)
            assertEquals(30_000L, registration.durationMillis)
            val actual = location(12f).apply { altitude = 50.0 }

            deliver(actual)
            runCurrent()
            val result = request.await()!!

            assertEquals(actual.latitude, result.latitude, 0.0)
            assertEquals(actual.longitude, result.longitude, 0.0)
            assertEquals(actual.time, result.timestamp)
            assertEquals(12f, result.accuracy, 0f)
            assertEquals("fused", result.provider)
            assertEquals(50.0, result.altitude!!, 0.0)
            assertNoRequests()
        }

    @Test
    fun disabledProviderOrStaleOneShotNeverProducesAFabricatedCurrentLocation() =
        runTest {
            val disabled = async { tracker.getCurrentLocation() }
            runCurrent()
            providers.setProviderEnabled("fused", false)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertNull(disabled.await())
            assertNoRequests()

            providers.setProviderEnabled("fused", true)
            val stale = async { tracker.getCurrentLocation() }
            runCurrent()
            deliver(location(10f).apply { time -= 120_001L })
            runCurrent()

            assertNull(stale.await())
            assertNoRequests()
        }

    @Test
    fun oneShotPermissionRaceAndPendingRevocationReturnNoLocation() =
        runTest {
            providers.revokeOnRegistration = true
            assertNull(tracker.getCurrentLocation())
            assertNoRequests()
            grantLocation()
            val request = async { tracker.getCurrentLocation() }
            runCurrent()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            deliver(location(10f))
            runCurrent()

            assertNull(request.await())
            assertNoRequests()
        }

    @Test
    fun lastKnownLocationKeepsNewestNativeProviderAndStaleTimestamp() =
        runTest {
            providers.simulateLocation(location(10f).apply { elapsedRealtimeNanos = 1_000L })
            val actual =
                location(15f, LocationManager.GPS_PROVIDER).apply {
                    time = 1_000L
                    elapsedRealtimeNanos = 2_000L
                }
            providers.simulateLocation(actual)

            val result = tracker.getLastKnownLocation()!!

            assertEquals(actual.latitude, result.latitude, 0.0)
            assertEquals(actual.longitude, result.longitude, 0.0)
            assertEquals(actual.time, result.timestamp)
            assertEquals(LocationManager.GPS_PROVIDER, result.provider)
            assertNull(result.altitude)
            assertNoRequests()
            providers.revokeOnLastKnown = true
            assertNull(tracker.getLastKnownLocation())
        }

    private fun grantLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun assertNoRequests() {
        for (provider in locationManager.allProviders) {
            assertTrue("Unexpected registration for $provider", providers.getLegacyLocationRequests(provider).isEmpty())
        }
    }

    private fun assertPublished(expected: AndroidLocation) {
        val state = tracker.locationState.value
        assertTrue(state is LocationTracker.LocationState.LocationUpdated)
        val actual = (state as LocationTracker.LocationState.LocationUpdated).location
        assertEquals(expected.latitude, actual.latitude, 0.0)
        assertEquals(expected.longitude, actual.longitude, 0.0)
        assertEquals(expected.accuracy, actual.accuracy, 0f)
        assertEquals(expected.time, actual.time)
        assertEquals(expected.provider, actual.provider)
    }

    private fun deliver(location: AndroidLocation) {
        providers.simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun location(
        accuracy: Float,
        provider: String = "fused",
    ) = AndroidLocation(provider).apply {
        latitude = 37.7749
        longitude = -122.4194
        this.accuracy = accuracy
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}
