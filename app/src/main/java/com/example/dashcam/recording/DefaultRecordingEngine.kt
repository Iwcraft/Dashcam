package com.example.dashcam.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.dashcam.service.RecordingService
import com.example.dashcam.utils.hasPermission
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultRecordingEngine(
    private val context: Context,
    private val recorder: CameraRecorder,
    private val videoConfig: VideoConfig = VideoConfig(),
) : RecordingEngine {
    // Process-wide scope: recording must outlive any Activity or ViewModel.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastRecording = MutableStateFlow<RecordingResult?>(null)
    override val lastRecording: StateFlow<RecordingResult?> = _lastRecording.asStateFlow()

    private var startJob: Job? = null
    private var currentFile: File? = null

    override fun start() {
        val current = _state.value
        if (current is RecordingState.Starting ||
            current is RecordingState.Recording ||
            current is RecordingState.Stopping
        ) return

        // On API 34+ a camera-type foreground service started without this permission throws.
        if (!context.hasPermission(Manifest.permission.CAMERA)) {
            _state.value = RecordingState.Error("Camera permission is required")
            return
        }

        _state.value = RecordingState.Starting
        try {
            ContextCompat.startForegroundService(context, serviceIntent())
        } catch (e: IllegalStateException) {
            // API 31+: foreground service starts from the background are restricted.
            _state.value = RecordingState.Error(e.message ?: "Could not start recording service")
        }
    }

    override fun stop() {
        when (_state.value) {
            is RecordingState.Idle, is RecordingState.Stopping -> Unit
            is RecordingState.Recording -> {
                // The file must be finalized before the camera is released, otherwise the MP4
                // has no index and will not play. onSegmentFinalized finishes the shutdown.
                _state.value = RecordingState.Stopping
                recorder.finishSegment()
            }
            is RecordingState.Starting -> {
                startJob?.cancel()
                releaseAndStopService(RecordingState.Idle)
            }
            is RecordingState.Error -> releaseAndStopService(RecordingState.Idle)
        }
    }

    override fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        recorder.setPreviewSurfaceProvider(provider)
    }

    // --- Called by RecordingService only ---

    /** The service is in the foreground and [owner] is its lifecycle: safe to open the camera. */
    internal fun onServiceForeground(owner: LifecycleOwner) {
        if (_state.value !is RecordingState.Starting) {
            // Stop was requested before the service finished starting.
            context.stopService(serviceIntent())
            return
        }
        if (startJob?.isActive == true) return

        startJob = scope.launch {
            try {
                val video = recorder.bind(owner, videoConfig)
                val file = newOutputFile()
                currentFile = file
                recorder.startSegment(
                    file = file,
                    onStarted = {
                        if (_state.value is RecordingState.Starting) {
                            _state.value = RecordingState.Recording(
                                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                                quality = video.description,
                            )
                        }
                    },
                    onFinalized = { result -> onSegmentFinalized(result) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                releaseAndStopService(RecordingState.Error(e.message ?: "Camera could not start"))
            }
        }
    }

    internal fun onServiceFailed(message: String) {
        _state.value = RecordingState.Error(message)
    }

    internal fun onServiceStopped() {
        when (_state.value) {
            is RecordingState.Starting, is RecordingState.Recording -> {
                // The service died while recording (not via stop()). Unbinding closes the
                // recording; the finalize callback still reports the file.
                startJob?.cancel()
                recorder.unbind()
                _state.value = RecordingState.Error("Recording service stopped unexpectedly")
            }
            is RecordingState.Error -> Unit // keep the failure visible
            else -> _state.value = RecordingState.Idle
        }
    }

    private fun onSegmentFinalized(result: Result<File>) {
        val finalState = result.fold(
            onSuccess = { if (_state.value is RecordingState.Error) _state.value else RecordingState.Idle },
            onFailure = { RecordingState.Error(it.message ?: "Recording failed") },
        )
        releaseAndStopService(finalState)

        // Even a failed recording may have left a playable file; report what is really on disk.
        val file = currentFile
        if (file != null && file.exists() && file.length() > 0) {
            scope.launch {
                _lastRecording.value = withContext(Dispatchers.IO) { RecordingProbe.probe(file) }
            }
        }
    }

    private fun releaseAndStopService(finalState: RecordingState) {
        recorder.unbind()
        _state.value = finalState
        context.stopService(serviceIntent())
    }

    private fun newOutputFile(): File {
        // App-specific external storage: no storage permission needed on any supported Android.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, "Dashcam")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        // Start time in the name, so later phases can index segments without a database.
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "$name.mp4")
    }

    private fun serviceIntent() = Intent(context, RecordingService::class.java)

    private companion object {
        const val TAG = "Dashcam"
    }
}
