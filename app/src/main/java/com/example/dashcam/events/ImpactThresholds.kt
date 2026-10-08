package com.example.dashcam.events

import com.example.dashcam.settings.ImpactSensitivity

/**
 * Every tunable number of the event system is here. These are starting values chosen from
 * typical phone-in-a-car readings, NOT calibrated against real crashes: tune them from the
 * "DashcamEvent" log after test drives.
 */
object ImpactThresholds {
    /** Both must be exceeded, close together in time, for a possible impact. */
    class Level(
        /** Acceleration with gravity removed, m/s² (9.81 = 1 g). */
        val accelMps2: Float,
        /** Angular speed, rad/s (1 rad/s is about 57 °/s). */
        val gyroRadPerSec: Float,
    )

    // Low = hardest to trigger, High = easiest.
    val LOW = Level(accelMps2 = 35f, gyroRadPerSec = 4.0f)      // ~3.6 g and ~230 °/s
    val MEDIUM = Level(accelMps2 = 25f, gyroRadPerSec = 3.0f)   // ~2.5 g and ~170 °/s
    val HIGH = Level(accelMps2 = 16f, gyroRadPerSec = 2.0f)     // ~1.6 g and ~115 °/s

    fun forSensitivity(sensitivity: ImpactSensitivity): Level = when (sensitivity) {
        ImpactSensitivity.LOW -> LOW
        ImpactSensitivity.MEDIUM -> MEDIUM
        ImpactSensitivity.HIGH -> HIGH
    }

    /** The acceleration and rotation peaks must be within this of each other. */
    const val CORRELATION_WINDOW_MS = 1_000L

    /** After both are seen, keep measuring this long so the reported peaks are the real peaks. */
    const val SETTLE_MS = 300L

    /** After an event, ignore further triggers: the rebound of one collision is the same event. */
    const val COOLDOWN_MS = 5_000L

    /** Ignored after start while the gravity estimate settles. */
    const val WARMUP_MS = 1_500L

    /** Time constant of the low-pass filter that estimates gravity. */
    const val GRAVITY_TAU_S = 0.5f

    /** Without a gyroscope the accelerometer alone must exceed its threshold by this factor. */
    const val NO_GYRO_ACCEL_FACTOR = 1.5f
}

/** How much footage an event protects. */
object EventProtection {
    const val PRE_EVENT_MS = 60_000L
    const val POST_EVENT_MS = 120_000L
}
