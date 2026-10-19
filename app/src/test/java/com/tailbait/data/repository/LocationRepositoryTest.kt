package com.tailbait.data.repository

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.Executor
import android.location.Location as AndroidLocation

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [PermissionRaceLocationManager::class])
class LocationRepositoryTest {
    private lateinit var context: Application
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: LocationRepository
    private lateinit var locationManager: LocationManager
    private lateinit var providers: PermissionRaceLocationManager
    private val home =
        Location(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            altitude = 50.0,
            timestamp = 1_000L,
            provider = "FUSED",
            createdAt = 1_000L,
        )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        database =
            Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        providers = Shadow.extract(locationManager)
        providers.setProviderEnabled("fused", true)
        providers.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        repository = LocationRepositoryImpl(context, database.locationDao(), database.userPathDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `returning after three days reuses the place without losing movement history`() =
        runTest {
            val (homeId, homeIsNew) = repository.findOrCreateLocation(home)
            assertTrue(homeIsNew)
            repository.insertUserPath(homeId, home)
            val work = home.copy(latitude = home.latitude + 0.01, timestamp = 2_000L, accuracy = 20f)
            val (workId, workIsNew) = repository.findOrCreateLocation(work)
            assertTrue(workIsNew)
            repository.insertUserPath(workId, work)
            val returnVisit =
                home.copy(
                    latitude = home.latitude + 0.0001,
                    timestamp = home.timestamp + 3 * 24 * 60 * 60 * 1_000L,
                    accuracy = 30f,
                )

            val (returnedId, isNew) = repository.findOrCreateLocation(returnVisit)
            repository.insertUserPath(returnedId, returnVisit)

            assertEquals(homeId, returnedId)
            assertFalse(isNew)
            assertEquals(
                listOf(home.copy(id = homeId, timestamp = returnVisit.timestamp), work.copy(id = workId)),
                repository.getAllLocations().first(),
            )
            val path = repository.getUserPathSince(0L)
            assertEquals(listOf(homeId, workId, homeId), path.map { it.locationId })
            assertEquals(listOf(home.timestamp, work.timestamp, returnVisit.timestamp), path.map { it.timestamp })
            assertEquals(listOf(10f, 20f, 30f), path.map { it.accuracy })
            assertEquals(path.drop(1), repository.getUserPathSince(work.timestamp))
        }

    @Test
    fun `candidate inside the bounding box but outside the reuse radius creates a new place`() =
        runTest {
            val existingId = repository.insertLocation(home)
            val nearby = home.copy(latitude = home.latitude + 0.00054, timestamp = 2_000L)

            val (id, isNew) = repository.findOrCreateLocation(nearby, radiusMeters = 50.0)

            assertTrue(isNew)
            assertNotEquals(existingId, id)
            assertEquals(
                listOf(nearby.copy(id = id), home.copy(id = existingId)),
                repository.getAllLocations().first(),
            )
        }

    @Test
    fun `stored boundary coordinates and metadata round trip in newest first order`() =
        runTest {
            val newest = home.copy(latitude = 90.0, longitude = 180.0, altitude = -50.0, timestamp = 3_000L)
            val oldest = home.copy(latitude = -90.0, longitude = -180.0, altitude = null, provider = "GPS")
            val middle = home.copy(accuracy = 10_000f, provider = "NETWORK", timestamp = 2_000L)
            val newestId = repository.insertLocation(newest)
            val oldestId = repository.insertLocation(oldest)
            val middleId = repository.insertLocation(middle)

            assertEquals(newest.copy(id = newestId), repository.getLastLocation())
            assertEquals(
                listOf(newest.copy(id = newestId), middle.copy(id = middleId), oldest.copy(id = oldestId)),
                repository.getAllLocations().first(),
            )
        }

    @Test
    fun `device histories contain only associated places`() =
        runTest {
            val deviceId =
                database.scannedDeviceDao().insert(
                    ScannedDevice(address = "00:00:00:00:00:01", name = "First", firstSeen = 1_000L, lastSeen = 3_000L),
                )
            val otherDeviceId =
                database.scannedDeviceDao().insert(
                    ScannedDevice(address = "00:00:00:00:00:02", name = "Second", firstSeen = 1_000L, lastSeen = 3_000L),
                )
            val places =
                listOf(home, home.copy(timestamp = 2_000L), home.copy(timestamp = 3_000L)).map {
                    it.copy(id = repository.insertLocation(it))
                }
            places.forEachIndexed { index, place ->
                database.deviceLocationRecordDao().insert(
                    DeviceLocationRecord(
                        deviceId = if (index == 2) otherDeviceId else deviceId,
                        locationId = place.id,
                        rssi = -60,
                        timestamp = place.timestamp,
                        scanTriggerType = "PERIODIC",
                    ),
                )
            }

            assertEquals(listOf(places[1], places[0]), repository.getLocationsForDevice(deviceId).first())
            assertEquals(listOf(places[1], places[0]), repository.getLocationsForDeviceOnce(deviceId))
            assertEquals(listOf(places[2]), repository.getLocationsForDeviceOnce(otherDeviceId))
            assertTrue(repository.getLocationsForDeviceOnce(-1L).isEmpty())
        }

    @Test
    fun `retention keeps the cutoff and cascades removed places to movement history`() =
        runTest {
            for (timestamp in listOf(1_000L, 2_000L, 3_000L)) {
                val place = home.copy(timestamp = timestamp)
                val id = repository.insertLocation(place)
                repository.insertUserPath(id, place)
            }

            assertEquals(1, repository.deleteOldLocations(2_000L))
            assertEquals(listOf(3_000L, 2_000L), repository.getAllLocations().first().map { it.timestamp })
            assertEquals(listOf(2_000L, 3_000L), repository.getUserPathSince(0L).map { it.timestamp })

            repository.deleteAllLocations()

            assertNull(repository.getLastLocation())
            assertTrue(repository.getAllLocations().first().isEmpty())
            assertTrue(repository.getUserPathSince(0L).isEmpty())
        }

    @Test
    fun `coarse permission alone never registers precise location requests`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

            assertNull(repository.getCurrentLocation())
            assertNull(repository.getLastKnownLocation())
            assertNoRequests()
        }

