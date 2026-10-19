package com.tailbait.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.util.Constants
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.kotlin.ble.core.MockServerDevice
import no.nordicsemi.android.kotlin.ble.core.mapper.ScanRecordSerializer
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class BleScannerManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)
    private val locationRepository = mockk<LocationRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()
    private val whitelistRepository = mockk<WhitelistRepository>(relaxed = true)
    private val alertGenerator = mockk<AlertGenerator>(relaxed = true)
    private val incoming = MutableSharedFlow<BleScanResult>(extraBufferCapacity = 16)
    private val persisted = mutableListOf<PersistedDevice>()
    private val recordTriggers = mutableListOf<String>()
    private var sourceStarts = 0
    private var sourceCloses = 0
    private var closeGate: CompletableDeferred<Unit>? = null
    private var beforePersist: suspend () -> Unit = {}
    private lateinit var scannerManager: BleScannerManager

    private data class PersistedDevice(
        val address: String,
        val isTracker: Boolean,
        val deviceType: String?,
        val fingerprint: String?,
        val method: String?,
        val highestRssi: Int?,
    )

    @Before
    fun setup() {
        coEvery { settingsRepository.getSettingsOnce() } returns
            AppSettings(scanDurationSeconds = 1, batteryOptimizationEnabled = false)
        val location = Location(id = 7, latitude = 37.0, longitude = -122.0, accuracy = 5f, timestamp = 1000L, provider = "TEST")
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { locationRepository.getLastKnownLocation() } returns location
        coEvery { locationRepository.findOrCreateLocation(any(), any()) } returns (7L to true)
        coEvery {
            deviceRepository.upsertDeviceWithFingerprint(
                address = any(),
                name = any(),
                advertisedName = any(),
                lastSeen = any(),
                manufacturerData = any(),
                manufacturerId = any(),
                manufacturerName = any(),
                deviceType = any(),
                deviceModel = any(),
                isTracker = any(),
                serviceUuids = any(),
                appearance = any(),
                txPowerLevel = any(),
                advertisingFlags = any(),
                appleContinuityType = any(),
                identificationConfidence = any(),
                identificationMethod = any(),
                payloadFingerprint = any(),
                findMyStatus = any(),
                findMySeparated = any(),
                highestRssi = any(),
                signalStrength = any(),
                beaconType = any(),
                threatLevel = any(),
            )
        } coAnswers {
            beforePersist()
            persisted +=
                PersistedDevice(
                    address = arg(0),
                    isTracker = arg(9),
                    deviceType = arg(7),
                    fingerprint = arg(17),
                    method = arg(16),
                    highestRssi = arg(20),
                )
            persisted.size.toLong()
        }
        coEvery {
            deviceRepository.insertDeviceLocationRecord(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordTriggers += arg<String>(6)
            recordTriggers.size.toLong()
        }
        scannerManager =
            manager { _, _ ->
                flow {
                    sourceStarts++
                    try {
                        incoming.collect { emit(it) }
                    } finally {
                        withContext(NonCancellable) {
                            closeGate?.await()
                            sourceCloses++
                        }
                    }
                }
            }
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `overlapping service and manual scans share collection and persistence`() =
        runTest(dispatcher) {
            val service = startScan(Constants.SCAN_TRIGGER_PERIODIC)
            val manual = async { scannerManager.performManualScan() }
            runCurrent()
            sighting(result("01", rssi = -75))
            sighting(result("01", rssi = -45))
            sighting(result("02", rssi = Constants.MIN_RSSI_THRESHOLD - 1))
            assertEquals(1, sourceStarts)
            assertEquals(1, scannerManager.getCurrentDeviceCount())

            advanceTimeBy(999)
            runCurrent()
            assertFalse(service.isCompleted)
            assertTrue(persisted.isEmpty())
            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, service.await())
            assertEquals(1, manual.await())
            assertEquals(-45, persisted.single().highestRssi)
            assertEquals(listOf(Constants.SCAN_TRIGGER_PERIODIC), recordTriggers)
            assertEquals(1, sourceCloses)
            assertEquals(1, scannerManager.processScanResults(Constants.SCAN_TRIGGER_MANUAL))
            assertEquals(1, persisted.size)
            assertEquals(BleScannerManager.ScanState.Idle, scannerManager.scanState.value)
        }

    @Test
    fun `stop joins source teardown before one stable partial save and rejects new scans`() =
        runTest(dispatcher) {
            closeGate = CompletableDeferred()
            val scan = startScan()
            sighting(result("01"))
            val firstStop = launch { scannerManager.stopAndSaveScanResults() }
            val secondStop = launch { scannerManager.stopAndSaveScanResults() }
            runCurrent()
            assertFalse(firstStop.isCompleted)
            assertFalse(secondStop.isCompleted)
            assertEquals(0, sourceCloses)
            assertTrue(persisted.isEmpty())
            assertEquals(0, incoming.subscriptionCount.value)

            val rejected = async { runCatching { scannerManager.performManualScan() }.exceptionOrNull() }
            runCurrent()
            assertTrue(rejected.await() is CancellationException)
            sighting(result("02"))
            closeGate!!.complete(Unit)
            runCurrent()
            firstStop.join()
            secondStop.join()

            assertTrue(scan.isCancelled)
            assertEquals(listOf("AA:BB:CC:DD:EE:01"), persisted.map { it.address })
            assertEquals(1, sourceStarts)
            assertEquals(1, sourceCloses)
            assertEquals(1, recordTriggers.size)
            sighting(result("03"))
            scannerManager.stopAndSaveScanResults()
            assertEquals(1, persisted.size)
            assertEquals(BleScannerManager.ScanState.Idle, scannerManager.scanState.value)
        }

    @Test
    fun `stop during persistence waits for the existing batch without processing twice`() =
        runTest(dispatcher) {
            val writing = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            beforePersist = {
                writing.complete(Unit)
                finishWrite.await()
            }
            val scan = startScan()
            sighting(result("01"))
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(writing.isCompleted)
            assertEquals(1, sourceCloses)
            assertEquals(BleScannerManager.ScanState.Processing, scannerManager.scanState.value)
            val overlap = async { scannerManager.performManualScan() }
            val stop = launch { scannerManager.stopAndSaveScanResults() }
            runCurrent()
            assertFalse(stop.isCompleted)
            assertEquals(1, sourceStarts)
            assertEquals(BleScannerManager.ScanState.Processing, scannerManager.scanState.value)

            finishWrite.complete(Unit)
            runCurrent()
            stop.join()
            assertTrue(scan.isCancelled)
            assertTrue(overlap.isCancelled)
            assertEquals(1, persisted.size)
            assertEquals(1, recordTriggers.size)
            assertEquals(BleScannerManager.ScanState.Idle, scannerManager.scanState.value)
        }

    @Test
    fun `stop cancels pending fresh location and saves the cached observation once`() =
        runTest(dispatcher) {
            val locating = CompletableDeferred<Unit>()
            val locationCancelled = CompletableDeferred<Unit>()
            val finishLocation = CompletableDeferred<Unit>()
            coEvery { locationRepository.getCurrentLocation() } coAnswers {
                locating.complete(Unit)
                try {
                    finishLocation.await()
                    null
                } finally {
                    locationCancelled.complete(Unit)
                }
            }
            val scan = startScan()
            sighting(result("01"))
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(locating.isCompleted)
            val stop = launch { scannerManager.stopAndSaveScanResults() }
            try {
                runCurrent()
                assertTrue(locationCancelled.isCompleted)
                assertTrue(stop.isCompleted)
                assertTrue(scan.isCancelled)
                assertEquals(1, persisted.size)
                assertEquals(1, recordTriggers.size)
                assertEquals(BleScannerManager.ScanState.Idle, scannerManager.scanState.value)
            } finally {
                finishLocation.complete(Unit)
                stop.join()
            }
        }

    @Test
    fun `cancelling the initiating caller preserves cancellation and saves its partial batch`() =
        runTest(dispatcher) {
            val scan = startScan()
            sighting(result("01"))
            scan.cancel(CancellationException("Learn Mode ended"))
            runCurrent()
            scan.join()

            assertTrue(runCatching { scan.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, sourceCloses)
            assertEquals(0, incoming.subscriptionCount.value)
            assertEquals(1, persisted.size)
            assertEquals(1, recordTriggers.size)
            scannerManager.stopAndSaveScanResults()
            assertEquals(1, persisted.size)
        }

    @Test
    fun `cancelling a coalesced caller does not cancel the owner`() =
        runTest(dispatcher) {
            val owner = startScan()
            val waiter = async { scannerManager.performManualScan() }
            runCurrent()
            waiter.cancel()
            runCurrent()
            assertFalse(owner.isCompleted)
            assertEquals(1, incoming.subscriptionCount.value)
            sighting(result("01"))
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, owner.await())
            assertEquals(1, sourceStarts)
            assertEquals(1, persisted.size)
        }

    @Test
    fun `caller timeout is not mistaken for the scan window ending`() =
        runTest(dispatcher) {
            val outcome =
                async {
                    runCatching { withTimeout(300) { scannerManager.performManualScan() } }.exceptionOrNull()
                }
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            sighting(result("01"))
            advanceTimeBy(100)
            runCurrent()

            assertTrue(outcome.await() is TimeoutCancellationException)
            assertEquals(1, sourceCloses)
            assertEquals(1, persisted.size)
            assertEquals(1, recordTriggers.size)
        }

    @Test
    fun `single scan entry point persists before returning and later processing does not repeat it`() =
        runTest(dispatcher) {
            val scan = async { scannerManager.performSingleScan(1000) }
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            sighting(result("01"))
            advanceTimeBy(1000)
            runCurrent()
            scan.await()

            assertEquals(1, scannerManager.processScanResults(Constants.SCAN_TRIGGER_PERIODIC))
            assertEquals(1, persisted.size)
            assertEquals(listOf(Constants.SCAN_TRIGGER_PERIODIC), recordTriggers)
        }

    @Test
    fun `Nordic service data reaches persisted classification and only physical fingerprints`() =
        runTest(dispatcher) {
            val key = ByteArray(20) { it.toByte() }
            val findHub = ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x41) + key + 0xA5.toByte())
            val appleNearby = ad(0xFF, byteArrayOf(0x4C, 0x00, 0x10, 0x02, 0x00, 0x00))
            val tile = ad(0x03, byteArrayOf(0xED.toByte(), 0xFE.toByte()))
            val fastPair = ad(0x16, byteArrayOf(0x2C, 0xFE.toByte(), 0x01, 0x02, 0x03))
            val eddystone = ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x00) + ByteArray(20))
            val scan = startScan()
            sighting(result("01", findHub))
            sighting(result("02", appleNearby + findHub))
            sighting(result("03", tile))
            sighting(result("04", fastPair))
            sighting(result("05", eddystone))
            advanceTimeBy(1000)
            runCurrent()

            assertEquals(5, scan.await())
            persisted.take(2).forEach {
                assertTrue(it.isTracker)
                assertEquals("TRACKER", it.deviceType)
                assertEquals("SERVICE_DATA", it.method)
                assertEquals("FHN:000102030405060708090A0B0C0D0E0F10111213", it.fingerprint)
            }
            assertTrue(persisted[2].isTracker)
            assertNull(persisted[2].fingerprint)
            persisted.drop(3).forEach {
                assertFalse(it.isTracker)
                assertNull(it.fingerprint)
            }
        }

    @Test
    fun `changed service bytes replace cached tracker evidence for the same address`() =
        runTest(dispatcher) {
            val key = ByteArray(20) { it.toByte() }
            val valid = ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x40) + key)
            val truncated = ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x41) + key)
            val scan = startScan()
            sighting(result("01", valid))
            sighting(result("01", truncated))
            advanceTimeBy(1000)
            runCurrent()

            assertEquals(1, scan.await())
            assertFalse(persisted.single().isTracker)
            assertNull(persisted.single().fingerprint)
        }

    @Test
    fun `Nordic native filters retain unknown advertisers and cancellation unregisters the callback`() =
        runTest(dispatcher) {
            val context = mockk<Context>()
            val bluetoothManager = mockk<BluetoothManager>()
            val adapter = mockk<BluetoothAdapter>()
            val nativeScanner = mockk<android.bluetooth.le.BluetoothLeScanner>(relaxed = true)
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
            every { bluetoothManager.adapter } returns adapter
            every { adapter.bluetoothLeScanner } returns nativeScanner
            every { adapter.bondedDevices } returns emptySet()
            val filters = slot<List<ScanFilter>>()
            val settings = slot<ScanSettings>()
            val callback = slot<ScanCallback>()
            every { nativeScanner.startScan(capture(filters), capture(settings), capture(callback)) } just Runs
            scannerManager = manager(BleScanner(context)::scan)
            val scan = startScan()
            val device = mockk<BluetoothDevice>()
            every { device.address } returns "AA:BB:CC:DD:EE:01"
            every { device.name } returns null
            @Suppress("DEPRECATION")
            val unknown = ScanResult(device, null, -45, 1L)

            // Exercise Nordic's actual native mapping and Android matching, not copied filter construction.
            assertTrue(filters.captured.any { it.serviceUuid != null })
            assertTrue(filters.captured.any { it.matches(unknown) })
            assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.captured.scanMode)
            assertEquals(0L, settings.captured.reportDelayMillis)
            callback.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, unknown)
            runCurrent()
            assertEquals(1, scannerManager.getCurrentDeviceCount())

            scannerManager.stopScanning()
            scannerManager.stopAndSaveScanResults()
            runCurrent()
            assertTrue(scan.isCancelled)
            verify(exactly = 1) { nativeScanner.stopScan(callback.captured) }
            assertEquals(1, persisted.size)
            callback.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, unknown)
            runCurrent()
            assertEquals(1, persisted.size)
            assertEquals(BleScannerManager.ScanState.Idle, scannerManager.scanState.value)
        }

    private fun manager(source: (List<BleScanFilter>, BleScannerSettings) -> Flow<BleScanResult>) =
        BleScannerManager(
            deviceRepository,
            locationRepository,
            settingsRepository,
            whitelistRepository,
            alertGenerator,
            source,
            dispatcher,
            dispatcher,
        )

    private fun TestScope.startScan(trigger: String = Constants.SCAN_TRIGGER_MANUAL): Deferred<Int> {
        val scan = async { scannerManager.performManualScan(trigger) }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        return scan
    }

    private suspend fun TestScope.sighting(result: BleScanResult) {
        incoming.emit(result)
        runCurrent()
    }

    private fun result(
        suffix: String,
        advertisement: ByteArray = byteArrayOf(0),
        rssi: Int = -55,
    ): BleScanResult =
        BleScanResult(
            MockServerDevice(name = null, address = "AA:BB:CC:DD:EE:$suffix"),
            BleScanResultData(rssi, 0L, ScanRecordSerializer.parseFromBytes(advertisement)),
        )

    private fun ad(
        type: Int,
        payload: ByteArray,
    ): ByteArray = byteArrayOf((payload.size + 1).toByte(), type.toByte()) + payload
}
