package com.tailbait.ui.screens.alert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.repository.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AlertViewModel @Inject constructor(
    private val alertRepository: AlertRepository
) : ViewModel() {

    val uiState: StateFlow<AlertListUiState> = combine(
        alertRepository.getActiveAlerts(),
        alertRepository.getDismissedAlerts()
    ) { activeAlerts, dismissedAlerts ->
        AlertListUiState.Success(activeAlerts = activeAlerts, dismissedAlerts = dismissedAlerts)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AlertListUiState.Loading
    )

    sealed class AlertListUiState {
        object Loading : AlertListUiState()
        data class Success(
            val activeAlerts: List<AlertHistory>,
            val dismissedAlerts: List<AlertHistory>
        ) : AlertListUiState()
        data class Error(val message: String) : AlertListUiState()
    }
}
