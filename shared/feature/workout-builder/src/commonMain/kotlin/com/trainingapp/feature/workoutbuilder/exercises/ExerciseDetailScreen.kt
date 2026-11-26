package com.trainingapp.feature.workoutbuilder.exercises

import androidx.compose.foundation.layout.Arrangement
import com.trainingapp.feature.workoutbuilder.resources.detail_title
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.MetaLine
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Videocam
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.feature.workoutbuilder.media.VideoPickResult
import com.trainingapp.feature.workoutbuilder.media.rememberVideoPicker
import com.trainingapp.feature.workoutbuilder.resources.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTopBar
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.SyncState
import com.trainingapp.feature.workoutbuilder.common.ExerciseDetailDestination
import com.trainingapp.feature.workoutbuilder.common.ExerciseImage
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.feature.workoutbuilder.media.DemoVideoCard
import com.trainingapp.feature.workoutbuilder.common.labelRes
import com.trainingapp.feature.workoutbuilder.common.muscleAndEquipment
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.detail_add_to_day
import com.trainingapp.feature.workoutbuilder.resources.detail_edit
import com.trainingapp.feature.workoutbuilder.resources.detail_hidden
import com.trainingapp.feature.workoutbuilder.resources.detail_hide
import com.trainingapp.feature.workoutbuilder.resources.detail_instructions
import com.trainingapp.feature.workoutbuilder.resources.detail_muscles
import com.trainingapp.feature.workoutbuilder.resources.detail_no_instructions
import com.trainingapp.feature.workoutbuilder.resources.detail_not_found
import com.trainingapp.feature.workoutbuilder.resources.detail_primary
import com.trainingapp.feature.workoutbuilder.resources.detail_secondary
import com.trainingapp.feature.workoutbuilder.resources.detail_step
import com.trainingapp.feature.workoutbuilder.resources.detail_sync_failed
import com.trainingapp.feature.workoutbuilder.resources.detail_sync_pending
import com.trainingapp.feature.workoutbuilder.resources.detail_sync_retry
import com.trainingapp.feature.workoutbuilder.resources.detail_tracking
import com.trainingapp.feature.workoutbuilder.resources.detail_unhide
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExerciseDetailScreen(
    destination: ExerciseDetailDestination,
    onBack: () -> Unit,
    onEdit: (exerciseId: String) -> Unit,
    onAddedToDay: () -> Unit,
    viewModel: ExerciseDetailViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExerciseDetailContent(
        state = state,
        languageTag = language.current(),
        onBack = onBack,
        onEdit = { onEdit(destination.exerciseId) },
        onSetHidden = viewModel::setHidden,
        onRetrySync = viewModel::retrySync,
        onRetryVideo = viewModel::retryVideoUpload,
        onSaveNote = viewModel::saveNote,
        onVideoPicked = { result -> if (result is VideoPickResult.Picked) viewModel.setVideo(result.video) },
        onRemoveVideo = viewModel::removeVideo,
        onAddToDay = {
            viewModel.addToDay()
            onAddedToDay()
        },
    )
}

