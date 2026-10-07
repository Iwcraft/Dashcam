package com.example.dashcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DashcamScreen(viewModel: DashcamViewModel = viewModel(factory = DashcamViewModel.Factory)) {
    val state by viewModel.recordingState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dashcam", style = MaterialTheme.typography.headlineLarge)
        Text("Foundation ready", style = MaterialTheme.typography.bodyLarge)
        Text("State: ${state::class.simpleName}", style = MaterialTheme.typography.bodyMedium)
    }
}
