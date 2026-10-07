package com.example.dashcam.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

/**
 * The only class that talks to MediaStore. Everything here is blocking: call off the main thread.
 * It only ever reads, moves or deletes rows under DCIM/Dashcam/ whose names are ours.
 */
internal class MediaStoreVideoStore(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    class PendingRow(val id: Long, val uri: Uri, val displayName: String)

    enum class DeleteResult { DELETED, FAILED }

    /**
     * Creates the row with IS_PENDING = 1 and opens it for writing. The file is invisible to
     * other apps until [publish] succeeds.
     */
    fun createPending(startTimeMs: Long): PendingSegment {
        val name = SegmentNaming.fileName(startTimeMs)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, VideoState.NORMAL.relativePath)
            put(MediaStore.Video.Media.DATE_TAKEN, startTimeMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $name")
        val descriptor = try {
            // "rw": MediaMuxer needs a seekable descriptor to write the MP4 index at the end.
            resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open $name for writing")
        } catch (e: Exception) {
            delete(uri)
            throw e
        }
        return PendingSegment(
            id = ContentUris.parseId(uri),
            uri = uri,
            displayName = name,
            // Same precision as the file name, so the index agrees with what a later query parses.
            startTimeMs = SegmentNaming.parseStartTimeMs(name) ?: startTimeMs,
            descriptor = descriptor,
        )
    }

    /** Sets IS_PENDING = 0, which makes the file visible and lets MediaProvider scan it. */
    fun markVisible(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        if (resolver.update(uri, values, null, null) == 0) throw IOException("MediaStore row is gone: $uri")
    }

    /**
     * Call only after the recorder reported the file finalized. Measures the real size on disk
     * first, then clears IS_PENDING. Throws if either fails, leaving the row pending.
     */
    fun publish(pending: PendingSegment, durationMs: Long): Segment {
        val size = resolver.openFileDescriptor(pending.uri, "r")?.use { it.statSize }
            ?: throw IOException("Could not read back ${pending.displayName}")
        markVisible(pending.uri)
        return Segment(
            id = pending.id,
            uri = pending.uri,
            // MediaStore renames on a name clash, so ask what it actually called the file.
            displayName = queryDisplayName(pending.uri) ?: pending.displayName,
            startTimeMs = pending.startTimeMs,
            durationMs = durationMs,
            sizeBytes = size,
            state = VideoState.NORMAL,
        )
    }

    /** Changes state by changing the folder. MediaProvider renames the file; nothing is copied. */
    fun moveTo(uri: Uri, state: VideoState): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.Video.Media.RELATIVE_PATH, state.relativePath) }
        resolver.update(uri, values, null, null) > 0
    } catch (e: Exception) {
        Log.e(TAG, "Could not move $uri to ${state.relativePath}", e)
        false
    }

    /** A row that is already gone counts as deleted. */
    fun delete(uri: Uri): DeleteResult = try {
        resolver.delete(uri, null, null)
        DeleteResult.DELETED
    } catch (e: Exception) {
        Log.w(TAG, "Could not delete $uri", e)
        DeleteResult.FAILED
    }

    /** All published footage in the three Dashcam folders: one query, real sizes. */
    fun queryManaged(): List<Segment> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${VideoState.NORMAL.relativePath}%")
        val result = ArrayList<Segment>()
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                // Sub-folders or names we did not create are not ours to manage.
                val state = VideoState.fromRelativePath(c.getString(pathCol)) ?: continue
                val start = SegmentNaming.parseStartTimeMs(name) ?: continue
                val id = c.getLong(idCol)
                result += Segment(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    startTimeMs = start,
                    durationMs = c.getLong(durationCol),
                    sizeBytes = c.getLong(sizeCol),
                    state = state,
                )
            }
        }
        return result
    }

    /** Rows still hidden by IS_PENDING in the Normal folder: the writer is either active or it died. */
    @Suppress("DEPRECATION")
    fun queryPending(): List<PendingRow> {
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Video.Media.IS_PENDING} = 1 AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(VideoState.NORMAL.relativePath)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val extras = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, extras, null)
        } else {
            // Android 10/11: pending rows are hidden from queries unless asked for.
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        val result = ArrayList<PendingRow>()
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (SegmentNaming.parseStartTimeMs(name) == null) continue
                val id = c.getLong(idCol)
                result += PendingRow(id, ContentUris.withAppendedId(collection, id), name)
            }
        }
        return result
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val TAG = "Dashcam"
        const val MIME_MP4 = "video/mp4"
    }
}
