package com.workeffortwaste

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun InfoBox(
    @DrawableRes iconResource: Int = R.drawable.ic_info,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
            Box(modifier = Modifier.weight(1f)) {
                val colors = MaterialTheme.colorScheme
                // Use the card's contrasting colour for actions when primary matches its background.
                if (colors.primary == colors.secondaryContainer) {
                    MaterialTheme(
                        colorScheme = colors.copy(
                            primary = colors.onSecondaryContainer,
                            onPrimary = colors.secondaryContainer,
                        ),
                        content = content,
                    )
                } else {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun InfoBoxDetail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Normal,
    )
}