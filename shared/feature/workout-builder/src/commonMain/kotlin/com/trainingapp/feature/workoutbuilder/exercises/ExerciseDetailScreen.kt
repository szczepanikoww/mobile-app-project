package com.trainingapp.feature.workoutbuilder.exercises

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTopBar
import com.trainingapp.designsystem.component.BottomActionBar
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
) {
    val content = state as? ExerciseDetailUiState.Content
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            AppTopBar(
                title = content?.exercise?.name(languageTag).orEmpty(),
                onNavigate = onBack,
                actions = {
                    if (content != null && content.isOwner) {
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(Res.string.detail_edit)) }
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
                    PrimaryButton(stringResource(Res.string.detail_add_to_day), onClick = onAddToDay, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        when (state) {
            ExerciseDetailUiState.Loading -> LoadingState(Modifier.padding(padding))
            ExerciseDetailUiState.NotFound -> ErrorState(stringResource(Res.string.detail_not_found), Modifier.padding(padding))
            is ExerciseDetailUiState.Content -> ExerciseDetailBody(state.exercise, languageTag, onRetrySync, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ExerciseDetailBody(exercise: Exercise, languageTag: String, onRetrySync: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        // The bundled start-position photo; a neutral placeholder when there is none. Trainer video comes later.
        ExerciseImage(
            exercise,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .aspectRatio(16f / 9f),
            shape = AppShapes.featureCard,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(exercise.name(languageTag), style = AppTheme.type.screenTitle, modifier = Modifier.semantics { heading() })
            Text(exercise.muscleAndEquipment(), style = AppTheme.type.body, color = colors.textSecondary)
            if (exercise.isCustom || exercise.isHidden) {
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

        DetailSection(stringResource(Res.string.detail_muscles)) {
            Text(stringResource(Res.string.detail_primary), style = AppTheme.type.label, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                exercise.primaryMuscles.forEach { StatusChip(stringResource(it.labelRes), tone = StatusTone.Accent) }
            }
            if (exercise.secondaryMuscles.isNotEmpty()) {
                Text(stringResource(Res.string.detail_secondary), style = AppTheme.type.label, color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    exercise.secondaryMuscles.forEach { StatusChip(stringResource(it.labelRes)) }
                }
            }
        }

        DetailSection(stringResource(Res.string.detail_tracking)) {
            Text(stringResource(exercise.trackingType.labelRes), style = AppTheme.type.body)
        }

        DetailSection(stringResource(Res.string.detail_instructions)) {
            val steps = exercise.instructions(languageTag)
            if (steps.isEmpty()) {
                Text(stringResource(Res.string.detail_no_instructions), style = AppTheme.type.body, color = colors.textSecondary)
            } else {
                steps.forEachIndexed { index, step ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(Res.string.detail_step, index + 1), style = AppTheme.type.label, color = colors.brandStrong)
                        Text(step, style = AppTheme.type.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = AppTheme.type.sectionTitle, modifier = Modifier.semantics { heading() })
        content()
    }
}
