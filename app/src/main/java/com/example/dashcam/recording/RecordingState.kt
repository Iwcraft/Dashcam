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
        /** elapsedRealtime when the current segment started; the UI derives the segment time from it. */
        val segmentStartedAtElapsedMs: Long = startedAtElapsedMs,
    ) : RecordingState

    /**
     * The camera failed mid-session and the engine is restarting it (bounded, with back-off).
     * Nothing is being recorded right now. Becomes [Recording] again when a new segment starts,
     * or [Error] when the attempts are used up or the failure cannot be retried.
     */
    data class Recovering(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: String,
        val startedAtElapsedMs: Long,
        val quality: String,
        val segmentNumber: Int,
    ) : RecordingState

    data object Stopping : RecordingState
    data class Error(val message: String) : RecordingState
}
