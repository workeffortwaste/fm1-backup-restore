package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workeffortwaste.protocol.FlashRestore

@Composable
internal fun BootloaderVerificationDialog(
    state: BootloaderVerificationState,
    onDismiss: () -> Unit,
) {
    if (!state.showDialog) return
    AlertDialog(
        onDismissRequest = { if (!state.working) onDismiss() },
        title = {
            Text(
                when {
                    state.sha256.isEmpty() -> "Bootloader verification"
                    state.matchesStockV15 == true || (state.identity != null && state.matchesPublishedRelease) -> "Verified"
                    else -> "Not verified"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Reads the protected 16 KiB bootloader region. No flash data is changed.")
                if (state.working) {
                    Text(
                        "Reading bootloader: ${state.bytesRead * 100 / FlashRestore.PROTECTED_SIZE}%",
                    )
                    LinearProgressIndicator(
                        progress = { state.bytesRead.toFloat() / FlashRestore.PROTECTED_SIZE },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.sha256.isNotEmpty()) {
                    Text("Protected region SHA-256", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(state.sha256) }
                }
                if (state.message.isNotEmpty()) Text(state.message)
            }
        },
        confirmButton = {
            if (!state.working) {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onDismiss,
                ) { Text("Close") }
            }
        },
    )
}