package com.tailbait.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepositoryImpl
import com.tailbait.util.Constants
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import no.nordicsemi.android.kotlin.ble.core.MockServerDevice
import no.nordicsemi.android.kotlin.ble.core.mapper.ScanRecordSerializer
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScanOwnershipTest {
    private val dispatcher = StandardTestDispatcher()
    private val incoming = MutableSharedFlow<BleScanResult>()
    private val sourceClosing = CompletableDeferred<Unit>()
    private var closeGate: CompletableDeferred<Unit>? = null
    private var sourceStarts = 0
    private var sourceCloses = 0
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: DeviceRepositoryImpl
    private lateinit var locations: LocationRepository
    private lateinit var scanner: BleScannerManager

    private val location =
        Location(
            id = 1,
            latitude = 51.5,
            longitude = -0.12,
            accuracy = 5f,
            timestamp = 1_800_000_000_000L,
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
            database.appSettingsDao().insert(
                AppSettings(scanDurationSeconds = 1, batteryOptimizationEnabled = false, cameraGlassesAlertsEnabled = false),
            )
            repository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
            locations = mockk()
            coEvery { locations.getCurrentLocation() } returns location
            coEvery { locations.getLastKnownLocation() } returns location
            coEvery { locations.findOrCreateLocation(location, 50.0) } returns (location.id to false)
            coEvery { locations.insertUserPath(location.id, location) } returns Unit
            scanner =
                BleScannerManager(
                    repository,
                    locations,
                    SettingsRepositoryImpl(database.appSettingsDao()),
                    mockk(),
                    mockk(),
                    { _, _ ->
                        flow {
                            sourceStarts++
                            try {
                                incoming.collect { emit(it) }
                            } finally {
                                withContext(NonCancellable) {
                                    sourceClosing.complete(Unit)
                                    closeGate?.await()
                                    sourceCloses++
                                }
                            }
                        }
                    },
                    dispatcher,
                    dispatcher,
                )
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualAndServiceTriggersShareOneCollectorAndOnePersistedObservation() =
        runTest(dispatcher) {
            try {
                val service = startServiceScan()
                val manual = async { scanner.performManualScan() }
                runCurrent()
                sighting(result("01", rssi = -75))
                sighting(result("01", rssi = -45))
                sighting(result("02", rssi = Constants.MIN_RSSI_THRESHOLD - 1))

                assertEquals(1, sourceStarts)
                assertEquals(1, incoming.subscriptionCount.value)
                assertEquals(1, scanner.getCurrentDeviceCount())
                advanceTimeBy(999)
                runCurrent()
                assertFalse(service.isCompleted)
                assertFalse(manual.isCompleted)
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())

                advanceTimeBy(1)
                runCurrent()
                service.await()
                assertEquals(1, manual.await())
                assertEquals(1, sourceCloses)
                assertEquals(0, incoming.subscriptionCount.value)
                val stored = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio("AA:BB:CC:DD:EE:01", "BLE"))
                assertEquals(1, repository.getAllDevices().first().size)
                assertEquals(1, stored.detectionCount)
                assertEquals(-45, stored.highestRssi)
                assertTrue(stored.isTracker)
                assertEquals("SERVICE_DATA", stored.identificationMethod)
                assertEquals(FINGERPRINT, stored.payloadFingerprint)
                val observation = repository.getAllDeviceLocationRecords().single()
                assertEquals(stored.id, observation.deviceId)
                assertEquals(location.id, observation.locationId)
                assertEquals(-45, observation.rssi)
                assertEquals(stored.lastSeen, observation.timestamp)
                assertEquals(Constants.SCAN_TRIGGER_PERIODIC, observation.scanTriggerType)

                assertEquals(1, scanner.processScanResults(Constants.SCAN_TRIGGER_MANUAL))
                sighting(result("03"))
                scanner.stopAndSaveScanResults()
                assertEquals(listOf(observation), repository.getAllDeviceLocationRecords())
                assertEquals(1, repository.getAllDevices().first().size)
                assertEquals(BleScannerManager.ScanState.Idle, scanner.scanState.value)
            } finally {
                withContext(NonCancellable) { scanner.stopAndSaveScanResults() }
            }
        }

    @Test
    fun stopJoinsSourceAndPartialSaveBeforeAcceptingAnotherScan() =
        runTest(dispatcher) {
            val releaseSource = CompletableDeferred<Unit>()
            val persistenceStarted = CompletableDeferred<Unit>()
            val releasePersistence = CompletableDeferred<Unit>()
            closeGate = releaseSource
            coEvery { locations.findOrCreateLocation(location, 50.0) } coAnswers {
                persistenceStarted.complete(Unit)
                releasePersistence.await()
                location.id to false
            }
            try {
                val service = startServiceScan()
                sighting(result("01"))
                val firstStop = launch { scanner.stopAndSaveScanResults() }
                val secondStop = launch { scanner.stopAndSaveScanResults() }
                runCurrent()
                sourceClosing.await()

                assertFalse(firstStop.isCompleted)
                assertFalse(secondStop.isCompleted)
                assertEquals(0, sourceCloses)
                assertEquals(0, incoming.subscriptionCount.value)
                assertFalse(persistenceStarted.isCompleted)
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())
                val rejectedManual = async { runCatching { scanner.performManualScan() }.exceptionOrNull() }
                assertTrue(rejectedManual.await() is CancellationException)
                sighting(result("02"))

                releaseSource.complete(Unit)
                runCurrent()
                persistenceStarted.await()
                assertEquals(1, sourceCloses)
                assertFalse(firstStop.isCompleted)
                assertFalse(secondStop.isCompleted)
                assertEquals(BleScannerManager.ScanState.Processing, scanner.scanState.value)
                assertTrue(repository.getAllDeviceLocationRecords().isEmpty())
                val rejectedService = async { runCatching { scanner.performSingleScan(1000) }.exceptionOrNull() }
                assertTrue(rejectedService.await() is CancellationException)
                assertEquals(1, sourceStarts)

                releasePersistence.complete(Unit)
                firstStop.join()
                secondStop.join()
                service.join()
                assertTrue(service.isCancelled)
                val stored = repository.getAllDevices().first().single()
                assertEquals("AA:BB:CC:DD:EE:01", stored.address)
                assertEquals(1, stored.detectionCount)
                val saved = repository.getAllDeviceLocationRecords().single()
                assertEquals(stored.id, saved.deviceId)
                assertEquals(location.id, saved.locationId)
                assertEquals(Constants.SCAN_TRIGGER_PERIODIC, saved.scanTriggerType)
                sighting(result("03"))
                scanner.stopAndSaveScanResults()
                assertEquals(1, scanner.processScanResults(Constants.SCAN_TRIGGER_MANUAL))
                assertEquals(listOf(saved), repository.getAllDeviceLocationRecords())

                closeGate = null
                val restarted = async { scanner.performManualScan() }
                runCurrent()
                advanceTimeBy(200)
                runCurrent()
                incoming.subscriptionCount.first { it == 1 }
                sighting(result("04"))
                scanner.stopAndSaveScanResults()
                restarted.join()
                assertTrue(restarted.isCancelled)
                assertEquals(2, sourceStarts)
                assertEquals(2, sourceCloses)
                assertEquals(0, incoming.subscriptionCount.value)
                val fresh = requireNotNull(database.scannedDeviceDao().getByAddressAndRadio("AA:BB:CC:DD:EE:04", "BLE"))
                assertEquals(2, repository.getAllDevices().first().size)
                assertEquals(2, repository.getAllDeviceLocationRecords().size)
                assertEquals(listOf(saved), repository.getDeviceLocationRecordsForDevice(stored.id))
                assertEquals(
                    Constants.SCAN_TRIGGER_MANUAL,
                    repository.getDeviceLocationRecordsForDevice(fresh.id).single().scanTriggerType,
                )
                assertNull(database.scannedDeviceDao().getByAddressAndRadio("AA:BB:CC:DD:EE:02", "BLE"))
                assertNull(database.scannedDeviceDao().getByAddressAndRadio("AA:BB:CC:DD:EE:03", "BLE"))
                assertEquals(BleScannerManager.ScanState.Idle, scanner.scanState.value)
            } finally {
                releaseSource.complete(Unit)
                releasePersistence.complete(Unit)
                withContext(NonCancellable) { scanner.stopAndSaveScanResults() }
            }
        }

    @Test
    fun stopCancelsFreshLocationBeforeSavingOneCachedObservation() =
        runTest(dispatcher) {
            val locating = CompletableDeferred<Unit>()
            val locationCancelled = CompletableDeferred<Unit>()
            val releaseLocation = CompletableDeferred<Unit>()
            coEvery { locations.getCurrentLocation() } coAnswers {
                locating.complete(Unit)
                try {
                    releaseLocation.await()
                    null
                } finally {
                    locationCancelled.complete(Unit)
                }
            }
            val scan = startServiceScan()
            sighting(result("05"))
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(locating.isCompleted)
            val stop = launch { scanner.stopAndSaveScanResults() }
            try {
                runCurrent()
                assertTrue(locationCancelled.isCompleted)
                stop.join()
                assertTrue(scan.isCancelled)
                val device = repository.getAllDevices().first().single()
                val observation = repository.getAllDeviceLocationRecords().single()
                assertEquals("AA:BB:CC:DD:EE:05", device.address)
                assertEquals(device.id, observation.deviceId)
                assertEquals(location.id, observation.locationId)
                assertEquals(1, device.detectionCount)
                assertEquals(BleScannerManager.ScanState.Idle, scanner.scanState.value)
            } finally {
                releaseLocation.complete(Unit)
                withContext(NonCancellable) { scanner.stopAndSaveScanResults() }
            }
        }

    private fun TestScope.startServiceScan(): Deferred<Unit> {
        val scan = async { scanner.performSingleScan(1000) }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, incoming.subscriptionCount.value)
        return scan
    }

    private suspend fun TestScope.sighting(result: BleScanResult) {
        incoming.emit(result)
        runCurrent()
    }

    private fun result(
        suffix: String,
        rssi: Int = -55,
    ): BleScanResult =
        BleScanResult(
            MockServerDevice(name = null, address = "AA:BB:CC:DD:EE:$suffix"),
            BleScanResultData(rssi, 0L, ScanRecordSerializer.parseFromBytes(ADVERTISEMENT)),
        )

    private companion object {
        const val FINGERPRINT = "FHN:000102030405060708090A0B0C0D0E0F10111213"
        val ADVERTISEMENT =
            byteArrayOf(25, 0x16, 0xAA.toByte(), 0xFE.toByte(), 0x41) +
                ByteArray(20) { it.toByte() } + byteArrayOf(0xA5.toByte())
    }
}
