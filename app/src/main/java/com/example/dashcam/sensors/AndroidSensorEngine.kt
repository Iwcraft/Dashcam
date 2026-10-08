package com.example.dashcam.sensors

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.dashcam.events.EventLog
import com.example.dashcam.utils.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SensorManager + LocationManager (no Google Play services, no network). All callbacks run on
 * one dedicated HandlerThread, never on the main thread, and nothing here touches disk.
 *
 * It takes no wake lock of its own: the recording service already holds one while recording.
 */
class AndroidSensorEngine(context: Context) : SensorEngine {
    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val locationManager: LocationManager? = appContext.getSystemService(LocationManager::class.java)

    private var thread: HandlerThread? = null
    private var availability = SensorAvailability(accelerometer = false, gyroscope = false, gps = false)

    @Volatile private var sink: MotionSink? = null

    // event.timestamp is nanoseconds since boot; this converts it to the epoch clock segments use.
    @Volatile private var wallOffsetMs = 0L

    // Rolling buffer: primitive arrays, so writing a sample allocates nothing.
    private val bufferLock = Any()
    private val times = LongArray(CAPACITY)
    private val values = FloatArray(CAPACITY * 6)
    private var head = 0
    private var count = 0

    // Latest gyro reading, paired with each accelerometer sample. Sensor thread only.
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var failureLogged = false

    private val _gps = MutableStateFlow(GpsStatus())
    override val gps: StateFlow<GpsStatus> = _gps.asStateFlow()

    @Volatile private var speedMps = Float.NaN
    @Volatile private var speedFixElapsedMs = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            try {
                val v = event.values
                val t = event.timestamp / 1_000_000L + wallOffsetMs
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        record(t, v[0], v[1], v[2])
                        sink?.onAccelerometer(t, v[0], v[1], v[2])
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroX = v[0]; gyroY = v[1]; gyroZ = v[2]
                        sink?.onGyroscope(t, v[0], v[1], v[2])
                    }
                }
            } catch (e: Exception) {
                // A sensor/detector bug must never reach the camera. Log once, keep running.
                if (!failureLogged) {
                    failureLogged = true
                    Log.e(EventLog.TAG, "Sensor callback failed; continuing", e)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // All four methods are implemented on purpose: before API 30 they are abstract, not default.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            speedMps = if (location.hasSpeed()) location.speed else Float.NaN
            speedFixElapsedMs = now
            _gps.value = GpsStatus(GpsState.FIX, speedMps.takeUnless { it.isNaN() }, now)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            Log.i(EventLog.TAG, "GPS provider enabled; searching for a fix")
            _gps.value = GpsStatus(GpsState.SEARCHING)
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(EventLog.TAG, "GPS provider disabled; speed unavailable")
            speedMps = Float.NaN
            _gps.value = GpsStatus(GpsState.UNAVAILABLE)
        }
    }

    @Synchronized
    override fun start(gpsEnabled: Boolean, sink: MotionSink): SensorAvailability {
        if (thread != null) return availability
        val manager = sensorManager
        if (manager == null) {
            Log.w(EventLog.TAG, "No SensorManager; impact detection disabled")
            return availability
        }

        synchronized(bufferLock) { head = 0; count = 0 }
        gyroX = 0f; gyroY = 0f; gyroZ = 0f
        failureLogged = false
        speedMps = Float.NaN
        wallOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        this.sink = sink

        val newThread = HandlerThread("DashcamSensors").also { it.start() }
        val handler = Handler(newThread.looper)

        val accelerometer = register(manager, Sensor.TYPE_ACCELEROMETER, handler)
        val gyroscope = register(manager, Sensor.TYPE_GYROSCOPE, handler)
        val gps = gpsEnabled && startGps(handler)
        if (!gpsEnabled) _gps.value = GpsStatus()

        availability = SensorAvailability(accelerometer, gyroscope, gps)
        thread = newThread
        Log.i(EventLog.TAG, "Sensor engine started: accelerometer=$accelerometer gyroscope=$gyroscope gps=$gps")
        return availability
    }

    @Synchronized
    override fun stop() {
        val running = thread ?: return
        try {
            sensorManager?.unregisterListener(sensorListener)
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(EventLog.TAG, "Error while releasing sensors", e)
        }
        sink = null
        running.quitSafely()
        thread = null
        availability = SensorAvailability(accelerometer = false, gyroscope = false, gps = false)
        speedMps = Float.NaN
        _gps.value = GpsStatus()
        Log.i(EventLog.TAG, "Sensor engine stopped")
    }

    override fun currentSpeedMps(): Float? {
        val speed = speedMps
        if (speed.isNaN()) return null
        return if (SystemClock.elapsedRealtime() - speedFixElapsedMs <= GPS_MAX_AGE_MS) speed else null
    }

    override fun snapshot(fromMs: Long, toMs: Long): List<MotionSample> = synchronized(bufferLock) {
        val result = ArrayList<MotionSample>()
        val oldest = (head - count + CAPACITY) % CAPACITY
        for (i in 0 until count) {
            val slot = (oldest + i) % CAPACITY
            val t = times[slot]
            if (t < fromMs || t > toMs) continue
            val o = slot * 6
            result += MotionSample(t, values[o], values[o + 1], values[o + 2], values[o + 3], values[o + 4], values[o + 5])
        }
        result
    }

    private fun record(t: Long, x: Float, y: Float, z: Float) {
        synchronized(bufferLock) {
            times[head] = t
            val o = head * 6
            values[o] = x; values[o + 1] = y; values[o + 2] = z
            values[o + 3] = gyroX; values[o + 4] = gyroY; values[o + 5] = gyroZ
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
        }
    }

    private fun register(manager: SensorManager, type: Int, handler: Handler): Boolean {
        val sensor = manager.getDefaultSensor(type)
        if (sensor == null) {
            Log.w(EventLog.TAG, "Sensor type $type is not available on this device")
            return false
        }
        return try {
            // GAME is ~50 Hz: plenty for impacts, light on CPU and battery.
            manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } catch (e: Exception) {
            Log.w(EventLog.TAG, "Could not register sensor type $type", e)
            false
        }
    }

    private fun startGps(handler: Handler): Boolean {
        val manager = locationManager
        if (manager == null) {
            _gps.value = GpsStatus(GpsState.UNAVAILABLE)
            return false
        }
        if (!appContext.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.i(EventLog.TAG, "GPS is on but location permission is not granted; speed unavailable")
            _gps.value = GpsStatus(GpsState.NO_PERMISSION)
            return false
        }
        return try {
            // One fix every few seconds is enough for an event's speed and is easy on the battery.
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, handler.looper,
            )
            // Registered even when location is switched off, so turning it on later is noticed.
            _gps.value = GpsStatus(
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsState.SEARCHING else GpsState.UNAVAILABLE,
            )
            true
        } catch (e: SecurityException) {
            Log.w(EventLog.TAG, "Location permission denied; speed unavailable", e)
            _gps.value = GpsStatus(GpsState.NO_PERMISSION)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(EventLog.TAG, "No GPS provider on this device; speed unavailable", e)
            _gps.value = GpsStatus(GpsState.UNAVAILABLE)
            false
        }
    }

    private companion object {
        /** About 20 s at 50 Hz. */
        const val CAPACITY = 1024
        const val GPS_INTERVAL_MS = 2_000L
        const val GPS_MAX_AGE_MS = 6_000L
    }
}
