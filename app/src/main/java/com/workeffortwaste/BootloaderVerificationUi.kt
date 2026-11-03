package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workeffortwaste.protocol.FlashRestore

@Composable
internal fun BootloaderVerificationDialog(
    state: BootloaderVerificationState,
    onDismiss: () -> Unit,
    diagnostics: String? = null,
    verifyEnabled: Boolean = false,
    onVerify: () -> Unit = {},
    onCopyDiagnostics: (String) -> Unit = {},
    recoveryConnected: Boolean = false,
    onEmergencyRecovery: () -> Unit = {},
    onProceedRecovery: () -> Unit = {},
    debugRecoveryOverride: Boolean = BuildConfig.DEBUG,
) {
    if (diagnostics == null && !state.showDialog) return
    val diagnosticsText = diagnostics.takeUnless { state.showDialog }
    val showingDiagnostics = diagnosticsText != null
    val showRecoveryAction = state.recovery == null && state.sha256.isNotEmpty()
    val showProceedAction = state.recovery?.stage == EmergencyRecoveryStage.CONFIRM
    val recoveryEnabled = state.canRecover(debugRecoveryOverride) && recoveryConnected
    AlertDialog(
        onDismissRequest = { if (showingDiagnostics || !state.working) onDismiss() },
        title = {
            Text(
                when {
                    showingDiagnostics -> "Diagnostics"
                    state.recovery != null -> "Emergency Recover"
                    state.sha256.isEmpty() -> "Bootloader verification"
                    state.hasKnownHash -> "Verified"
                    else -> "Not verified"
                },
            )
        },
        text = {
            if (diagnosticsText != null) {
                SelectionContainer {
                    Text(diagnosticsText, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            } else if (state.recovery != null) {
                EmergencyRecoveryContent(state.recovery, recoveryConnected)
            } else {
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
            }
        },
        confirmButton = {
            if (diagnosticsText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = onVerify,
                        enabled = verifyEnabled,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("Verify bootloader") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onCopyDiagnostics(diagnosticsText) },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("Copy") }
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("Close") }
                    }
                }
            } else if (!state.working) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (showRecoveryAction || showProceedAction) {
                        Arrangement.SpaceBetween
                    } else Arrangement.End,
                ) {
                    if (showRecoveryAction) {
                        TextButton(
                            onClick = onEmergencyRecovery,
                            enabled = recoveryEnabled,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("Emergency restore") }
                    }
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = onDismiss,
                    ) { Text("Close") }
                    if (showProceedAction) {
                        TextButton(
                            onClick = onProceedRecovery,
                            enabled = recoveryEnabled,
                        ) { Text("Proceed at my own risk") }
                    }
                }
            }
        },
    )
}

@Composable
private fun EmergencyRecoveryContent(state: EmergencyRecoveryState, connected: Boolean) {
    Column(
        modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.stage) {
            EmergencyRecoveryStage.DOWNLOADING, EmergencyRecoveryStage.FLASHING -> {
                Text(state.phase)
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                if (state.stage == EmergencyRecoveryStage.FLASHING) {
                    Text("Keep USB and power connected. Do not restart the FM-1. Recovery cannot be cancelled.")
                }
            }
            EmergencyRecoveryStage.CONFIRM -> {
                Text(
                    "Use this only as a last resort. Emergency recovery replaces the first 16 KiB of flash with the stock v15 bootloader and chip configuration from M-VAVE's official repository. Firmware, projects and settings outside this boot area are not changed.",
                )
                Text(
                    "An unrecognised SHA-256 does not prove that the bootloader is damaged, and this may not fix your device.",
                )
                Text(
                    "If your bootloader is damaged but you have made it this far, you have still been able to enter recovery mode via SysEx. If the bootloader is damaged further or recovery is interrupted, you may not be able to boot your device or recover your FM-1 without additional hardware.",
                )
                if (!connected) {
                    Text("The FM-1 is no longer connected in recovery mode. Close this dialog, reconnect and verify the bootloader again.")
                }
            }
            EmergencyRecoveryStage.SUCCESS, EmergencyRecoveryStage.FAILURE -> Text(state.message)
        }
    }
}