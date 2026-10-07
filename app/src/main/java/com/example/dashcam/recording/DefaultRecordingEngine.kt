package com.example.dashcam.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.dashcam.service.RecordingService
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.storage.PendingSegment
import com.example.dashcam.storage.StorageManager
import com.example.dashcam.utils.hasPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records until Stop or an error, as a chain of segments:
 *
 *   segment 1 -> finalized -> segment 2 -> finalized -> ...
 *
 * CameraX ends each segment itself when its duration limit is reached. The finalize callback
 * immediately starts the next one; publishing the finished file (IS_PENDING -> 0), probing it and
 * loop cleanup run behind it on a sequential queue, so they never delay the camera.
 *
 * Everything here runs on the main thread except MediaStore/file I/O, which is wrapped in
 * withContext(Dispatchers.IO).
 */
class DefaultRecordingEngine(
    private val context: Context,
    private val recorder: CameraRecorder,
    private val segmentManager: SegmentManager,
    private val storageManager: StorageManager,
    private val settings: SettingsRepository,
    private val videoConfig: VideoConfig = VideoConfig(),
) : RecordingEngine {
    // Process-wide scope: recording must outlive any Activity or ViewModel.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastRecording = MutableStateFlow<RecordingResult?>(null)
    override val lastRecording: StateFlow<RecordingResult?> = _lastRecording.asStateFlow()

    private var startJob: Job? = null

    /** Bumped on every start, so a late callback from an old session cannot touch a new one. */
    private var session = 0
    private var quality = ""
    private var segmentNumber = 0
    private var activeSegment: PendingSegment? = null
    private var segmentStartedAtMs = 0L
    private var watchdog: Job? = null

    /** A failure that must end the session even though the camera itself is fine (e.g. saving failed). */
    private var sessionError: String? = null

    /** Publishing, probing and cleanup run strictly one after another, in segment order. */
    private var saveChain: Job = Job().apply { complete() }

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

        session++
        sessionError = null
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
                // has no index and will not play. The finalize callback finishes the shutdown.
                _state.value = RecordingState.Stopping
                // Between two segments nothing is recording: startNextSegment sees Stopping
                // and finishes the shutdown itself.
                if (activeSegment != null) recorder.finishSegment()
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
                // One query of the Dashcam folders; also repairs files left by a crashed session.
                // Not worth failing the recording for.
                try {
                    storageManager.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Storage refresh failed", e)
                }

                val video = recorder.bind(owner, videoConfig)
                quality = video.description
                segmentNumber = 1
                beginSegment(segmentManager.newSegment(System.currentTimeMillis()))
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
                // recording; the finalize callback still saves the segment.
                startJob?.cancel()
                recorder.unbind()
                _state.value = RecordingState.Error("Recording service stopped unexpectedly")
            }
            is RecordingState.Error -> Unit // keep the failure visible
            else -> _state.value = RecordingState.Idle
        }
    }

    // --- Segments ---

    /** Hands [pending] to the camera. On failure the pending file is removed and the error rethrown. */
    private fun beginSegment(pending: PendingSegment) {
        val mySession = session
        val limitMs = segmentDurationMs()
        activeSegment = pending
        try {
            recorder.startSegment(
                output = pending.descriptor,
                durationLimitMs = limitMs,
                onStarted = { onSegmentStarted(mySession, limitMs) },
                onFinalized = { result -> onSegmentFinalized(mySession, pending, result) },
            )
        } catch (e: Exception) {
            activeSegment = null
            pending.closeDescriptor()
            scope.launch { discardQuietly(pending) }
            throw e
        }
    }

    private fun onSegmentStarted(mySession: Int, limitMs: Long) {
        if (mySession != session) return
        segmentStartedAtMs = System.currentTimeMillis()

        when (val current = _state.value) {
            // The session timer starts once, with the first segment, and runs across rollovers.
            is RecordingState.Starting -> _state.value = RecordingState.Recording(
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                quality = quality,
                segmentNumber = segmentNumber,
            )
            is RecordingState.Recording -> _state.value = current.copy(segmentNumber = segmentNumber)
            else -> Unit // Stop was already pressed
        }

        // Safety net only. CameraX normally ends the segment at the limit; if it ever does not,
        // this makes the rollover happen anyway.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(limitMs + WATCHDOG_GRACE_MS)
            Log.w(TAG, "Segment ran past its limit; ending it")
            recorder.finishSegment()
        }

        if (segmentNumber == 1) enqueue { enforceStorageLimit() }
    }

    private fun onSegmentFinalized(mySession: Int, pending: PendingSegment, result: Result<Unit>) {
        val isCurrentSession = mySession == session
        if (isCurrentSession) {
            watchdog?.cancel()
            watchdog = null
            activeSegment = null
        }
        val wallDurationMs = (System.currentTimeMillis() - segmentStartedAtMs).coerceAtLeast(0L)
        val failure = result.exceptionOrNull()
        val keepRecording = isCurrentSession && failure == null && _state.value is RecordingState.Recording

        if (keepRecording) {
            segmentNumber++
            // Next segment first: saving the finished one must never delay the camera.
            scope.launch { startNextSegment(mySession) }
        }

        val saved = enqueue { saveSegment(pending, failure, wallDurationMs) }
        if (!keepRecording) {
            scope.launch {
                saved.join()
                if (mySession == session) finishSession(failure)
            }
        }
    }

    private suspend fun startNextSegment(mySession: Int) {
        try {
            val next = segmentManager.newSegment(System.currentTimeMillis())
            if (mySession != session || _state.value !is RecordingState.Recording) {
                // Stop (or a failure) arrived during the rollover: nothing more to record.
                discardQuietly(next)
                finishAfterSaves(mySession)
                return
            }
            beginSegment(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the next segment", e)
            sessionError = sessionError ?: "Could not start the next segment: ${e.message}"
            finishAfterSaves(mySession)
        }
    }

    /** Waits until every finished segment has been published, then shuts the session down. */
    private fun finishAfterSaves(mySession: Int) {
        scope.launch {
            saveChain.join()
            if (mySession == session) finishSession(null)
        }
    }

    // --- Saving (sequential, behind the camera) ---

    private fun enqueue(block: suspend () -> Unit): Job {
        val previous = saveChain
        val job = scope.launch {
            previous.join()
            block()
        }
        saveChain = job
        return job
    }

    private suspend fun saveSegment(pending: PendingSegment, failure: Throwable?, wallDurationMs: Long) {
        try {
            val info = withContext(Dispatchers.IO) { RecordingProbe.probe(context, pending.uri) }

            // A segment that ended in an error is only kept if what is on disk can be played.
            if (failure != null && !info.isPlayable) {
                Log.w(TAG, "Discarding ${pending.displayName}: no playable video (${failure.message})")
                segmentManager.discard(pending)
                return
            }

            val segment = segmentManager.onSegmentFinalized(
                pending,
                durationMs = info.durationMs.takeIf { it > 0 } ?: wallDurationMs,
            )
            _lastRecording.value = RecordingResult.of(segment, info)
            enforceStorageLimit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not save ${pending.displayName}", e)
            sessionError = sessionError ?: "Could not save video: ${e.message}"
            // Keep going would only produce more files that cannot be saved.
            if (_state.value is RecordingState.Recording) stop()
        }
    }

    private suspend fun enforceStorageLimit() {
        try {
            val limit = settings.settings.value.storageLimitBytes
            // Room for the segment being written, so the cap is respected when it is finished.
            val reserve = videoConfig.bitrateBps / 8L * (segmentDurationMs() / 1000L) * 12L / 10L
            storageManager.enforceLimit(limit, reserve)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Storage cleanup failed", e)
        }
    }

    private suspend fun discardQuietly(pending: PendingSegment) {
        try {
            segmentManager.discard(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not discard ${pending.displayName}", e)
        }
    }

    // --- Ending ---

    private fun finishSession(failure: Throwable?) {
        val current = _state.value
        val error = sessionError
        val finalState: RecordingState = when {
            error != null -> RecordingState.Error(error)
            current is RecordingState.Error -> current
            current is RecordingState.Idle -> current
            // Stop pressed right after a rollover leaves an empty segment: that is not a failure.
            failure != null && !(current is RecordingState.Stopping && failure is NoValidDataException) ->
                RecordingState.Error(failure.message ?: "Recording failed")
            else -> RecordingState.Idle
        }
        releaseAndStopService(finalState)
    }

    private fun releaseAndStopService(finalState: RecordingState) {
        recorder.unbind()
        _state.value = finalState
        context.stopService(serviceIntent())
    }

    /** Settings can later offer 1/3/5/10 minutes; anything outside sane bounds is clamped. */
    private fun segmentDurationMs(): Long =
        settings.settings.value.segmentDurationMs.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)

    private fun serviceIntent() = Intent(context, RecordingService::class.java)

    private companion object {
        const val TAG = "Dashcam"
        const val MIN_SEGMENT_MS = 30_000L
        const val MAX_SEGMENT_MS = 60 * 60_000L
        const val WATCHDOG_GRACE_MS = 10_000L
    }
}
