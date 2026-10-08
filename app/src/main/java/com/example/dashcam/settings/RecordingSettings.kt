package com.example.dashcam.settings

private const val MINUTE_MS = 60_000L
private const val GB = 1024L * 1024L * 1024L

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    P1080("1080p", 1920, 1080),
    P720("720p", 1280, 720),
}

enum class VideoQuality(val label: String) {
    STANDARD("Standard"),
    HIGH("High"),
}

enum class ImpactSensitivity(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

data class RecordingSettings(
    // --- Recording (connected to the recording engine) ---
    val resolution: VideoResolution = VideoResolution.P1080,
    val frameRate: Int = 30,
    val segmentDurationMs: Long = 5 * MINUTE_MS,
    val audioEnabled: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.HIGH,
    /** Only affects the UI (window flag while the app is open); the recording engine ignores it. */
    val keepScreenOn: Boolean = false,

    // --- Storage ---
    /** Cap for all app footage (rolling + protected). */
    val storageLimitBytes: Long = 4 * GB,
    /** How far back "Save Last 5 Minutes" reaches. */
    val emergencySaveWindowMs: Long = 5 * MINUTE_MS,

    // --- Stored for later phases; nothing reads these to act yet ---
    val gpsEnabled: Boolean = false,
    val impactSensitivity: ImpactSensitivity = ImpactSensitivity.MEDIUM,
    val autoStartRecording: Boolean = false,
) {
    companion object {
        val RESOLUTION_OPTIONS: List<VideoResolution> = VideoResolution.entries
        val FRAME_RATE_OPTIONS: List<Int> = listOf(30, 24)
        val SEGMENT_DURATION_OPTIONS_MS: List<Long> = listOf(1L, 3L, 5L, 10L).map { it * MINUTE_MS }
        val STORAGE_LIMIT_OPTIONS_BYTES: List<Long> = listOf(2L, 4L, 8L, 16L, 20L).map { it * GB }
    }
}
