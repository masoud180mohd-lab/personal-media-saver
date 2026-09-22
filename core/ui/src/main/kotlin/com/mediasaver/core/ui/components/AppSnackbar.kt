package com.mediasaver.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediasaver.core.ui.theme.SnackbarShape

@Composable
fun AppSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Rounded.Info
) {
    Surface(
        shape = SnackbarShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = SnackbarShape
            ),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = snackbarData.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            snackbarData.visuals.actionLabel?.let { actionLabel ->
                TextButton(
                    onClick = { snackbarData.performAction() },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private class SimpleSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: androidx.compose.material3.SnackbarDuration = androidx.compose.material3.SnackbarDuration.Short
) : androidx.compose.material3.SnackbarVisuals

private class SimpleSnackbarData(
    override val visuals: androidx.compose.material3.SnackbarVisuals
) : SnackbarData {
    override fun dismiss() {}
    override fun performAction() {}
}

@androidx.compose.ui.tooling.preview.Preview(name = "Dark Theme", showBackground = true)
@Composable
private fun AppSnackbarDarkPreview() {
    com.mediasaver.core.ui.theme.MediaSaverTheme(darkTheme = true) {
        AppSnackbar(
            snackbarData = SimpleSnackbarData(
                SimpleSnackbarVisuals(
                    message = "Media saved to Pictures/MediaSaver/",
                    actionLabel = "View"
                )
            )
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Light Theme", showBackground = true)
@Composable
private fun AppSnackbarLightPreview() {
    com.mediasaver.core.ui.theme.MediaSaverTheme(darkTheme = false) {
        AppSnackbar(
            snackbarData = SimpleSnackbarData(
                SimpleSnackbarVisuals(
                    message = "Media saved to Pictures/MediaSaver/",
                    actionLabel = "View"
                )
            )
        )
    }
}