    @Test
    fun `native fused callback supplies a current fix and removes the one shot registration`() =
        runTest {
            grantLocation()
            val request = async { repository.getCurrentLocationOnce() }
            runCurrent()
            val registration = providers.getLocationRequests("fused").single()
            assertEquals(LocationRequest.QUALITY_HIGH_ACCURACY, registration.quality)
            assertEquals(1, registration.maxUpdates)
            assertEquals(30_000L, registration.durationMillis)
            assertTrue(providers.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
            val fix = fix("fused").apply { altitude = 50.0 }

            providers.simulateLocation(fix)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            val result = request.await()!!

            assertEquals(fix.latitude, result.latitude, 0.0)
            assertEquals(fix.longitude, result.longitude, 0.0)
            assertEquals(fix.accuracy, result.accuracy, 0f)
            assertEquals(fix.altitude, result.altitude!!, 0.0)
            assertEquals("fused", result.provider)
            assertNoRequests()
        }

    @Test
    fun `GPS only devices receive native fixes without any fused or network provider`() =
        runTest {
            grantLocation()
            providers.removeProvider("fused")
            providers.removeProvider(LocationManager.NETWORK_PROVIDER)
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            assertEquals(
                LocationRequest.QUALITY_HIGH_ACCURACY,
                providers.getLocationRequests(LocationManager.GPS_PROVIDER).single().quality,
            )

            providers.simulateLocation(fix(LocationManager.GPS_PROVIDER))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertEquals(LocationManager.GPS_PROVIDER, request.await()!!.provider)
            assertNoRequests()
        }

    @Test
    @Config(sdk = [26])
    fun `API 26 compatibility listener delivers a fix and is removed`() =
        runTest {
            grantLocation()
            providers.removeProvider("fused")
            providers.removeProvider(LocationManager.NETWORK_PROVIDER)
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).size)

