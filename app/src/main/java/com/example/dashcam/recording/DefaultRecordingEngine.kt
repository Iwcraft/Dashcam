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
import com.example.dashcam.settings.VideoQuality
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

    /**
     * Resolution, frame rate, quality and audio are read once per session, when Start is pressed,
     * so a settings change never touches a running camera. Segment duration and the storage limit
     * are read live, so they apply from the next segment.
     */
    private var sessionConfig = VideoConfig()

    /** Bitrate actually set on the encoder (after any resolution fallback). */
    private var activeBitrateBps = VideoConfig.bitrateFor(1080, VideoQuality.HIGH)

    /**
     * True when this session records microphone audio. The service reads it to decide whether to
     * declare the microphone foreground-service type, so it must be decided here, once.
     */
    var sessionAudioActive = false
        private set
    private var segmentNumber = 0
    private var activeSegment: PendingSegment? = null
    private var segmentStartedAtMs = 0L
    private var watchdog: Job? = null

    /** The service's lifecycle, held only while it runs so the camera can be re-bound on recovery. */
    private var serviceOwner: LifecycleOwner? = null
    private var recoveryJob: Job? = null

    /** elapsedRealtime of each recent recovery attempt; bounds how often the camera is restarted. */
    private val recoveryAttempts = ArrayDeque<Long>()
    private var sessionStartedAtElapsedMs = 0L

    /** A failure that must end the session even though the camera itself is fine (e.g. saving failed). */
    private var sessionError: String? = null

    /** Publishing, probing and cleanup run strictly one after another, in segment order. */
    private var saveChain: Job = Job().apply { complete() }

    override fun start() {
        val current = _state.value
        if (current is RecordingState.Starting ||
            current is RecordingState.Recording ||
            current is RecordingState.Recovering ||
            current is RecordingState.Stopping
        ) return

        // On API 34+ a camera-type foreground service started without this permission throws.
        if (!context.hasPermission(Manifest.permission.CAMERA)) {
            _state.value = RecordingState.Error("Camera permission is required")
            return
        }

        session++
        sessionError = null
        recoveryAttempts.clear()
        Log.i(TAG, "Recording start requested")
        val snapshot = settings.settings.value
        // Audio needs RECORD_AUDIO; without it the session is silent rather than failing.
        sessionAudioActive = snapshot.audioEnabled && context.hasPermission(Manifest.permission.RECORD_AUDIO)
        sessionConfig = VideoConfig.from(snapshot, sessionAudioActive)
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
                Log.i(TAG, "Stop requested")
                _state.value = RecordingState.Stopping
                // Between two segments nothing is recording: startNextSegment sees Stopping
                // and finishes the shutdown itself.
                if (activeSegment != null) recorder.finishSegment()
            }
            is RecordingState.Recovering -> {
                // Nothing is being written, or a recovery segment is about to start. The recovery
                // loop notices the state change and exits; wait for it so the camera is not
                // re-bound after the shutdown.
                Log.i(TAG, "Stop requested while recovering")
                _state.value = RecordingState.Stopping
                val mySession = session
                scope.launch {
                    recoveryJob?.join()
                    if (mySession != session) return@launch
                    // A segment that already started is ended here and its finalize callback shuts
                    // the session down; otherwise shut down now.
                    if (activeSegment != null) recorder.finishSegment() else finishAfterSaves(mySession)
                }
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
        serviceOwner = owner
        val current = _state.value
        if (current !is RecordingState.Starting) {
            // Stop was requested before the service finished starting. A repeated start command
            // while a session is running must never shut that session down.
            if (current is RecordingState.Idle || current is RecordingState.Error) {
                context.stopService(serviceIntent())
            }
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

                // Make room first (also frees space when the phone is nearly full), and refuse to
                // start at all if there still is none, before the camera is touched.
                activeBitrateBps = VideoConfig.bitrateFor(sessionConfig.height, sessionConfig.quality)
                enforceStorageLimit()
                if (withContext(Dispatchers.IO) { isStorageLow() }) {
                    throw IllegalStateException(STORAGE_FULL_MESSAGE)
                }

                val video = recorder.bind(owner, sessionConfig)
                quality = video.description
                activeBitrateBps = video.bitrateBps
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
        serviceOwner = null
        when (_state.value) {
            is RecordingState.Starting, is RecordingState.Recording, is RecordingState.Recovering -> {
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
        Log.i(TAG, "Segment $segmentNumber: created ${pending.displayName}")
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

        val nowElapsed = SystemClock.elapsedRealtime()
        when (val current = _state.value) {
            // The session timer starts once, with the first segment, and runs across rollovers.
            is RecordingState.Starting -> {
                sessionStartedAtElapsedMs = nowElapsed
                _state.value = RecordingState.Recording(
                    startedAtElapsedMs = nowElapsed,
                    quality = quality,
                    segmentNumber = segmentNumber,
                    segmentStartedAtElapsedMs = nowElapsed,
                )
                Log.i(TAG, "Recording started: $quality")
            }
            is RecordingState.Recording -> _state.value = current.copy(
                segmentNumber = segmentNumber,
                segmentStartedAtElapsedMs = nowElapsed,
            )
            is RecordingState.Recovering -> {
                Log.i(TAG, "Recovered: recording again as segment $segmentNumber")
                _state.value = RecordingState.Recording(
                    startedAtElapsedMs = sessionStartedAtElapsedMs,
                    quality = quality,
                    segmentNumber = segmentNumber,
                    segmentStartedAtElapsedMs = nowElapsed,
                )
            }
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
        val stateNow = _state.value
        val live = isCurrentSession && (stateNow is RecordingState.Recording || stateNow is RecordingState.Recovering)
        val keepRecording = live && failure == null && stateNow is RecordingState.Recording
        // Only failures the camera layer marked as retryable; storage-full etc. end the session.
        val recoverable = live && failure != null && (failure as? RecordingFailure)?.recoverable == true

        if (failure == null) {
            Log.i(TAG, "Segment finalized: ${pending.displayName}")
        } else {
            Log.w(TAG, "Segment ended with an error: ${pending.displayName}: ${failure.message}")
        }

        if (keepRecording) {
            segmentNumber++
            // Next segment first: saving the finished one must never delay the camera.
            scope.launch { startNextSegment(mySession) }
        } else if (recoverable) {
            startRecovery(mySession, failure?.message ?: "Camera error")
        }

        val saved = enqueue { saveSegment(pending, failure, wallDurationMs) }
        if (!keepRecording && !recoverable) {
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
            sessionError = sessionError
                ?: if (isStorageLow()) STORAGE_FULL_MESSAGE else "Could not start the next segment: ${e.message}"
            finishAfterSaves(mySession)
        }
    }

    // --- Camera recovery ---

    private fun stillRecovering(mySession: Int) =
        mySession == session && _state.value is RecordingState.Recovering

    /**
     * Restarts the camera after a retryable failure: unbind, back off, bind again, new segment.
     * At most [MAX_RECOVERY_ATTEMPTS] attempts per [RECOVERY_WINDOW_MS], with growing pauses, so a
     * camera that keeps failing ends the session instead of looping (and heating the phone).
     * Cooperative: it never gets cancelled mid-step; it re-checks the state after every wait.
     */
    private fun startRecovery(mySession: Int, reason: String) {
        recoveryJob = scope.launch {
            var lastReason = reason
            while (true) {
                val now = SystemClock.elapsedRealtime()
                while (recoveryAttempts.isNotEmpty() && now - recoveryAttempts.first() > RECOVERY_WINDOW_MS) {
                    recoveryAttempts.removeFirst()
                }
                if (recoveryAttempts.size >= MAX_RECOVERY_ATTEMPTS) {
                    Log.e(TAG, "Giving up: camera failed repeatedly. Last error: $lastReason")
                    sessionError = sessionError ?: "The camera kept failing ($lastReason). Recording stopped."
                    finishAfterSaves(mySession)
                    return@launch
                }
                recoveryAttempts.addLast(now)
                val attempt = recoveryAttempts.size
                _state.value = RecordingState.Recovering(
                    attempt = attempt,
                    maxAttempts = MAX_RECOVERY_ATTEMPTS,
                    reason = lastReason,
                    startedAtElapsedMs = sessionStartedAtElapsedMs,
                    quality = quality,
                    segmentNumber = segmentNumber,
                )
                Log.w(TAG, "Recovery attempt $attempt/$MAX_RECOVERY_ATTEMPTS after: $lastReason")

                // Waits in short slices so Stop is honoured within a fraction of a second.
                val backoffMs = RECOVERY_BACKOFF_MS[(attempt - 1).coerceAtMost(RECOVERY_BACKOFF_MS.lastIndex)]
                var waitedMs = 0L
                while (waitedMs < backoffMs && stillRecovering(mySession)) {
                    delay(BACKOFF_SLICE_MS)
                    waitedMs += BACKOFF_SLICE_MS
                }
                if (!stillRecovering(mySession)) return@launch

                val owner = serviceOwner
                if (owner == null || !context.hasPermission(Manifest.permission.CAMERA)) {
                    Log.e(TAG, "Cannot recover: camera permission or service is gone")
                    sessionError = sessionError ?: "Camera permission was removed. Recording stopped."
                    finishAfterSaves(mySession)
                    return@launch
                }
                try {
                    recorder.unbind()
                    val video = recorder.bind(owner, sessionConfig)
                    quality = video.description
                    activeBitrateBps = video.bitrateBps
                    val next = segmentManager.newSegment(System.currentTimeMillis())
                    if (!stillRecovering(mySession)) {
                        discardQuietly(next) // Stop arrived while the camera was being re-bound
                        return@launch
                    }
                    segmentNumber++
                    beginSegment(next)
                    return@launch // onSegmentStarted flips the state back to Recording
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastReason = e.message ?: "Camera could not restart"
                    Log.w(TAG, "Recovery attempt $attempt failed", e)
                    // Stop or a service death during the attempt wins: never overwrite its state.
                    if (!stillRecovering(mySession)) return@launch
                    if (isStorageLow()) {
                        sessionError = sessionError ?: STORAGE_FULL_MESSAGE
                        finishAfterSaves(mySession)
                        return@launch
                    }
                }
            }
        }
    }

    /** True when the phone has too little free space to record. A failed check counts as "fine". */
    private fun isStorageLow(): Boolean = try {
        storageManager.availableBytes() < MIN_START_FREE_BYTES
    } catch (e: Exception) {
        false
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
            Log.i(TAG, "Saved ${segment.displayName}: ${segment.sizeBytes / 1024} KB, ${segment.durationMs} ms")
            enforceStorageLimit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not save ${pending.displayName}", e)
            sessionError = sessionError ?: "Could not save video: ${e.message}"
            // Keep going would only produce more files that cannot be saved.
            val current = _state.value
            if (current is RecordingState.Recording || current is RecordingState.Recovering) stop()
        }
    }

    private suspend fun enforceStorageLimit() {
        try {
            val limit = settings.settings.value.storageLimitBytes
            // Room for the segment being written, so the cap is respected when it is finished.
            val reserve = activeBitrateBps / 8L * (segmentDurationMs() / 1000L) * 12L / 10L
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

    /** Settings offer 1/3/5/10 minutes; anything outside sane bounds is clamped. */
    private fun segmentDurationMs(): Long =
        settings.settings.value.segmentDurationMs.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)

    private fun serviceIntent() = Intent(context, RecordingService::class.java)

    private companion object {
        const val TAG = "Dashcam"
        const val MIN_SEGMENT_MS = 30_000L
        const val MAX_SEGMENT_MS = 60 * 60_000L
        const val WATCHDOG_GRACE_MS = 10_000L

        /** Camera restarts allowed per window, with growing pauses: no uncontrolled restart loop. */
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val RECOVERY_WINDOW_MS = 10 * 60_000L
        val RECOVERY_BACKOFF_MS = longArrayOf(3_000L, 10_000L, 30_000L)
        const val BACKOFF_SLICE_MS = 250L

        /** About one segment of room; below this a session is not started. */
        const val MIN_START_FREE_BYTES = 500L * 1024L * 1024L
        const val STORAGE_FULL_MESSAGE =
            "Storage is full. Free up space or delete old Protected/Event footage, then start again."
    }
}
