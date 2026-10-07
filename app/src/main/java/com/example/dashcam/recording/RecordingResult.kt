package com.example.dashcam.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.dashcam.storage.Segment
import com.example.dashcam.storage.VideoState

/** What was actually written, read back from the finished segment rather than assumed. */
data class RecordingResult(
    val uri: Uri,
    val displayName: String,
    /** Where to find it in the Files app, e.g. "Internal storage/DCIM/Dashcam/20250101_120000.mp4". */
    val location: String,
    val state: VideoState,
    val sizeBytes: Long,
    val durationMs: Long,
    /** "video/avc" is H.264. Null if the file could not be read back. */
    val videoMime: String?,
    val width: Int,
    val height: Int,
    /** Measured from the first seconds of video sample timestamps. */
    val effectiveFps: Float?,
) {
    internal companion object {
        fun of(segment: Segment, info: VideoInfo) = RecordingResult(
            uri = segment.uri,
            displayName = segment.displayName,
            location = segment.displayPath,
            state = segment.state,
            sizeBytes = segment.sizeBytes,
            durationMs = info.durationMs,
            videoMime = info.videoMime,
            width = info.width,
            height = info.height,
            effectiveFps = info.effectiveFps,
        )
    }
}

internal data class VideoInfo(
    val durationMs: Long,
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val effectiveFps: Float?,
) {
    /** Has a video track with a duration, i.e. the MP4 was finalized and can be played. */
    val isPlayable: Boolean get() = videoMime != null && durationMs > 0
}

internal object RecordingProbe {
    private const val FPS_SAMPLE_LIMIT = 300 // ~10 s at 30 fps: enough to measure, cheap to read
    private val UNREADABLE = VideoInfo(0, null, 0, 0, null)

    /** Blocking file I/O: call off the main thread. Works on pending and published rows alike. */
    fun probe(context: Context, uri: Uri): VideoInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return UNREADABLE

            val format = extractor.getTrackFormat(track)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            // Sampling only the start keeps this cheap on a segment that can be hundreds of MB.
            extractor.selectTrack(track)
            var samples = 0
            var firstUs = -1L
            var lastUs = -1L
            while (samples < FPS_SAMPLE_LIMIT) {
                val timeUs = extractor.sampleTime
                if (timeUs < 0) break
                if (firstUs < 0) firstUs = timeUs
                lastUs = timeUs
                samples++
                if (!extractor.advance()) break
            }

            VideoInfo(
                durationMs = durationUs / 1000,
                videoMime = format.getString(MediaFormat.KEY_MIME),
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                effectiveFps = if (samples > 1 && lastUs > firstUs) (samples - 1) * 1_000_000f / (lastUs - firstUs) else null,
            )
        } catch (e: Exception) {
            UNREADABLE
        } finally {
            extractor.release()
        }
    }
}
