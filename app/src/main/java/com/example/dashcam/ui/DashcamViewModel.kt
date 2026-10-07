package com.example.dashcam.ui

import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dashcam.DashcamApp
import com.example.dashcam.recording.RecordingEngine
import com.example.dashcam.recording.RecordingResult
import com.example.dashcam.recording.RecordingState
import kotlinx.coroutines.flow.StateFlow

class DashcamViewModel(private val engine: RecordingEngine) : ViewModel() {
    val recordingState: StateFlow<RecordingState> = engine.state
    val lastRecording: StateFlow<RecordingResult?> = engine.lastRecording

    fun startRecording() = engine.start()

    fun stopRecording() = engine.stop()

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) =
        engine.setPreviewSurfaceProvider(provider)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as DashcamApp
                DashcamViewModel(app.container.recordingEngine)
            }
        }
    }
}
