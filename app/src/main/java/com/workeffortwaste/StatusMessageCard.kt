package com.workeffortwaste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
internal fun StatusMessageCard(
    message: StatusMessage,
    iconResource: Int,
    isError: Boolean = false,
    progressDescription: String? = null,
) {
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    // Fall back to the card's text colour when the accent would disappear into the card.
    val primary = MaterialTheme.colorScheme.primary
    val indicatorColor = if (contrastRatio(primary, containerColor) >= 3f) primary else contentColor
    val trackColor = if (primary == containerColor) contentColor.copy(alpha = 0.24f)
    else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(iconResource),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        message.heading,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    message.secondary.takeIf(String::isNotEmpty)?.let { detail ->
                        Text(detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                progressDescription?.let { description ->
                    LinearWavyProgressIndicator(
                        color = indicatorColor,
                        trackColor = trackColor,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics {
                            contentDescription = description
                        },
                    )
                }
            }
        }
    }
}
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
