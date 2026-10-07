package com.example.dashcam.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.dashcam.service.RecordingService
import com.example.dashcam.utils.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultRecordingEngine(private val context: Context) : RecordingEngine {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    override fun start() {
        val current = _state.value
        if (current is RecordingState.Starting || current is RecordingState.Recording) return

        // On API 34+ a camera-type foreground service started without this permission throws.
        if (!context.hasPermission(Manifest.permission.CAMERA)) {
            _state.value = RecordingState.Error("Camera permission is required")
            return
        }

        _state.value = RecordingState.Starting
        try {
            ContextCompat.startForegroundService(context, serviceIntent())
        } catch (e: IllegalStateException) {
            // API 31+: foreground service starts from the background are restricted.
            _state.value = RecordingState.Error(e.message ?: "Could not start recording service")
        }
    }

    override fun stop() {
        if (_state.value is RecordingState.Idle) return
        _state.value = RecordingState.Stopping
        // false means the service wasn't running, so no onDestroy will report back.
        if (!context.stopService(serviceIntent())) _state.value = RecordingState.Idle
    }

    // --- Called by RecordingService only ---

    internal fun onServiceFailed(message: String) {
        _state.value = RecordingState.Error(message)
    }

    internal fun onServiceStopped() {
        // Keep a failure visible instead of overwriting it with Idle.
        if (_state.value !is RecordingState.Error) _state.value = RecordingState.Idle
    }

    private fun serviceIntent() = Intent(context, RecordingService::class.java)
}
