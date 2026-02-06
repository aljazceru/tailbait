package com.tailbait.algorithm

import com.tailbait.data.database.entities.ScannedDevice
import org.junit.Assert.*
import org.junit.Test

class ShadowKeyGeneratorTest {

    private fun device(
        manufacturerId: Int? = null,
        deviceType: String? = null,
        appleContinuityType: Int? = null,
        isTracker: Boolean = false,
        findMySeparated: Boolean = false,
        beaconType: String? = null,
        txPowerLevel: Int? = null,
        serviceUuids: String? = null
    ) = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = null,
        firstSeen = 1000L,
        lastSeen = 2000L,
        manufacturerId = manufacturerId,
        deviceType = deviceType,
        appleContinuityType = appleContinuityType,
        isTracker = isTracker,
        findMySeparated = findMySeparated,
        beaconType = beaconType,
        txPowerLevel = txPowerLevel,
        serviceUuids = serviceUuids
    )

    @Test
    fun `AirTag generates correct shadow key`() {
        val airtag = device(
            manufacturerId = 0x004C,
            deviceType = "TRACKER",
            appleContinuityType = 0x12,
            isTracker = true,
            findMySeparated = true,
            beaconType = "FIND_MY",
            txPowerLevel = -7
        )

        val key = ShadowKeyGenerator.generate(airtag)

        assertNotNull(key)
        assertTrue("Key should contain manufacturer: $key", key!!.contains("M:004C"))
        assertTrue("Key should contain device type: $key", key.contains("T:TRACKER"))
        assertTrue("Key should contain continuity type: $key", key.contains("C:12"))
        assertTrue("Key should contain tracker flag: $key", key.contains("TR:1"))
        assertTrue("Key should contain separated flag: $key", key.contains("SEP:1"))
        assertTrue("Key should contain beacon type: $key", key.contains("B:FIND_MY"))
        assertTrue("Key should contain TX power: $key", key.contains("P:-7"))
    }

    @Test
    fun `iPhone generates different key than AirTag`() {
        val airtag = device(
            manufacturerId = 0x004C,
            deviceType = "TRACKER",
            appleContinuityType = 0x12,
            isTracker = true
        )
        val iphone = device(
            manufacturerId = 0x004C,
            deviceType = "PHONE",
            appleContinuityType = 0x10
        )

        val airtagKey = ShadowKeyGenerator.generate(airtag)
        val iphoneKey = ShadowKeyGenerator.generate(iphone)

        assertNotNull(airtagKey)
        assertNotNull(iphoneKey)
        assertNotEquals("AirTag and iPhone should have different keys", airtagKey, iphoneKey)
    }

    @Test
    fun `two AirTags with different MACs produce same shadow key`() {
        val airtag1 = device(
            manufacturerId = 0x004C,
            deviceType = "TRACKER",
            appleContinuityType = 0x12,
            isTracker = true,
            beaconType = "FIND_MY"
        )
        // Same properties, different MAC (not relevant to shadow key)
        val airtag2 = airtag1.copy(id = 2L, address = "11:22:33:44:55:66")

        val key1 = ShadowKeyGenerator.generate(airtag1)
        val key2 = ShadowKeyGenerator.generate(airtag2)

        assertEquals("Same device type should produce same shadow key", key1, key2)
    }

    @Test
    fun `device with fewer than 2 components returns null`() {
        // Only manufacturer ID - not enough components
        val sparse = device(manufacturerId = 0x004C)
        val key = ShadowKeyGenerator.generate(sparse)
        assertNull("Should return null with only 1 component", key)
    }

    @Test
    fun `device with exactly 2 components returns valid key`() {
        val minimal = device(
            manufacturerId = 0x004C,
            deviceType = "PHONE"
        )
        val key = ShadowKeyGenerator.generate(minimal)
        assertNotNull("Should return key with 2 components", key)
        assertEquals("M:004C|T:PHONE", key)
    }

    @Test
    fun `specificity score reflects component count`() {
        val twoComponent = "M:004C|T:TRACKER"
        val fiveComponent = "B:FIND_MY|C:12|M:004C|T:TRACKER|TR:1"

        val score2 = ShadowKeyGenerator.specificityScore(twoComponent)
        val score5 = ShadowKeyGenerator.specificityScore(fiveComponent)

        assertTrue("More components should give higher score", score5 > score2)
        assertEquals(2.0f / ShadowKeyGenerator.MAX_COMPONENTS, score2, 0.001f)
        assertEquals(5.0f / ShadowKeyGenerator.MAX_COMPONENTS, score5, 0.001f)
    }

    @Test
    fun `components are sorted for stability`() {
        // Even if properties are set in different orders, the key should be the same
        val device = device(
            manufacturerId = 0x004C,
            deviceType = "TRACKER",
            isTracker = true,
            beaconType = "FIND_MY"
        )

        val key = ShadowKeyGenerator.generate(device)
        assertNotNull(key)

        // Verify components are alphabetically sorted
        val parts = key!!.split("|")
        assertEquals(parts.sorted(), parts)
    }

    @Test
    fun `Samsung SmartTag generates correct key`() {
        val smartTag = device(
            manufacturerId = 0x0075,
            deviceType = "TRACKER",
            isTracker = true,
            serviceUuids = "0000FD5A-0000-1000-8000-00805F9B34FB"
        )

        val key = ShadowKeyGenerator.generate(smartTag)

        assertNotNull(key)
        assertTrue("Key should contain Samsung ID: $key", key!!.contains("M:0075"))
        assertTrue("Key should contain tracker type: $key", key.contains("T:TRACKER"))
        assertTrue("Key should contain tracker flag: $key", key.contains("TR:1"))
        assertTrue("Key should contain service UUID: $key", key.contains("U:FD5A"))
    }

    @Test
    fun `UNKNOWN device type is excluded from key`() {
        val unknown = device(
            manufacturerId = 0x004C,
            deviceType = "UNKNOWN",
            txPowerLevel = -7
        )

        val key = ShadowKeyGenerator.generate(unknown)
        assertNotNull(key)
        assertFalse("UNKNOWN type should not appear in key: $key", key!!.contains("T:UNKNOWN"))
    }

    @Test
    fun `zero manufacturer ID is excluded from key`() {
        val zero = device(
            manufacturerId = 0,
            deviceType = "PHONE",
            txPowerLevel = -7
        )

        val key = ShadowKeyGenerator.generate(zero)
        assertNotNull(key)
        assertFalse("Zero manufacturer ID should not appear: $key", key!!.contains("M:0000"))
    }
}
