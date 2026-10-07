package com.example.dashcam.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** What was actually written to disk, read back from the finished file rather than assumed. */
data class RecordingResult(
    val file: File,
    val sizeBytes: Long,
    val durationMs: Long,
    /** "video/avc" is H.264. Null if the file could not be read back. */
    val videoMime: String?,
    val width: Int,
    val height: Int,
    /** Video samples divided by duration. */
    val effectiveFps: Float?,
)

internal object RecordingProbe {
    /** Blocking file I/O: call off the main thread. */
    fun probe(file: File): RecordingResult {
        val size = file.length()
        val unreadable = RecordingResult(file, size, 0, null, 0, 0, null)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return unreadable

            val format = extractor.getTrackFormat(track)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            extractor.selectTrack(track)
            var samples = 0
            if (extractor.sampleTime >= 0) {
                do {
                    samples++
                } while (extractor.advance())
            }

            RecordingResult(
                file = file,
                sizeBytes = size,
                durationMs = durationUs / 1000,
                videoMime = format.getString(MediaFormat.KEY_MIME),
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                effectiveFps = if (durationUs > 0 && samples > 0) samples * 1_000_000f / durationUs else null,
            )
        } catch (e: Exception) {
            unreadable
        } finally {
            extractor.release()
        }
    }
}
