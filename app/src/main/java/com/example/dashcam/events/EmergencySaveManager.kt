package com.example.dashcam.events

/**
 * Policy layer over SegmentManager.protectRange: decides *which* time range to protect.
 * Used by the manual button and by detected events.
 */
interface EmergencySaveManager {
    /** Protects the configured window before now, plus the segment currently being written. */
    suspend fun saveLast()

    suspend fun protectAround(event: DrivingEvent)
}
