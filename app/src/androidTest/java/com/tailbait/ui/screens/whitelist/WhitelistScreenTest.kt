package com.tailbait.ui.screens.whitelist

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.data.repository.WhitelistRepositoryImpl
import com.tailbait.ui.theme.TailBaitTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhitelistScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: TailBaitDatabase
    private lateinit var whitelistRepository: WhitelistRepository
    private var viewModel: WhitelistViewModel? = null

    private val ownDevice =
        ScannedDevice(
            id = 1L,
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Phone",
            firstSeen = 1000L,
            lastSeen = 2000L,
        )
    private val partnerDevice =
        ownDevice.copy(
            id = 2L,
            address = "11:22:33:44:55:66",
            name = "Test Watch",
        )
    private val trustedDevice =
        ownDevice.copy(
            id = 3L,
            address = "AA:11:BB:22:CC:33",
            name = null,
        )

    @Before
    fun setup() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java).build()
            whitelistRepository = WhitelistRepositoryImpl(database.whitelistEntryDao(), database.scannedDeviceDao())
            database.scannedDeviceDao().insertAll(listOf(ownDevice, partnerDevice, trustedDevice))
            whitelistRepository.addMultipleToWhitelist(
                listOf(
                    WhitelistEntry(
                        deviceId = ownDevice.id,
                        label = "My Phone",
                        category = WhitelistRepository.Category.OWN,
                        notes = "Personal device",
                        createdAt = 3000L,
                    ),
                    WhitelistEntry(
                        deviceId = partnerDevice.id,
                        label = "Partner's Watch",
                        category = WhitelistRepository.Category.PARTNER,
                        addedViaLearnMode = true,
                        createdAt = 3001L,
                    ),
                    WhitelistEntry(
                        deviceId = trustedDevice.id,
                        label = "Office Beacon",
                        category = WhitelistRepository.Category.TRUSTED,
                        createdAt = 3002L,
                    ),
                ),
            )
            Unit
        }

    @After
    fun tearDown() {
        composeTestRule.runOnUiThread { viewModel?.viewModelScope?.cancel() }
        database.close()
    }

    @Test
    fun whitelistScreen_categoryAndSearchFiltersIntersectAndCanBeCleared() {
        showScreen()
        composeTestRule.onNodeWithText("My Devices (1)").performClick()
        awaitEntries(ownDevice.id)
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Partner's Watch").assertDoesNotExist()
        composeTestRule.onNodeWithText("Office Beacon").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Search whitelist").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("wAtCh")
        awaitEntries()
        composeTestRule.onNodeWithText("My Phone").assertDoesNotExist()
        composeTestRule.onNodeWithText("Partner's Watch").assertDoesNotExist()

        composeTestRule.onNodeWithText("All").performClick()
        awaitEntries(partnerDevice.id)
        composeTestRule.onNodeWithText("Partner's Watch").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Phone").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        awaitEntries(ownDevice.id, partnerDevice.id, trustedDevice.id)
        composeTestRule.onNodeWithContentDescription("Close search").performClick()
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun whitelistScreen_editsUnnamedDeviceAndRejectsBlankLabel() {
        showScreen()
        composeTestRule.onNodeWithContentDescription("Search whitelist").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(trustedDevice.address)
        awaitEntries(trustedDevice.id)
        composeTestRule.onNodeWithText("Office Beacon").performClick()

        val labelField = composeTestRule.onNode(hasSetTextAction() and hasText("Label"))
        labelField.performTextClearance()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        labelField.performTextInput("Shared Beacon")
        composeTestRule.onNodeWithText("Partner's Devices").performScrollTo().performClick()
        composeTestRule.onNode(hasSetTextAction() and hasText("Notes (Optional)"))
            .performScrollTo().performTextInput("Shared with my partner")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitUntil(5000) { !requireNotNull(viewModel).uiState.value.showEditDialog }

        runBlocking {
            val entry = requireNotNull(whitelistRepository.getEntryByDeviceId(trustedDevice.id))
            assertEquals("Shared Beacon", entry.label)
            assertEquals(WhitelistRepository.Category.PARTNER, entry.category)
            assertEquals("Shared with my partner", entry.notes)
            assertTrue(whitelistRepository.isDeviceWhitelisted(trustedDevice.id))
            assertNull(database.scannedDeviceDao().getById(trustedDevice.id)?.name)
        }
    }

    @Test
    fun whitelistScreen_cancelEditPreservesTrustMetadata() {
        showScreen()
        composeTestRule.onNodeWithContentDescription("Search whitelist").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(ownDevice.address)
        awaitEntries(ownDevice.id)
        composeTestRule.onNodeWithText("My Phone").performClick()
        composeTestRule.onNode(hasSetTextAction() and hasText("Label"))
            .performTextReplacement("Unsaved label")
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitUntil(5000) { !requireNotNull(viewModel).uiState.value.showEditDialog }

        runBlocking {
            val entry = requireNotNull(whitelistRepository.getEntryByDeviceId(ownDevice.id))
            assertEquals("My Phone", entry.label)
            assertEquals("Personal device", entry.notes)
            assertTrue(whitelistRepository.isDeviceWhitelisted(ownDevice.id))
        }
    }

    @Test
    fun whitelistScreen_cancelThenConfirmRemovalOnlyRevokesSelectedTrust() {
        showScreen()
        composeTestRule.onNodeWithContentDescription("Search whitelist").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(ownDevice.address)
        awaitEntries(ownDevice.id)
        composeTestRule.onNodeWithContentDescription("Remove from whitelist").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitUntil(5000) { !requireNotNull(viewModel).uiState.value.showDeleteConfirmation }
        runBlocking { assertTrue(whitelistRepository.isDeviceWhitelisted(ownDevice.id)) }

        composeTestRule.onNodeWithContentDescription("Remove from whitelist").performClick()
        composeTestRule.onNodeWithText("Remove").performClick()
        awaitEntries()
        composeTestRule.waitUntil(5000) { !requireNotNull(viewModel).uiState.value.showDeleteConfirmation }

        runBlocking {
            assertFalse(whitelistRepository.isDeviceWhitelisted(ownDevice.id))
            assertTrue(whitelistRepository.isDeviceWhitelisted(partnerDevice.id))
            assertTrue(whitelistRepository.isDeviceWhitelisted(trustedDevice.id))
            assertNotNull(database.scannedDeviceDao().getById(ownDevice.id))
        }
    }

    @Test
    fun addToWhitelistDialog_requiresSelectionAndNonblankLabelBeforeConfirm() {
        var confirmedEntry: WhitelistEntry? = null
        composeTestRule.setContent {
            TailBaitTheme {
                AddToWhitelistDialog(
                    devices = listOf(ownDevice, partnerDevice),
                    onDismiss = {},
                    onConfirm = { deviceId, label, category, notes ->
                        confirmedEntry =
                            WhitelistEntry(
                                deviceId = deviceId,
                                label = label,
                                category = category,
                                notes = notes,
                            )
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
        composeTestRule.onNodeWithText(ownDevice.address).performClick()
        val labelField = composeTestRule.onNode(hasSetTextAction() and hasText("Label"))
        labelField.performScrollTo().performTextClearance()
        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
        labelField.performTextInput("Travel Phone")
        composeTestRule.onNodeWithText("Trusted Devices").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Add").performClick()

        composeTestRule.runOnIdle {
            val entry = requireNotNull(confirmedEntry)
            assertEquals(ownDevice.id, entry.deviceId)
            assertEquals("Travel Phone", entry.label)
            assertEquals(WhitelistRepository.Category.TRUSTED, entry.category)
            assertNull(entry.notes)
        }
    }

    @Test
    fun addToWhitelistDialog_emptyDevicesCannotConfirmAndCanBeCancelled() {
        var dismissed = false
        var confirmed = false
        composeTestRule.setContent {
            TailBaitTheme {
                AddToWhitelistDialog(
                    devices = emptyList(),
                    onDismiss = { dismissed = true },
                    onConfirm = { _, _, _, _ -> confirmed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle {
            assertTrue(dismissed)
            assertFalse(confirmed)
        }
    }

    private fun showScreen() {
        composeTestRule.runOnUiThread { viewModel = WhitelistViewModel(whitelistRepository) }
        composeTestRule.setContent {
            TailBaitTheme {
                WhitelistScreen(onNavigateBack = {}, viewModel = requireNotNull(viewModel))
            }
        }
        awaitEntries(ownDevice.id, partnerDevice.id, trustedDevice.id)
    }

    private fun awaitEntries(vararg deviceIds: Long) {
        val expectedIds = deviceIds.toSet()
        composeTestRule.waitUntil(5000) {
            val state = requireNotNull(viewModel).uiState.value
            !state.isLoading && state.filteredEntries.map { it.device.id }.toSet() == expectedIds
        }
    }
}
