package com.tailbait.data.repository

import androidx.room.Room
import app.cash.turbine.test
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WhitelistRepositoryTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: WhitelistRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = WhitelistRepositoryImpl(database.whitelistEntryDao(), database.scannedDeviceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `adding an alias trusts only its confirmed family and removal works from another alias`() =
        runTest {
            seedFamily()

            val entryId = repository.addToWhitelist(2L, "My tag", WhitelistRepository.Category.OWN, notes = "Personal")

            assertEquals(1L, repository.getEntryById(entryId)!!.deviceId)
            assertEquals("Personal", repository.getEntryByDeviceId(3L)!!.notes)
            for (id in 1L..3L) assertTrue(repository.isDeviceWhitelisted(id))
            assertFalse(repository.isDeviceWhitelisted(4L))
            assertFalse(repository.isDeviceWhitelisted(5L))
            assertNull(repository.getEntryByDeviceId(4L))
            assertEquals(setOf(1L, 2L, 3L), repository.getAllWhitelistedDeviceIds().first().toSet())

            assertEquals(1, repository.removeFromWhitelist(3L))
            assertTrue(repository.getAllWhitelistedDeviceIds().first().isEmpty())
            for (id in 1L..5L) assertFalse(repository.isDeviceWhitelisted(id))
            assertEquals(0, repository.removeFromWhitelist(2L))
        }

    @Test
    fun `legacy trust on confirmed children applies consistently and removal clears every family entry`() =
        runTest {
            seedFamily()
            database.whitelistEntryDao().insert(entry(101L, 2L, "First alias"))
            database.whitelistEntryDao().insert(entry(102L, 3L, "Second alias"))
            database.whitelistEntryDao().insert(entry(103L, 4L, "Different same-profile device"))

            assertEquals(setOf(1L, 2L, 3L, 4L), repository.getAllWhitelistedDeviceIds().first().toSet())
            for (id in 1L..4L) assertTrue(repository.isDeviceWhitelisted(id))
            assertFalse(repository.isDeviceWhitelisted(5L))
            assertEquals(101L, repository.getEntryByDeviceId(1L)!!.id)
            assertEquals(2, repository.removeFromWhitelist(1L))
            assertEquals(setOf(4L), repository.getAllWhitelistedDeviceIds().first().toSet())
            assertEquals("Different same-profile device", repository.getEntryByDeviceId(4L)!!.label)
        }

    @Test
    fun `weak orphan self and chained links never spread explicit trust`() =
        runTest {
            seedFamily()
            database.scannedDeviceDao().insert(device(6L, 999L, "STRONG"))
            database.scannedDeviceDao().insert(device(7L, 7L, "STRONG"))
            database.scannedDeviceDao().insert(device(8L, 2L, "STRONG"))
            database.scannedDeviceDao().insert(device(9L, 5L, "STRONG"))
            for (id in listOf(5L, 6L, 7L, 8L)) {
                val entryId = repository.addToWhitelist(id, "Explicit $id", WhitelistRepository.Category.TRUSTED)
                assertEquals(id, repository.getEntryById(entryId)!!.deviceId)
            }

            assertEquals(setOf(5L, 6L, 7L, 8L), repository.getAllWhitelistedDeviceIds().first().toSet())
            for (id in listOf(1L, 2L, 3L, 4L, 9L)) assertFalse(repository.isDeviceWhitelisted(id))
            assertEquals(0, repository.removeFromWhitelist(1L))
            assertEquals(1, repository.removeFromWhitelist(5L))
            assertEquals(setOf(6L, 7L, 8L), repository.getAllWhitelistedDeviceIds().first().toSet())
        }

    @Test
    fun `learn mode batch canonicalizes families while retaining labels and notes`() =
        runTest {
            seedFamily()
            val first = entry(101L, 2L, "My tag").copy(addedViaLearnMode = true, notes = "Tag note")
            val second = entry(102L, 4L, "Partner tag").copy(category = WhitelistRepository.Category.PARTNER)
            val ids = repository.addMultipleToWhitelist(listOf(first, second))

            assertEquals(listOf(101L, 102L), ids)
            assertEquals(first.copy(deviceId = 1L), repository.getEntryById(101L))
            assertEquals(second, repository.getEntryById(102L))
            assertEquals(setOf(1L, 2L, 3L, 4L), repository.getAllWhitelistedDeviceIds().first().toSet())
            assertFalse(repository.isDeviceWhitelisted(5L))
            assertEquals(listOf(first.copy(deviceId = 1L)), repository.getLearnModeEntries().first())
            assertEquals(listOf(second), repository.getEntriesByCategory(WhitelistRepository.Category.PARTNER).first())
        }

    @Test
    fun `whitelisted IDs include aliases linked after trust without including the shared profile`() =
        runTest {
            seedFamily()
            repository.addToWhitelist(1L, "My tag", WhitelistRepository.Category.OWN)
            database.scannedDeviceDao().insert(device(7L, 1L, "WEAK"))
            repository.getAllWhitelistedDeviceIds().test {
                assertEquals(setOf(1L, 2L, 3L), awaitItem().toSet())
                database.scannedDeviceDao().insert(device(6L, 1L, "STRONG"))
                assertEquals(setOf(1L, 2L, 3L, 6L), awaitItem().toSet())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repository.isDeviceWhitelisted(6L))
            assertFalse(repository.isDeviceWhitelisted(7L))
            assertFalse(repository.isDeviceWhitelisted(4L))
        }

    @Test
    fun `entry updates and UI joins preserve canonical device and user metadata`() =
        runTest {
            seedFamily()
            val id = repository.addToWhitelist(2L, "My tag", WhitelistRepository.Category.OWN)
            val updated = repository.getEntryById(id)!!.copy(label = "Keys", notes = "Blue case")
            repository.updateEntry(updated)

            val displayed = repository.getAllEntriesWithDevices().first().single()
            assertEquals(updated, displayed.entry)
            assertEquals(1L, displayed.device.id)
            assertEquals(listOf(updated), repository.searchEntries("Blue").first())
            assertEquals(1, repository.getWhitelistCount().first())
            repository.clearWhitelist()
            assertTrue(repository.getAllWhitelistEntries().first().isEmpty())
            assertFalse(repository.isDeviceWhitelisted(2L))
        }

    private suspend fun seedFamily() {
        database.scannedDeviceDao().insertAll(
            listOf(
                device(1L),
                device(2L, 1L, "STRONG"),
                device(3L, 1L, "STRONG"),
                device(4L),
                device(5L, 1L, "WEAK"),
            ),
        )
    }

    private fun device(
        id: Long,
        linkedId: Long? = null,
        strength: String? = null,
    ) = ScannedDevice(
        id = id,
        address = "00:00:00:00:00:${id.toString(16).padStart(2, '0')}",
        name = "Tag $id",
        firstSeen = 1000L,
        lastSeen = 2000L,
        payloadFingerprint = "FM:" + "AB".repeat(22),
        linkedDeviceId = linkedId,
        linkStrength = strength,
        shadowKey = "same-profile",
    )

    private fun entry(
        id: Long,
        deviceId: Long,
        label: String,
    ) = WhitelistEntry(
        id = id,
        deviceId = deviceId,
        label = label,
        category = WhitelistRepository.Category.OWN,
        createdAt = 3000L,
    )
}
