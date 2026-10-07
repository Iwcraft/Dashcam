package com.example.dashcam.events

enum class EventType { HARD_BRAKING, POSSIBLE_IMPACT }

data class DrivingEvent(
    val type: EventType,
    val timestampMs: Long,
    val peakMagnitude: Float,
)
