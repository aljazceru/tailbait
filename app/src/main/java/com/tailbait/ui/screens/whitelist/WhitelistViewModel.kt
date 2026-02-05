package com.tailbait.ui.screens.whitelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Whitelist Screen.
 *
 * This ViewModel manages the state and business logic for the whitelist management screen.
 * It provides:
 * - Display of whitelisted devices with device information
 * - Category-based filtering (OWN, PARTNER, TRUSTED)
 * - Search functionality
 * - Add/Edit/Remove operations
 * - Statistics (counts by category)
 *
 * The ViewModel observes the whitelist repository and combines it with device data
 * to provide a complete UI state for display.
 *
 * @property whitelistRepository Repository for whitelist data
 */
@HiltViewModel
class WhitelistViewModel @Inject constructor(
    private val whitelistRepository: WhitelistRepository
) : ViewModel() {

    /**
     * UI State for the Whitelist Screen
     */
    data class WhitelistUiState(
        val isLoading: Boolean = true,
        val entries: List<WhitelistRepository.WhitelistEntryWithDevice> = emptyList(),
        val filteredEntries: List<WhitelistRepository.WhitelistEntryWithDevice> = emptyList(),
        val selectedCategory: String? = null,
        val searchQuery: String = "",
        val totalCount: Int = 0,
        val ownCount: Int = 0,
        val partnerCount: Int = 0,
        val trustedCount: Int = 0,
        val errorMessage: String? = null,
        val showAddDialog: Boolean = false,
        val showEditDialog: Boolean = false,
        val editingEntry: WhitelistEntry? = null,
        val showDeleteConfirmation: Boolean = false,
        val deletingEntry: WhitelistRepository.WhitelistEntryWithDevice? = null
    )

    // Internal state flows
    private val _uiState = MutableStateFlow(WhitelistUiState())
    val uiState: StateFlow<WhitelistUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow<String?>(null)

    init {
        Timber.d("WhitelistViewModel initialized")
        initializeViewModel()
    }

    /**
     * Initialize the ViewModel by observing data sources
     */
    private fun initializeViewModel() {
        viewModelScope.launch {
            try {
                // Combine all data sources into UI state
                combine(
                    whitelistRepository.getAllEntriesWithDevices(),
                    whitelistRepository.getWhitelistCount(),
                    whitelistRepository.getCountByCategory(WhitelistRepository.Category.OWN),
                    whitelistRepository.getCountByCategory(WhitelistRepository.Category.PARTNER),
                    whitelistRepository.getCountByCategory(WhitelistRepository.Category.TRUSTED),
                    _searchQuery,
                    _selectedCategory
                ) { values ->
                    val entries = values[0] as List<WhitelistRepository.WhitelistEntryWithDevice>
                    val totalCount = values[1] as Int
                    val ownCount = values[2] as Int
                    val partnerCount = values[3] as Int
                    val trustedCount = values[4] as Int
                    val searchQuery = values[5] as String
                    val selectedCategory = values[6] as String?

                    // Apply filtering based on category and search
                    val filteredEntries = entries
                        .filter { entryWithDevice ->
                            // Apply category filter
                            val categoryMatch = selectedCategory == null ||
                                    entryWithDevice.entry.category == selectedCategory

                            // Apply search filter
                            val searchMatch = searchQuery.isBlank() ||
                                    entryWithDevice.entry.label.contains(searchQuery, ignoreCase = true) ||
                                    entryWithDevice.device.name?.contains(searchQuery, ignoreCase = true) == true ||
                                    entryWithDevice.device.address.contains(searchQuery, ignoreCase = true) ||
                                    entryWithDevice.entry.notes?.contains(searchQuery, ignoreCase = true) == true

                            categoryMatch && searchMatch
                        }

                    WhitelistUiState(
                        isLoading = false,
                        entries = entries,
                        filteredEntries = filteredEntries,
                        selectedCategory = selectedCategory,
                        searchQuery = searchQuery,
                        totalCount = totalCount,
                        ownCount = ownCount,
                        partnerCount = partnerCount,
                        trustedCount = trustedCount
                    )
                }.catch { e ->
                    Timber.e(e, "Error observing whitelist data")
                    emit(
                        WhitelistUiState(
                            isLoading = false,
                            errorMessage = "Failed to load whitelist: ${e.message}"
                        )
                    )
                }.collect { state ->
                    _uiState.value = state.copy(
                        showAddDialog = _uiState.value.showAddDialog,
                        showEditDialog = _uiState.value.showEditDialog,
                        editingEntry = _uiState.value.editingEntry,
                        showDeleteConfirmation = _uiState.value.showDeleteConfirmation,
                        deletingEntry = _uiState.value.deletingEntry,
                        errorMessage = _uiState.value.errorMessage
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing WhitelistViewModel")
                _uiState.value = WhitelistUiState(
                    isLoading = false,
                    errorMessage = "Failed to initialize: ${e.message}"
                )
            }
        }
    }

    // ========== Category Filter Actions ==========

    /**
     * Set the selected category filter.
     * Pass null to clear the filter.
     */
    fun selectCategory(category: String?) {
        Timber.d("Category selected: $category")
        _selectedCategory.value = category
    }

    /**
     * Clear the category filter.
     */
    fun clearCategoryFilter() {
        selectCategory(null)
    }

    // ========== Search Actions ==========

    /**
     * Update the search query.
     */
    fun updateSearchQuery(query: String) {
        Timber.d("Search query updated: $query")
        _searchQuery.value = query
    }

    /**
     * Clear the search query.
     */
    fun clearSearch() {
        _searchQuery.value = ""
    }

    // ========== Dialog Actions ==========

    /**
     * Show the add device dialog.
     */
    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    /**
     * Hide the add device dialog.
     */
    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    /**
     * Show the edit dialog for an entry.
     */
    fun showEditDialog(entry: WhitelistEntry) {
        _uiState.value = _uiState.value.copy(
            showEditDialog = true,
            editingEntry = entry
        )
    }

    /**
     * Hide the edit dialog.
     */
    fun hideEditDialog() {
        _uiState.value = _uiState.value.copy(
            showEditDialog = false,
            editingEntry = null
        )
    }

    /**
     * Show delete confirmation dialog.
     */
    fun showDeleteConfirmation(entry: WhitelistRepository.WhitelistEntryWithDevice) {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirmation = true,
            deletingEntry = entry
        )
    }

    /**
     * Hide delete confirmation dialog.
     */
    fun hideDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirmation = false,
            deletingEntry = null
        )
    }

    // ========== CRUD Operations ==========

    /**
     * Add a device to the whitelist.
     */
    fun addToWhitelist(
        deviceId: Long,
        label: String,
        category: String,
        notes: String?
    ) {
        viewModelScope.launch {
            try {
                Timber.i("Adding device $deviceId to whitelist with label: $label, category: $category")

                whitelistRepository.addToWhitelist(
                    deviceId = deviceId,
                    label = label,
                    category = category,
                    notes = notes,
                    addedViaLearnMode = false
                )

                hideAddDialog()
                Timber.i("Device added to whitelist successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add device to whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to add device: ${e.message}"
                )
            }
        }
    }

    /**
     * Update an existing whitelist entry.
     */
    fun updateEntry(
        entryId: Long,
        label: String,
        category: String,
        notes: String?
    ) {
        viewModelScope.launch {
            try {
                Timber.i("Updating whitelist entry $entryId")

                // Get the current entry and update it
                val currentEntry = whitelistRepository.getEntryById(entryId)
                currentEntry?.let { entry ->
                    val updatedEntry = entry.copy(
                        label = label,
                        category = category,
                        notes = notes
                    )
                    whitelistRepository.updateEntry(updatedEntry)
                }

                hideEditDialog()
                Timber.i("Whitelist entry updated successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to update whitelist entry")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to update entry: ${e.message}"
                )
            }
        }
    }

    /**
     * Remove a device from the whitelist.
     */
    fun removeFromWhitelist(entryId: Long) {
        viewModelScope.launch {
            try {
                Timber.i("Removing entry $entryId from whitelist")

                whitelistRepository.removeFromWhitelist(entryId)

                hideDeleteConfirmation()
                Timber.i("Entry removed from whitelist successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove entry from whitelist")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to remove entry: ${e.message}"
                )
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Get formatted category name for display.
     */
    fun getCategoryDisplayName(category: String): String {
        return when (category) {
            WhitelistRepository.Category.OWN -> "My Devices"
            WhitelistRepository.Category.PARTNER -> "Partner's Devices"
            WhitelistRepository.Category.TRUSTED -> "Trusted Devices"
            else -> category
        }
    }

    /**
     * Get all available categories.
     */
    fun getAllCategories(): List<String> {
        return WhitelistRepository.Category.values()
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("WhitelistViewModel cleared")
    }
}
