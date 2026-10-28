package com.trainingapp.feature.workoutbuilder.plans

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.CountPill
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
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
import com.trainingapp.feature.workoutbuilder.resources.plans_pill
import com.trainingapp.feature.workoutbuilder.resources.plans_row_edited
import com.trainingapp.feature.workoutbuilder.resources.plans_row_summary
import com.trainingapp.feature.workoutbuilder.resources.plans_search
import com.trainingapp.feature.workoutbuilder.resources.plans_section
import com.trainingapp.feature.workoutbuilder.resources.plans_untitled
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * S1, the trainer's "+ PLAN" destination. The shell renders the "Plany" header and the bottom navigation;
 * this renders the list, laid out like the Stitch Klienci screen.
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = contentPadding.calculateTopPadding() + Spacing.s,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (content != null) {
                        CountPill(
                            pluralStringResource(Res.plurals.plans_pill, content.totalCount, content.totalCount),
                            dotColor = AppTheme.colors.success,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(text = stringResource(Res.string.plans_new), onClick = onNewPlan, icon = Icons.Outlined.Add)
                }
                SearchField(
                    query = content?.query.orEmpty(),
                    onQueryChange = onQueryChange,
                    placeholder = stringResource(Res.string.plans_search),
                )
                if (content != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        PlanListFilter.entries.forEach { chip ->
                            val count = content.counts[chip] ?: 0
                            AppFilterChip(
                                label = when (chip) {
                                    PlanListFilter.ALL -> stringResource(Res.string.plans_filter_all, count)
                                    PlanListFilter.DRAFTS -> stringResource(Res.string.plans_filter_drafts, count)
                                    PlanListFilter.PUBLISHED -> stringResource(Res.string.plans_filter_published, count)
                                },
                                selected = content.filter == chip,
                                onClick = { onFilterChange(chip) },
                            )
                        }
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
                item(key = "section") {
                    SectionHeader(
                        title = stringResource(Res.string.plans_section),
                        actionLabel = stringResource(Res.string.plans_exercise_library),
                        onAction = onOpenExerciseLibrary,
                    )
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
    }
}

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
        supporting = "$summary\n$edited",
        onClick = onClick,
        leading = { ExerciseImage(firstExercise, size = 64.dp, shape = CircleShape) },
        badges = {
            val version = item.latestVersion
            when {
                item.isNewDraft -> StatusChip(stringResource(Res.string.plans_badge_draft), tone = StatusTone.Warning)
                item.hasUnpublishedChanges -> StatusChip(stringResource(Res.string.plans_badge_unpublished), tone = StatusTone.Warning)
                version != null -> StatusChip(stringResource(Res.string.plans_badge_version, version), tone = StatusTone.Success)
            }
            item.tags.take(2).forEach { StatusChip(it) }
        },
    )
}
