package com.example.dashcam.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dashcam.events.EventStatus
import com.example.dashcam.events.ManualSaveStatus
import com.example.dashcam.recording.RecordingResult
import com.example.dashcam.recording.RecordingState
import com.example.dashcam.sensors.GpsState
import com.example.dashcam.sensors.GpsStatus
import com.example.dashcam.storage.StorageUsage
import com.example.dashcam.utils.hasPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val TICK_MS = 1_000L
private const val USAGE_REFRESH_MS = 10_000L

/** GPS asks for a fix every 2 s; this much silence means the signal is gone. */
private const val GPS_STALE_MS = 10_000L
private const val MB = 1024L * 1024L
private const val GB = 1024L * MB
private const val LOW_FREE_BYTES = GB

/**
 * The whole screen is derived from state owned elsewhere (recording engine, event monitor, sensor
 * engine), so rotating the phone, which recreates the Activity, never touches the recording and
 * loses nothing on screen. Layout switches on the available size, not on a locked orientation:
 * portrait stacks everything, landscape puts the preview beside the status and controls.
 */
@Composable
fun DashcamScreen(
    onOpenSettings: () -> Unit,
    viewModel: DashcamViewModel = viewModel(factory = DashcamViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val lastRecording by viewModel.lastRecording.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val lastManualSave by viewModel.lastManualSave.collectAsStateWithLifecycle()
    val manualSaving by viewModel.manualSaving.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val gps by viewModel.gps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionDenied = grants[Manifest.permission.CAMERA] != true
        if (!permissionDenied) viewModel.startRecording()
    }

    UsagePolling(viewModel)
    val now = rememberNowMs()

    val onToggle: () -> Unit = {
        when (state) {
            is RecordingState.Idle, is RecordingState.Error -> {
                if (context.hasPermission(Manifest.permission.CAMERA)) {
                    viewModel.startRecording()
                } else {
                    permissionLauncher.launch(requiredPermissions())
                }
            }
            is RecordingState.Starting, is RecordingState.Recording, is RecordingState.Recovering ->
                viewModel.stopRecording()
            is RecordingState.Stopping -> Unit
        }
    }

    val windowMinutes = (settings.emergencySaveWindowMs / 60_000L).toInt().coerceAtLeast(1)
    val saveLabel = "Save Last $windowMinutes ${if (windowMinutes == 1) "Minute" else "Minutes"}"
    val sessionActive = state is RecordingState.Starting ||
        state is RecordingState.Recording ||
        state is RecordingState.Recovering
    val showPreviewHint = !(state is RecordingState.Recording || state is RecordingState.Recovering)

    // Everything below the preview. Scrolls instead of clipping when the space is tight.
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (state as? RecordingState.Error)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (permissionDenied) {
                Text("Camera permission was denied. Recording needs it.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Open app settings") }
            }

            StorageInfo(usage, settings.storageLimitBytes)
            GpsInfo(settings.gpsEnabled, gps, sessionActive, now)

            val event = lastEvent
            if (event == null) {
                Text("Impact: none detected", style = MaterialTheme.typography.bodySmall)
            } else {
                EventCard(event, onDismiss = viewModel::dismissEvent)
            }

            lastManualSave?.let { ManualSaveText(it, windowMinutes) }
            lastRecording?.let { LastRecording(it) }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val landscape = maxWidth > maxHeight

        // A View can only have one parent; a new one per layout avoids re-parenting if the window
        // is resized across the portrait/landscape boundary without recreating the Activity.
        val previewView = remember(landscape) {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        }
        PreviewAttachment(previewView, viewModel)

        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewPane(previewView, showPreviewHint, Modifier.weight(1.2f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusHeader(state, now, onOpenSettings)
                    info(Modifier.weight(1f).fillMaxWidth())
                    Controls(true, state, saveLabel, manualSaving, onToggle, viewModel::saveLastFiveMinutes)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusHeader(state, now, onOpenSettings)
                PreviewPane(previewView, showPreviewHint, Modifier.weight(1f).fillMaxWidth())
                info(Modifier.fillMaxWidth().heightIn(max = 200.dp))
                Controls(false, state, saveLabel, manualSaving, onToggle, viewModel::saveLastFiveMinutes)
            }
        }
    }
}

