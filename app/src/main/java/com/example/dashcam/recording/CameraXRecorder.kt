package com.example.dashcam.recording

import android.content.Context
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.dashcam.utils.await
import java.io.File

class CameraXRecorder(private val context: Context) : CameraRecorder {
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    // Must be held: a Recording that is garbage collected finalizes with an error.
    private var activeRecording: Recording? = null
    private var previewProvider: Preview.SurfaceProvider? = null

    private class Rung(val quality: Quality, val height: Int)

    override suspend fun bind(owner: LifecycleOwner, config: VideoConfig): BoundVideo {
        val provider = ProcessCameraProvider.getInstance(context).await(mainExecutor)
        cameraProvider = provider

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val cameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
            ?: throw IllegalStateException("No rear camera found")

        val (rung, isFallback) = chooseQuality(cameraInfo, config)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(rung.quality))
            .setTargetVideoEncodingBitRate(config.bitrateBps)
            .build()
        // A target, not a guarantee: CameraX picks the closest range the camera supports.
        val capture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(config.frameRate, config.frameRate))
            .build()

        provider.unbindAll()
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(previewProvider) }
        preview = try {
            provider.bindToLifecycle(owner, selector, previewUseCase, capture)
            previewUseCase
        } catch (e: IllegalArgumentException) {
            // Preview + video is guaranteed on most devices, but recording matters more than preview.
            Log.w(TAG, "Preview + video combination rejected, recording without preview", e)
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, capture)
            null
        }
        videoCapture = capture

        val description = "${rung.height}p" + if (isFallback) " (fallback)" else ""
        Log.i(TAG, "Bound rear camera: $description @ ${config.frameRate} fps target")
        return BoundVideo(description)
    }

    // Deprecated in favour of Recorder.getVideoCapabilities but still functional and simpler.
    @Suppress("DEPRECATION")
    private fun chooseQuality(cameraInfo: CameraInfo, config: VideoConfig): Pair<Rung, Boolean> {
        val ladder = listOf(Rung(Quality.FHD, 1080), Rung(Quality.HD, 720), Rung(Quality.SD, 480))
        val supported = QualitySelector.getSupportedQualities(cameraInfo)
        val candidates = ladder.dropWhile { it.height > config.height }
        val chosen = candidates.firstOrNull { it.quality in supported }
            ?: throw IllegalStateException("This camera supports none of 1080p, 720p or 480p video")
        return chosen to (chosen !== candidates.first())
    }

    override fun startSegment(file: File, onStarted: () -> Unit, onFinalized: (Result<File>) -> Unit) {
        val capture = checkNotNull(videoCapture) { "Camera is not bound" }
        check(activeRecording == null) { "A recording is already running" }

        // No audio: it would need RECORD_AUDIO and is not part of this phase.
        activeRecording = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onStarted()
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError()) {
                            Log.e(TAG, "Recording finalized with error ${event.error}", event.cause)
                            onFinalized(Result.failure(IllegalStateException(describe(event.error))))
                        } else {
                            onFinalized(Result.success(file))
                        }
                    }
                    else -> Unit
                }
            }
    }

    private fun describe(error: Int): String = when (error) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Not enough free storage"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "Camera stopped delivering frames"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "Recording was too short to contain video"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "Video encoder failed"
        else -> "Recording failed (error $error)"
    }

    override fun finishSegment() {
        activeRecording?.stop()
    }

    override fun unbind() {
        activeRecording?.close()
        activeRecording = null
        cameraProvider?.unbindAll()
        preview = null
        videoCapture = null
    }

    override fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewProvider = provider
        preview?.setSurfaceProvider(provider)
    }

    private companion object {
        const val TAG = "Dashcam"
    }
}
