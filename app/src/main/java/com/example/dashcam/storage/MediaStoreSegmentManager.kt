package com.example.dashcam.storage

import android.util.Log
import com.example.dashcam.recording.SegmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MediaStoreSegmentManager(
    private val store: MediaStoreVideoStore,
    private val index: SegmentIndex,
) : SegmentManager {
    private class Rule(val fromMs: Long, val toMs: Long, val state: VideoState)

    private val rulesLock = Any()
    private val rules = ArrayList<Rule>()

    override suspend fun newSegment(startTimeMs: Long): PendingSegment =
        withContext(Dispatchers.IO) {
            // Registered as "writing" immediately so a refresh can never mistake it for a leftover.
            store.createPending(startTimeMs).also { index.markWriting(it) }
        }

    override suspend fun onSegmentFinalized(pending: PendingSegment, durationMs: Long): Segment {
        pending.closeDescriptor()
        return index.mutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    var segment = store.publish(pending, durationMs)
                    val target = stateFor(segment.startTimeMs, segment.endTimeMs)
                    if (target != VideoState.NORMAL) {
                        if (store.moveTo(segment.uri, target)) {
                            segment = segment.copy(state = target)
                        } else {
                            Log.e(TAG, "Could not protect ${segment.displayName}; it stays Normal")
                        }
                    }
                    index.put(segment)
                    segment
                }
            } finally {
                index.clearWriting(pending.id)
            }
        }
    }

    override suspend fun discard(pending: PendingSegment) {
        pending.closeDescriptor()
        withContext(Dispatchers.IO) { store.delete(pending.uri) }
        index.clearWriting(pending.id)
    }

    override fun segmentsOverlapping(fromMs: Long, toMs: Long): List<Segment> {
        val now = System.currentTimeMillis()
        val writing = index.writingSegments().map {
            Segment(
                id = it.id,
                uri = it.uri,
                displayName = it.displayName,
                startTimeMs = it.startTimeMs,
                durationMs = (now - it.startTimeMs).coerceAtLeast(1),
                sizeBytes = 0,
                state = VideoState.NORMAL,
            )
        }
        return (index.snapshot() + writing)
            .filter { overlaps(it, fromMs, toMs) }
            .sortedBy { it.startTimeMs }
    }

    override suspend fun protectRange(fromMs: Long, toMs: Long, state: VideoState) {
        require(state != VideoState.NORMAL) { "protectRange needs Protected or Event" }
        index.ensureLoaded()
        // Under the index mutex: either a segment is published before this runs (and is moved
        // below) or after (and finds the rule), never in between. Cleanup is locked out as well.
        index.mutex.withLock {
            synchronized(rulesLock) { rules += Rule(fromMs, toMs, state) }
            withContext(Dispatchers.IO) {
                for (segment in index.snapshot()) {
                    if (segment.state >= state || !overlaps(segment, fromMs, toMs)) continue
                    if (store.moveTo(segment.uri, state)) {
                        index.put(segment.copy(state = state))
                    } else {
                        Log.e(TAG, "Could not protect ${segment.displayName}")
                    }
                }
            }
        }
    }

    /** The strongest state requested for a segment spanning [startMs, endMs]. */
    private fun stateFor(startMs: Long, endMs: Long): VideoState = synchronized(rulesLock) {
        val horizon = System.currentTimeMillis() - RULE_RETENTION_MS
        rules.removeAll { it.toMs < horizon }
        rules.filter { startMs < it.toMs && endMs > it.fromMs }.maxOfOrNull { it.state } ?: VideoState.NORMAL
    }

    private fun overlaps(segment: Segment, fromMs: Long, toMs: Long): Boolean =
        segment.startTimeMs < toMs && segment.endTimeMs > fromMs

    private companion object {
        const val TAG = "Dashcam"

        /** Longer than any segment plus its save time, so a rule outlives the segments it covers. */
        const val RULE_RETENTION_MS = 30 * 60_000L
    }
}