/**
 * Ties the preview to screen visibility. Detaching on stop means no preview surface is fed
 * while the screen is off, and avoids handing CameraX a destroyed surface.
 */
@Composable
private fun PreviewAttachment(previewView: PreviewView, viewModel: DashcamViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, previewView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setPreviewSurfaceProvider(previewView.surfaceProvider)
                Lifecycle.Event.ON_STOP -> viewModel.setPreviewSurfaceProvider(null)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setPreviewSurfaceProvider(null)
        }
    }
}

/** Refreshes the storage numbers only while the screen is visible. */
@Composable
private fun UsagePolling(viewModel: DashcamViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshUsage()
                delay(USAGE_REFRESH_MS)
            }
        }
    }
}

/**
 * A once-a-second clock that only runs while the screen is visible. It is returned as State so
 * that only the small composables that read it (timers, GPS age) recompose on each tick.
 */
@Composable
private fun rememberNowMs(): State<Long> {
    val now = remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.value = SystemClock.elapsedRealtime()
                delay(TICK_MS)
            }
        }
    }
    return now
}

@Composable
private fun PreviewPane(previewView: PreviewView, showHint: Boolean, modifier: Modifier) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (showHint) {
            Text(
                "Preview appears while recording",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun StatusHeader(state: RecordingState, now: State<Long>, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (state) {
                is RecordingState.Recording -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(14.dp).background(Color.Red, CircleShape))
                        Text("REC", style = MaterialTheme.typography.titleLarge)
                        Elapsed(state.startedAtElapsedMs, now, MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.quality} · Segment ${state.segmentNumber} ·", style = MaterialTheme.typography.bodyMedium)
                        Elapsed(state.segmentStartedAtElapsedMs, now, MaterialTheme.typography.bodyMedium)
                    }
                }
                is RecordingState.Recovering -> {
                    Text(
                        "Camera problem: restarting",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Attempt ${state.attempt} of ${state.maxAttempts}. ${state.reason}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is RecordingState.Starting -> Text("Starting camera…", style = MaterialTheme.typography.titleLarge)
                is RecordingState.Stopping -> Text("Saving…", style = MaterialTheme.typography.titleLarge)
                is RecordingState.Error -> Text(
                    "Not recording: error",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                is RecordingState.Idle -> Text("Not recording", style = MaterialTheme.typography.titleLarge)
            }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Settings") }
    }
}

@Composable
private fun Elapsed(startedAtElapsedMs: Long, now: State<Long>, style: TextStyle) {
    Text(formatDuration((now.value - startedAtElapsedMs).coerceAtLeast(0L)), style = style)
}

/** The two primary actions. Large, and always on screen: portrait stacks them, landscape pairs them. */
@Composable
private fun Controls(
    landscape: Boolean,
    state: RecordingState,
    saveLabel: String,
    saving: Boolean,
    onToggle: () -> Unit,
    onSave: () -> Unit,
) {
    val running = state is RecordingState.Starting ||
        state is RecordingState.Recording ||
        state is RecordingState.Recovering
    val toggleLabel = when (state) {
        is RecordingState.Idle, is RecordingState.Error -> "Start recording"
        is RecordingState.Starting -> "Cancel start"
        is RecordingState.Recording, is RecordingState.Recovering -> "Stop recording"
        is RecordingState.Stopping -> "Saving…"
    }

    val toggle: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            onClick = onToggle,
            enabled = state !is RecordingState.Stopping,
            colors = if (running) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = modifier.heightIn(min = 64.dp),
        ) { Text(toggleLabel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
    }
    val save: @Composable (Modifier) -> Unit = { modifier ->
        FilledTonalButton(
            onClick = onSave,
            enabled = !saving,
            modifier = modifier.heightIn(min = 64.dp),
        ) {
            Text(
                if (saving) "Saving…" else saveLabel,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (landscape) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            toggle(Modifier.weight(1f))
            save(Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            toggle(Modifier.fillMaxWidth())
            save(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StorageInfo(usage: StorageUsage?, limitBytes: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (usage == null) {
            Text("Storage: …", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            "Storage: ${formatStorage(usage.dashcamBytes)} of ${formatStorage(limitBytes)} used · " +
                "${formatStorage(usage.freeBytes)} free",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (limitBytes > 0) {
            LinearProgressIndicator(
                progress = { (usage.dashcamBytes.toFloat() / limitBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (usage.freeBytes < LOW_FREE_BYTES) {
            Text(
                "Phone storage is low. Old footage is deleted first; Protected and Event footage is never deleted automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GpsInfo(enabledInSettings: Boolean, status: GpsStatus, sessionActive: Boolean, now: State<Long>) {
    val text = when {
        status.state == GpsState.OFF && !enabledInSettings -> "GPS: off"
        status.state == GpsState.OFF ->
            if (sessionActive) "GPS: starts with the next recording" else "GPS: on, active while recording"
        status.state == GpsState.NO_PERMISSION -> "GPS: location permission needed"
        status.state == GpsState.UNAVAILABLE -> "GPS: location is turned off or unavailable"
        status.state == GpsState.SEARCHING -> "GPS: searching for signal…"
        else -> {
            val fresh = now.value - status.lastFixElapsedMs <= GPS_STALE_MS
            when {
                !fresh -> "GPS: signal lost"
                status.speedMps != null -> "GPS: fix · ${(status.speedMps * 3.6f).roundToInt()} km/h"
                else -> "GPS: fix"
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LastRecording(result: RecordingResult) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Last saved segment", style = MaterialTheme.typography.titleSmall)
        Text(result.location, style = MaterialTheme.typography.bodySmall)
        Text(
            "${formatDuration(result.durationMs)} · ${"%.1f".format(Locale.US, result.sizeBytes / 1_048_576.0)} MB",
            style = MaterialTheme.typography.bodySmall,
        )
        val details = if (result.videoMime == null) {
            "Could not read the file back"
        } else {
            val fps = result.effectiveFps?.let { " · ${"%.1f".format(Locale.US, it)} fps" } ?: ""
            "${result.videoMime} · ${result.width}x${result.height}$fps"
        }
        Text(details, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManualSaveText(save: ManualSaveStatus, windowMinutes: Int) {
    val time = formatClock(save.timeMs)
    val result = save.result
    val failed = save.failed || result == null
    val message = if (result == null || save.failed) {
        "$time · Save failed. Footage was NOT protected."
    } else if (result.protectedSegments == 0 && result.pendingSegments == 0) {
        "$time · No footage found in the last $windowMinutes min to protect."
    } else {
        val pending = if (result.pendingSegments > 0) " The current segment is protected when it finishes." else ""
        "$time · Protected ${result.protectedSegments} segment(s) in ${result.state.displayFolder}.$pending"
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

@Composable
private fun EventCard(status: EventStatus, onDismiss: () -> Unit) {
    val e = status.event
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Possible impact detected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
        Text(
            "${formatClock(e.timestampMs)} · peak ${"%.1f".format(Locale.US, e.peakAccelMps2)} m/s² (${"%.1f".format(Locale.US, e.peakAccelMps2 / 9.81f)} g)" +
                " · rotation ${"%.1f".format(Locale.US, e.peakRotationRadPerSec)} rad/s",
            style = MaterialTheme.typography.bodySmall,
        )
        e.speedMps?.let {
            Text("Speed ${"%.0f".format(Locale.US, it * 3.6f)} km/h (GPS)", style = MaterialTheme.typography.bodySmall)
        }
        if (status.extraImpacts > 0) {
            Text("+${status.extraImpacts} more impact(s) in the same event", style = MaterialTheme.typography.bodySmall)
        }
        val protection = status.protection
        val footage = when {
            status.protectionFailed -> "Could not protect the footage"
            protection == null -> "Protecting footage…"
            else -> {
                val pending = if (protection.pendingSegments > 0) "; ${protection.pendingSegments} more protected as they finish" else ""
                "Footage protected: ${protection.protectedSegments} segment(s) in ${protection.state.displayFolder}$pending"
            }
        }
        Text(footage, style = MaterialTheme.typography.bodySmall)
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    // Without it the recording notification is hidden; recording itself still works.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(Locale.US, s / 3600, s % 3600 / 60, s % 60)
}

private fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

/** "1.2 GB", "4 GB", "350 MB". */
private fun formatStorage(bytes: Long): String = when {
    bytes >= GB && bytes % GB == 0L -> "${bytes / GB} GB"
    bytes >= GB -> "%.1f GB".format(Locale.US, bytes / GB.toDouble())
    else -> "%d MB".format(Locale.US, bytes / MB)
}
