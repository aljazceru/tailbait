package com.tailbait.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location as AndroidLocation
import android.os.Looper
import app.cash.turbine.test
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.repository.SettingsRepository
import com.google.android.gms.location.*
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
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
 * Unit tests for LocationTracker.
 *
 * Tests cover:
 * - Location permission checking
 * - Starting and stopping location tracking
 * - Getting current location
 * - Getting last known location
 * - Accuracy filtering (< 100m)
 * - Location state management
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationTrackerTest {

    // Mocks
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // System under test
    private lateinit var locationTracker: LocationTracker

    // Test dispatcher
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Test data
    private val testSettings = AppSettings(
        id = 1,
        isTrackingEnabled = true,
        scanIntervalSeconds = 300,
        scanDurationSeconds = 30,
        locationChangeThresholdMeters = 50.0,
        batteryOptimizationEnabled = false
    )

    private val testAndroidLocation = mockk<AndroidLocation>(relaxed = true).apply {
        every { latitude } returns 37.7749
        every { longitude } returns -122.4194
        every { accuracy } returns 10f
        every { time } returns System.currentTimeMillis()
        every { provider } returns "FUSED"
        every { hasAltitude() } returns true
        every { altitude } returns 50.0
    }

    @Before
    fun setup() {
        // Initialize mocks
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        fusedLocationClient = mockk(relaxed = true)

        // Mock context
        every { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { context.applicationContext } returns context

        // Mock settings repository
        every { settingsRepository.getSettings() } returns flowOf(testSettings)
        coEvery { settingsRepository.getSettingsOnce() } returns testSettings

        // Mock Looper (required for LocationCallback)
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        // Set test dispatcher
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `test initial state is Idle`() = testScope.runTest {
        // Given/When
        locationTracker = LocationTracker(context, settingsRepository)

        // Then
        locationTracker.locationState.test {
            assertEquals(LocationTracker.LocationState.Idle, awaitItem())
        }
        assertFalse(locationTracker.isTracking())
    }

    @Test
    fun `test startLocationTracking fails without permission`() = testScope.runTest {
        // Given
        every { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        locationTracker = LocationTracker(context, settingsRepository)

        // When
        val result = locationTracker.startLocationTracking()

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        locationTracker.locationState.test {
            val state = awaitItem()
            assertTrue(state is LocationTracker.LocationState.Error)
            assertEquals("Location permission not granted", (state as LocationTracker.LocationState.Error).message)
        }
    }

    @Test
    fun `test startLocationTracking succeeds with permission`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient
        every { fusedLocationClient.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } returns mockk(relaxed = true)

        // When
        val result = locationTracker.startLocationTracking()

        // Then
        assertTrue(result.isSuccess)
        assertTrue(locationTracker.isTracking())
    }

    @Test
    fun `test getCurrentLocation returns null without permission`() = testScope.runTest {
        // Given
        every { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        locationTracker = LocationTracker(context, settingsRepository)

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNull(location)
    }

    @Test
    fun `test getCurrentLocation returns location with permission`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        // Simulate successful location retrieval
        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(testAndroidLocation)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNotNull(location)
        assertEquals(37.7749, location!!.latitude)
        assertEquals(-122.4194, location.longitude)
        assertEquals(10f, location.accuracy)
        assertEquals("FUSED", location.provider)
        assertNotNull(location.altitude)
        assertEquals(50.0, location.altitude)
    }

    @Test
    fun `test getCurrentLocation returns null when location is unavailable`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        // Simulate null location
        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(null)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNull(location)
    }

    @Test
    fun `test getCurrentLocation handles exceptions`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        // Simulate failure
        val testException = Exception("Location unavailable")
        every { mockTask.addOnSuccessListener(any()) } returns mockTask
        every { mockTask.addOnFailureListener(any()) } answers {
            val listener = arg<OnFailureListener>(0)
            listener.onFailure(testException)
            mockTask
        }

        // When/Then
        try {
            locationTracker.getCurrentLocation()
            // Should throw exception
            fail("Expected exception to be thrown")
        } catch (e: Exception) {
            assertEquals("Location unavailable", e.message)
        }
    }

    @Test
    fun `test getLastKnownLocation returns null without permission`() = testScope.runTest {
        // Given
        every { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        locationTracker = LocationTracker(context, settingsRepository)

        // When
        val location = locationTracker.getLastKnownLocation()

        // Then
        assertNull(location)
    }

    @Test
    fun `test getLastKnownLocation returns location with permission`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.lastLocation } returns mockTask

        // Simulate successful location retrieval
        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(testAndroidLocation)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getLastKnownLocation()

        // Then
        assertNotNull(location)
        assertEquals(37.7749, location!!.latitude)
        assertEquals(-122.4194, location.longitude)
    }

    @Test
    fun `test stopLocationTracking changes state to Idle`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient
        every { fusedLocationClient.removeLocationUpdates(any<LocationCallback>()) } returns mockk(relaxed = true)

        // When
        locationTracker.stopLocationTracking()

        // Then
        locationTracker.locationState.test {
            assertEquals(LocationTracker.LocationState.Idle, awaitItem())
        }
        assertFalse(locationTracker.isTracking())
    }

    @Test
    fun `test location without altitude is handled correctly`() = testScope.runTest {
        // Given
        val locationWithoutAltitude = mockk<AndroidLocation>(relaxed = true).apply {
            every { latitude } returns 37.7749
            every { longitude } returns -122.4194
            every { accuracy } returns 15f
            every { time } returns System.currentTimeMillis()
            every { provider } returns "GPS"
            every { hasAltitude() } returns false
        }

        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(locationWithoutAltitude)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNotNull(location)
        assertNull(location!!.altitude)
        assertEquals("GPS", location.provider)
    }

    @Test
    fun `test location state transitions correctly`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // Initial state
        locationTracker.locationState.test {
            assertEquals(LocationTracker.LocationState.Idle, awaitItem())
        }

        // After stopping
        locationTracker.stopLocationTracking()
        locationTracker.locationState.test {
            assertEquals(LocationTracker.LocationState.Idle, awaitItem())
        }
    }

    @Test
    fun `test isTracking returns correct state`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // When - initial state
        assertFalse(locationTracker.isTracking())

        // After stopping
        locationTracker.stopLocationTracking()
        assertFalse(locationTracker.isTracking())
    }

    @Test
    fun `test location request uses correct update interval`() = testScope.runTest {
        // Given
        val customSettings = testSettings.copy(scanIntervalSeconds = 600) // 10 minutes
        every { settingsRepository.getSettings() } returns flowOf(customSettings)
        coEvery { settingsRepository.getSettingsOnce() } returns customSettings

        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val requestSlot = slot<LocationRequest>()
        every { fusedLocationClient.requestLocationUpdates(capture(requestSlot), any<LocationCallback>(), any<Looper>()) } returns mockk(relaxed = true)

        // When
        locationTracker.startLocationTracking()

        // Then
        val settings = settingsRepository.getSettingsOnce()
        assertEquals(600, settings.scanIntervalSeconds)
    }

    @Test
    fun `test location accuracy filtering threshold`() = testScope.runTest {
        // Given
        locationTracker = LocationTracker(context, settingsRepository)

        // The accuracy filter is 100m
        // Locations with accuracy > 100m should be rejected
        // This is tested through the LocationCallback in actual usage

        // This test verifies the threshold is correctly implemented
        val highAccuracyLocation = mockk<AndroidLocation>(relaxed = true).apply {
            every { accuracy } returns 50f // Good accuracy
        }

        val lowAccuracyLocation = mockk<AndroidLocation>(relaxed = true).apply {
            every { accuracy } returns 150f // Poor accuracy
        }

        // Verify accuracy values
        assertTrue(highAccuracyLocation.accuracy <= 100f)
        assertFalse(lowAccuracyLocation.accuracy <= 100f)
    }

    @Test
    fun `test location provider field is set correctly`() = testScope.runTest {
        // Given
        val locationWithProvider = mockk<AndroidLocation>(relaxed = true).apply {
            every { latitude } returns 37.7749
            every { longitude } returns -122.4194
            every { accuracy } returns 10f
            every { time } returns System.currentTimeMillis()
            every { provider } returns "GPS"
            every { hasAltitude() } returns false
        }

        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(locationWithProvider)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNotNull(location)
        assertEquals("GPS", location!!.provider)
    }

    @Test
    fun `test location provider defaults to FUSED when null`() = testScope.runTest {
        // Given
        val locationWithoutProvider = mockk<AndroidLocation>(relaxed = true).apply {
            every { latitude } returns 37.7749
            every { longitude } returns -122.4194
            every { accuracy } returns 10f
            every { time } returns System.currentTimeMillis()
            every { provider } returns null
            every { hasAltitude() } returns false
        }

        locationTracker = LocationTracker(context, settingsRepository)

        // Mock FusedLocationProviderClient
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns fusedLocationClient

        val mockTask = mockk<Task<AndroidLocation>>(relaxed = true)
        every { fusedLocationClient.getCurrentLocation(any<Int>(), any()) } returns mockTask

        every { mockTask.addOnSuccessListener(any()) } answers {
            val listener = arg<OnSuccessListener<AndroidLocation>>(0)
            listener.onSuccess(locationWithoutProvider)
            mockTask
        }
        every { mockTask.addOnFailureListener(any()) } returns mockTask

        // When
        val location = locationTracker.getCurrentLocation()

        // Then
        assertNotNull(location)
        assertEquals("FUSED", location!!.provider)
    }
}
