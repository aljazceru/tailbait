package com.tailbait.ui.screens.alert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import androidx.navigation.NavController
import com.tailbait.R
import com.tailbait.ui.components.EmptyView
import com.tailbait.ui.components.LoadingView
import com.tailbait.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertScreen(
    navController: NavController,
    viewModel: AlertViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button_desc)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (val state = uiState) {
                is AlertViewModel.AlertListUiState.Loading -> {
                    LoadingView(message = "Loading alerts...")
                }
                is AlertViewModel.AlertListUiState.Error -> {
                    EmptyView(
                        title = "Error Loading Alerts",
                        message = state.message
                    )
                }
                is AlertViewModel.AlertListUiState.Success -> {
                    val allAlerts = state.activeAlerts + state.dismissedAlerts
                    if (allAlerts.isEmpty()) {
                        EmptyView(
                            title = "No Alerts",
                            message = "You're all safe! No suspicious devices detected.",
                            actionButtonText = "Go to Home",
                            onActionClick = { navController.navigate(Screen.Home.route) }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(TailBaitDimensions.SpacingLG),
                            verticalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingLG)
                        ) {
                            items(allAlerts.size) { index ->
                                val alert = allAlerts[index]
                                AlertCard(
                                    alert = alert,
                                    onClick = { navController.navigate("${Screen.AlertDetail.route}/${alert.id}") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
