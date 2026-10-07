package com.example.dashcam.recording

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState

    /**
     * The UI derives the running duration of the whole session from [startedAtElapsedMs]
     * (SystemClock.elapsedRealtime) so the engine does not have to emit a tick every second.
     */
    data class Recording(
        val startedAtElapsedMs: Long,
        /** e.g. "1080p", or "720p (fallback)" when the camera can't do the target. */
        val quality: String,
        /** 1-based. Changes at every rollover; [startedAtElapsedMs] does not, so the timer keeps running. */
        val segmentNumber: Int = 1,
    ) : RecordingState

    data object Stopping : RecordingState
    data class Error(val message: String) : RecordingState
}
