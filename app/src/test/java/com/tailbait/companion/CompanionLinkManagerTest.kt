package com.tailbait.companion

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.tailbait.data.repository.CompanionDeviceRepository
import io.mockk.CapturingSlot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionLinkManagerTest {
    private lateinit var context: Context
    private lateinit var repository: CompanionDeviceRepository
    private lateinit var scanner: BluetoothLeScanner
    private lateinit var device: BluetoothDevice
    private lateinit var gatt: BluetoothGatt
    private lateinit var service: BluetoothGattService
    private lateinit var data: BluetoothGattCharacteristic
    private lateinit var stat: BluetoothGattCharacteristic
    private lateinit var control: BluetoothGattCharacteristic
    private lateinit var dataCccd: BluetoothGattDescriptor
    private lateinit var statCccd: BluetoothGattDescriptor
    private lateinit var callback: CapturingSlot<BluetoothGattCallback>
    private lateinit var scanCallback: CapturingSlot<ScanCallback>
    private var manager: CompanionLinkManager? = null
    private val link: CompanionLinkManager get() = requireNotNull(manager)

    @Before
    fun setup() {
        context = mockk()
        repository = mockk(relaxed = true)
        val bluetoothManager = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>()
        scanner = mockk(relaxed = true)
        device = mockk()
        gatt = mockk(relaxed = true)
        callback = slot()
        scanCallback = slot()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.bluetoothLeScanner } returns scanner
        every { adapter.getRemoteDevice(ADDRESS) } returns device
        every { device.address } returns ADDRESS
        every { device.connectGatt(context, false, capture(callback), BluetoothDevice.TRANSPORT_LE) } returns gatt
        every { gatt.device } returns device
        every { gatt.discoverServices() } returns true
        every { gatt.requestMtu(247) } returns true
        every { gatt.setCharacteristicNotification(any(), true) } returns true
        every { gatt.writeDescriptor(any<BluetoothGattDescriptor>()) } returns true
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } returns true
        every { scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), capture(scanCallback)) } returns Unit

        data = characteristic(CompanionProtocol.DATA_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        stat = characteristic(CompanionProtocol.STAT_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        control = characteristic(CompanionProtocol.CTRL_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE)
        dataCccd = cccd().also { data.addDescriptor(it) }
        statCccd = cccd().also { stat.addDescriptor(it) }
        service =
            BluetoothGattService(UUID.fromString(CompanionProtocol.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(data)
                addCharacteristic(stat)
                addCharacteristic(control)
            }
        every { gatt.getService(UUID.fromString(CompanionProtocol.SERVICE_UUID)) } returns service
    }

    @After
    fun tearDown() {
        manager?.stop()
        unmockkAll()
    }

    @Test
    fun `only successful discovery MTU and both CCCDs make the link ready`() =
        runTest {
            connectTransport()
            link.start(ADDRESS)
            verify(exactly = 1) { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) }
            verify(exactly = 1) { gatt.discoverServices() }

            callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            assertConnecting()
            verify(exactly = 1) { gatt.requestMtu(247) }
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            assertConnecting()
            verify(exactly = 1) { gatt.setCharacteristicNotification(data, true) }
            verify(exactly = 1) { gatt.writeDescriptor(dataCccd) }

            // Even a valid unsolicited STATUS must not skip initialization.
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            assertConnecting()
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            assertConnecting()
            verify(exactly = 1) { gatt.setCharacteristicNotification(stat, true) }
            verify(exactly = 1) { gatt.writeDescriptor(statCccd) }
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            assertEquals(CompanionLinkManager.State.Connected(ADDRESS), link.state.value)
            runCurrent()
            coVerify(exactly = 1) { repository.markConnected(ADDRESS) }
            verify(exactly = 1) { gatt.writeCharacteristic(control) }

            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            assertEquals(CompanionLinkManager.State.Connected(ADDRESS, "1.2", CompanionProtocol.MODE_CARRY), link.state.value)
            callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            assertTrue(link.state.value is CompanionLinkManager.State.Reconnecting)
            verify(exactly = 1) { gatt.close() }
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            assertFalse(link.state.value is CompanionLinkManager.State.Connected)
        }

    @Test
    fun `permission revoked before stop closes the ready link and rejects stale records`() =
        runTest {
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            assertEquals(CompanionLinkManager.State.Connected(ADDRESS), link.state.value)

            val received = mutableListOf<CompanionProtocol.Record>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.records.collect { received.add(it) }
            }
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            runCurrent()
            assertEquals(1, received.size)
            coVerify(exactly = 1) { repository.markConnected(ADDRESS) }

            every { gatt.disconnect() } throws SecurityException("Bluetooth permission revoked")
            every { gatt.close() } throws SecurityException("Bluetooth permission revoked")
            link.stop()
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
            verify(exactly = 1) { gatt.disconnect() }
            verify(exactly = 1) { gatt.close() }

            callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            callback.captured.onCharacteristicChanged(gatt, data, statusPacket())
            link.setMode(CompanionProtocol.MODE_CARRY)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
            assertEquals(1, received.size)
            verify(exactly = 1) { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) }
            verify(exactly = 1) { gatt.writeCharacteristic(control) }
            coVerify(exactly = 1) { repository.markConnected(ADDRESS) }
        }

    @Test
    fun `unexpected disconnect failure surfaces after clearing readiness and attempting close`() =
        runTest {
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            assertEquals(CompanionLinkManager.State.Connected(ADDRESS), link.state.value)

            val failure = IllegalStateException("Unexpected transport failure")
            every { gatt.disconnect() } throws failure
            assertEquals(failure, assertThrows(IllegalStateException::class.java) { link.stop() })
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
            verify(exactly = 1) { gatt.close() }
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            runCurrent()
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
            coVerify(exactly = 0) { repository.markConnected(any()) }
        }

    @Test
    fun `rejected discovery closes transport without reporting readiness`() =
        runTest {
            every { gatt.discoverServices() } returns false
            startLink()
            callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            assertFailedClosed()
        }

    @Test
    fun `failed discovery callback cannot initialize a present service`() =
        runTest {
            connectTransport()
            callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
            assertFailedClosed()
            verify(exactly = 0) { gatt.requestMtu(any()) }
        }

    @Test
    fun `missing DATA characteristic cannot report readiness`() =
        runTest {
            val missingData =
                BluetoothGattService(UUID.fromString(CompanionProtocol.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    addCharacteristic(stat)
                    addCharacteristic(control)
                }
            every { gatt.getService(UUID.fromString(CompanionProtocol.SERVICE_UUID)) } returns missingData
            discoverServices()
            assertFailedClosed()
        }

    @Test
    fun `rejected MTU request cannot report readiness`() =
        runTest {
            every { gatt.requestMtu(247) } returns false
            discoverServices()
            assertFailedClosed()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            assertFalse(link.state.value is CompanionLinkManager.State.Connected)
        }

    @Test
    fun `failed MTU callback cannot subscribe or report readiness`() =
        runTest {
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_FAILURE)
            assertFailedClosed()
            verify(exactly = 0) { gatt.writeDescriptor(any<BluetoothGattDescriptor>()) }
        }

    @Test
    fun `rejected local notification registration closes the link`() =
        runTest {
            every { gatt.setCharacteristicNotification(data, true) } returns false
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            assertFailedClosed()
            verify(exactly = 0) { gatt.writeDescriptor(any<BluetoothGattDescriptor>()) }
        }

    @Test
    fun `rejected CCCD write closes the link`() =
        runTest {
            every { gatt.writeDescriptor(dataCccd) } returns false
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            assertFailedClosed()
        }

    @Test
    fun `failed DATA CCCD does not subscribe STAT or report readiness`() =
        runTest {
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_FAILURE)
            assertFailedClosed()
            verify(exactly = 0) { gatt.writeDescriptor(statCccd) }
        }

    @Test
    fun `failed STAT CCCD does not report readiness despite live DATA notifications`() =
        runTest {
            discoverServices()
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_FAILURE)
            assertFailedClosed()
        }

    @Test
    fun `initialization timeout closes transport and stop rejects late success callbacks`() =
        runTest {
            connectTransport()
            advanceTimeBy(20_000)
            runCurrent()
            verify(exactly = 1) { gatt.close() }
            assertFalse(link.state.value is CompanionLinkManager.State.Connected)
            coVerify(exactly = 0) { repository.markConnected(any()) }

            link.stop()
            callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, dataCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onDescriptorWrite(gatt, statCccd, BluetoothGatt.GATT_SUCCESS)
            callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
        }

    @Test
    fun `first pairing scan works before tracking and cancellation ignores later results`() =
        runTest {
            manager = CompanionLinkManager(context, repository, backgroundScope)
            val found = mutableListOf<ScanResult>()
            var done = 0
            link.scanForCompanions(onFound = { found.add(it) }, onDone = { done++ })
            assertTrue(scanCallback.isCaptured)
            val result = mockk<ScanResult>()
            scanCallback.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            assertEquals(listOf(result), found)

            link.stopScanningForCompanions()
            scanCallback.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            advanceTimeBy(16_000)
            runCurrent()
            assertEquals(1, found.size)
            assertEquals(0, done)
            verify(exactly = 1) { scanner.stopScan(scanCallback.captured) }
            verify(exactly = 0) { device.connectGatt(any(), any(), any(), any<Int>()) }
        }

    @Test
    fun `stop cancels a pending address resync without reconnecting or re-pairing`() =
        runTest {
            startLink()
            repeat(4) { attempt ->
                callback.captured.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
                advanceTimeBy(1_000L shl attempt)
                runCurrent()
            }
            assertTrue(scanCallback.isCaptured)
            verify(exactly = 4) { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) }
            link.stop()
            verify(exactly = 1) { scanner.stopScan(scanCallback.captured) }

            val changedDevice = mockk<BluetoothDevice>()
            val result = mockk<ScanResult>()
            every { changedDevice.address } returns "AA:BB:CC:DD:EE:02"
            every { result.device } returns changedDevice
            scanCallback.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(CompanionLinkManager.State.Idle, link.state.value)
            verify(exactly = 4) { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) }
            coVerify(exactly = 0) { repository.pair(any(), any()) }
            coVerify(exactly = 0) { repository.forget(any()) }
        }

    private fun TestScope.startLink() {
        manager = CompanionLinkManager(context, repository, backgroundScope)
        link.start(ADDRESS)
        runCurrent()
        assertTrue(callback.isCaptured)
        assertConnecting()
    }

    private fun TestScope.connectTransport() {
        startLink()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertConnecting()
    }

    private fun TestScope.discoverServices() {
        connectTransport()
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
    }

    private fun assertConnecting() {
        assertEquals(CompanionLinkManager.State.Connecting(ADDRESS), link.state.value)
    }

    private fun assertFailedClosed() {
        assertTrue(link.state.value is CompanionLinkManager.State.Error)
        callback.captured.onCharacteristicChanged(gatt, stat, statusPacket())
        assertFalse(link.state.value is CompanionLinkManager.State.Connected)
        verify(exactly = 1) { gatt.disconnect() }
        verify(exactly = 1) { gatt.close() }
        coVerify(exactly = 0) { repository.markConnected(any()) }
    }

    private fun characteristic(
        uuid: String,
        properties: Int,
    ) = BluetoothGattCharacteristic(UUID.fromString(uuid), properties, BluetoothGattCharacteristic.PERMISSION_WRITE)

    private fun cccd() = BluetoothGattDescriptor(UUID.fromString(CompanionProtocol.CCCD_UUID), BluetoothGattDescriptor.PERMISSION_WRITE)

    private fun statusPacket(): ByteArray =
        byteArrayOf(0x10, 32) +
            ByteArray(32).also {
                it[0] = 1
                it[1] = 2
                it[2] = CompanionProtocol.MODE_CARRY.toByte()
            }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
