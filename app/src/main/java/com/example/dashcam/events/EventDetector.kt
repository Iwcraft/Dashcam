package com.example.dashcam.events

import com.example.dashcam.sensors.MotionSink

/** Consumes raw sensor readings and reports events. Does no I/O itself. */
interface EventDetector : MotionSink {
    /** False makes the detector fall back to the accelerometer alone, at a higher threshold. */
    var gyroscopeAvailable: Boolean

    /** [onEvent] runs on the sensor thread: hand real work off quickly. */
    fun start(onEvent: (DrivingEvent) -> Unit)

    fun stop()
}
