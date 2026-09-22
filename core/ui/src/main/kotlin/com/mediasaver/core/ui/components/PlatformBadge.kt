package com.mediasaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.theme.PlatformInstagram
import com.mediasaver.core.ui.theme.PlatformTikTok
import com.mediasaver.core.ui.theme.PlatformTwitter
import com.mediasaver.core.ui.theme.PlatformUnknown
import com.mediasaver.core.ui.theme.PlatformWhatsApp
import com.mediasaver.core.ui.theme.PlatformYouTube

fun PlatformId.getBrandColor(): Color = when (this) {
    PlatformId.YOUTUBE -> PlatformYouTube
    PlatformId.TIKTOK -> PlatformTikTok
    PlatformId.WHATSAPP -> PlatformWhatsApp
    PlatformId.INSTAGRAM -> PlatformInstagram
    PlatformId.UNKNOWN -> PlatformUnknown
}

fun PlatformId.getIcon(): ImageVector = when (this) {
    PlatformId.YOUTUBE -> Icons.Rounded.PlayCircle
    PlatformId.TIKTOK -> Icons.Rounded.MusicNote
    PlatformId.WHATSAPP -> Icons.AutoMirrored.Rounded.Chat
    PlatformId.INSTAGRAM -> Icons.Rounded.CameraAlt
    PlatformId.UNKNOWN -> Icons.Rounded.Link
}

fun PlatformId.getDisplayName(): String = when (this) {
    PlatformId.YOUTUBE -> "YouTube"
    PlatformId.TIKTOK -> "TikTok"
    PlatformId.WHATSAPP -> "WhatsApp"
    PlatformId.INSTAGRAM -> "Instagram"
    PlatformId.UNKNOWN -> "Generic"
}

@Composable
fun PlatformBadge(
    platformId: PlatformId,
    modifier: Modifier = Modifier,
    customLabel: String? = null,
    showLabel: Boolean = true
) {
    val isTwitter = customLabel?.equals("Twitter", ignoreCase = true) == true ||
            customLabel?.equals("X", ignoreCase = true) == true

    val brandColor = if (isTwitter) PlatformTwitter else platformId.getBrandColor()
    val displayName = customLabel ?: platformId.getDisplayName()
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(width = 1.dp, color = brandColor.copy(alpha = 0.4f), shape = CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = platformId.getIcon(),
            contentDescription = displayName,
            tint = brandColor,
            modifier = Modifier.size(14.dp)
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
private fun PlatformBadgeDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        Row(modifier = Modifier.padding(16.dp)) {
            PlatformBadge(PlatformId.YOUTUBE)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.TIKTOK)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.WHATSAPP)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.INSTAGRAM)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.UNKNOWN, customLabel = "X / Twitter")
        }
    }
}

@Preview(name = "Light Theme", showBackground = true)
@Composable
private fun PlatformBadgeLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        Row(modifier = Modifier.padding(16.dp)) {
            PlatformBadge(PlatformId.YOUTUBE)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.TIKTOK)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.WHATSAPP)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.INSTAGRAM)
            Spacer(Modifier.width(8.dp))
            PlatformBadge(PlatformId.UNKNOWN, customLabel = "X / Twitter")
        }
    }
}
