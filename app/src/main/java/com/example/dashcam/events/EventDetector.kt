package com.example.dashcam.events

import kotlinx.coroutines.flow.Flow

/** Consumes SensorEngine data and emits events. Does no I/O itself. */
interface EventDetector {
    val events: Flow<DrivingEvent>

    fun start()

    fun stop()
}
