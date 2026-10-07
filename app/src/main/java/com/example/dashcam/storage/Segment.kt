package com.example.dashcam.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished, published segment as MediaStore knows it. */
data class Segment(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val startTimeMs: Long,
    /** 0 when unknown (not yet scanned). */
    val durationMs: Long,
    /** Actual size on disk. */
    val sizeBytes: Long,
    val state: VideoState,
) {
    val isProtected: Boolean get() = state != VideoState.NORMAL

    /** When the duration is unknown the longest allowed segment is assumed, erring towards keeping footage. */
    val endTimeMs: Long get() = startTimeMs + if (durationMs > 0) durationMs else UNKNOWN_DURATION_MS

    /** e.g. "Internal storage/DCIM/Dashcam/20250101_120000.mp4" */
    val displayPath: String get() = state.displayFolder + displayName

    companion object {
        const val UNKNOWN_DURATION_MS = 10 * 60_000L
    }
}

/**
 * A segment that is being written. Its MediaStore row has IS_PENDING = 1, so it is hidden from
 * Gallery and Files until it has been finalized. [descriptor] is handed to the camera recorder,
 * which closes it when the file is finalized.
 */
class PendingSegment(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val startTimeMs: Long,
    val descriptor: ParcelFileDescriptor,
) {
    /** Safe to call more than once. */
    fun closeDescriptor() {
        try {
            descriptor.close()
        } catch (e: IOException) {
            // Nothing useful to do if closing fails.
        }
    }
}

/** The existing file name format (yyyyMMdd_HHmmss.mp4); the start time in it is how footage is indexed. */
object SegmentNaming {
    private const val PATTERN = "yyyyMMdd_HHmmss"
    private val OURS = Regex("""^(\d{8}_\d{6}).*\.mp4$""")

    fun fileName(startTimeMs: Long): String =
        SimpleDateFormat(PATTERN, Locale.US).format(Date(startTimeMs)) + ".mp4"

    /** Null when the name is not one of ours, so unrelated files are never treated as footage. */
    fun parseStartTimeMs(displayName: String): Long? {
        val stamp = OURS.find(displayName)?.groupValues?.get(1) ?: return null
        return try {
            SimpleDateFormat(PATTERN, Locale.US).parse(stamp)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }
}
