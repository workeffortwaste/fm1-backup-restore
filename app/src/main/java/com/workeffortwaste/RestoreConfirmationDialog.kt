package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    targetFamily: DeviceFamily = DeviceFamily.FM1,
    manuallySelected: Boolean = false,
) {
    var sameUnitConfirmed by remember(backupName, targetFamily) { mutableStateOf(false) }
    val smkTarget = targetFamily.isSmk37Family
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore ${targetFamily.displayName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Are you sure you want to restore this backup to your ${targetFamily.displayName}?")
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        backupName.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall)
                        }
                        backupDate?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        sha256?.let { hash ->
                            SelectionContainer {
                                Text(hash, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (manuallySelected) {
                    Text("The device model was manually selected and is not verified.")
                }
                if (!smkTarget) {
                    Text(
                        "Only restore backups to the same physical device they were created on. " +
                                "This app cannot verify physical-unit identity."
                    )
                }
                Text("The first 16 KB bootloader area is not overwritten.")
                Text(
                    "Restoring always carries a risk. There is no automatic rollback, and a restore " +
                        "cannot be cancelled once started. Keep USB and power connected until it finishes.",
                )
                if (smkTarget) {
                    Text(
                        "Only restore a ${targetFamily.displayName} backup to the exact physical keyboard it was created with, due to device specific calibration data.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            sameUnitConfirmed = !sameUnitConfirmed
                        },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = sameUnitConfirmed,
                            onCheckedChange = { sameUnitConfirmed = it },
                        )
                        Text("I confirm this backup was created with this exact physical ${targetFamily.displayName}.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = restoreEnabled && (!smkTarget || sameUnitConfirmed),
            ) { Text("Yes, restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("No, go back") }
        },
    )
}
