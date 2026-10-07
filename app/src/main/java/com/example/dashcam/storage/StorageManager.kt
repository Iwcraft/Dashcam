package com.example.dashcam.storage

import java.io.File

interface StorageManager {
    /** Rolling footage; files here may be deleted automatically. */
    val rollingDir: File

    /** Protected footage is never auto-deleted. */
    val protectedDir: File

    /** Rolling + protected. */
    fun usedBytes(): Long

    fun availableBytes(): Long

    /**
     * Deletes the oldest unprotected segments until [usedBytes] fits [limitBytes], never touching
     * [inProgress]. If protected footage alone exceeds the limit it deletes nothing further.
     */
    suspend fun enforceLimit(limitBytes: Long, inProgress: File?)
}
