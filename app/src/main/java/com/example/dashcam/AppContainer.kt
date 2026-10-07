package com.example.dashcam

import android.content.Context
import com.example.dashcam.recording.CameraXRecorder
import com.example.dashcam.recording.DefaultRecordingEngine
import com.example.dashcam.recording.SegmentManager
import com.example.dashcam.settings.SettingsRepository
import com.example.dashcam.storage.MediaStoreSegmentManager
import com.example.dashcam.storage.MediaStoreStorageManager
import com.example.dashcam.storage.MediaStoreVideoStore
import com.example.dashcam.storage.SegmentIndex
import com.example.dashcam.storage.StorageManager

/**
 * Manual wiring. The UI process and RecordingService share one process, so they must share
 * the same engine/settings instances; a DI framework isn't needed for that.
 * Each later phase adds its implementation here.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)

    // One MediaStore gateway and one index, shared by both managers: the cleanup must see
    // exactly the footage (and protection changes) the segment manager has made.
    private val videoStore = MediaStoreVideoStore(appContext)
    private val segmentIndex = SegmentIndex(videoStore)

    val segmentManager: SegmentManager = MediaStoreSegmentManager(videoStore, segmentIndex)
    val storageManager: StorageManager = MediaStoreStorageManager(appContext, videoStore, segmentIndex)

    // Concrete type on purpose: RecordingService uses the service-side callbacks that are
    // not part of the UI-facing RecordingEngine interface.
    val recordingEngine = DefaultRecordingEngine(
        context = appContext,
        recorder = CameraXRecorder(appContext),
        segmentManager = segmentManager,
        storageManager = storageManager,
        settings = settingsRepository,
    )
}
