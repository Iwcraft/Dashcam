package com.example.dashcam.recording

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import java.io.File

data class VideoConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val bitrateBps: Int = 10_000_000,
)

/** What the camera was actually configured for, as opposed to what was requested. */
data class BoundVideo(val description: String)

/**
 * Thin CameraX adapter (rear camera, H.264/MP4). Knows nothing about segments, storage or
 * events, so the engine can be reasoned about without touching camera code.
 * All functions must be called on the main thread.
 */
interface CameraRecorder {
    /** Throws if there is no rear camera or no usable configuration. */
    suspend fun bind(owner: LifecycleOwner, config: VideoConfig): BoundVideo

    /**
     * Starts writing a new MP4 to [file]. [onStarted] fires when the first frames are being
     * written; [onFinalized] fires once the file is closed, successfully or not.
     */
    fun startSegment(file: File, onStarted: () -> Unit, onFinalized: (Result<File>) -> Unit)

    fun finishSegment()

    fun unbind()

    /**
     * Optional on-screen preview. Safe to call before [bind] or while recording; null removes
     * the preview so nothing is rendered while the screen is off.
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?)
}
