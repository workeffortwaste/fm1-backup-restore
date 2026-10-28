package com.workeffortwaste

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay

class UpdateDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val release = remember {
                AppRelease(
                    version = "v0.2",
                    notes = "Preview release - demonstration only.\n\n" +
                        "A new version of FM-1 Backup & Restore is available.\n\n" +
                        "This demo uses simulated release information and download progress. " +
                        "No APK is downloaded or installed.",
                    apkUrl = "",
                    size = 8 * 1024 * 1024L,
                    sha256 = "",
                )
            }
            var state by remember {
                mutableStateOf(AppUpdateState(release = release, showDialog = true))
            }
            var showHandoff by remember { mutableStateOf(false) }
            LaunchedEffect(state.downloading) {
                if (state.downloading) {
                    for (progress in 0..100 step 2) {
                        delay(120)
                        state = state.copy(progress = progress)
                    }
                    state = state.copy(
                        downloading = false,
                        apk = File(cacheDir, "demo-only-not-a-real-apk"),
                    )
                }
            }
            MaterialExpressiveTheme(colorScheme = fm1BlackColorScheme) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text("FM-1 Backup & Restore", style = MaterialTheme.typography.headlineSmall)
                        Text("Update preview", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Debug-only interactive demo. The popup is the real app component; " +
                                "release information, download and installation are simulated.",
                        )
                        Text(
                            "1. Review the release and tap Download update.\n" +
                                "2. Watch download progress.\n" +
                                "3. Tap Install update when verification finishes.\n" +
                                "4. In normal use, Android handles permission and installation.",
                        )
                        Button(
                            onClick = { state = state.copy(showDialog = true) },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Show update popup") }
                        Button(
                            onClick = {
                                showHandoff = false
                                state = AppUpdateState(release = release, showDialog = true)
                            },
                            enabled = !state.downloading,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Restart demo") }
                        TextButton(onClick = ::finish) { Text("Exit preview") }
                    }
                    AppUpdateDialog(
                        state = state,
                        deviceIdle = true,
                        installerOpen = false,
                        onDismiss = {
                            if (!state.downloading) state = state.copy(showDialog = false)
                        },
                        onDownload = { state = state.copy(downloading = true, progress = 0) },
                        onInstall = {
                            state = state.copy(showDialog = false)
                            showHandoff = true
                        },
                    )
                    if (showHandoff) {
                        AlertDialog(
                            onDismissRequest = { showHandoff = false },
                            title = { Text("Android installer hand-off (demo)") },
                            text = {
                                Text(
                                    "In the real flow:\n\n" +
                                        "If needed, Android opens 'Allow from this source'. " +
                                        "Enable it, return to the app and tap Install update again.\n\n" +
                                        "Android then shows its installation confirmation. " +
                                        "Approve the update, then reopen the app. " +
                                        "Your saved backups remain in place.\n\n" +
                                        "This preview stops here. No APK has been installed.",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showHandoff = false }) { Text("Done") }
                            },
                        )
                    }
                }
            }
        }
    }
}
