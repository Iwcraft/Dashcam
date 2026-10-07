package com.example.dashcam

import android.content.Context
import com.example.dashcam.recording.DefaultRecordingEngine
import com.example.dashcam.settings.SettingsRepository

/**
 * Manual wiring. The UI process and RecordingService share one process, so they must share
 * the same engine/settings instances; a DI framework isn't needed for that.
 * Each later phase adds its implementation here.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)

    // Concrete type on purpose: RecordingService uses the service-side callbacks that are
    // not part of the UI-facing RecordingEngine interface.
    val recordingEngine = DefaultRecordingEngine(appContext)
}