@Composable
fun ExerciseDetailContent(
    state: ExerciseDetailUiState,
    languageTag: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onRetrySync: () -> Unit,
    onAddToDay: () -> Unit,
    onRetryVideo: () -> Unit = {},
    onSaveNote: (String) -> Unit = {},
    onVideoPicked: (VideoPickResult) -> Unit = {},
    onRemoveVideo: () -> Unit = {},
) {
    val content = state as? ExerciseDetailUiState.Content
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            DetailHeader(
                title = stringResource(Res.string.detail_title),
                onBack = onBack,
                actions = {
                    if (content != null && content.isOwner) {
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(Res.string.detail_edit), tint = AppTheme.colors.text) }
                        OverflowMenu(
                            actions = listOf(
                                if (content.exercise.isHidden) {
                                    MenuAction(stringResource(Res.string.detail_unhide), Icons.Outlined.Visibility) { onSetHidden(false) }
                                } else {
                                    MenuAction(stringResource(Res.string.detail_hide), Icons.Outlined.VisibilityOff) { onSetHidden(true) }
                                },
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (content != null && content.pickMode && !content.exercise.isHidden) {
                BottomActionBar {
                    PrimaryButton(
                        stringResource(Res.string.detail_add_to_day),
                        onClick = onAddToDay,
                        icon = Icons.Outlined.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        when (state) {
            ExerciseDetailUiState.Loading -> LoadingState(Modifier.padding(padding))
            ExerciseDetailUiState.NotFound -> ErrorState(stringResource(Res.string.detail_not_found), Modifier.padding(padding))
            is ExerciseDetailUiState.Content -> ExerciseDetailBody(
                state.exercise,
                languageTag,
                onRetrySync,
                Modifier.padding(padding),
                video = state.video,
                onRetryVideo = if (state.isOwner || state.trainerNote?.demoMediaId != null) onRetryVideo else null,
                trainerNote = {
                    // "Od trenera": on library exercises (an own exercise has its notes and video in its form).
                    val note = state.trainerNote
                    if (note != null && !state.exercise.isCustom) {
                        TrainerNoteSection(note, state.noteSaving, state.noteFailed, onSaveNote, onVideoPicked, onRemoveVideo)
                    }
                },
            )
        }
    }
}

@Composable
private fun ExerciseDetailBody(
    exercise: Exercise,
    languageTag: String,
    onRetrySync: () -> Unit,
    modifier: Modifier,
    video: MediaAsset? = null,
    onRetryVideo: (() -> Unit)? = null,
    trainerNote: @Composable () -> Unit = {},
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        // The trainer's demo video comes first, as the media card of "Trening na żywo".
        if (video != null) {
            DemoVideoCard(thumbnailPath = video.thumbnailPath, durationMs = video.durationMs, asset = video, onRetry = onRetryVideo)
        }

        // The photo, then the name and its meta line, as the profile header on the calendar/clients branch.
        ExerciseImage(
            exercise,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).aspectRatio(16f / 9f),
            shape = AppShapes.featureCard,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(exercise.name(languageTag), style = AppTheme.type.sectionTitle, modifier = Modifier.semantics { heading() })
            MetaLine(Icons.Outlined.FitnessCenter, exercise.muscleAndEquipment())
            if (exercise.isCustom || exercise.isHidden || exercise.demoMediaId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { ExerciseBadges(exercise) }
            }
        }

        when (exercise.syncState) {
            SyncState.PENDING -> InlineMessage(stringResource(Res.string.detail_sync_pending), tone = MessageTone.Offline)
            SyncState.FAILED -> InlineMessage(
                stringResource(Res.string.detail_sync_failed),
                tone = MessageTone.Error,
                action = { AppTextButton(stringResource(Res.string.detail_sync_retry), onClick = onRetrySync) },
            )
            SyncState.SYNCED -> Unit
        }
        if (exercise.isHidden) InlineMessage(stringResource(Res.string.detail_hidden), tone = MessageTone.Info)

        trainerNote()

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SectionHeader(stringResource(Res.string.detail_muscles))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                // Primary muscles are highlighted like "Korekta postawy"; secondary ones are white pills.
                exercise.primaryMuscles.forEach { TagPill(stringResource(it.labelRes), highlighted = true) }
                exercise.secondaryMuscles.forEach { TagPill(stringResource(it.labelRes), highlighted = false) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SectionHeader(stringResource(Res.string.detail_tracking))
            TagPill(stringResource(exercise.trackingType.labelRes), highlighted = false)
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SectionHeader(stringResource(Res.string.detail_instructions))
            val steps = exercise.instructions(languageTag)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (steps.isEmpty()) {
                    Text(stringResource(Res.string.detail_no_instructions), style = AppTheme.type.body, color = colors.textSecondary)
                } else {
                    steps.forEachIndexed { index, step ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(Res.string.detail_step, index + 1), style = AppTheme.type.label, color = colors.textSecondary)
                            Text(step, style = AppTheme.type.body, color = colors.text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Od trenera": the trainer's own note and video on a library exercise. The video is shown at the top of the screen,
 * before the library images; here it can be added, replaced or removed.
 */
@Composable
private fun TrainerNoteSection(
    note: com.trainingapp.domain.exercise.TrainerExerciseNote,
    saving: Boolean,
    failed: Boolean,
    onSave: (String) -> Unit,
    onVideoPicked: (VideoPickResult) -> Unit,
    onRemoveVideo: () -> Unit,
) {
    var editing by remember(note) { mutableStateOf(false) }
    var draft by remember(note) { mutableStateOf(note.note) }
    val picker = rememberVideoPicker(onVideoPicked)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.trainer_note_title))
        if (editing) {
            AppTextField(value = draft, onValueChange = { draft = it.take(2000) }, label = stringResource(Res.string.trainer_note_label), singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PrimaryButton(stringResource(Res.string.trainer_note_save), onClick = { onSave(draft); editing = false }, loading = saving)
                AppTextButton(stringResource(Res.string.trainer_note_cancel), onClick = { draft = note.note; editing = false })
            }
        } else {
            Text(
                note.note.ifBlank { stringResource(Res.string.trainer_note_empty) },
                style = AppTheme.type.body,
                color = if (note.note.isBlank()) AppTheme.colors.textSecondary else AppTheme.colors.text,
            )
            AppTextButton(
                stringResource(if (note.note.isBlank()) Res.string.trainer_note_add else Res.string.trainer_note_edit),
                onClick = { editing = true },
                icon = Icons.Outlined.Edit,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppTextButton(
                stringResource(if (note.demoMediaId == null) Res.string.trainer_video_add else Res.string.trainer_video_replace),
                onClick = { picker.pickFromGallery() },
                enabled = !saving,
                icon = Icons.Outlined.Videocam,
            )
            if (note.demoMediaId != null) AppTextButton(stringResource(Res.string.trainer_video_remove), onClick = onRemoveVideo, enabled = !saving)
        }
        if (failed) InlineMessage(stringResource(Res.string.trainer_note_failed), tone = MessageTone.Error)
    }
}

/** A muscle pill: soft accent for primary muscles, outlined for secondary ones. */
@Composable
private fun TagPill(text: String, highlighted: Boolean) {
    val colors = AppTheme.colors
    Text(
        text,
        style = AppTheme.type.button,
        color = if (highlighted) colors.onSoftAccent else colors.textSecondary,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (highlighted) colors.softAccent else colors.surface)
            .border(BorderStroke(1.dp, if (highlighted) colors.softAccent else colors.outline), CircleShape)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}
