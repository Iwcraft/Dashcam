package com.example.dashcam.storage

interface StorageManager {
    /**
     * Re-reads the Dashcam folders from MediaStore (one query, never the whole phone) and repairs
     * leftovers of an interrupted recording: a finished-but-unpublished file is published, an
     * unplayable one is removed. Call once per recording session, before the first segment.
     */
    suspend fun refresh()

    /** Normal + Protected + Event, from the cached index of real file sizes. */
    fun usedBytes(): Long

    /** Free space on the phone's shared storage. */
    fun availableBytes(): Long

    /**
     * Deletes the oldest NORMAL segments until [usedBytes] + [reserveBytes] fits [limitBytes]
     * (and the phone keeps a minimum of free space). Never touches Protected/Event footage or
     * the segment being written. If protected footage alone fills the limit, the limit part
     * deletes nothing.
     */
    suspend fun enforceLimit(limitBytes: Long, reserveBytes: Long = 0L)
}
