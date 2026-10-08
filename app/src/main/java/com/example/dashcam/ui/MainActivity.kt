package com.example.dashcam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.dashcam.DashcamApp
import com.example.dashcam.recording.RecordingState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DashcamApp).container
        setContent {
            // Dark by default: a bright screen in a car at night is a distraction.
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }

                    // UI-only: a window flag while the app is visible. The recording engine and
                    // service never see this setting, so screen-off recording is unaffected.
                    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
                    val state by container.recordingEngine.state.collectAsStateWithLifecycle()
                    val keepOn = settings.keepScreenOn &&
                        (state is RecordingState.Recording || state is RecordingState.Recovering)
                    val view = LocalView.current
                    DisposableEffect(keepOn) {
                        view.keepScreenOn = keepOn
                        onDispose { view.keepScreenOn = false }
                    }

                    if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        DashcamScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
