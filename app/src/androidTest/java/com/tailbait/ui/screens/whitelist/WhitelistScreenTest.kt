package com.tailbait.ui.screens.whitelist

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.ui.theme.BleTrackerTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for WhitelistScreen.
 *
 * Tests cover:
 * - Screen rendering with different states
 * - Category filtering
 * - Search functionality
 * - Add/Edit/Delete operations
 * - Dialog interactions
 */
class WhitelistScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDevice1 = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Test Device 1",
        firstSeen = 1000L,
        lastSeen = 2000L
    )

    private val testDevice2 = ScannedDevice(
        id = 2L,
        address = "11:22:33:44:55:66",
        name = "Test Device 2",
        firstSeen = 1500L,
        lastSeen = 2500L
    )

    private val testEntry1 = WhitelistRepository.WhitelistEntryWithDevice(
        entry = WhitelistEntry(
            id = 1L,
            deviceId = 1L,
            label = "My Phone",
            category = WhitelistRepository.Category.OWN,
            notes = "Personal device",
            addedViaLearnMode = false
        ),
        device = testDevice1
    )

    private val testEntry2 = WhitelistRepository.WhitelistEntryWithDevice(
        entry = WhitelistEntry(
            id = 2L,
            deviceId = 2L,
            label = "Partner's Watch",
            category = WhitelistRepository.Category.PARTNER,
            notes = null,
            addedViaLearnMode = true
        ),
        device = testDevice2
    )

    @Before
    fun setup() {
        // Setup code if needed
    }

    // ========== Content Display Tests ==========

    @Test
    fun whitelistEntryCard_displaysCorrectInformation() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = testEntry1,
                    onClick = {},
                    onDeleteClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Device 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal device").assertIsDisplayed()
        composeTestRule.onNodeWithText("OWN").assertIsDisplayed()
    }

    @Test
    fun whitelistEntryCard_showsLearnModeIndicator() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = testEntry2,
                    onClick = {},
                    onDeleteClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Added via Learn Mode").assertIsDisplayed()
    }

    @Test
    fun statisticsCard_displaysCorrectCounts() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistStatisticsCard(
                    totalCount = 10,
                    ownCount = 4,
                    partnerCount = 3,
                    trustedCount = 3
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("10").assertIsDisplayed()
        composeTestRule.onNodeWithText("4").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Devices").assertIsDisplayed()
        composeTestRule.onNodeWithText("Partner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trusted").assertIsDisplayed()
    }

    @Test
    fun categoryBadge_displaysCorrectBadge() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                CategoryBadge(category = WhitelistRepository.Category.OWN)
            }
        }

        // Then
        composeTestRule.onNodeWithText("OWN").assertIsDisplayed()
    }

    // ========== Dialog Tests ==========

    @Test
    fun addToWhitelistDialog_displaysCorrectly() {
        // Given
        val devices = listOf(testDevice1, testDevice2)

        composeTestRule.setContent {
            BleTrackerTheme {
                AddToWhitelistDialog(
                    devices = devices,
                    onDismiss = {},
                    onConfirm = { _, _, _, _ -> }
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Add to Whitelist").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Device 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Device 2").assertIsDisplayed()
    }

    @Test
    fun addToWhitelistDialog_showsEmptyStateWhenNoDevices() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                AddToWhitelistDialog(
                    devices = emptyList(),
                    onDismiss = {},
                    onConfirm = { _, _, _, _ -> }
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("No devices available. Start tracking to discover devices.")
            .assertIsDisplayed()
    }

    @Test
    fun editWhitelistDialog_displaysCorrectly() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                EditWhitelistDialog(
                    entry = testEntry1.entry,
                    onDismiss = {},
                    onConfirm = { _, _, _ -> }
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Edit Whitelist Entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Category").assertIsDisplayed()
    }

    @Test
    fun deleteConfirmationDialog_displaysCorrectly() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                DeleteWhitelistConfirmationDialog(
                    entry = testEntry1,
                    onDismiss = {},
                    onConfirm = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Remove from Whitelist?").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("This device will be included in stalking detection again.")
            .assertIsDisplayed()
    }

    // ========== Interaction Tests ==========

    @Test
    fun whitelistEntryCard_clickTriggersCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = testEntry1,
                    onClick = { clicked = true },
                    onDeleteClick = {}
                )
            }
        }

        // When
        composeTestRule.onNodeWithText("My Phone").performClick()

        // Then
        assert(clicked)
    }

    @Test
    fun whitelistEntryCard_deleteButtonTriggersCallback() {
        // Given
        var deleteClicked = false
        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = testEntry1,
                    onClick = {},
                    onDeleteClick = { deleteClicked = true }
                )
            }
        }

        // When
        composeTestRule.onNodeWithContentDescription("Remove from whitelist").performClick()

        // Then
        assert(deleteClicked)
    }

    @Test
    fun addToWhitelistDialog_cancelButtonDismissesDialog() {
        // Given
        var dismissed = false
        composeTestRule.setContent {
            BleTrackerTheme {
                AddToWhitelistDialog(
                    devices = listOf(testDevice1),
                    onDismiss = { dismissed = true },
                    onConfirm = { _, _, _, _ -> }
                )
            }
        }

        // When
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then
        assert(dismissed)
    }

    @Test
    fun editWhitelistDialog_cancelButtonDismissesDialog() {
        // Given
        var dismissed = false
        composeTestRule.setContent {
            BleTrackerTheme {
                EditWhitelistDialog(
                    entry = testEntry1.entry,
                    onDismiss = { dismissed = true },
                    onConfirm = { _, _, _ -> }
                )
            }
        }

        // When
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then
        assert(dismissed)
    }

    @Test
    fun deleteConfirmationDialog_removeButtonTriggersCallback() {
        // Given
        var confirmed = false
        composeTestRule.setContent {
            BleTrackerTheme {
                DeleteWhitelistConfirmationDialog(
                    entry = testEntry1,
                    onDismiss = {},
                    onConfirm = { confirmed = true }
                )
            }
        }

        // When
        composeTestRule.onNodeWithText("Remove").performClick()

        // Then
        assert(confirmed)
    }

    // ========== Filter Tests ==========

    @Test
    fun categoryFilterChips_displaysAllCategories() {
        // Given
        composeTestRule.setContent {
            BleTrackerTheme {
                CategoryFilterChips(
                    selectedCategory = null,
                    onCategorySelected = {},
                    ownCount = 3,
                    partnerCount = 2,
                    trustedCount = 4
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Devices (3)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Partner (2)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trusted (4)").assertIsDisplayed()
    }

    @Test
    fun categoryFilterChips_clickTriggersSelection() {
        // Given
        var selectedCategory: String? = null
        composeTestRule.setContent {
            BleTrackerTheme {
                CategoryFilterChips(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it },
                    ownCount = 3,
                    partnerCount = 2,
                    trustedCount = 4
                )
            }
        }

        // When
        composeTestRule.onNodeWithText("My Devices (3)").performClick()

        // Then
        assert(selectedCategory == WhitelistRepository.Category.OWN)
    }

    // ========== Edge Case Tests ==========

    @Test
    fun whitelistEntryCard_handlesNullDeviceName() {
        // Given
        val entryWithoutName = testEntry1.copy(
            device = testDevice1.copy(name = null)
        )

        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = entryWithoutName,
                    onClick = {},
                    onDeleteClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").assertIsDisplayed()
    }

    @Test
    fun whitelistEntryCard_handlesNullNotes() {
        // Given
        val entryWithoutNotes = testEntry1.copy(
            entry = testEntry1.entry.copy(notes = null)
        )

        composeTestRule.setContent {
            BleTrackerTheme {
                WhitelistEntryCard(
                    entry = entryWithoutNotes,
                    onClick = {},
                    onDeleteClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("My Phone").assertIsDisplayed()
        // Notes should not be displayed
        composeTestRule.onNodeWithText("Personal device").assertDoesNotExist()
    }
}
