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

    @Synchronized
    fun update(transform: (RecordingSettings) -> RecordingSettings) {
        val updated = transform(_settings.value)
        prefs.edit {
            putString(KEY_RESOLUTION, updated.resolution.name)
            putInt(KEY_FRAME_RATE, updated.frameRate)
            putLong(KEY_SEGMENT_MS, updated.segmentDurationMs)
            putBoolean(KEY_AUDIO, updated.audioEnabled)
            putString(KEY_VIDEO_QUALITY, updated.videoQuality.name)
            putBoolean(KEY_KEEP_SCREEN_ON, updated.keepScreenOn)
            putLong(KEY_STORAGE_LIMIT, updated.storageLimitBytes)
            putLong(KEY_EMERGENCY_WINDOW, updated.emergencySaveWindowMs)
            putBoolean(KEY_GPS, updated.gpsEnabled)
            putString(KEY_IMPACT, updated.impactSensitivity.name)
            putBoolean(KEY_AUTO_START, updated.autoStartRecording)
        }
        _settings.value = updated
    }

    private fun read(): RecordingSettings {
        val d = RecordingSettings()
        return RecordingSettings(
            resolution = enumOf(prefs.getString(KEY_RESOLUTION, null), d.resolution),
            frameRate = prefs.getInt(KEY_FRAME_RATE, d.frameRate)
                .takeIf { it in RecordingSettings.FRAME_RATE_OPTIONS } ?: d.frameRate,
            segmentDurationMs = prefs.getLong(KEY_SEGMENT_MS, d.segmentDurationMs),
            audioEnabled = prefs.getBoolean(KEY_AUDIO, d.audioEnabled),
            videoQuality = enumOf(prefs.getString(KEY_VIDEO_QUALITY, null), d.videoQuality),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, d.keepScreenOn),
            storageLimitBytes = prefs.getLong(KEY_STORAGE_LIMIT, d.storageLimitBytes),
            emergencySaveWindowMs = prefs.getLong(KEY_EMERGENCY_WINDOW, d.emergencySaveWindowMs),
            gpsEnabled = prefs.getBoolean(KEY_GPS, d.gpsEnabled),
            impactSensitivity = enumOf(prefs.getString(KEY_IMPACT, null), d.impactSensitivity),
            autoStartRecording = prefs.getBoolean(KEY_AUTO_START, d.autoStartRecording),
        )
    }

    /** An unknown or missing stored name falls back to the default instead of crashing. */
    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FRAME_RATE = "frame_rate"
        const val KEY_SEGMENT_MS = "segment_duration_ms"
        const val KEY_AUDIO = "audio_enabled"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_STORAGE_LIMIT = "storage_limit_bytes"
        const val KEY_EMERGENCY_WINDOW = "emergency_window_ms"
        const val KEY_GPS = "gps_enabled"
        const val KEY_IMPACT = "impact_sensitivity"
        const val KEY_AUTO_START = "auto_start_recording"
    }
}
