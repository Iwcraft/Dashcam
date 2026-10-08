package com.example.dashcam.events

import com.example.dashcam.storage.VideoState

/**
 * What a protection request achieved. Footage is moved by MediaStore folder change, never copied.
 * The segment still being written, and segments that start later inside the range, cannot be
 * moved yet: they are counted in [pendingSegments] and moved automatically when they finish.
 */
data class ProtectionResult(
    val state: VideoState,
    val protectedSegments: Int,
    val pendingSegments: Int,
)

/**
 * Policy layer over SegmentManager.protectRange: decides *which* time range to protect.
 * Used by the manual button and by detected events.
 */
interface EmergencySaveManager {
    /** Protects the configured window before now into Protected/. Safe to call repeatedly. */
    suspend fun saveLast(): ProtectionResult

    /** Protects the pre-event window and the post-event window into Event/. */
    suspend fun protectAround(event: DrivingEvent): ProtectionResult
}
