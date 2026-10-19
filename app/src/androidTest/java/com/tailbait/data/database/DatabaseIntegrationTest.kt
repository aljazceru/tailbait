package com.tailbait.data.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrationTest {
    private lateinit var database: TailBaitDatabase
    private var now = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java).build()
        now = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observationForeignKeys_rejectMissingDeviceAndLocation() =
        runBlocking {
            val device = insertDevice("A0:00:00:00:00:01")
            val location = insertLocation()
            val valid = observation(device.id, location.id)
            val validId = database.deviceLocationRecordDao().insert(valid)

            for (orphan in listOf(valid.copy(deviceId = device.id + 1), valid.copy(locationId = location.id + 1))) {
                val failure = runCatching { database.deviceLocationRecordDao().insert(orphan) }.exceptionOrNull()
                assertTrue("Expected foreign-key failure, got $failure", failure is SQLiteConstraintException)
            }

            assertEquals(listOf(validId), database.deviceLocationRecordDao().getAllRecords().first().map { it.id })
            assertEquals(listOf(device.id), database.scannedDeviceDao().getDevicesAtLocation(location.id).map { it.id })
        }

    @Test
    fun invalidBatchInsert_rollsBackEarlierValidObservations() =
        runBlocking {
            val device = insertDevice("A0:00:00:00:00:01")
            val location = insertLocation()
            val valid = observation(device.id, location.id)

            val failure =
                runCatching {
                    database.deviceLocationRecordDao().insertAll(
                        listOf(valid, valid.copy(deviceId = device.id + 1, timestamp = now + 1)),
                    )
                }.exceptionOrNull()

            assertTrue("Expected foreign-key failure, got $failure", failure is SQLiteConstraintException)
            assertTrue(database.deviceLocationRecordDao().getAllRecords().first().isEmpty())
            assertNotNull(database.scannedDeviceDao().getById(device.id))
            assertNotNull(database.locationDao().getById(location.id))
        }

    @Test
    fun deletingDevice_cascadesOnlyItsObservationsAndTrust() =
        runBlocking {
            val deletedDevice = insertDevice("A0:00:00:00:00:01")
            val retainedDevice = insertDevice("A0:00:00:00:00:02")
            val location = insertLocation()
            database.deviceLocationRecordDao().insert(observation(deletedDevice.id, location.id))
            val retainedRecordId = database.deviceLocationRecordDao().insert(observation(retainedDevice.id, location.id))
            database.whitelistEntryDao().insert(
                WhitelistEntry(deviceId = deletedDevice.id, label = "Trusted", category = "OWN"),
            )

            database.scannedDeviceDao().delete(deletedDevice)

            assertNull(database.scannedDeviceDao().getById(deletedDevice.id))
            assertNull(database.whitelistEntryDao().getByDeviceId(deletedDevice.id))
            assertEquals(
                listOf(retainedRecordId),
                database.deviceLocationRecordDao().getAllRecords().first().map { it.id },
            )
            assertNotNull(database.scannedDeviceDao().getById(retainedDevice.id))
            assertNotNull(database.locationDao().getById(location.id))
        }

    @Test
    fun repeatedWhitelistInsert_keepsOneEntryWithoutDeletingDeviceHistory() =
        runBlocking {
            val device = insertDevice("A0:00:00:00:00:01")
            val location = insertLocation()
            val recordId = database.deviceLocationRecordDao().insert(observation(device.id, location.id))
            val original = WhitelistEntry(deviceId = device.id, label = "My tracker", category = "OWN")
            database.whitelistEntryDao().insert(original)

            database.whitelistEntryDao().insert(original.copy(label = "Renamed tracker"))

            val entries = database.whitelistEntryDao().getAllEntries().first()
            assertEquals(1, entries.size)
            assertEquals("Renamed tracker", entries.single().label)
            assertEquals(device.id, entries.single().deviceId)
            assertNotNull(database.scannedDeviceDao().getById(device.id))
            assertNotNull(database.deviceLocationRecordDao().getById(recordId))
        }

    private suspend fun insertDevice(address: String): ScannedDevice {
        val device = ScannedDevice(address = address, name = null, firstSeen = now, lastSeen = now)
        return device.copy(id = database.scannedDeviceDao().insert(device))
    }

    private suspend fun insertLocation(): Location {
        val location = Location(latitude = 37.0, longitude = -122.0, accuracy = 5f, timestamp = now, provider = "GPS")
        return location.copy(id = database.locationDao().insert(location))
    }

    private fun observation(
        deviceId: Long,
        locationId: Long,
    ) = DeviceLocationRecord(deviceId = deviceId, locationId = locationId, rssi = -50, timestamp = now, scanTriggerType = "PERIODIC")
}