            providers.simulateLocation(fix(LocationManager.GPS_PROVIDER))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertEquals(LocationManager.GPS_PROVIDER, request.await()!!.provider)
            assertNoRequests()
        }

    @Test
    fun `disabled fused and GPS providers use the enabled network provider`() =
        runTest {
            grantLocation()
            providers.setProviderEnabled("fused", false)
            providers.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLocationRequests(LocationManager.NETWORK_PROVIDER).size)

            providers.simulateLocation(fix(LocationManager.NETWORK_PROVIDER))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertEquals(LocationManager.NETWORK_PROVIDER, request.await()!!.provider)
            assertNoRequests()
        }

    @Test
    fun `disabled or absent active providers never cause a passive request`() =
        runTest {
            grantLocation()
            for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                providers.setProviderEnabled(provider, false)
            }
            assertNull(repository.getCurrentLocation())
            assertNoRequests()
            for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                providers.removeProvider(provider)
            }
            assertNull(repository.getCurrentLocation())
            assertNoRequests()
        }

    @Test
    fun `permission revoked during native registration or cache access returns no location`() =
        runTest {
            grantLocation()
            providers.revokeOnRegistration = true
            assertNull(repository.getCurrentLocation())
            assertNoRequests()

            grantLocation()
            providers.revokeOnLastKnown = true
            assertNull(repository.getLastKnownLocation())
            assertNoRequests()
        }

    @Test
    fun `permission revoked while a fix is pending prevents its delivery`() =
        runTest {
            grantLocation()
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            providers.simulateLocation(fix("fused"))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertNull(request.await())
            assertNoRequests()
        }

    @Test
    @Config(sdk = [26, 33])
    fun `caller cancellation removes the native request and ignores late locations`() =
        runTest {
            grantLocation()
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLocationRequests("fused").size)

            request.cancelAndJoin()
            assertNoRequests()
            providers.simulateLocation(fix("fused"))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertTrue(request.isCancelled)
        }

    @Test
    @Config(sdk = [26, 33])
    fun `current request times out without retaining a GPS registration`() =
        runTest {
            grantLocation()
            providers.removeProvider("fused")
            providers.removeProvider(LocationManager.NETWORK_PROVIDER)
            val request = async { repository.getCurrentLocation() }
            runCurrent()
            assertEquals(1, providers.getLocationRequests(LocationManager.GPS_PROVIDER).size)

            advanceTimeBy(30_000L)
            runCurrent()

            assertNull(request.await())
            assertNoRequests()
        }

    @Test
    fun `provider disabled during a request returns no fabricated location`() =
        runTest {
            grantLocation()
            val request = async { repository.getCurrentLocation() }
            runCurrent()

            providers.setProviderEnabled("fused", false)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertNull(request.await())
            assertNoRequests()
        }

    @Test
    fun `current requests reject stale fixes but keep existing low accuracy policy`() =
        runTest {
            grantLocation()
            val stale = async { repository.getCurrentLocation() }
            runCurrent()
            providers.simulateLocation(fix("fused").apply { time -= 120_001L })
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertNull(stale.await())

            val inaccurate = async { repository.getCurrentLocation() }
            runCurrent()
            providers.simulateLocation(fix("fused").apply { accuracy = 250f })
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()

            assertEquals(250f, inaccurate.await()!!.accuracy, 0f)
            assertNoRequests()
        }

    @Test
    fun `last known fix selects newest provider time and preserves stale coordinates without inventing altitude`() =
        runTest {
            grantLocation()
            providers.simulateLocation(fix("fused").apply { elapsedRealtimeNanos = 1_000L })
            val fix =
                fix(LocationManager.GPS_PROVIDER).apply {
                    time = 1_000L
                    elapsedRealtimeNanos = 2_000L
                }
            providers.simulateLocation(fix)

            val result = repository.getLastKnownLocation()!!

            assertEquals(fix.latitude, result.latitude, 0.0)
            assertEquals(fix.longitude, result.longitude, 0.0)
            assertEquals(12f, result.accuracy, 0f)
            assertEquals(1_000L, result.timestamp)
            assertEquals(LocationManager.GPS_PROVIDER, result.provider)
            assertNull(result.altitude)
            assertNoRequests()
        }

    private fun grantLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun assertNoRequests() {
        for (provider in locationManager.allProviders) {
            assertTrue("Unexpected registration for $provider", providers.getLegacyLocationRequests(provider).isEmpty())
        }
    }

    private fun fix(provider: String) =
        AndroidLocation(provider).apply {
            latitude = home.latitude
            longitude = home.longitude
            accuracy = 12f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
}

/** Injects only the permission race; registrations and callback delivery remain Robolectric's. */
@Implements(LocationManager::class)
class PermissionRaceLocationManager : ShadowLocationManager() {
    var revokeOnRegistration = false
    var revokeOnLastKnown = false

    @Implementation(minSdk = 31)
    override fun requestLocationUpdates(
        provider: String,
        request: LocationRequest,
        executor: Executor,
        listener: LocationListener,
    ) {
        if (revokeOnRegistration) {
            revokeOnRegistration = false
            revokePermission()
        }
        super.requestLocationUpdates(provider, request, executor, listener)
    }

    @Implementation
    override fun getLastKnownLocation(provider: String): AndroidLocation? {
        if (revokeOnLastKnown) {
            revokeOnLastKnown = false
            revokePermission()
        }
        return super.getLastKnownLocation(provider)
    }

    private fun revokePermission(): Nothing {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        throw SecurityException("Permission revoked")
    }
}
