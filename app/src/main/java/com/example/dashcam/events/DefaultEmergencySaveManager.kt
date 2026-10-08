package com.example.dashcam.events

import android.util.Log
import com.example.dashcam.recording.SegmentManager
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.storage.VideoState

/**
 * Pure policy: it only chooses a time range and a target state, then hands over to
 * SegmentManager.protectRange, which renames files via MediaStore (no copying), defers the
 * segment CameraX is still writing until it is finalized, and covers segments that start later.
 */
class DefaultEmergencySaveManager(
    private val segments: SegmentManager,
    private val settings: SettingsRepository,
) : EmergencySaveManager {

    override suspend fun saveLast(): ProtectionResult {
        val now = System.currentTimeMillis()
        val windowMs = settings.settings.value.emergencySaveWindowMs
        return protect("manual save", now - windowMs, now, VideoState.PROTECTED)
    }

    override suspend fun protectAround(event: DrivingEvent): ProtectionResult =
        protect(
            "possible impact",
            event.timestampMs - EventProtection.PRE_EVENT_MS,
            event.timestampMs + EventProtection.POST_EVENT_MS,
            VideoState.EVENT,
        )

    private suspend fun protect(reason: String, fromMs: Long, toMs: Long, state: VideoState): ProtectionResult {
        segments.protectRange(fromMs, toMs, state)

        // Read back what the request achieved. The segment being written has no final state yet.
        val covering = segments.segmentsOverlapping(fromMs, toMs)
        val done = covering.count { it.state >= state }
        val result = ProtectionResult(state, protectedSegments = done, pendingSegments = covering.size - done)
        Log.i(
            EventLog.TAG,
            "Protection ($reason) -> ${state.relativePath}: ${result.protectedSegments} segment(s) moved, " +
                "${result.pendingSegments} still being written (protected when they finish)",
        )
        return result
    }
}
