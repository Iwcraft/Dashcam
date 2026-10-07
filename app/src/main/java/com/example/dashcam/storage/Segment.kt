package com.example.dashcam.storage

import java.io.File

/** Start time is encoded in the file name, so no database is needed to index footage. */
data class Segment(
    val file: File,
    val startTimeMs: Long,
    val isProtected: Boolean,
)
