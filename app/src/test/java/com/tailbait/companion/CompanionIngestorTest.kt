package com.tailbait.companion

import android.os.ParcelUuid
import androidx.room.Room
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.CompanionDeviceRepository
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LocationRepository
import com.tailbait.util.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionIngestorTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var deviceRepository: DeviceRepositoryImpl
    private lateinit var locationRepository: LocationRepository
    private lateinit var ingestor: CompanionIngestor
    private lateinit var link: CompanionLinkManager
    private lateinit var records: MutableSharedFlow<CompanionProtocol.Record>

    private val location =
        Location(
            id = 1,
            latitude = 51.5,
            longitude = -0.12,
            accuracy = 5f,
            timestamp = TIMESTAMP,
            provider = "gps",
        )

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        runBlocking { database.locationDao().insert(location) }
        deviceRepository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
        locationRepository = mockk()
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { locationRepository.findOrCreateLocation(location, 50.0) } returns (location.id to false)
        records = MutableSharedFlow(extraBufferCapacity = 16)
        link = mockk()
        every { link.records } returns records
        ingestor = CompanionIngestor(deviceRepository, locationRepository, mockk<CompanionDeviceRepository>(relaxed = true))
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    @Test
    fun `attach twice persists one observation and detach permits a clean reattach`() =
        runTest {
            ingestor.attach(link, backgroundScope)
            ingestor.attach(link, backgroundScope)
            assertEquals(1, records.subscriptionCount.value)

            records.emit(wifiRecord())
            runCurrent()
            val stored = database.scannedDeviceDao().getByAddressAndRadio(ADDRESS, "WIFI_STA")!!
            assertEquals(1, deviceRepository.getDeviceLocationRecordsForDevice(stored.id).size)
            assertEquals(1L, ingestor.counters.sta.get())

            ingestor.detach()
            assertEquals(0, records.subscriptionCount.value)
            records.emit(wifiRecord().copy(timestamp = TIMESTAMP + 1))
            runCurrent()
            assertEquals(1, deviceRepository.getDeviceLocationRecordsForDevice(stored.id).size)
            assertEquals(1L, ingestor.counters.sta.get())

            ingestor.attach(link, backgroundScope)
            records.emit(wifiRecord().copy(timestamp = TIMESTAMP + 2))
            runCurrent()
            val observations = deviceRepository.getDeviceLocationRecordsForDevice(stored.id)
            assertEquals(2, observations.size)
            assertTrue(observations.all { it.scanTriggerType == Constants.SCAN_TRIGGER_COMPANION })
            assertEquals(2L, ingestor.counters.sta.get())
            ingestor.detach()
        }

    @Test
    fun `service scope cancellation stops ingestion without permanently disabling the singleton`() =
        runTest {
            val serviceJob = Job(backgroundScope.coroutineContext[Job])
            val serviceScope = CoroutineScope(backgroundScope.coroutineContext + serviceJob)
            ingestor.attach(link, serviceScope)
            serviceJob.cancelAndJoin()
            assertEquals(0, records.subscriptionCount.value)

            records.emit(wifiRecord())
            runCurrent()
            assertNull(deviceRepository.getDeviceByAddress(ADDRESS))
            assertEquals(0L, ingestor.counters.sta.get())

            ingestor.attach(link, backgroundScope)
            records.emit(wifiRecord())
            runCurrent()
            assertEquals(1, deviceRepository.getAllDeviceLocationRecords().size)
            assertEquals(1L, ingestor.counters.sta.get())
            ingestor.detach()
        }

    @Test
    fun `detach joins cancelled in flight ingestion before returning`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleaningUp = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            coEvery { locationRepository.getCurrentLocation() } coAnswers {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            ingestor.attach(link, backgroundScope)
            records.emit(wifiRecord())
            runCurrent()
            started.await()

            val detached = async { ingestor.detach() }
            runCurrent()
            cleaningUp.await()
            assertFalse(detached.isCompleted)
            records.emit(wifiRecord().copy(mac = "AA:BB:CC:DD:EE:02"))
            releaseCleanup.complete(Unit)
            detached.await()
            runCurrent()
            assertEquals(0, records.subscriptionCount.value)
            assertEquals(1L, ingestor.counters.sta.get())
            assertNull(deviceRepository.getDeviceByAddress("AA:BB:CC:DD:EE:02"))
            assertTrue(deviceRepository.getAllDeviceLocationRecords().isEmpty())
        }

    @Test
    fun `repository cancellation terminates collection rather than being swallowed`() =
        runTest {
            coEvery { locationRepository.getCurrentLocation() } throws CancellationException("Location request cancelled")
            ingestor.attach(link, backgroundScope)
            records.emit(wifiRecord())
            runCurrent()
            assertEquals(0, records.subscriptionCount.value)
            assertTrue(backgroundScope.isActive)

            records.emit(wifiRecord().copy(mac = "AA:BB:CC:DD:EE:02"))
            runCurrent()
            assertNull(deviceRepository.getDeviceByAddress("AA:BB:CC:DD:EE:02"))
            assertEquals(1L, ingestor.counters.sta.get())
            assertTrue(deviceRepository.getAllDeviceLocationRecords().isEmpty())
            ingestor.detach()
        }

    @Test
    fun `FEAA service bytes reach persisted shared classification and full identity`() =
        runTest {
            ingestor.attach(link, backgroundScope)
            var index = 0
            for (frame in listOf(0x40, 0x41)) {
                for (eidSize in listOf(20, 32)) {
                    index++
                    val eid = ByteArray(eidSize) { (it + index).toByte() }
                    val serviceBytes = byteArrayOf(frame.toByte()) + eid + byteArrayOf(0xA5.toByte())
                    val manufacturer =
                        if (frame == 0x41) {
                            // Generic Espressif evidence must not override a validated tracker frame.
                            ad(0xFF, byteArrayOf(0xE5.toByte(), 0x02, 0x01))
                        } else {
                            byteArrayOf()
                        }
                    val advertisement = manufacturer + ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte()) + serviceBytes)
                    val parsed = AdvPayloadParser.parse(advertisement)
                    assertArrayEquals(serviceBytes, parsed.serviceData.getValue(FEAA))

                    val address = "AA:BB:CC:DD:EE:0$index"
                    records.emit(CompanionProtocol.Record.BleAdv(address, -55, TIMESTAMP + index, advertisement))
                    runCurrent()
                    val stored = deviceRepository.getDeviceByAddress(address)!!
                    assertTrue(stored.isTracker)
                    assertEquals("TRACKER", stored.deviceType)
                    assertEquals("FHN:" + eid.joinToString("") { "%02X".format(it) }, stored.payloadFingerprint)
                    assertEquals(1, deviceRepository.getDeviceLocationRecordsForDevice(stored.id).size)
                }
            }
            assertEquals(4L, ingestor.counters.ble.get())
            assertEquals(4, deviceRepository.getAllDeviceLocationRecords().size)
            ingestor.detach()
        }

    @Test
    fun `truncated FEAA and bare service UUID are persisted without tracker identity`() =
        runTest {
            ingestor.attach(link, backgroundScope)
            val advertisements =
                listOf(
                    ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x41) + ByteArray(20) { 1 }),
                    ad(0x03, byteArrayOf(0xAA.toByte(), 0xFE.toByte())),
                )
            advertisements.forEachIndexed { index, advertisement ->
                val address = "AA:BB:CC:DD:EE:0${index + 1}"
                records.emit(CompanionProtocol.Record.BleAdv(address, -55, TIMESTAMP + index, advertisement))
                runCurrent()
                val stored = deviceRepository.getDeviceByAddress(address)!!
                assertFalse(stored.isTracker)
                assertNull(stored.payloadFingerprint)
                assertEquals(1, deviceRepository.getDeviceLocationRecordsForDevice(stored.id).size)
            }
            ingestor.detach()
        }

    @Test
    fun `32 and 128 bit service data retain little endian UUIDs and exact payloads`() {
        val uuid128 = UUID.fromString("feed0001-c497-4476-a7ed-727de7648ab1")
        val encoded128 =
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(uuid128.leastSignificantBits)
                .putLong(uuid128.mostSignificantBits)
                .array()
        val firstPayload = byteArrayOf(0x00, 0x01, 0xFF.toByte())
        val secondPayload = byteArrayOf(0x41, 0x04, 0x00, 0xA5.toByte())
        val parsed =
            AdvPayloadParser.parse(
                ad(0x20, byteArrayOf(0x78, 0x56, 0x34, 0x12) + firstPayload) +
                    ad(0x21, encoded128 + secondPayload) +
                    ad(0x21, byteArrayOf(1, 2, 3)),
            )
        assertEquals(2, parsed.serviceData.size)
        assertArrayEquals(firstPayload, parsed.serviceData.getValue(ParcelUuid.fromString("12345678-0000-1000-8000-00805f9b34fb")))
        assertArrayEquals(secondPayload, parsed.serviceData.getValue(ParcelUuid(uuid128)))
    }

    private fun wifiRecord() = CompanionProtocol.Record.WifiSta(ADDRESS, -50, 6, TIMESTAMP)

    private fun ad(
        type: Int,
        data: ByteArray,
    ): ByteArray = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val TIMESTAMP = 1_800_000_000_000L
        val FEAA: ParcelUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    }
}
