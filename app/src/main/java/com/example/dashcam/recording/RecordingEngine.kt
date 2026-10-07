package com.example.dashcam.recording

import androidx.camera.core.Preview
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing entry point. Requesting start/stop only asks the foreground service to do the
 * work; recording lives and dies with the service, never with an Activity or ViewModel.
 * Nothing here depends on Compose.
 */
interface RecordingEngine {
    val state: StateFlow<RecordingState>

    /** The most recent finished recording, read back from disk. Null until one exists. */
    val lastRecording: StateFlow<RecordingResult?>

    /** Idempotent. */
    fun start()

    /** Idempotent. The file is finalized before the service is stopped. */
    fun stop()

    /** Attach/detach an on-screen preview. Pass null whenever the UI is not visible. */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?)
}
