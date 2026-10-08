package com.example.dashcam.ui

import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dashcam.DashcamApp
import com.example.dashcam.events.DrivingEventMonitor
import com.example.dashcam.events.EventStatus
import com.example.dashcam.events.ManualSaveStatus
import com.example.dashcam.recording.RecordingEngine
import com.example.dashcam.recording.RecordingResult
import com.example.dashcam.recording.RecordingState
import kotlinx.coroutines.flow.StateFlow

class DashcamViewModel(
    private val engine: RecordingEngine,
    private val events: DrivingEventMonitor,
) : ViewModel() {
    val recordingState: StateFlow<RecordingState> = engine.state
    val lastRecording: StateFlow<RecordingResult?> = engine.lastRecording

    val lastEvent: StateFlow<EventStatus?> = events.lastEvent
    val lastManualSave: StateFlow<ManualSaveStatus?> = events.lastManualSave

    fun saveLastFiveMinutes() = events.saveLastFiveMinutes()

    fun dismissEvent() = events.dismissEvent()

    fun startRecording() = engine.start()

    fun stopRecording() = engine.stop()

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) =
        engine.setPreviewSurfaceProvider(provider)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as DashcamApp
                DashcamViewModel(app.container.recordingEngine, app.container.eventMonitor)
            }
        }
    }
}
