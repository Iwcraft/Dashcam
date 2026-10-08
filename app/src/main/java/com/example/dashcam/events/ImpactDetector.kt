package com.example.dashcam.events

import android.util.Log
import com.example.dashcam.settings.SettingsRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Possible-impact detection from two sensors, no ML:
 *
 *  1. Gravity is estimated with a low-pass filter and subtracted, leaving the sudden part of the
 *     acceleration. A reading above the accelerometer threshold becomes an "acceleration peak".
 *  2. A gyroscope reading above its threshold becomes a "rotation peak".
 *  3. When both peaks are within [ImpactThresholds.CORRELATION_WINDOW_MS] of each other, the
 *     detector measures for [ImpactThresholds.SETTLE_MS] more, then fires once and goes quiet for
 *     [ImpactThresholds.COOLDOWN_MS].
 *
 * Called on the sensor thread only, so it keeps plain fields and allocates nothing per sample.
 * Thresholds follow the Impact sensitivity setting live.
 */
class ImpactDetector(
    private val settings: SettingsRepository,
    private val speedProvider: () -> Float?,
) : EventDetector {
    @Volatile override var gyroscopeAvailable: Boolean = true
    @Volatile private var listener: ((DrivingEvent) -> Unit)? = null

    private var gravityReady = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var prevSampleMs = 0L
    private var warmupUntilMs = 0L

    private var accelPeak = 0f
    private var accelPeakMs = 0L
    private var accelLastMs = 0L
    private var gyroPeak = 0f
    private var gyroLastMs = 0L
    private var pendingSinceMs = 0L
    private var cooldownUntilMs = 0L

    override fun start(onEvent: (DrivingEvent) -> Unit) {
        gravityReady = false
        accelPeak = 0f; accelPeakMs = 0L; accelLastMs = 0L
        gyroPeak = 0f; gyroLastMs = 0L
        pendingSinceMs = 0L
        cooldownUntilMs = 0L
        listener = onEvent
    }

    override fun stop() {
        listener = null
    }

    override fun onAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float) {
        if (!gravityReady) {
            gravityX = x; gravityY = y; gravityZ = z
            gravityReady = true
            prevSampleMs = timestampMs
            warmupUntilMs = timestampMs + ImpactThresholds.WARMUP_MS
            return
        }
        val dt = ((timestampMs - prevSampleMs) / 1000f).coerceIn(0.001f, 0.2f)
        prevSampleMs = timestampMs
        val alpha = ImpactThresholds.GRAVITY_TAU_S / (ImpactThresholds.GRAVITY_TAU_S + dt)
        gravityX = alpha * gravityX + (1f - alpha) * x
        gravityY = alpha * gravityY + (1f - alpha) * y
        gravityZ = alpha * gravityZ + (1f - alpha) * z
        if (timestampMs < warmupUntilMs || timestampMs < cooldownUntilMs) return

        val dx = x - gravityX
        val dy = y - gravityY
        val dz = z - gravityZ
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)
        if (magnitude >= level().accelMps2) {
            if (timestampMs - accelLastMs > ImpactThresholds.CORRELATION_WINDOW_MS) accelPeak = 0f
            if (magnitude > accelPeak) {
                accelPeak = magnitude
                accelPeakMs = timestampMs
            }
            accelLastMs = timestampMs
        }
        evaluate(timestampMs)
    }

    override fun onGyroscope(timestampMs: Long, x: Float, y: Float, z: Float) {
        if (timestampMs < warmupUntilMs || timestampMs < cooldownUntilMs) return
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude >= level().gyroRadPerSec) {
            if (timestampMs - gyroLastMs > ImpactThresholds.CORRELATION_WINDOW_MS) gyroPeak = 0f
            gyroPeak = max(gyroPeak, magnitude)
            gyroLastMs = timestampMs
        }
        evaluate(timestampMs)
    }

    private fun evaluate(now: Long) {
        if (pendingSinceMs != 0L) {
            // Both sensors already agreed: just wait out the settle time, then report.
            if (now - pendingSinceMs >= ImpactThresholds.SETTLE_MS) fire(now)
            return
        }
        val accelRecent = accelLastMs != 0L && now - accelLastMs <= ImpactThresholds.CORRELATION_WINDOW_MS
        if (!accelRecent) return

        val correlated = if (gyroscopeAvailable) {
            gyroLastMs != 0L &&
                now - gyroLastMs <= ImpactThresholds.CORRELATION_WINDOW_MS &&
                abs(accelLastMs - gyroLastMs) <= ImpactThresholds.CORRELATION_WINDOW_MS
        } else {
            // No gyroscope on this device: accelerometer alone, but only for a much harder jolt.
            accelPeak >= level().accelMps2 * ImpactThresholds.NO_GYRO_ACCEL_FACTOR
        }
        if (correlated) pendingSinceMs = now
    }

    private fun fire(now: Long) {
        val event = DrivingEvent(
            type = EventType.POSSIBLE_IMPACT,
            timestampMs = accelPeakMs,
            peakAccelMps2 = accelPeak,
            peakRotationRadPerSec = gyroPeak,
            speedMps = speedProvider(),
        )
        accelPeak = 0f; accelPeakMs = 0L; accelLastMs = 0L
        gyroPeak = 0f; gyroLastMs = 0L
        pendingSinceMs = 0L
        cooldownUntilMs = now + ImpactThresholds.COOLDOWN_MS
        try {
            listener?.invoke(event)
        } catch (e: Exception) {
            Log.e(EventLog.TAG, "Event handler failed", e)
        }
    }

    private fun level() = ImpactThresholds.forSensitivity(settings.settings.value.impactSensitivity)
}
