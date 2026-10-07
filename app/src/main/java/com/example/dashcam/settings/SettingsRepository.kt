package com.example.dashcam.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences is enough here: a handful of scalars, same process for UI and service. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    fun update(transform: (RecordingSettings) -> RecordingSettings) {
        val updated = transform(_settings.value)
        prefs.edit {
            putLong(KEY_SEGMENT_MS, updated.segmentDurationMs)
            putLong(KEY_STORAGE_LIMIT, updated.storageLimitBytes)
            putLong(KEY_EMERGENCY_WINDOW, updated.emergencySaveWindowMs)
            putBoolean(KEY_GPS, updated.gpsEnabled)
        }
        _settings.value = updated
    }

    private fun read(): RecordingSettings {
        val d = RecordingSettings()
        return RecordingSettings(
            segmentDurationMs = prefs.getLong(KEY_SEGMENT_MS, d.segmentDurationMs),
            storageLimitBytes = prefs.getLong(KEY_STORAGE_LIMIT, d.storageLimitBytes),
            emergencySaveWindowMs = prefs.getLong(KEY_EMERGENCY_WINDOW, d.emergencySaveWindowMs),
            gpsEnabled = prefs.getBoolean(KEY_GPS, d.gpsEnabled),
        )
    }

    private companion object {
        const val KEY_SEGMENT_MS = "segment_duration_ms"
        const val KEY_STORAGE_LIMIT = "storage_limit_bytes"
        const val KEY_EMERGENCY_WINDOW = "emergency_window_ms"
        const val KEY_GPS = "gps_enabled"
    }
}
