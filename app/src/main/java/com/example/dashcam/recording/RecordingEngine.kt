package com.example.dashcam.recording

import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing entry point. Requesting start/stop only asks the foreground service to do the
 * work; recording lives and dies with the service, never with an Activity or ViewModel.
 */
interface RecordingEngine {
    val state: StateFlow<RecordingState>

    /** Idempotent. */
    fun start()

    /** Idempotent. */
    fun stop()
}
