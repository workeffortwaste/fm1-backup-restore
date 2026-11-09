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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FirmwareExportDialog(
    state: FirmwareExportState,
    exportEnabled: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    if (!state.showDialog) return
    AlertDialog(
        onDismissRequest = { if (!state.working && (state.completed || exportEnabled)) onDismiss() },
        title = { Text(if (state.singleBackup) "Export backup" else "Export all backups") },
        text = {
            Column(
                modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.working) {
                    Text(
                        when {
                            state.cancelling -> "Cancelling export"
                            state.progress == 100 -> "Finalizing ZIP"
                            else -> "Exporting backups: ${state.progress}%"
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (state.completed) {
                    Text(state.message)
                } else {
                    val count = state.entries.size
                    Text("$count unique ${if (count == 1) "backup" else "backups"} as a ZIP archive.")
                }
            }
        },
        confirmButton = {
            if (!state.working) {
                TextButton(
                    enabled = state.completed || exportEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = if (state.completed) onDismiss else onExport,
                ) { Text(if (state.completed) "Close" else "Export") }
            }
        },
        dismissButton = {
            if (!state.completed) {
                TextButton(
                    enabled = !state.cancelling && (state.working || exportEnabled),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = if (state.working) onCancel else onDismiss,
                ) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun FirmwareImportDialog(
    state: FirmwareImportState,
    selected: SelectedImport?,
    name: String,
    onNameChange: (String) -> Unit,
    pickerOpen: Boolean,
    onChoose: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!state.showDialog) return
    val valid = name.isNotBlank() && name.length <= BackupStore.MAX_NAME_LENGTH &&
        name.all { it.code in 32..126 }
    AlertDialog(
        onDismissRequest = { if (!state.working && !pickerOpen) onCancel() },
        title = { Text("Import backups") },
        text = {
            Column(
                modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.message.isNotEmpty()) {
                    Text(
                        text = if (state.cancelling) "Cancelling import" else state.message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (state.working) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (!state.completed && selected != null) {
                    Text(selected.sourceName)
                    Text("SHA-256: ${selected.sha256}")
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("File name") },
                        singleLine = true,
                        isError = !valid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (!state.completed) {
                    Text("No file selected")
                }
            }
        },
        confirmButton = {
            if (!state.working) {
                TextButton(
                    enabled = !pickerOpen && (state.completed || selected == null || valid),
                    onClick = when {
                        state.completed -> onCancel
                        selected != null -> onSave
                        else -> onChoose
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            state.completed -> "Close"
                            selected != null -> "Save"
                            else -> "Choose file"
                        },
                    )
                }
            }
        },
        dismissButton = {
            if (!state.completed) {
                TextButton(
                    onClick = onCancel,
                    enabled = !state.cancelling && !pickerOpen,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            }
        },
    )
}