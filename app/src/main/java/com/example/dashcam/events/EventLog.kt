package com.example.dashcam.events

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * One JSON line per event in app-private storage (events are rare, so this is not a continuous
 * write). Sensor samples themselves are never written to disk. Failures here are only logged.
 */
class EventLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "events.jsonl")

    @Synchronized
    fun append(event: DrivingEvent, protection: ProtectionResult?, extendedExistingEvent: Boolean) {
        try {
            val line = JSONObject().apply {
                put("timestampMs", event.timestampMs)
                put("type", event.type.name)
                put("peakAccelMps2", event.peakAccelMps2.toDouble())
                put("peakRotationRadPerSec", event.peakRotationRadPerSec.toDouble())
                event.speedMps?.let { put("speedMps", it.toDouble()) }
                put("extendedExistingEvent", extendedExistingEvent)
                protection?.let {
                    put("protectedSegments", it.protectedSegments)
                    put("pendingSegments", it.pendingSegments)
                }
            }
            if (file.length() > MAX_BYTES) {
                // Keep the newest half rather than growing forever.
                val keep = file.readLines().takeLast(MAX_LINES_AFTER_TRIM)
                file.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
            }
            file.appendText(line.toString() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the event log", e)
        }
    }

    companion object {
        const val TAG = "DashcamEvent"
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_LINES_AFTER_TRIM = 300
    }
}
