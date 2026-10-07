package com.example.dashcam.recording

import com.example.dashcam.storage.PendingSegment
import com.example.dashcam.storage.Segment
import com.example.dashcam.storage.VideoState

/** Owns segment allocation, publishing and protection, on top of MediaStore. */
interface SegmentManager {
    /**
     * Creates the hidden (IS_PENDING = 1) MediaStore entry for a segment starting at [startTimeMs]
     * and opens it for writing. Name format is unchanged: yyyyMMdd_HHmmss.mp4.
     */
    suspend fun newSegment(startTimeMs: Long): PendingSegment

    /**
     * The recorder has finalized the file: clears IS_PENDING so it appears in Gallery/Files,
     * measures its real size, applies any protection that was requested while it was being
     * written, and adds it to the index. Throws if the file could not be published.
     */
    suspend fun onSegmentFinalized(pending: PendingSegment, durationMs: Long): Segment

    /** Removes a segment that is not worth keeping (e.g. it contains no video). */
    suspend fun discard(pending: PendingSegment)

    /** Includes the in-progress segment. Finished-but-not-yet-published segments are not listed. */
    fun segmentsOverlapping(fromMs: Long, toMs: Long): List<Segment>

    /**
     * Moves all footage overlapping the range to [state]'s folder (a rename, never a copy).
     * The in-progress segment cannot be moved while CameraX writes it, so the request is
     * remembered and applied when that segment is published; the same goes for segments that
     * start later but overlap the range (post-event footage). Never downgrades a segment.
     */
    suspend fun protectRange(fromMs: Long, toMs: Long, state: VideoState = VideoState.PROTECTED)
}
