package com.example.dashcam.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.dashcam.utils.await
import com.example.dashcam.utils.hasPermission
import java.io.IOException

class CameraXRecorder(private val context: Context) : CameraRecorder {
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    // Must be held: a Recording that is garbage collected finalizes with an error.
    private var activeRecording: Recording? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    /** Fixed for the session at bind time, like resolution and frame rate. */
    private var audioEnabled = false

    private class Rung(val quality: Quality, val height: Int)

    override suspend fun bind(owner: LifecycleOwner, config: VideoConfig): BoundVideo {
        val provider = ProcessCameraProvider.getInstance(context).await(mainExecutor)
        cameraProvider = provider

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val cameraInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
            ?: throw IllegalStateException("No rear camera found")

        val (rung, isFallback) = chooseQuality(cameraInfo, config)
        val bitrate = VideoConfig.bitrateFor(rung.height, config.quality)

        provider.unbindAll()
        // The frame rate is a target, not a guarantee: CameraX picks the closest range the camera
        // supports. If the camera rejects the combination outright, retry once with its default
        // frame rate: recording at the wrong fps beats not recording.
        var fpsNote = "${config.frameRate} fps target"
        val capture = try {
            bindUseCases(provider, owner, selector, rung, bitrate, config.frameRate)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Frame rate ${config.frameRate} rejected, using the camera default", e)
            provider.unbindAll()
            fpsNote = "default fps"
            bindUseCases(provider, owner, selector, rung, bitrate, null)
        }
        videoCapture = capture
        audioEnabled = config.audioEnabled

        val description = "${rung.height}p" + if (isFallback) " (fallback)" else ""
        Log.i(TAG, "Bound rear camera: $description, $fpsNote, ${bitrate / 1000} kbps, audio=$audioEnabled")
        return BoundVideo(description, bitrate)
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        rung: Rung,
        bitrateBps: Int,
        frameRate: Int?,
    ): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(rung.quality))
            .setTargetVideoEncodingBitRate(bitrateBps)
            .build()
        val capture = VideoCapture.Builder(recorder)
            .apply { if (frameRate != null) setTargetFrameRate(Range(frameRate, frameRate)) }
            .build()

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
        return capture
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

    override fun startSegment(
        output: ParcelFileDescriptor,
        durationLimitMs: Long,
        onStarted: () -> Unit,
        onFinalized: (Result<Unit>) -> Unit,
    ) {
        val capture = checkNotNull(videoCapture) { "Camera is not bound" }
        check(activeRecording == null) { "A recording is already running" }

        // CameraX ends the segment itself, measured on the recorded timestamps, so every segment
        // is the same length however busy the main thread is.
        val options = FileDescriptorOutputOptions.Builder(output)
            .setDurationLimitMillis(durationLimitMs)
            .build()

        // Audio is off unless the session asked for it AND the permission is still granted;
        // otherwise the recording stays silent instead of failing.
        val pending = capture.output.prepareRecording(context, options)
        activeRecording = withAudioIfAllowed(pending)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onStarted()
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        closeQuietly(output)
                        onFinalized(resultOf(event))
                    }
                    else -> Unit
                }
            }
    }

    @SuppressLint("MissingPermission") // checked on the line above the call
    private fun withAudioIfAllowed(pending: PendingRecording): PendingRecording =
        if (audioEnabled && context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending.withAudioEnabled()
        } else {
            pending
        }

    private fun resultOf(event: VideoRecordEvent.Finalize): Result<Unit> = when {
        !event.hasError() -> Result.success(Unit)
        // Reaching the limit is how a segment normally ends; the file is complete and playable.
        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> Result.success(Unit)
        else -> {
            Log.e(TAG, "Recording finalized with error ${event.error}", event.cause)
            val message = describe(event.error)
            Result.failure(
                if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                    NoValidDataException(message)
                } else {
                    IllegalStateException(message)
                },
            )
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        try {
            descriptor.close()
        } catch (e: IOException) {
            // Nothing useful to do if closing fails.
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
