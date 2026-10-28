package com.trainingapp.feature.workoutbuilder.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.video_new_pending
import com.trainingapp.feature.workoutbuilder.resources.video_play
import com.trainingapp.feature.workoutbuilder.resources.video_retry
import com.trainingapp.feature.workoutbuilder.resources.video_state_failed
import com.trainingapp.feature.workoutbuilder.resources.video_state_processing
import com.trainingapp.feature.workoutbuilder.resources.video_state_queued
import com.trainingapp.feature.workoutbuilder.resources.video_state_ready
import com.trainingapp.feature.workoutbuilder.resources.video_state_uploading
import com.trainingapp.feature.workoutbuilder.resources.video_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val VideoShape = RoundedCornerShape(24.dp) // AppShapes.featureCard

/**
 * The trainer's demo video as in the Stitch "Trening na żywo" media card: a 16:9 frame with the "Wideo wzorcowe"
 * chip, the duration, and a play button only when the video is READY. While it uploads or processes, the card
 * shows that state instead, so an unfinished upload never looks playable.
 *
 * [asset] is `null` for a video picked in the form but not saved yet.
 */
@Composable
fun DemoVideoCard(
    thumbnailPath: String?,
    durationMs: Long?,
    asset: MediaAsset?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val thumbnail by produceState<ImageBitmap?>(null, thumbnailPath) { value = thumbnailPath?.let { loadLocalImage(it) } }
    var playing by rememberSaveable(asset?.id) { mutableStateOf(false) }
    val playbackUrl = if (asset != null && asset.state == MediaState.READY) asset.playbackUrl else null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(VideoShape)
                .background(colors.text),
        ) {
            if (playing && playbackUrl != null) {
                DemoVideoPlayer(playbackUrl, Modifier.fillMaxSize())
            } else {
                thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                StatusChip(
                    stringResource(Res.string.video_title),
                    icon = Icons.Outlined.Videocam,
                    tone = StatusTone.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.m),
                )
                durationMs?.let {
                    Text(
                        SetInput.formatDuration((it / 1000).toInt()),
                        style = AppTheme.type.label,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(Spacing.m)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                }
                if (playbackUrl != null) {
                    val playLabel = stringResource(Res.string.video_play)
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .clickable(role = Role.Button, onClickLabel = playLabel) { playing = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = playLabel, tint = colors.brandStrong, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        VideoStatus(asset, onRetry)
    }
}

@Composable
private fun VideoStatus(asset: MediaAsset?, onRetry: (() -> Unit)?) {
    val colors = AppTheme.colors
    if (asset == null) {
        Text(stringResource(Res.string.video_new_pending), style = AppTheme.type.compactBody, color = colors.textSecondary)
        return
    }
    when (asset.state) {
        MediaState.QUEUED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.video_state_queued), style = AppTheme.type.compactBody, color = colors.textSecondary)
        }
        MediaState.UPLOADING -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(Res.string.video_state_uploading, "${(asset.progress * 100).roundToInt()}%"),
                style = AppTheme.type.compactBody,
                color = colors.textSecondary,
            )
            LinearProgressIndicator(
                progress = { asset.progress },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = colors.brandStrong,
                trackColor = colors.softAccent,
            )
        }
        MediaState.PROCESSING -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(Res.string.video_state_processing), style = AppTheme.type.compactBody, color = colors.textSecondary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape), color = colors.brandStrong, trackColor = colors.softAccent)
        }
        MediaState.READY -> StatusChip(stringResource(Res.string.video_state_ready), tone = StatusTone.Success)
        MediaState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(18.dp))
            Text(
                stringResource(Res.string.video_state_failed),
                style = AppTheme.type.compactBody,
                color = colors.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) AppTextButton(stringResource(Res.string.video_retry), onClick = onRetry)
        }
    }
}
