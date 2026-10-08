package com.example.dashcam.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.dashcam.recording.RecordingProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MediaStoreStorageManager(
    private val context: Context,
    private val store: MediaStoreVideoStore,
    private val index: SegmentIndex,
    /** Even under the cap, keep the phone from filling up completely: the camera would fail to record. */
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
) : StorageManager {

    override suspend fun refresh() {
        index.mutex.withLock {
            withContext(Dispatchers.IO) {
                // A row still pending that nobody is writing belongs to a session that died.
                for (row in store.queryPending()) {
                    if (index.isWriting(row.id)) continue
                    if (RecordingProbe.probe(context, row.uri).isPlayable) {
                        try {
                            store.markVisible(row.uri)
                            Log.i(TAG, "Recovered ${row.displayName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not recover ${row.displayName}", e)
                        }
                    } else {
                        Log.i(TAG, "Removing unplayable leftover ${row.displayName}")
                        store.delete(row.uri)
                    }
                }
            }
            index.reload()
        }
    }

    override fun usedBytes(): Long = index.totalBytes()

    override fun availableBytes(): Long {
        // Same volume as DCIM; an app-specific directory is just a convenient path to stat.
        val path = (context.getExternalFilesDir(null) ?: context.filesDir).path
        return StatFs(path).availableBytes
    }

    override suspend fun usage(): StorageUsage {
        index.ensureLoaded()
        return StorageUsage(
            dashcamBytes = index.totalBytes(),
            freeBytes = withContext(Dispatchers.IO) { availableBytes() },
        )
    }

    override suspend fun enforceLimit(limitBytes: Long, reserveBytes: Long) {
        index.ensureLoaded()
        val all = index.snapshot()
        val used = all.sumOf { it.sizeBytes }
        val protectedBytes = all.filter { it.state != VideoState.NORMAL }.sumOf { it.sizeBytes }

        val overLimit = if (protectedBytes + reserveBytes >= limitBytes) {
            if (used > limitBytes) Log.w(TAG, "Protected footage alone fills the limit; not deleting by limit")
            0L
        } else {
            used + reserveBytes - limitBytes
        }
        val underFree = minFreeBytes - availableBytes()
        var toFree = maxOf(overLimit, underFree)
        if (toFree <= 0) return

        // Oldest first. Only NORMAL, and only segments that were published: the one being
        // written is not in the index at all.
        val candidates = all.filter { it.state == VideoState.NORMAL }
            .sortedWith(compareBy({ it.startTimeMs }, { it.id }))
        for (candidate in candidates) {
            if (toFree <= 0) break
            toFree -= deleteIfStillNormal(candidate.id)
        }
    }

    /** Returns the bytes freed. Re-checks the state under the lock, in case it was just protected. */
    private suspend fun deleteIfStillNormal(id: Long): Long = index.mutex.withLock {
        val current = index.get(id)
        if (current == null || current.state != VideoState.NORMAL) return@withLock 0L
        val result = withContext(Dispatchers.IO) { store.delete(current.uri) }
        if (result == MediaStoreVideoStore.DeleteResult.DELETED) {
            index.remove(id)
            Log.i(TAG, "Loop cleanup deleted ${current.displayName} (${current.sizeBytes} bytes)")
            current.sizeBytes
        } else {
            0L
        }
    }

    private companion object {
        const val TAG = "Dashcam"
        const val DEFAULT_MIN_FREE_BYTES = 1024L * 1024L * 1024L
    }
}
