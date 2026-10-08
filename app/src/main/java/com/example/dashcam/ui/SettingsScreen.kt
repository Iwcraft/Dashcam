package com.example.dashcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dashcam.recording.RecordingState
import com.example.dashcam.settings.ImpactSensitivity
import com.example.dashcam.settings.RecordingSettings
import com.example.dashcam.settings.VideoQuality
import com.example.dashcam.settings.VideoResolution
import com.example.dashcam.utils.hasPermission
import java.util.Locale
import kotlinx.coroutines.delay

private const val GB = 1024L * 1024L * 1024L
private const val MB = 1024L * 1024L
private const val USAGE_REFRESH_MS = 5_000L

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    var micDenied by remember { mutableStateOf(false) }

    // Cached index + one StatFs per tick; cancelled automatically when the screen closes.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshUsage()
            delay(USAGE_REFRESH_MS)
        }
    }

    // The microphone permission is only ever requested here, when the user turns audio on.
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) viewModel.update { it.copy(audioEnabled = true) }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader("Recording")
            (state as? RecordingState.Recording)?.let {
                InfoText("Recording now at ${it.quality}. Resolution, frame rate, quality and audio apply from the next recording; segment length from the next segment.")
            }
            DropdownRow(
                title = "Resolution",
                subtitle = "Falls back to the next supported resolution if unavailable",
                options = RecordingSettings.RESOLUTION_OPTIONS,
                selected = settings.resolution,
                label = VideoResolution::label,
                onSelect = { v -> viewModel.update { it.copy(resolution = v) } },
            )
            DropdownRow(
                title = "Frame rate",
                subtitle = "A target; the camera uses the closest rate it supports",
                options = RecordingSettings.FRAME_RATE_OPTIONS,
                selected = settings.frameRate,
                label = { "$it FPS" },
                onSelect = { v -> viewModel.update { it.copy(frameRate = v) } },
            )
            DropdownRow(
                title = "Video quality",
                subtitle = "High uses more storage and a higher bitrate",
                options = VideoQuality.entries,
                selected = settings.videoQuality,
                label = VideoQuality::label,
                onSelect = { v -> viewModel.update { it.copy(videoQuality = v) } },
            )
            DropdownRow(
                title = "Segment duration",
                options = RecordingSettings.SEGMENT_DURATION_OPTIONS_MS,
                selected = settings.segmentDurationMs,
                label = ::formatMinutes,
                onSelect = { v -> viewModel.update { it.copy(segmentDurationMs = v) } },
            )
            val micGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
            SwitchRow(
                title = "Audio recording",
                subtitle = when {
                    micDenied -> "Microphone permission was denied, so video stays silent"
                    settings.audioEnabled && !micGranted -> "Microphone permission is off in Android settings, so video stays silent"
                    else -> "Records microphone audio with the video"
                },
                checked = settings.audioEnabled,
                onCheckedChange = { on ->
                    when {
                        !on -> {
                            micDenied = false
                            viewModel.update { it.copy(audioEnabled = false) }
                        }
                        micGranted -> {
                            micDenied = false
                            viewModel.update { it.copy(audioEnabled = true) }
                        }
                        else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
            DropdownRow(
                title = "Screen while recording",
                subtitle = "Recording continues either way; this only affects the display while the app is open",
                options = listOf(true, false),
                selected = settings.keepScreenOn,
                label = { if (it) "Keep screen on" else "Allow screen off" },
                onSelect = { v -> viewModel.update { it.copy(keepScreenOn = v) } },
            )

            SectionHeader("Storage")
            DropdownRow(
                title = "Maximum storage",
                subtitle = "Oldest normal footage is deleted first. Protected and Event footage is kept",
                options = RecordingSettings.STORAGE_LIMIT_OPTIONS_BYTES,
                selected = settings.storageLimitBytes,
                label = ::formatBytes,
                onSelect = { v -> viewModel.update { it.copy(storageLimitBytes = v) } },
            )
            InfoRow(
                title = "Dashcam storage",
                value = usage?.let { "${formatBytes(it.dashcamBytes)} / ${formatBytes(settings.storageLimitBytes)}" } ?: "…",
            )
            InfoRow(
                title = "Free phone storage",
                value = usage?.let { formatBytes(it.freeBytes) } ?: "…",
            )

            SectionHeader("Sensors")
            SwitchRow(
                title = "GPS",
                subtitle = "Saved for a later update. Nothing is recorded and no permission is requested yet",
                checked = settings.gpsEnabled,
                onCheckedChange = { on -> viewModel.update { it.copy(gpsEnabled = on) } },
            )
            DropdownRow(
                title = "Impact sensitivity",
                subtitle = "Saved for a later update. No sensors are active yet",
                options = ImpactSensitivity.entries,
                selected = settings.impactSensitivity,
                label = ImpactSensitivity::label,
                onSelect = { v -> viewModel.update { it.copy(impactSensitivity = v) } },
            )

            SectionHeader("Automation")
            SwitchRow(
                title = "Auto-start recording",
                subtitle = "Saved for a later update. Recording does not start by itself yet",
                checked = settings.autoStartRecording,
                onCheckedChange = { on -> viewModel.update { it.copy(autoStartRecording = on) } },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(title, subtitle, Modifier.weight(1f))
            Text(label(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier = modifier.padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatMinutes(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}

/** "1.2 GB", "4 GB", "350 MB". */
private fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> {
        val gb = bytes / GB.toDouble()
        if (bytes % GB == 0L) "${bytes / GB} GB" else "%.1f GB".format(Locale.US, gb)
    }
    else -> "%d MB".format(Locale.US, bytes / MB)
}
