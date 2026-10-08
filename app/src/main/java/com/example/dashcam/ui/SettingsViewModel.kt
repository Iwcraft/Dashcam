package com.example.dashcam.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dashcam.DashcamApp
import com.example.dashcam.recording.RecordingState
import com.example.dashcam.settings.RecordingSettings
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.storage.StorageManager
import com.example.dashcam.storage.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val storage: StorageManager,
    recordingState: StateFlow<RecordingState>,
) : ViewModel() {
    val settings: StateFlow<RecordingSettings> = repository.settings
    val recordingState: StateFlow<RecordingState> = recordingState

    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    fun update(transform: (RecordingSettings) -> RecordingSettings) = repository.update(transform)

    fun refreshUsage() {
        viewModelScope.launch {
            try {
                _usage.value = storage.usage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the previous numbers; the display is informational only.
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as DashcamApp).container
                SettingsViewModel(
                    repository = container.settingsRepository,
                    storage = container.storageManager,
                    recordingState = container.recordingEngine.state,
                )
            }
        }
    }
}
