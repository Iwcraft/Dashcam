package com.example.dashcam.events

enum class EventType {
    /** Reserved: not produced yet. */
    HARD_BRAKING,

    /** Strong acceleration and strong rotation close together. Not a confirmed crash. */
    POSSIBLE_IMPACT,
}

data class DrivingEvent(
    val type: EventType,
    /** Epoch ms of the acceleration peak: the same clock as segment start times. */
    val timestampMs: Long,
    /** Peak acceleration with gravity removed, m/s². */
    val peakAccelMps2: Float,
    /** Peak angular speed, rad/s. */
    val peakRotationRadPerSec: Float,
    /** Null unless GPS is on and had a fresh fix. */
    val speedMps: Float? = null,
)
