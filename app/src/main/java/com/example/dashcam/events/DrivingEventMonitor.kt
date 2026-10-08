package com.example.dashcam.events

import android.Manifest
import android.content.Context
import android.util.Log
import com.example.dashcam.sensors.SensorEngine
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.utils.hasPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the UI shows about the latest possible impact. */
data class EventStatus(
    /** Merged when several impacts fall inside one active event: earliest time, highest peaks. */
    val event: DrivingEvent,
    /** Extra impacts folded into this event instead of becoming new events. */
    val extraImpacts: Int = 0,
    /** Null while the protection request is still running. */
    val protection: ProtectionResult? = null,
    val protectionFailed: Boolean = false,
)

data class ManualSaveStatus(
    val timeMs: Long,
    val result: ProtectionResult?,
    val failed: Boolean,
)

/**
 * The one place that connects sensors -> detector -> protection, and the only thing the service
 * and UI talk to. It is deliberately separate from the recording engine: every failure here is
 * caught and logged, so nothing in this class can stop or disturb video recording.
 */
class DrivingEventMonitor(
    private val context: Context,
    private val sensors: SensorEngine,
    private val detector: EventDetector,
    private val emergencySave: EmergencySaveManager,
    private val eventLog: EventLog,
    private val settings: SettingsRepository,
) {
    // Process-wide, like the recording engine's scope; protection must finish even if the UI leaves.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lastEvent = MutableStateFlow<EventStatus?>(null)
    val lastEvent: StateFlow<EventStatus?> = _lastEvent.asStateFlow()

    private val _lastManualSave = MutableStateFlow<ManualSaveStatus?>(null)
    val lastManualSave: StateFlow<ManualSaveStatus?> = _lastManualSave.asStateFlow()

    /** True while a manual save runs; the UI disables the button instead of relying on the guard. */
    private val _manualSaving = MutableStateFlow(false)
    val manualSaving: StateFlow<Boolean> = _manualSaving.asStateFlow()

    private var running = false
    private val savingManually = AtomicBoolean(false)

    /** Events are handled strictly one at a time, so the active-window bookkeeping is race-free. */
    private val eventMutex = Mutex()
    private var activeUntilMs = 0L

    @Synchronized
    fun start() {
        if (running) return
        try {
            val wantsGps = settings.settings.value.gpsEnabled &&
                context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            detector.start(::onDetected)
            val availability = sensors.start(wantsGps, detector)
            detector.gyroscopeAvailable = availability.gyroscope
            running = true
            when {
                !availability.accelerometer ->
                    Log.w(EventLog.TAG, "No accelerometer: impact detection is disabled, recording is unaffected")
                !availability.gyroscope ->
                    Log.w(EventLog.TAG, "No gyroscope: detecting from the accelerometer alone at a higher threshold")
            }
        } catch (e: Exception) {
            Log.e(EventLog.TAG, "Could not start sensors; recording continues without impact detection", e)
            stopQuietly()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopQuietly()
    }

    private fun stopQuietly() {
        try {
            sensors.stop()
            detector.stop()
        } catch (e: Exception) {
            Log.w(EventLog.TAG, "Error while stopping sensors", e)
        }
    }

    fun dismissEvent() {
        _lastEvent.value = null
    }

    /** Safe to press repeatedly: a press while one is running is ignored. */
    fun saveLastFiveMinutes() {
        if (!savingManually.compareAndSet(false, true)) return
        _manualSaving.value = true
        scope.launch {
            try {
                Log.i(EventLog.TAG, "Manual save requested")
                val result = emergencySave.saveLast()
                _lastManualSave.value = ManualSaveStatus(System.currentTimeMillis(), result, failed = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(EventLog.TAG, "Manual save failed", e)
                _lastManualSave.value = ManualSaveStatus(System.currentTimeMillis(), null, failed = true)
            } finally {
                savingManually.set(false)
                _manualSaving.value = false
            }
        }
    }

    /** Runs on the sensor thread: only hands off. */
    private fun onDetected(event: DrivingEvent) {
        scope.launch { handle(event) }
    }

    private suspend fun handle(event: DrivingEvent) = eventMutex.withLock {
        try {
            // An impact inside the post-event window of an earlier one is the same incident:
            // extend its window and merge the peaks instead of starting a second event.
            val previous = _lastEvent.value
            val extends = previous != null && event.timestampMs <= activeUntilMs
            activeUntilMs = max(activeUntilMs, event.timestampMs + EventProtection.POST_EVENT_MS)

            val status = if (extends && previous != null) {
                EventStatus(
                    event = previous.event.copy(
                        peakAccelMps2 = max(previous.event.peakAccelMps2, event.peakAccelMps2),
                        peakRotationRadPerSec = max(previous.event.peakRotationRadPerSec, event.peakRotationRadPerSec),
                        speedMps = previous.event.speedMps ?: event.speedMps,
                    ),
                    extraImpacts = previous.extraImpacts + 1,
                )
            } else {
                EventStatus(event)
            }
            _lastEvent.value = status

            Log.i(
                EventLog.TAG,
                "Possible impact detected${if (extends) " (extends active event)" else ""}: " +
                    "peakAccel=%.1f m/s2 peakRotation=%.2f rad/s speed=%s at %d".format(
                        event.peakAccelMps2,
                        event.peakRotationRadPerSec,
                        event.speedMps?.let { "%.1f m/s".format(it) } ?: "n/a",
                        event.timestampMs,
                    ),
            )

            val key = status.event.timestampMs
            try {
                val result = emergencySave.protectAround(event)
                _lastEvent.update { if (it?.event?.timestampMs == key) it.copy(protection = result) else it }
                eventLog.append(event, result, extends)
                Log.i(EventLog.TAG, "Event protection complete")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(EventLog.TAG, "Could not protect event footage", e)
                _lastEvent.update { if (it?.event?.timestampMs == key) it.copy(protectionFailed = true) else it }
                eventLog.append(event, null, extends)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(EventLog.TAG, "Event handling failed", e)
        }
    }
}
