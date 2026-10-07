package com.example.dashcam.recording

import androidx.lifecycle.LifecycleOwner
import java.io.File

data class VideoConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val bitrateBps: Int = 10_000_000,
)

/**
 * Thin CameraX adapter (rear camera, H.264/MP4). Knows nothing about segments, storage or
 * events, so the engine can be reasoned about without touching camera code.
 */
interface CameraRecorder {
    suspend fun bind(owner: LifecycleOwner, config: VideoConfig)

    /** Starts writing a new MP4 to [file]. [onFinalized] fires once the file is closed. */
    fun startSegment(file: File, onFinalized: (Result<File>) -> Unit)

    fun finishSegment()

    fun unbind()
}
