package com.example.dashcam.recording

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data object Recording : RecordingState
    data object Stopping : RecordingState
    data class Error(val message: String) : RecordingState
}
