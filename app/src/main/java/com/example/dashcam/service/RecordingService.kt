package com.example.dashcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.example.dashcam.DashcamApp
import com.example.dashcam.utils.hasPermission

/**
 * Keeps the process alive and foregrounded while recording. It is a LifecycleService because
 * CameraX binds use cases to a LifecycleOwner. It only hosts the session: the engine decides
 * when to open the camera, via onServiceForeground.
 */
class RecordingService : LifecycleService() {
    private val container get() = (application as DashcamApp).container
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        RecordingNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Must be reached promptly after startForegroundService(), or the system kills the app.
        val inForeground = try {
            ServiceCompat.startForeground(
                this,
                RecordingNotification.ID,
                RecordingNotification.build(this),
                foregroundServiceTypes(),
            )
            true
        } catch (e: RuntimeException) {
            // SecurityException (missing permission for a declared type, API 34+) or
            // IllegalStateException (start not allowed from background, API 31+).
            container.recordingEngine.onServiceFailed(e.message ?: "Could not enter foreground")
            stopSelf()
            false
        }

        if (inForeground) {
            acquireWakeLock()
            container.recordingEngine.onServiceForeground(this)
            // After the camera is requested, and fully isolated: the monitor catches its own errors.
            container.eventMonitor.start()
        }

        // Not sticky: a silent restart from the background is not allowed to open the camera
        // on newer Android, and the user should know when recording has stopped.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        container.eventMonitor.stop()
        container.recordingEngine.onServiceStopped()
        releaseWakeLock()
        super.onDestroy()
    }

    // Keeps the CPU running so encoding does not stall when the screen turns off.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dashcam:recording")
            .apply { acquire(MAX_WAKE_LOCK_MS) } // timeout is a safety net against a leaked lock
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // TYPE_CAMERA is an API 30 constant; it is inlined at compile time, so API 29 is safe.
    @SuppressLint("InlinedApi")
    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        // Android 11+ silences a background service's microphone unless it declares this type.
        // The engine already checked RECORD_AUDIO when it decided the session records audio.
        if (container.recordingEngine.sessionAudioActive &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        val wantsGps = container.settingsRepository.settings.value.gpsEnabled
        if (wantsGps && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private companion object {
        const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    }
}
