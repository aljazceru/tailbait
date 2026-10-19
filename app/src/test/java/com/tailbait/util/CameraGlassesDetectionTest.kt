package com.tailbait.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Camera glasses detection tests — signals from zuckoff.app:
 * - 0x0D53 Luxottica (Ray-Ban Meta, Oakley Meta) manufacturer ID
 * - 0x058E Meta Platforms Technologies wearable manufacturer ID
 * - 0x03C2 Snap Spectacles manufacturer ID
 * - 0xFD5F Oculus VR service UUID
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraGlassesDetectionTest {
    private fun parse(
        id: Int,
        payload: ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04),
    ) = ManufacturerDataParser.parseManufacturerData(id, payload)!!

    @Test
    fun `luxottica manufacturer id 0x0D53 classifies as camera glasses`() {
        val info = parse(0x0D53)
        assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, info.deviceType)
        assertEquals("Luxottica (Ray-Ban/Oakley Meta)", info.manufacturerName)
        assertTrue(info.deviceModel!!.contains("Ray-Ban Meta"))
        assertFalse(info.isTracker)
    }

    @Test
    fun `meta platforms manufacturer id 0x058E classifies as camera glasses`() {
        val info = parse(0x058E)
        assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, info.deviceType)
        assertFalse(info.isTracker)
    }

    @Test
    fun `meta platforms inc manufacturer id 0x01AB classifies as camera glasses`() {
        val info = parse(0x01AB)
        assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, info.deviceType)
        assertEquals("Meta Platforms", info.manufacturerName)
        assertFalse(info.isTracker)
    }

    @Test
    fun `snap manufacturer id 0x03C2 classifies as camera glasses`() {
        val info = parse(0x03C2)
        assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, info.deviceType)
        assertEquals("Snap Spectacles", info.deviceModel)
        assertFalse(info.isTracker)
    }

    @Test
    fun `oculus service uuid classifies glasses without a physical fingerprint`() {
        val serviceUuids = listOf(android.os.ParcelUuid(java.util.UUID.fromString("0000FD5F-0000-1000-8000-00805F9B34FB")))
        assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, DeviceIdentifier.getDeviceTypeFromServiceUuids(serviceUuids))
        assertNull(
            ManufacturerDataParser.extractBestFingerprint(
                manufacturerId = null,
                manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                serviceUuids = serviceUuids,
            ),
        )
    }

    @Test
    fun `shared glasses manufacturer profiles do not link physical devices`() {
        for (manufacturerId in listOf(0x0D53, 0x058E, 0x01AB, 0x03C2)) {
            assertNull(
                ManufacturerDataParser.extractBestFingerprint(
                    manufacturerId = manufacturerId,
                    manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    serviceUuids = listOf(android.os.ParcelUuid(java.util.UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))),
                ),
            )
        }
    }
}
