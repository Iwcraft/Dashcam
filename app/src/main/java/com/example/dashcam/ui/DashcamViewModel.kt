package com.example.dashcam.ui

import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dashcam.DashcamApp
import com.example.dashcam.events.DrivingEventMonitor
import com.example.dashcam.events.EventStatus
import com.example.dashcam.events.ManualSaveStatus
import com.example.dashcam.recording.RecordingEngine
import com.example.dashcam.recording.RecordingResult
import com.example.dashcam.recording.RecordingState
import com.example.dashcam.sensors.GpsStatus
import com.example.dashcam.sensors.SensorEngine
import com.example.dashcam.settings.RecordingSettings
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.storage.StorageManager
import com.example.dashcam.storage.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds no recording logic: every value here is a view of state owned by the service-side
 * engine, monitor and sensor engine (app-wide singletons). That is why a rotation, which
 * recreates the Activity and this ViewModel's consumers, loses nothing.
 */
class DashcamViewModel(
    private val engine: RecordingEngine,
    private val events: DrivingEventMonitor,
    private val storage: StorageManager,
    sensors: SensorEngine,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val recordingState: StateFlow<RecordingState> = engine.state
    val lastRecording: StateFlow<RecordingResult?> = engine.lastRecording

    val lastEvent: StateFlow<EventStatus?> = events.lastEvent
    val lastManualSave: StateFlow<ManualSaveStatus?> = events.lastManualSave
    val manualSaving: StateFlow<Boolean> = events.manualSaving

    val gps: StateFlow<GpsStatus> = sensors.gps
    val settings: StateFlow<RecordingSettings> = settingsRepository.settings

    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    /** Cheap: the cached index plus one StatFs. The screen calls it every few seconds while visible. */
    fun refreshUsage() {
        viewModelScope.launch {
            try {
                _usage.value = storage.usage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Informational only: keep the previous numbers.
            }
        }
    }

    fun saveLastFiveMinutes() = events.saveLastFiveMinutes()

    fun dismissEvent() = events.dismissEvent()

    fun startRecording() = engine.start()

    fun stopRecording() = engine.stop()

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) =
        engine.setPreviewSurfaceProvider(provider)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as DashcamApp).container
                DashcamViewModel(
                    engine = container.recordingEngine,
                    events = container.eventMonitor,
                    storage = container.storageManager,
                    sensors = container.sensorEngine,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }
}
