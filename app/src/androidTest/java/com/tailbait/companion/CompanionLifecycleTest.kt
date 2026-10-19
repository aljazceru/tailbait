package com.tailbait.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.CompanionDeviceRepositoryImpl
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LocationRepository
import com.tailbait.util.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionLifecycleTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: DeviceRepositoryImpl
    private lateinit var locations: LocationRepository
    private lateinit var ingestor: CompanionIngestor
    private lateinit var link: CompanionLinkManager
    private val records = MutableSharedFlow<CompanionProtocol.Record>()
    private val location =
        Location(
            id = 1,
            latitude = 51.5,
            longitude = -0.12,
            accuracy = 5f,
            timestamp = TIMESTAMP,
            provider = "TEST",
        )

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    TailBaitDatabase::class.java,
                )
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            database.locationDao().insert(location)
            repository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
            locations = mockk()
            coEvery { locations.getCurrentLocation() } returns location
            coEvery { locations.findOrCreateLocation(location, 50.0) } returns (location.id to false)
            link = mockk()
            every { link.records } returns records
            ingestor =
                CompanionIngestor(
                    repository,
                    locations,
                    CompanionDeviceRepositoryImpl(database.companionDeviceDao(), database.alertHistoryDao()),
                )
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedAttachDetachAndReusePersistExactlyOnceAndPreserveBleServiceData() =
        runTest {
            try {
                ingestor.attach(link, backgroundScope)
                ingestor.attach(link, backgroundScope)
                assertEquals(1, records.subscriptionCount.value)
                deliver(wifiRecord())

                val wifi = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "WIFI_STA"))
                assertEquals(1, wifi.detectionCount)
                assertEquals(6, wifi.channel)
                val first = repository.getAllDeviceLocationRecords().single()
                assertEquals(wifi.id, first.deviceId)
                assertEquals(location.id, first.locationId)
                assertEquals(TIMESTAMP, first.timestamp)
                assertEquals(-50, first.rssi)
                assertEquals(Constants.SCAN_TRIGGER_COMPANION, first.scanTriggerType)
                assertEquals(1L, ingestor.counters.sta.get())

                ingestor.detach()
                assertEquals(0, records.subscriptionCount.value)
                records.emit(wifiRecord().copy(timestamp = TIMESTAMP + 1))
                records.emit(CompanionProtocol.Record.BleAdv(LATE_ADDRESS, -55, TIMESTAMP + 1, FIND_HUB_ADVERTISEMENT))
                runCurrent()
                assertEquals(listOf(first), repository.getAllDeviceLocationRecords())
                assertNull(database.scannedDeviceDao().getByAddressAndRadio(LATE_ADDRESS, "BLE"))
                assertEquals(1L, ingestor.counters.sta.get())
                assertEquals(0L, ingestor.counters.ble.get())

                ingestor.attach(link, backgroundScope)
                ingestor.attach(link, backgroundScope)
                assertEquals(1, records.subscriptionCount.value)
                deliver(wifiRecord().copy(timestamp = TIMESTAMP + 2))
                // The same observed MAC must remain distinct across the two radios.
                deliver(CompanionProtocol.Record.BleAdv(ADDRESS, -55, TIMESTAMP + 3, FIND_HUB_ADVERTISEMENT))

                val reused = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "WIFI_STA"))
                assertEquals(wifi.id, reused.id)
                assertEquals(2, reused.detectionCount)
                assertEquals(TIMESTAMP + 2, reused.lastSeen)
                assertEquals(
                    listOf(TIMESTAMP + 2, TIMESTAMP),
                    repository.getDeviceLocationRecordsForDevice(wifi.id).map { it.timestamp },
                )
                val ble = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "BLE"))
                assertNotEquals(wifi.id, ble.id)
                assertTrue(ble.isTracker)
                assertEquals("TRACKER", ble.deviceType)
                assertEquals("SERVICE_DATA", ble.identificationMethod)
                assertEquals(FINGERPRINT, ble.payloadFingerprint)
                assertEquals(1, ble.detectionCount)
                val bleObservation = repository.getDeviceLocationRecordsForDevice(ble.id).single()
                assertEquals(TIMESTAMP + 3, bleObservation.timestamp)
                assertEquals(-55, bleObservation.rssi)
                val observations = repository.getAllDeviceLocationRecords()
                assertEquals(3, observations.size)
                assertTrue(observations.all { it.locationId == location.id && it.scanTriggerType == Constants.SCAN_TRIGGER_COMPANION })
                assertEquals(2, repository.getAllDevices().first().size)
                assertEquals(2L, ingestor.counters.sta.get())
                assertEquals(1L, ingestor.counters.ble.get())

                ingestor.detach()
                assertEquals(0, records.subscriptionCount.value)
                records.emit(CompanionProtocol.Record.BleAdv(ADDRESS, -40, TIMESTAMP + 4, FIND_HUB_ADVERTISEMENT))
                runCurrent()
                assertEquals(observations, repository.getAllDeviceLocationRecords())
            } finally {
                withContext(NonCancellable) { ingestor.detach() }
            }
        }

    @Test
    fun cancelledServiceScopeStopsObservationsWithoutDestroyingReusableIngestor() =
        runTest {
            val serviceJob = Job(backgroundScope.coroutineContext[Job])
            val serviceScope = CoroutineScope(backgroundScope.coroutineContext + serviceJob)
            try {
                ingestor.attach(link, serviceScope)
                deliver(wifiRecord())
                val first = repository.getAllDeviceLocationRecords().single()
                serviceJob.cancelAndJoin()
                assertEquals(0, records.subscriptionCount.value)
                records.emit(wifiRecord().copy(mac = LATE_ADDRESS, timestamp = TIMESTAMP + 1))
                runCurrent()
                assertEquals(listOf(first), repository.getAllDeviceLocationRecords())
                assertNull(database.scannedDeviceDao().getByAddressAndRadio(LATE_ADDRESS, "WIFI_STA"))
                assertEquals(1L, ingestor.counters.sta.get())

                ingestor.attach(link, backgroundScope)
                assertEquals(1, records.subscriptionCount.value)
                deliver(wifiRecord().copy(timestamp = TIMESTAMP + 2))
                val stored = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "WIFI_STA"))
                assertEquals(first.deviceId, stored.id)
                assertEquals(2, stored.detectionCount)
                val observations = repository.getAllDeviceLocationRecords()
                assertEquals(2, observations.size)
                assertTrue(observations.all { it.deviceId == stored.id && it.scanTriggerType == Constants.SCAN_TRIGGER_COMPANION })
                assertEquals(2L, ingestor.counters.sta.get())
            } finally {
                withContext(NonCancellable) {
                    serviceJob.cancelAndJoin()
                    ingestor.detach()
                }
            }
        }

    @Test
    fun detachWaitsForCancelledIngestionCleanupAndDiscardsQueuedObservations() =
        runTest {
            val locationRequested = CompletableDeferred<Unit>()
            val cleaningUp = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            coEvery { locations.getCurrentLocation() } coAnswers {
                try {
                    locationRequested.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            try {
                ingestor.attach(link, backgroundScope)
                records.emit(wifiRecord())
                locationRequested.await()
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())
                val pending = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "WIFI_STA"))
                assertEquals(1, pending.detectionCount)

                val detached = async { ingestor.detach() }
                runCurrent()
                cleaningUp.await()
                assertFalse(detached.isCompleted)
                ingestor.attach(link, backgroundScope)
                assertEquals(1, records.subscriptionCount.value)
                val lateEmission = async { records.emit(wifiRecord().copy(mac = LATE_ADDRESS, timestamp = TIMESTAMP + 1)) }
                runCurrent()
                assertFalse(lateEmission.isCompleted)
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())

                releaseCleanup.complete(Unit)
                detached.await()
                lateEmission.await()
                assertEquals(0, records.subscriptionCount.value)
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())
                assertNull(database.scannedDeviceDao().getByAddressAndRadio(LATE_ADDRESS, "WIFI_STA"))
                assertEquals(1L, ingestor.counters.sta.get())

                coEvery { locations.getCurrentLocation() } returns location
                ingestor.attach(link, backgroundScope)
                deliver(wifiRecord().copy(timestamp = TIMESTAMP + 2))
                val saved = repository.getAllDeviceLocationRecords().single()
                assertEquals(pending.id, saved.deviceId)
                assertEquals(location.id, saved.locationId)
                assertEquals(TIMESTAMP + 2, saved.timestamp)
                assertEquals(Constants.SCAN_TRIGGER_COMPANION, saved.scanTriggerType)
                assertEquals(1, repository.getAllDevices().first().size)
                assertEquals(2L, ingestor.counters.sta.get())
            } finally {
                releaseCleanup.complete(Unit)
                withContext(NonCancellable) { ingestor.detach() }
            }
        }

    private suspend fun TestScope.deliver(record: CompanionProtocol.Record) {
        assertEquals(1, records.subscriptionCount.value)
        records.emit(record)
        // With no buffer, this handoff waits until the preceding ingestion has returned.
        records.emit(STATUS_BARRIER)
        runCurrent()
    }

    private fun wifiRecord() = CompanionProtocol.Record.WifiSta(ADDRESS, -50, 6, TIMESTAMP)

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val LATE_ADDRESS = "AA:BB:CC:DD:EE:02"
        const val TIMESTAMP = 1_800_000_000_000L
        const val FINGERPRINT = "FHN:000102030405060708090A0B0C0D0E0F10111213"
        val FIND_HUB_ADVERTISEMENT =
            byteArrayOf(4, 0xFF.toByte(), 0xE5.toByte(), 0x02, 0x01) +
                byteArrayOf(25, 0x16, 0xAA.toByte(), 0xFE.toByte(), 0x41) +
                ByteArray(20) { it.toByte() } + byteArrayOf(0xA5.toByte())
        val STATUS_BARRIER =
            CompanionProtocol.Record.Status(
                timestamp = TIMESTAMP,
                firmware = "test",
                mode = 0,
                phase = 0,
                placeCount = 0,
                deviceCount = 0,
                heapFree = 0,
                drops = 0,
                bleCount = 0,
                wifiCount = 0,
                cycles = 0,
                dwellMs = 0,
            )
    }
}
