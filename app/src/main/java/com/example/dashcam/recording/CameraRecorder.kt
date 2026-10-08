package com.example.dashcam.recording

import android.os.ParcelFileDescriptor
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.example.dashcam.settings.RecordingSettings
import com.example.dashcam.settings.VideoQuality

/** What is *requested* for one recording session; the camera may fall back (see [BoundVideo]). */
data class VideoConfig(
    val height: Int = 1080,
    val frameRate: Int = 30,
    val quality: VideoQuality = VideoQuality.HIGH,
    /** Already checked against the RECORD_AUDIO permission by the caller. */
    val audioEnabled: Boolean = false,
) {
    companion object {
        fun from(settings: RecordingSettings, audioActive: Boolean) = VideoConfig(
            height = settings.resolution.height,
            frameRate = settings.frameRate,
            quality = settings.videoQuality,
            audioEnabled = audioActive,
        )

        /**
         * Chosen for the resolution actually used, so a 720p fallback does not get a 1080p bitrate.
         * HIGH at 1080p is the original Phase 1 value (10 Mbps). These are deliberately moderate:
         * a dashcam records for hours, so heat and storage matter more than peak quality.
         */
        fun bitrateFor(height: Int, quality: VideoQuality): Int = when {
            height >= 1080 -> if (quality == VideoQuality.HIGH) 10_000_000 else 6_000_000
            height >= 720 -> if (quality == VideoQuality.HIGH) 5_000_000 else 3_000_000
            else -> if (quality == VideoQuality.HIGH) 2_500_000 else 1_500_000
        }
    }
}

/** What the camera was actually configured for, as opposed to what was requested. */
data class BoundVideo(
    val description: String,
    /** The bitrate target actually set, for estimating how much disk a segment needs. */
    val bitrateBps: Int,
)

/**
 * A segment ended because of a CameraX error. [recoverable] says whether starting a fresh camera
 * session can help; it is false for storage-full and bad-output failures, which must not be retried.
 */
open class RecordingFailure(message: String, val recoverable: Boolean) : IllegalStateException(message)

/** A segment ended without a single frame, e.g. Stop was pressed right after a rollover. */
class NoValidDataException(message: String) : RecordingFailure(message, recoverable = true)

/**
 * Thin CameraX adapter (rear camera, H.264/MP4). Knows nothing about segments, storage or
 * events, so the engine can be reasoned about without touching camera code.
 * All functions must be called on the main thread.
 */
interface CameraRecorder {
    /** Throws if there is no rear camera or no usable configuration. */
    suspend fun bind(owner: LifecycleOwner, config: VideoConfig): BoundVideo

    /**
     * Starts writing a new MP4 to [output] and ends it by itself after [durationLimitMs] of video;
     * that is the rollover trigger. The recorder takes ownership of [output] and closes it once
     * the file is finalized.
     *
     * [onStarted] fires when the first frames are being written. [onFinalized] fires once the file
     * is closed: success also covers a segment that ended because its limit was reached, failure
     * is a real error ([NoValidDataException] if nothing was recorded).
     */
    fun startSegment(
        output: ParcelFileDescriptor,
        durationLimitMs: Long,
        onStarted: () -> Unit,
        onFinalized: (Result<Unit>) -> Unit,
    )

    fun finishSegment()

    fun unbind()

    /**
     * Optional on-screen preview. Safe to call before [bind] or while recording; null removes
     * the preview so nothing is rendered while the screen is off.
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?)
}
