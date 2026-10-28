package com.trainingapp.feature.workoutbuilder.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanListFilter
import com.trainingapp.feature.workoutbuilder.common.ExerciseImage
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.days_count
import com.trainingapp.feature.workoutbuilder.resources.exercises_count
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_draft
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_unpublished
import com.trainingapp.feature.workoutbuilder.resources.plans_badge_version
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_action
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_message
import com.trainingapp.feature.workoutbuilder.resources.plans_empty_title
import com.trainingapp.feature.workoutbuilder.resources.plans_error
import com.trainingapp.feature.workoutbuilder.resources.plans_exercise_library
import com.trainingapp.feature.workoutbuilder.resources.plans_filter_all
import com.trainingapp.feature.workoutbuilder.resources.plans_filter_drafts
import com.trainingapp.feature.workoutbuilder.resources.plans_filter_published
import com.trainingapp.feature.workoutbuilder.resources.plans_new
import com.trainingapp.feature.workoutbuilder.resources.plans_no_results
import com.trainingapp.feature.workoutbuilder.resources.plans_offline
import com.trainingapp.feature.workoutbuilder.resources.plans_results
import com.trainingapp.feature.workoutbuilder.resources.plans_row_edited
import com.trainingapp.feature.workoutbuilder.resources.plans_row_summary
import com.trainingapp.feature.workoutbuilder.resources.plans_search
import com.trainingapp.feature.workoutbuilder.resources.plans_title
import com.trainingapp.feature.workoutbuilder.resources.plans_untitled
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** S1 as a pushed screen ("Plany treningowe"), opened from the shell's "Co chcesz dodać?" menu. */
@Composable
fun PlanLibraryScreen(
    onBack: () -> Unit,
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { DetailHeader(title = stringResource(Res.string.plans_title), onBack = onBack) },
    ) { padding ->
        PlanLibraryRoute(onNewPlan, onOpenPlan, onOpenExerciseLibrary, contentPadding = padding)
    }
}

/** S1 content, laid out like "Twoi podopieczni" on the trainer calendar/clients branch. */
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
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
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
    onQueryChange: (String) -> Unit,
    onFilterChange: (PlanListFilter) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val content = state as? PlanLibraryUiState.Content
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item(key = "search") {
            SearchField(
                query = content?.query.orEmpty(),
                onQueryChange = onQueryChange,
                placeholder = stringResource(Res.string.plans_search),
            )
        }
        if (content != null) {
            item(key = "tabs") {
                SegmentedTabs(
                    options = PlanListFilter.entries.map { chip ->
                        val count = content.counts[chip] ?: 0
                        when (chip) {
                            PlanListFilter.ALL -> stringResource(Res.string.plans_filter_all, count)
                            PlanListFilter.DRAFTS -> stringResource(Res.string.plans_filter_drafts, count)
                            PlanListFilter.PUBLISHED -> stringResource(Res.string.plans_filter_published, count)
                        }
                    },
                    selectedIndex = content.filter.ordinal,
                    onSelect = { onFilterChange(PlanListFilter.entries[it]) },
                )
            }
            item(key = "results") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(
                        stringResource(Res.string.plans_results, content.items.size),
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                        modifier = Modifier.heightIn(min = Sizes.minTouchTarget).wrapContentHeight(Alignment.CenterVertically),
                    )
                    Row {
                        AppTextButton(stringResource(Res.string.plans_exercise_library), onClick = onOpenExerciseLibrary, icon = Icons.AutoMirrored.Outlined.LibraryBooks)
                        AppTextButton(stringResource(Res.string.plans_new), onClick = onNewPlan, icon = Icons.Outlined.Add)
                    }
                }
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
                when {
                    state.totalCount == 0 -> item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.plans_empty_title),
                            message = stringResource(Res.string.plans_empty_message),
                            actionLabel = stringResource(Res.string.plans_empty_action),
                            onAction = onNewPlan,
                            icon = Icons.AutoMirrored.Outlined.Assignment,
                        )
                    }
                    state.items.isEmpty() -> item(key = "no-results") {
                        EmptyState(title = stringResource(Res.string.plans_no_results), message = null, icon = Icons.Outlined.SearchOff)
                    }
                    else -> items(state.items, key = { it.key }) { item ->
                        PlanCard(item, item.firstExerciseId?.let { state.exercises[it] }, languageTag, onClick = { onOpenPlan(item) })
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(Spacing.l)) }
    }
}

/** A plan row like ClientRow: first exercise photo instead of initials, meta lines and the plan's state. */
@Composable
private fun PlanCard(item: PlanLibraryItem, firstExercise: Exercise?, languageTag: String, onClick: () -> Unit) {
    val summary = stringResource(
        Res.string.plans_row_summary,
        pluralStringResource(Res.plurals.days_count, item.dayCount, item.dayCount),
        pluralStringResource(Res.plurals.exercises_count, item.exerciseCount, item.exerciseCount),
    )
    val edited = stringResource(Res.string.plans_row_edited, LocaleFormats.formatDate(item.updatedAtEpochMs, languageTag))
    CardRow(
        title = item.name.ifBlank { stringResource(Res.string.plans_untitled) },
        onClick = onClick,
        leading = { ExerciseImage(firstExercise, size = Sizes.minTouchTarget, shape = CircleShape) },
        badges = {
            val version = item.latestVersion
            when {
                item.isNewDraft -> StatusChip(stringResource(Res.string.plans_badge_draft), tone = StatusTone.Warning, icon = Icons.Outlined.EditNote)
                item.hasUnpublishedChanges -> StatusChip(stringResource(Res.string.plans_badge_unpublished), tone = StatusTone.Warning, icon = Icons.Outlined.EditNote)
                version != null -> StatusChip(stringResource(Res.string.plans_badge_version, version), tone = StatusTone.Success, icon = Icons.Outlined.CheckCircle)
            }
            item.tags.take(2).forEach { StatusChip(it) }
        },
        meta = {
            MetaLine(Icons.Outlined.FitnessCenter, summary)
            MetaLine(Icons.Outlined.Schedule, edited)
        },
    )
}
