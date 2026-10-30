package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun V15RecoveryDialog(
    state: V15RecoveryState,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    if (!state.showDialog) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create v15 recovery snapshot") },
        text = {
            Column(
                modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Didn't manage to take a backup of your device before you started flashing custom firmware?")
                Text(
                    "Downloads and verifies the official v15 firmware update directly from M-Vave, " +
                            "reconstructs a stock v15 snapshot, and adds FM-1_015 RECOVERY to your library.",
                )
                if (state.working) {
                    Text(state.phase, style = MaterialTheme.typography.titleSmall)
                    state.progress?.let { progress ->
                        Text("Downloading: $progress%")
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.message.isNotEmpty()) Text(state.message)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.working,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = if (state.completed) onDismiss else onCreate,
            ) {
                Text(
                    when {
                        state.working -> "Working…"
                        state.completed -> "Done"
                        state.message.isNotEmpty() -> "Try again"
                        else -> "Download and build"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.working,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = onDismiss,
            ) {
                Text(if (state.completed) "Close" else "Cancel")
            }
        },
    )
}
