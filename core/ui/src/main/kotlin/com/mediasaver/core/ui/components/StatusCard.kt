package com.mediasaver.core.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.util.shimmerBackground

@Composable
fun StatusCard(
    status: WhatsAppStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val ringBorderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(CardShape)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = ringBorderColor,
                shape = CardShape
            )
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail image with shimmer placeholder
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(Uri.parse(status.uriString))
                    .crossfade(true)
                    .build(),
                contentDescription = status.fileName,
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shimmerBackground()
                    )
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (status.mediaType == StatusMediaType.VIDEO) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Video indicator overlay (top-start)
            if (status.mediaType == StatusMediaType.VIDEO) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Saved indicator = small check badge top-end
            if (status.isSaved) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Saved",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Selection overlay: check circle overlay
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
private fun StatusCardDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp).size(width = 160.dp, height = 284.dp)) {
            StatusCard(
                status = WhatsAppStatus(
                    id = "1",
                    fileName = "status_1.jpg",
                    uriString = "content://media/1",
                    mediaType = StatusMediaType.IMAGE,
                    fileSize = 1024L * 500,
                    lastModified = System.currentTimeMillis(),
                    isSaved = true
                ),
                isSelected = true,
                onClick = {}
            )
        }
    }
}

@Preview(name = "Light Theme", showBackground = true)
@Composable
private fun StatusCardLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(16.dp).size(width = 160.dp, height = 284.dp)) {
            StatusCard(
                status = WhatsAppStatus(
                    id = "1",
                    fileName = "status_1.jpg",
                    uriString = "content://media/1",
                    mediaType = StatusMediaType.IMAGE,
                    fileSize = 1024L * 500,
                    lastModified = System.currentTimeMillis(),
                    isSaved = true
                ),
                isSelected = true,
                onClick = {}
            )
        }
    }
}
