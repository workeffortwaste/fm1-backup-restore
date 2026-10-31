package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.util.Locale

@Composable
internal fun UpdateSettings(updater: AppUpdateModel, deviceIdle: Boolean) {
    val state = updater.state
    InfoBox(iconResource = R.drawable.ic_refresh) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val unavailableReason = updater.unavailableReason
            if (unavailableReason != null) {
                InfoBoxDetail(unavailableReason)
            } else {
                UpdateControls(updater, state, deviceIdle)
            }
        }
    }
}

@Composable
private fun UpdateControls(updater: AppUpdateModel, state: AppUpdateState, deviceIdle: Boolean) {
    InfoBoxDetail(
        "FM-1 Backup & Restore automatically checks for updates on launch once a day. Check manually below.",
    )
    if (state.message.isNotEmpty()) InfoBoxDetail(state.message)
    if (state.release != null) {
        TextButton(
            onClick = updater::show,
            enabled = deviceIdle && !state.checking && !updater.installerOpen,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("View update ${state.release.version}") }
    }
    if (!deviceIdle) InfoBoxDetail("Updates can be installed after the FM-1 operation finishes.")
    Button(
        onClick = { updater.check(manual = true) },
        enabled = deviceIdle && !state.checking && !state.downloading && !updater.installerOpen,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            when {
                state.checking -> "Checking for updates..."
                state.downloading -> "Downloading update: ${state.progress}%"
                else -> "Check for updates"
            },
        )
    }
}

/** Keeps prompting and installation unavailable during device work; download and install stay separate. */
@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    deviceIdle: Boolean,
    installerOpen: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    if (!state.showDialog || !deviceIdle) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.release == null) "App updates" else "Update ${state.release.version}")
        },
        text = {
            Column(
                modifier = Modifier.sizeIn(maxHeight = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.checking) {
                    Text("Checking GitHub for a new version...")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.release?.let { release ->
                    Text(
                        "Download: ${String.format(Locale.ROOT, "%.1f", release.size / (1024.0 * 1024))} MiB",
                    )
                    Markdown(
                        content = release.notes.ifBlank { "No release notes provided." },
                        modifier = Modifier.fillMaxWidth(),
                        colors = markdownColor(
                            text = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleMedium,
                            h2 = MaterialTheme.typography.titleSmall,
                            h3 = MaterialTheme.typography.titleSmall,
                            h4 = MaterialTheme.typography.titleSmall,
                            h5 = MaterialTheme.typography.titleSmall,
                            h6 = MaterialTheme.typography.titleSmall,
                            paragraph = MaterialTheme.typography.bodyMedium,
                            textLink = TextLinkStyles(
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Normal,
                                    textDecoration = TextDecoration.Underline,
                                ).toSpanStyle(),
                            ),
                        ),
                    )
                }
                if (state.downloading) {
                    Text("Downloading: ${state.progress}%")
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.apk != null) {
                    Text("Download verified. Android will ask you to approve installation. Your backups stay in place.")
                }
                if (state.message.isNotEmpty()) Text(state.message)
            }
        },
        confirmButton = {
            if (state.release != null && !state.checking) {
                TextButton(
                    onClick = { if (state.apk != null) onInstall() else onDownload() },
                    enabled = !state.downloading && !installerOpen,
                ) { Text(if (state.apk != null) "Install update" else "Download update") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.checking && !state.downloading && !installerOpen,
            ) { Text(if (state.release == null) "Close" else "Later") }
        },
    )
}
