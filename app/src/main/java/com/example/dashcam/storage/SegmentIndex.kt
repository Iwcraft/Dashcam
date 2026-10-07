package com.example.dashcam.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory view of the Dashcam folders, so cleanup and protection never have to scan storage.
 * It is filled by one MediaStore query and then kept current as segments are published, moved
 * or deleted.
 */
internal class SegmentIndex(private val store: MediaStoreVideoStore) {
    /**
     * Held by anything that moves or deletes published footage, so cleanup can never delete a
     * segment in the instant it is being protected. Not re-entrant: never nest.
     */
    val mutex = Mutex()

    private val lock = Any()
    private var loaded = false
    private val segments = LinkedHashMap<Long, Segment>()
    private val writing = LinkedHashMap<Long, PendingSegment>()

    /** Loads on first use. */
    suspend fun ensureLoaded() {
        if (synchronized(lock) { loaded }) return
        mutex.withLock {
            if (!synchronized(lock) { loaded }) reload()
        }
    }

    /** One MediaStore query. The caller holds [mutex] when concurrent changes are possible. */
    suspend fun reload() {
        val all = withContext(Dispatchers.IO) { store.queryManaged() }
        synchronized(lock) {
            segments.clear()
            all.forEach { segments[it.id] = it }
            loaded = true
        }
    }

    fun snapshot(): List<Segment> = synchronized(lock) { segments.values.toList() }

    fun get(id: Long): Segment? = synchronized(lock) { segments[id] }

    fun put(segment: Segment) {
        synchronized(lock) { segments[segment.id] = segment }
    }

    fun remove(id: Long) {
        synchronized(lock) { segments.remove(id) }
    }

    fun totalBytes(): Long = synchronized(lock) { segments.values.sumOf { it.sizeBytes } }

    fun markWriting(pending: PendingSegment) {
        synchronized(lock) { writing[pending.id] = pending }
    }

    fun clearWriting(id: Long) {
        synchronized(lock) { writing.remove(id) }
    }

    fun isWriting(id: Long): Boolean = synchronized(lock) { writing.containsKey(id) }

    fun writingSegments(): List<PendingSegment> = synchronized(lock) { writing.values.toList() }
}
