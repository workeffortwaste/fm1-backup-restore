package com.workeffortwaste

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun TransferProgressScreen(
    operation: FlashOperation,
    status: StatusMessage,
    progress: Float?,
    titleColor: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "Transfer phase progress",
    )
    val colors = MaterialTheme.colorScheme
    val trackColor = if (colors.primary == colors.secondaryContainer) {
        colors.primary.copy(alpha = 0.24f)
    } else {
        WavyProgressIndicatorDefaults.trackColor
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            // Same logo size and logo-to-title gap as the main screen header.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_wave),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    operation.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val indicatorModifier = Modifier.size(80.dp).semantics {
                contentDescription = "${operation.label} progress"
            }
            if (progress == null) {
                CircularWavyProgressIndicator(
                    modifier = indicatorModifier,
                    trackColor = trackColor,
                )
            } else {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = indicatorModifier,
                    trackColor = trackColor,
                )
                Text(
                    "${transferProgressPercent(animatedProgress, progress)}%",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                status.secondary.trimEnd('.'),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            StatusMessageCard(
                message = statusMessage(
                    "Keep USB and power connected",
                    if (operation == FlashOperation.RESTORE) {
                        "Disconnecting your device during a restore can corrupt the flash"
                    } else {
                        "Disconnecting your device during a backup will stop it before it finishes"
                    },
                ),
                iconResource = R.drawable.ic_warning,
            )
        }
    }
}

internal fun transferProgressPercent(displayed: Float, target: Float): Int =
    (displayed.coerceIn(0f, 1f) * 100).toInt().coerceAtMost(if (target >= 1f) 100 else 99)
