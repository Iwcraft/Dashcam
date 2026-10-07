package com.example.dashcam.settings

private const val MINUTE_MS = 60_000L
private const val GB = 1024L * 1024L * 1024L

data class RecordingSettings(
    val segmentDurationMs: Long = 5 * MINUTE_MS,
    /** Cap for all app footage (rolling + protected). Sized for a 64 GB device. */
    val storageLimitBytes: Long = 20 * GB,
    /** How far back "Save Last 5 Minutes" reaches. */
    val emergencySaveWindowMs: Long = 5 * MINUTE_MS,
    val gpsEnabled: Boolean = false,
)
