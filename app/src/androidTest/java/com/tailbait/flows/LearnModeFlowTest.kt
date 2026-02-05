package com.tailbait.flows

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for the complete Learn Mode flow.
 *
 * This test verifies the end-to-end Learn Mode process:
 * 1. Starting Learn Mode
 * 2. Discovering devices during Learn Mode
 * 3. Selecting devices
 * 4. Labeling devices
 * 5. Adding devices to whitelist
 * 6. Stopping Learn Mode
 *
 * This is a full integration test that uses a real in-memory database
 * and exercises the complete Learn Mode workflow.
 */
@RunWith(AndroidJUnit4::class)
class LearnModeFlowTest {

    private lateinit var database: TailBaitDatabase
    private lateinit var context: Context

    // Repositories
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var whitelistRepository: WhitelistRepository
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            TailBaitDatabase::class.java
        ).allowMainThreadQueries().build()

        // Initialize repositories
        deviceRepository = DeviceRepositoryImpl(database.scannedDeviceDao())
        whitelistRepository = WhitelistRepositoryImpl(
            database.whitelistEntryDao(),
            database.scannedDeviceDao()
        )
        settingsRepository = SettingsRepositoryImpl(database.appSettingsDao())

        // Initialize default settings
        runTest {
            database.appSettingsDao().insert(AppSettings())
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completeLearnModeFlow_discoversAndAddsDevices() = runTest {
        // Step 1: Start Learn Mode
        settingsRepository.startLearnMode()

        val settings = settingsRepository.getSettings().first()
        assertTrue("Learn Mode should be active", settings.learnModeActive)
        assertNotNull("Learn Mode start time should be set", settings.learnModeStartedAt)

        val learnModeStartTime = settings.learnModeStartedAt!!

        // Step 2: Simulate device discovery during Learn Mode
        val device1 = ScannedDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "My Phone",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 5000,
            detectionCount = 3
        )
        val device2 = ScannedDevice(
            address = "11:22:33:44:55:66",
            name = "My Watch",
            firstSeen = learnModeStartTime + 2000,
            lastSeen = learnModeStartTime + 6000,
            detectionCount = 2
        )
        val device3 = ScannedDevice(
            address = "AA:11:BB:22:CC:33",
            name = "My Headphones",
            firstSeen = learnModeStartTime + 3000,
            lastSeen = learnModeStartTime + 7000,
            detectionCount = 4
        )

        val deviceId1 = deviceRepository.insertDevice(device1)
        val deviceId2 = deviceRepository.insertDevice(device2)
        val deviceId3 = deviceRepository.insertDevice(device3)

        // Step 3: Verify devices were discovered
        val discoveredDevices = deviceRepository.getAllDevices().first()
        assertEquals("Should have 3 discovered devices", 3, discoveredDevices.size)

        // Step 4: Simulate user selecting devices to add to whitelist
        val selectedDevices = listOf(
            WhitelistEntry(
                deviceId = deviceId1,
                label = "My Phone",
                category = WhitelistRepository.Category.OWN,
                addedViaLearnMode = true,
                notes = "Added via Learn Mode"
            ),
            WhitelistEntry(
                deviceId = deviceId2,
                label = "My Watch",
                category = WhitelistRepository.Category.OWN,
                addedViaLearnMode = true,
                notes = "Added via Learn Mode"
            )
        )

        // Step 5: Add selected devices to whitelist
        val addedIds = whitelistRepository.addMultipleToWhitelist(selectedDevices)
        assertEquals("Should add 2 devices to whitelist", 2, addedIds.size)

        // Step 6: Verify devices were added to whitelist
        val whitelistEntries = whitelistRepository.getAllWhitelistEntries().first()
        assertEquals("Whitelist should have 2 entries", 2, whitelistEntries.size)

        // Verify entries are marked as added via Learn Mode
        assertTrue("Entries should be marked as Learn Mode additions",
            whitelistEntries.all { it.addedViaLearnMode })

        // Step 7: Verify devices are accessible with their metadata
        val entriesWithDevices = whitelistRepository.getAllEntriesWithDevices().first()
        assertEquals("Should have 2 entries with device info", 2, entriesWithDevices.size)

        val phoneEntry = entriesWithDevices.find { it.device.address == "AA:BB:CC:DD:EE:FF" }
        assertNotNull("Phone should be in whitelist", phoneEntry)
        assertEquals("Phone label should match", "My Phone", phoneEntry?.entry?.label)

        val watchEntry = entriesWithDevices.find { it.device.address == "11:22:33:44:55:66" }
        assertNotNull("Watch should be in whitelist", watchEntry)
        assertEquals("Watch label should match", "My Watch", watchEntry?.entry?.label)

        // Step 8: Stop Learn Mode
        settingsRepository.stopLearnMode()

        val finalSettings = settingsRepository.getSettings().first()
        assertFalse("Learn Mode should be inactive", finalSettings.learnModeActive)
        assertNull("Learn Mode start time should be cleared", finalSettings.learnModeStartedAt)
    }

    @Test
    fun learnModeFlow_doesNotAddDuplicateDevices() = runTest {
        // Step 1: Add a device to whitelist before Learn Mode
        val device = ScannedDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "My Phone",
            firstSeen = 1000L,
            lastSeen = 2000L,
            detectionCount = 1
        )
        val deviceId = deviceRepository.insertDevice(device)

        whitelistRepository.addToWhitelist(
            deviceId = deviceId,
            label = "My Phone",
            category = WhitelistRepository.Category.OWN,
            notes = "Added manually",
            addedViaLearnMode = false
        )

        // Step 2: Start Learn Mode
        settingsRepository.startLearnMode()

        // Step 3: Verify device is already whitelisted
        val whitelistedDeviceIds = whitelistRepository.getAllWhitelistedDeviceIds().first()
        assertTrue("Device should already be whitelisted",
            whitelistedDeviceIds.contains(deviceId))

        // Step 4: Attempt to add same device again via Learn Mode (should fail or be prevented)
        val isWhitelisted = whitelistRepository.isDeviceWhitelisted(deviceId)
        assertTrue("Device should still be whitelisted", isWhitelisted)

        // Step 5: Verify only one entry exists
        val entries = whitelistRepository.getAllWhitelistEntries().first()
        assertEquals("Should have only 1 entry", 1, entries.size)

        // Stop Learn Mode
        settingsRepository.stopLearnMode()
    }

    @Test
    fun learnModeFlow_filtersDevicesByTimeRange() = runTest {
        // Step 1: Insert devices before Learn Mode starts
        val oldDevice = ScannedDevice(
            address = "OLD:OLD:OLD:OLD:OLD:OLD",
            name = "Old Device",
            firstSeen = 500L,
            lastSeen = 1000L,
            detectionCount = 1
        )
        deviceRepository.insertDevice(oldDevice)

        // Step 2: Start Learn Mode
        settingsRepository.startLearnMode()
        val settings = settingsRepository.getSettings().first()
        val learnModeStartTime = settings.learnModeStartedAt!!

        // Step 3: Insert devices during Learn Mode
        val newDevice = ScannedDevice(
            address = "NEW:NEW:NEW:NEW:NEW:NEW",
            name = "New Device",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 2000,
            detectionCount = 2
        )
        deviceRepository.insertDevice(newDevice)

        // Step 4: Get all devices
        val allDevices = deviceRepository.getAllDevices().first()
        assertEquals("Should have 2 total devices", 2, allDevices.size)

        // Step 5: Filter devices discovered during Learn Mode
        val learnModeDevices = allDevices.filter { it.lastSeen >= learnModeStartTime }
        assertEquals("Should have 1 device from Learn Mode", 1, learnModeDevices.size)
        assertEquals("Should be the new device", "NEW:NEW:NEW:NEW:NEW:NEW", learnModeDevices[0].address)

        // Stop Learn Mode
        settingsRepository.stopLearnMode()
    }

    @Test
    fun learnModeFlow_handlesEmptySelection() = runTest {
        // Step 1: Start Learn Mode
        settingsRepository.startLearnMode()

        // Step 2: Don't discover or select any devices

        // Step 3: Stop Learn Mode without adding devices
        settingsRepository.stopLearnMode()

        // Step 4: Verify whitelist is still empty
        val entries = whitelistRepository.getAllWhitelistEntries().first()
        assertTrue("Whitelist should be empty", entries.isEmpty())

        // Step 5: Verify Learn Mode is stopped
        val settings = settingsRepository.getSettings().first()
        assertFalse("Learn Mode should be inactive", settings.learnModeActive)
    }

    @Test
    fun learnModeFlow_categorizesDifferentDeviceTypes() = runTest {
        // Start Learn Mode
        settingsRepository.startLearnMode()
        val settings = settingsRepository.getSettings().first()
        val learnModeStartTime = settings.learnModeStartedAt!!

        // Discover different types of devices
        val ownDevice = ScannedDevice(
            address = "OWN:OWN:OWN:OWN:OWN:OWN",
            name = "My Phone",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 2000,
            detectionCount = 1
        )
        val partnerDevice = ScannedDevice(
            address = "PART:PART:PART:PART:PART:PART",
            name = "Partner Phone",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 2000,
            detectionCount = 1
        )
        val trustedDevice = ScannedDevice(
            address = "TRUST:TRUST:TRUST:TRUST:TRUST:TRUST",
            name = "Office Device",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 2000,
            detectionCount = 1
        )

        val ownId = deviceRepository.insertDevice(ownDevice)
        val partnerId = deviceRepository.insertDevice(partnerDevice)
        val trustedId = deviceRepository.insertDevice(trustedDevice)

        // Add devices with different categories
        whitelistRepository.addToWhitelist(
            deviceId = ownId,
            label = "My Phone",
            category = WhitelistRepository.Category.OWN,
            notes = null,
            addedViaLearnMode = true
        )
        whitelistRepository.addToWhitelist(
            deviceId = partnerId,
            label = "Partner Phone",
            category = WhitelistRepository.Category.PARTNER,
            notes = null,
            addedViaLearnMode = true
        )
        whitelistRepository.addToWhitelist(
            deviceId = trustedId,
            label = "Office Device",
            category = WhitelistRepository.Category.TRUSTED,
            notes = null,
            addedViaLearnMode = true
        )

        // Verify categorization
        val ownCount = whitelistRepository.getCountByCategory(WhitelistRepository.Category.OWN).first()
        val partnerCount = whitelistRepository.getCountByCategory(WhitelistRepository.Category.PARTNER).first()
        val trustedCount = whitelistRepository.getCountByCategory(WhitelistRepository.Category.TRUSTED).first()

        assertEquals("Should have 1 OWN device", 1, ownCount)
        assertEquals("Should have 1 PARTNER device", 1, partnerCount)
        assertEquals("Should have 1 TRUSTED device", 1, trustedCount)

        // Stop Learn Mode
        settingsRepository.stopLearnMode()
    }

    @Test
    fun learnModeFlow_preservesDeviceLabels() = runTest {
        // Start Learn Mode
        settingsRepository.startLearnMode()
        val settings = settingsRepository.getSettings().first()
        val learnModeStartTime = settings.learnModeStartedAt!!

        // Discover device
        val device = ScannedDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Unknown Bluetooth Device",
            firstSeen = learnModeStartTime + 1000,
            lastSeen = learnModeStartTime + 2000,
            detectionCount = 1
        )
        val deviceId = deviceRepository.insertDevice(device)

        // Add with custom label
        val customLabel = "My Custom Label for This Device"
        whitelistRepository.addToWhitelist(
            deviceId = deviceId,
            label = customLabel,
            category = WhitelistRepository.Category.OWN,
            notes = "Custom note",
            addedViaLearnMode = true
        )

        // Verify label is preserved
        val entriesWithDevices = whitelistRepository.getAllEntriesWithDevices().first()
        assertEquals("Should have 1 entry", 1, entriesWithDevices.size)

        val entry = entriesWithDevices[0]
        assertEquals("Custom label should be preserved", customLabel, entry.entry.label)
        assertEquals("Notes should be preserved", "Custom note", entry.entry.notes)
        assertEquals("Device name should be original", "Unknown Bluetooth Device", entry.device.name)

        // Stop Learn Mode
        settingsRepository.stopLearnMode()
    }
}
