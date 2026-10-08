package com.example.dashcam.sensors

/** Timestamps are wall-clock epoch ms, the same clock as segment start times. */
data class MotionSample(
    val timestampMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
)

/**
 * Receives raw readings on the sensor thread. Implementations must be cheap and must not
 * allocate: this is called ~50 times a second per sensor for as long as recording runs.
 */
interface MotionSink {
    /** m/s², including gravity. */
    fun onAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float)

    /** rad/s. */
    fun onGyroscope(timestampMs: Long, x: Float, y: Float, z: Float)
}

/** What actually started. A missing sensor disables only what depends on it. */
data class SensorAvailability(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val gps: Boolean,
)

interface SensorEngine {
    /**
     * Registers the sensors (and GPS when [gpsEnabled] and permitted) and feeds [sink].
     * Never throws: failures are logged and reported through the result. Idempotent.
     */
    fun start(gpsEnabled: Boolean, sink: MotionSink): SensorAvailability

    /** Releases every sensor and the sensor thread. Idempotent. */
    fun stop()

    /** Null when GPS is off, has no fix, or the last fix is stale. */
    fun currentSpeedMps(): Float?

    /** Reads the rolling in-memory buffer (about the last 20 s). Allocates; not for per-sample use. */
    fun snapshot(fromMs: Long, toMs: Long): List<MotionSample>
}
