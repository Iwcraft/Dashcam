package com.example.dashcam

import android.content.Context
import com.example.dashcam.events.DefaultEmergencySaveManager
import com.example.dashcam.events.DrivingEventMonitor
import com.example.dashcam.events.EmergencySaveManager
import com.example.dashcam.events.EventLog
import com.example.dashcam.events.ImpactDetector
import com.example.dashcam.recording.CameraXRecorder
import com.example.dashcam.recording.DefaultRecordingEngine
import com.example.dashcam.recording.SegmentManager
import com.example.dashcam.sensors.AndroidSensorEngine
import com.example.dashcam.sensors.SensorEngine
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

    val emergencySaveManager: EmergencySaveManager = DefaultEmergencySaveManager(segmentManager, settingsRepository)

    // Sensors and impact detection. Independent of the recording engine on purpose: the service
    // starts/stops the monitor, and nothing in it can fail the camera.
    val sensorEngine: SensorEngine = AndroidSensorEngine(appContext)
    val eventMonitor = DrivingEventMonitor(
        context = appContext,
        sensors = sensorEngine,
        detector = ImpactDetector(settingsRepository, speedProvider = sensorEngine::currentSpeedMps),
        emergencySave = emergencySaveManager,
        eventLog = EventLog(appContext),
        settings = settingsRepository,
    )

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
