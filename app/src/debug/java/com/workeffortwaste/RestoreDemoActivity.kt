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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class RestoreDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var showPrompt by remember { mutableStateOf(true) }
            var result by remember { mutableStateOf("") }
            MaterialExpressiveTheme(colorScheme = fm1BlackColorScheme) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text("Restore prompt preview", style = MaterialTheme.typography.headlineSmall)
                        Text("The real restore dialog with sample backup details. No FM-1 data is read or written.")
                        if (result.isNotEmpty()) Text(result)
                        Button(
                            onClick = { showPrompt = true },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Preview saved backup") }
                        TextButton(onClick = ::finish) { Text("Exit preview") }
                    }
                    if (showPrompt) {
                        RestoreConfirmationDialog(
                            backupName = "FM-1 factory firmware (demo)",
                            backupDate = "27 Oct 2026, 16:00",
                            sha256 = "bc5d2317a8e09f624d1c73ab906fe812c49a0bd6753e18f29a647cd0285b93ef",
                            restoreEnabled = true,
                            onConfirm = {
                                showPrompt = false
                                result = "Restore confirmed in preview only. Nothing was written to an FM-1."
                            },
                            onDismiss = {
                                showPrompt = false
                                result = "Restore cancelled. Nothing was changed."
                            },
                        )
                    }
                }
            }
        }
    }
}
