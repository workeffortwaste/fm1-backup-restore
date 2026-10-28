package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Presents backup identity and irreversible restore risks before allowing the caller to proceed. */
@Composable
internal fun RestoreConfirmationDialog(
    backupName: String,
    backupDate: String?,
    sha256: String?,
    restoreEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore FM-1") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Are you sure you want to restore this backup to your FM-1?")
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(backupName, style = MaterialTheme.typography.titleSmall)
                        backupDate?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        sha256?.let { hash ->
                            SelectionContainer {
                                Text(hash, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(
                    "This replaces the current firmware, settings, projects and patches. " +
                        "The protected bootloader is never changed.",
                )
                Text(
                    "Restoring always carries a risk. There is no automatic rollback, and a restore " +
                        "cannot be cancelled once started. Keep USB and power connected until it finishes.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = restoreEnabled) { Text("Yes, restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("No, go back") }
        },
    )
}
