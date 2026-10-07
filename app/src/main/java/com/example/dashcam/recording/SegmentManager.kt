package com.example.dashcam.recording

import com.example.dashcam.storage.Segment
import java.io.File

/** Owns segment naming, rollover bookkeeping and protection. */
interface SegmentManager {
    /** Allocates the file for a new segment in the rolling directory. */
    fun newSegmentFile(startTimeMs: Long): File

    fun onSegmentFinalized(file: File)

    /** Includes the in-progress segment. */
    fun segmentsOverlapping(fromMs: Long, toMs: Long): List<Segment>

    /**
     * Protects all footage overlapping the range. Finalized segments are moved to the protected
     * directory now; the in-progress segment is moved when it is finalized, because it must not
     * be renamed while CameraX is writing it. A range extending into the future also covers
     * segments that start later (post-event footage).
     */
    suspend fun protectRange(fromMs: Long, toMs: Long)
}
