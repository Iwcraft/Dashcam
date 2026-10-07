package com.example.dashcam.recording

import android.os.ParcelFileDescriptor
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner

data class VideoConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val bitrateBps: Int = 10_000_000,
)

/** What the camera was actually configured for, as opposed to what was requested. */
data class BoundVideo(val description: String)

/** A segment ended without a single frame, e.g. Stop was pressed right after a rollover. */
class NoValidDataException(message: String) : IllegalStateException(message)

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
