package com.example.dashcam.sensors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

interface SensorEngine {
    val motion: Flow<MotionSample>

    /** Null when GPS is disabled or there is no fix. */
    val speedMps: StateFlow<Float?>

    fun start(gpsEnabled: Boolean)

    fun stop()

    /** Reads the rolling buffer so sensor data can be saved next to protected footage. */
    fun snapshot(fromMs: Long, toMs: Long): List<MotionSample>
}
