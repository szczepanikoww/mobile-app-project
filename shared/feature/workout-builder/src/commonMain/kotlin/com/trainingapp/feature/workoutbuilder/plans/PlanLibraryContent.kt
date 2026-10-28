package com.trainingapp.feature.workoutbuilder.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.GroupedSurface
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.ListRow
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.days_count
import com.trainingapp.feature.workoutbuilder.resources.exercises_count
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_draft
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_unpublished
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_version
import com.trainingapp.feature.workoutbuilder.resources.plans_count
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_action
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_message
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_title
import com.trainingapp.feature.workoutbuilder.resources.plans_error
import com.trainingapp.feature.workoutbuilder.resources.plans_exercise_library
import com.trainingapp.feature.workoutbuilder.resources.plans_new
import com.trainingapp.feature.workoutbuilder.resources.plans_offline
import com.trainingapp.feature.workoutbuilder.resources.plans_row_edited
import com.trainingapp.feature.workoutbuilder.resources.plans_row_summary
import com.trainingapp.feature.workoutbuilder.resources.plans_untitled
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * S1, shown in the "Plany" segment of the trainer's Klienci tab (decision D1). The shell owns the tab and its
 * title; this renders the list and its actions.
 */
@Composable
fun PlanLibraryRoute(
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: PlanLibraryViewModel = koinViewModel(),
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlanLibraryContent(
        state = state,
        languageTag = language.current(),
        onNewPlan = onNewPlan,
        onOpenPlan = onOpenPlan,
        onOpenExerciseLibrary = onOpenExerciseLibrary,
        onRetry = viewModel::retry,
        contentPadding = contentPadding,
    )
}

@Composable
fun PlanLibraryContent(
    state: PlanLibraryUiState,
    languageTag: String,
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = contentPadding.calculateTopPadding() + Spacing.s,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val count = (state as? PlanLibraryUiState.Content)?.items?.size
                Text(
                    text = count?.let { pluralStringResource(Res.plurals.plans_count, it, it) }.orEmpty(),
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(text = stringResource(Res.string.plans_new), onClick = onNewPlan, icon = Icons.Outlined.Add)
                OverflowMenu(
                    actions = listOf(
                        MenuAction(
                            label = stringResource(Res.string.plans_exercise_library),
                            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                            onClick = onOpenExerciseLibrary,
                        ),
                    ),
                )
            }
        }
        when (state) {
            PlanLibraryUiState.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl)) { LoadingState() }
            }
            PlanLibraryUiState.Error -> item(key = "error") {
                ErrorState(stringResource(Res.string.plans_error), onRetry = onRetry)
            }
            is PlanLibraryUiState.Content -> {
                if (state.offline) {
                    item(key = "offline") { InlineMessage(stringResource(Res.string.plans_offline), tone = MessageTone.Offline) }
                }
                if (state.items.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.plans_empty_title),
                            message = stringResource(Res.string.plans_empty_message),
                            actionLabel = stringResource(Res.string.plans_empty_action),
                            onAction = onNewPlan,
                            icon = Icons.AutoMirrored.Outlined.Assignment,
                        )
                    }
                } else {
                    item(key = "list") {
                        GroupedSurface {
                            state.items.forEachIndexed { index, item ->
                                if (index > 0) RowDivider()
                                PlanRow(item, languageTag, onClick = { onOpenPlan(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(item: PlanLibraryItem, languageTag: String, onClick: () -> Unit) {
    val summary = stringResource(
        Res.string.plans_row_summary,
        pluralStringResource(Res.plurals.days_count, item.dayCount, item.dayCount),
        pluralStringResource(Res.plurals.exercises_count, item.exerciseCount, item.exerciseCount),
    )
    val edited = stringResource(Res.string.plans_row_edited, LocaleFormats.formatDate(item.updatedAtEpochMs, languageTag))
    ListRow(
        title = item.name.ifBlank { stringResource(Res.string.plans_untitled) },
        supporting = "$summary\n$edited",
        onClick = onClick,
        badges = {
            val version = item.latestVersion
            when {
                item.isNewDraft -> StatusChip(stringResource(Res.string.plans_badge_draft), tone = StatusTone.Warning)
                item.hasUnpublishedChanges -> StatusChip(stringResource(Res.string.plans_badge_unpublished), tone = StatusTone.Warning)
                version != null -> StatusChip(stringResource(Res.string.plans_badge_version, version))
            }
        },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textSecondary) },
    )
}
