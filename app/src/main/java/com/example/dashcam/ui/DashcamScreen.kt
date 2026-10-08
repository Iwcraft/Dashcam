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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dashcam.events.EventStatus
import com.example.dashcam.events.ManualSaveStatus
import com.example.dashcam.recording.RecordingResult
import com.example.dashcam.recording.RecordingState
import com.example.dashcam.storage.VideoState
import com.example.dashcam.utils.hasPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionDenied = grants[Manifest.permission.CAMERA] != true
        if (!permissionDenied) viewModel.startRecording()
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    PreviewAttachment(previewView, viewModel)

    val elapsedMs = rememberElapsedMs(state)
    val isRecording = state is RecordingState.Recording
    val canStart = state is RecordingState.Idle || state is RecordingState.Error
    val canStop = isRecording || state is RecordingState.Starting

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusRow(state, elapsedMs)
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        if (isRecording) {
            Text(
                "Saving to ${VideoState.NORMAL.displayFolder}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (!isRecording) {
                Text(
                    "Preview appears while recording",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (context.hasPermission(Manifest.permission.CAMERA)) {
                        viewModel.startRecording()
                    } else {
                        permissionLauncher.launch(requiredPermissions())
                    }
                },
                enabled = canStart,
                modifier = Modifier.weight(1f),
            ) { Text("Start recording") }

            Button(
                onClick = viewModel::stopRecording,
                enabled = canStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }

        Button(onClick = viewModel::saveLastFiveMinutes, modifier = Modifier.fillMaxWidth()) {
            Text("Save Last 5 Minutes")
        }
        lastManualSave?.let { ManualSaveText(it) }

        lastEvent?.let { EventCard(it, onDismiss = viewModel::dismissEvent) }

        if (permissionDenied) {
            Text("Camera permission was denied. Recording needs it.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Open app settings") }
        }

        (state as? RecordingState.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }

        lastRecording?.let { LastRecording(it) }
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

@Composable
private fun StatusRow(state: RecordingState, elapsedMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is RecordingState.Recording -> {
                Box(Modifier.size(14.dp).background(Color.Red, CircleShape))
                Text("REC  ${formatDuration(elapsedMs)}", style = MaterialTheme.typography.titleLarge)
                Text(state.quality, style = MaterialTheme.typography.bodyMedium)
                Text("· segment ${state.segmentNumber}", style = MaterialTheme.typography.bodyMedium)
            }
            is RecordingState.Starting -> Text("Starting camera…", style = MaterialTheme.typography.titleLarge)
            is RecordingState.Stopping -> Text("Saving…", style = MaterialTheme.typography.titleLarge)
            is RecordingState.Error -> Text("Error", style = MaterialTheme.typography.titleLarge)
            is RecordingState.Idle -> Text("Ready", style = MaterialTheme.typography.titleLarge)
        }
    }
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
private fun ManualSaveText(save: ManualSaveStatus) {
    val text = when {
        save.failed -> "Could not protect the last 5 minutes"
        else -> save.result?.let { r ->
            val pending = if (r.pendingSegments > 0) " (current segment is protected when it finishes)" else ""
            "Saved ${r.protectedSegments} segment(s) to ${r.state.displayFolder}$pending"
        } ?: ""
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EventCard(status: EventStatus, onDismiss: () -> Unit) {
    val e = status.event
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Possible impact detected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(e.timestampMs))
        Text(
            "$time · peak ${"%.1f".format(Locale.US, e.peakAccelMps2)} m/s² (${"%.1f".format(Locale.US, e.peakAccelMps2 / 9.81f)} g)" +
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

/** Ticks only while recording, twice a second. */
@Composable
private fun rememberElapsedMs(state: RecordingState): Long {
    val startedAt = (state as? RecordingState.Recording)?.startedAtElapsedMs
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return if (startedAt == null) 0L else (now - startedAt).coerceAtLeast(0L)
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
