package com.trainingapp.feature.workoutlogging.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.workoutlogging.resources.Res
import com.trainingapp.feature.workoutlogging.resources.technique_failed
import com.trainingapp.feature.workoutlogging.resources.technique_processing
import com.trainingapp.feature.workoutlogging.resources.technique_queued
import com.trainingapp.feature.workoutlogging.resources.technique_ready
import com.trainingapp.feature.workoutlogging.resources.technique_retry
import com.trainingapp.feature.workoutlogging.resources.technique_uploading
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The state of a technique video linked to a set. It is labelled as playable only when READY; an unfinished upload
 * shows its progress, and a failed one offers retry.
 */
@Composable
fun TechniqueVideoStatus(asset: MediaAsset?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        when (asset?.state) {
            null, MediaState.QUEUED -> StatusChip(stringResource(Res.string.technique_queued), icon = Icons.Outlined.CloudOff)
            MediaState.UPLOADING -> StatusChip(
                stringResource(Res.string.technique_uploading, "${(asset.progress * 100).roundToInt()}%"),
                tone = StatusTone.Accent,
                icon = Icons.Outlined.CloudUpload,
            )
            MediaState.PROCESSING -> StatusChip(stringResource(Res.string.technique_processing), tone = StatusTone.Accent, icon = Icons.Outlined.HourglassTop)
            MediaState.READY -> StatusChip(stringResource(Res.string.technique_ready), tone = StatusTone.Success, icon = Icons.Outlined.Videocam)
            MediaState.FAILED -> {
                StatusChip(stringResource(Res.string.technique_failed), tone = StatusTone.Error, icon = Icons.Outlined.ErrorOutline)
                AppTextButton(stringResource(Res.string.technique_retry), onClick = onRetry)
            }
        }
    }
}
