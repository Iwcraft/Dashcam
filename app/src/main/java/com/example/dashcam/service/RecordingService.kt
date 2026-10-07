package com.example.dashcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.example.dashcam.DashcamApp
import com.example.dashcam.utils.hasPermission

/**
 * Keeps the process alive and foregrounded while recording. It is a LifecycleService because
 * CameraX binds use cases to a LifecycleOwner. Camera/segment/sensor work is added in later
 * phases and is driven from here via the engine.
 */
class RecordingService : LifecycleService() {
    private val container get() = (application as DashcamApp).container

    override fun onCreate() {
        super.onCreate()
        RecordingNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Must be reached promptly after startForegroundService(), or the system kills the app.
        try {
            ServiceCompat.startForeground(
                this,
                RecordingNotification.ID,
                RecordingNotification.build(this),
                foregroundServiceTypes(),
            )
        } catch (e: RuntimeException) {
            // SecurityException (missing permission for a declared type, API 34+) or
            // IllegalStateException (start not allowed from background, API 31+).
            container.recordingEngine.onServiceFailed(e.message ?: "Could not enter foreground")
            stopSelf()
        }

        // Not sticky: a silent restart from the background is not allowed to open the camera
        // on newer Android, and the user should know when recording has stopped.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        container.recordingEngine.onServiceStopped()
        super.onDestroy()
    }

    // TYPE_CAMERA is an API 30 constant; it is inlined at compile time, so API 29 is safe.
    @SuppressLint("InlinedApi")
    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val wantsGps = container.settingsRepository.settings.value.gpsEnabled
        if (wantsGps && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }
}
