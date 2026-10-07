package com.example.dashcam.storage

/**
 * A segment's state is the MediaStore folder it lives in, so it survives app-data clears and
 * is visible in the Files app. Changing state is a RELATIVE_PATH update, which MediaProvider
 * performs as a rename on the same volume: no bytes are copied.
 *
 * Declaration order is precedence: a later state outranks an earlier one.
 */
enum class VideoState(val relativePath: String) {
    /** Loop footage. The only state automatic cleanup may delete. */
    NORMAL("DCIM/Dashcam/"),

    /** Kept on purpose (e.g. "Save Last 5 Minutes"). Never auto-deleted. */
    PROTECTED("DCIM/Dashcam/Protected/"),

    /** Kept because an event (hard braking, impact) was detected. Never auto-deleted. */
    EVENT("DCIM/Dashcam/Event/");

    /** The folder as the person sees it in the Files app. */
    val displayFolder: String get() = "Internal storage/$relativePath"

    companion object {
        /** Null for any folder this app does not manage, which callers must leave alone. */
        fun fromRelativePath(path: String?): VideoState? {
            if (path == null) return null
            val normalized = if (path.endsWith("/")) path else "$path/"
            return entries.firstOrNull { it.relativePath == normalized }
        }
    }
}
