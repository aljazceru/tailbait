package com.tailbait.ui.screens.alert

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.tailbait.R
import com.tailbait.ui.components.EmptyView
import com.tailbait.ui.components.KeyValueColumn
import com.tailbait.ui.components.LoadingView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    navController: NavController,
    viewModel: AlertDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alert Details") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button_desc)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                uiState.isLoading -> {
                    LoadingView(message = "Loading alert details...")
                }
                uiState.errorMessage != null -> {
                    EmptyView(
                        title = "Error Loading Alert",
                        message = uiState.errorMessage
                    )
                }
                uiState.alert != null -> {
                    AlertDetailContent(state = uiState, onDeviceClick = {
                        // TODO: Navigate to device detail screen
                    })
                }
            }
        }
    }
}

@Composable
private fun AlertDetailContent(
    state: AlertDetailViewModel.AlertDetailUiState,
    onDeviceClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Alert Info Section
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeyValueColumn(label = "Title", value = state.alert?.title ?: "Unknown")
                    KeyValueColumn(label = "Message", value = state.alert?.message ?: "No message")
                    KeyValueColumn(label = "Level", value = state.alert?.alertLevel ?: "Unknown")
                    KeyValueColumn(label = "Timestamp", value = formatTimestamp(state.alert?.timestamp ?: 0))
                }
            }
        }

        // Threat Score Section
        item {
            ThreatScoreBreakdown(
                locationScore = state.threatScoreBreakdown?.locationScore ?: 0.0,
                distanceScore = state.threatScoreBreakdown?.distanceScore ?: 0.0,
                timeScore = state.threatScoreBreakdown?.timeScore ?: 0.0,
                consistencyScore = state.threatScoreBreakdown?.consistencyScore ?: 0.0,
                deviceTypeScore = state.threatScoreBreakdown?.deviceTypeScore ?: 0.0
            )
        }

        // Involved Devices Section
        item {
            Text(
                text = "Involved Devices",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(state.involvedDevices.size) { index ->
            val device = state.involvedDevices[index]
            InvolvedDeviceCard(
                deviceAddress = device.address,
                deviceName = device.name,
                onClick = { onDeviceClick(device.address) }
            )
        }
    }
}

/**
 * Helper function to format timestamp
 */
private fun formatTimestamp(timestamp: Long): String {
    return if (timestamp > 0) {
        java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    } else {
        "Unknown"
    }
}
