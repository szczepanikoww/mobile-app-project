package com.trainingapp.feature.workoutbuilder.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTopBar
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.ExerciseThumbnail
import com.trainingapp.designsystem.component.ListRow
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MultiChoiceSheet
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.component.SheetOption
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseScope
import com.trainingapp.domain.exercise.SyncState
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.labelRes
import com.trainingapp.feature.workoutbuilder.common.muscleAndEquipment
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.exercises_count
import com.trainingapp.feature.workoutbuilder.resources.library_add_selected
import com.trainingapp.feature.workoutbuilder.resources.library_badge_failed
import com.trainingapp.feature.workoutbuilder.resources.library_badge_hidden
import com.trainingapp.feature.workoutbuilder.resources.library_badge_own
import com.trainingapp.feature.workoutbuilder.resources.library_badge_pending
import com.trainingapp.feature.workoutbuilder.resources.library_create_from_query
import com.trainingapp.feature.workoutbuilder.resources.library_details
import com.trainingapp.feature.workoutbuilder.resources.library_error
import com.trainingapp.feature.workoutbuilder.resources.library_filter_equipment
import com.trainingapp.feature.workoutbuilder.resources.library_filter_equipment_count
import com.trainingapp.feature.workoutbuilder.resources.library_filter_muscle
import com.trainingapp.feature.workoutbuilder.resources.library_filter_muscle_count
import com.trainingapp.feature.workoutbuilder.resources.library_mine_empty_message
import com.trainingapp.feature.workoutbuilder.resources.library_mine_empty_title
import com.trainingapp.feature.workoutbuilder.resources.library_new
import com.trainingapp.feature.workoutbuilder.resources.library_no_results_filters
import com.trainingapp.feature.workoutbuilder.resources.library_no_results_query
import com.trainingapp.feature.workoutbuilder.resources.library_no_results_title
import com.trainingapp.feature.workoutbuilder.resources.library_open
import com.trainingapp.feature.workoutbuilder.resources.library_pick_title
import com.trainingapp.feature.workoutbuilder.resources.library_replace_title
import com.trainingapp.feature.workoutbuilder.resources.library_scope_all
import com.trainingapp.feature.workoutbuilder.resources.library_scope_mine
import com.trainingapp.feature.workoutbuilder.resources.library_search
import com.trainingapp.feature.workoutbuilder.resources.library_section_all
import com.trainingapp.feature.workoutbuilder.resources.library_section_hidden
import com.trainingapp.feature.workoutbuilder.resources.library_section_mine
import com.trainingapp.feature.workoutbuilder.resources.library_section_recent
import com.trainingapp.feature.workoutbuilder.resources.library_section_results
import com.trainingapp.feature.workoutbuilder.resources.library_select
import com.trainingapp.feature.workoutbuilder.resources.library_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExerciseBrowserScreen(
    destination: ExerciseBrowserDestination,
    onBack: () -> Unit,
    onOpenExercise: (exerciseId: String) -> Unit,
    onCreateExercise: (prefillName: String?) -> Unit,
    viewModel: ExerciseBrowserViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExerciseBrowserContent(
        state = state,
        languageTag = language.current(),
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onScopeChange = viewModel::onScopeChange,
        onMusclesChange = viewModel::onMusclesChange,
        onEquipmentChange = viewModel::onEquipmentChange,
        onToggle = viewModel::toggle,
        onOpenExercise = onOpenExercise,
        onCreateExercise = onCreateExercise,
        onConfirm = {
            viewModel.confirmSelection()
            onBack()
        },
        onRetry = viewModel::retry,
    )
}

private enum class FilterSheet { Muscle, Equipment }

@Composable
fun ExerciseBrowserContent(
    state: ExerciseBrowserUiState,
    languageTag: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (ExerciseScope) -> Unit,
    onMusclesChange: (Set<MuscleGroup>) -> Unit,
    onEquipmentChange: (Set<Equipment>) -> Unit,
    onToggle: (String) -> Unit,
    onOpenExercise: (String) -> Unit,
    onCreateExercise: (String?) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf<FilterSheet?>(null) }
    val title = when {
        !state.pickMode -> Res.string.library_title
        state.singleSelect -> Res.string.library_replace_title
        else -> Res.string.library_pick_title
    }
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            AppTopBar(
                title = stringResource(title),
                onNavigate = onBack,
                actions = {
                    if (!state.pickMode) {
                        AppTextButton(stringResource(Res.string.library_new), onClick = { onCreateExercise(null) }, icon = Icons.Outlined.Add)
                    }
                },
            )
        },
        bottomBar = {
            if (state.pickMode) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(Res.string.library_add_selected, state.selection.size),
                        onClick = onConfirm,
                        enabled = state.selection.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s, bottom = Spacing.xxl),
        ) {
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(bottom = Spacing.m)) {
                    SearchField(
                        query = state.filter.query,
                        onQueryChange = onQueryChange,
                        placeholder = stringResource(Res.string.library_search),
                    )
                    SegmentedTabs(
                        options = listOf(stringResource(Res.string.library_scope_all), stringResource(Res.string.library_scope_mine)),
                        selectedIndex = if (state.filter.scope == ExerciseScope.ALL) 0 else 1,
                        onSelect = { onScopeChange(if (it == 0) ExerciseScope.ALL else ExerciseScope.MINE) },
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        val muscles = state.filter.muscles.size
                        val equipment = state.filter.equipment.size
                        AppFilterChip(
                            label = if (muscles == 0) stringResource(Res.string.library_filter_muscle) else stringResource(Res.string.library_filter_muscle_count, muscles),
                            selected = muscles > 0,
                            showChevron = true,
                            onClick = { sheet = FilterSheet.Muscle },
                        )
                        AppFilterChip(
                            label = if (equipment == 0) stringResource(Res.string.library_filter_equipment) else stringResource(Res.string.library_filter_equipment_count, equipment),
                            selected = equipment > 0,
                            showChevron = true,
                            onClick = { sheet = FilterSheet.Equipment },
                        )
                    }
                    if (!state.loading && !state.error) {
                        Text(
                            pluralStringResource(Res.plurals.exercises_count, state.matchCount, state.matchCount),
                            style = AppTheme.type.compactBody,
                            color = AppTheme.colors.textSecondary,
                        )
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl)) { LoadingState() } }
                state.error -> item(key = "error") { ErrorState(stringResource(Res.string.library_error), onRetry = onRetry) }
                state.isEmpty -> item(key = "empty") { NoResults(state, onCreateExercise) }
                else -> state.sections.forEach { section ->
                    item(key = "header-${section.kind}") { SectionHeader(stringResource(section.kind.titleRes())) }
                    itemsIndexed(section.exercises, key = { _, exercise -> "${section.kind}-${exercise.id}" }) { index, exercise ->
                        val shape = groupShape(index, section.exercises.lastIndex)
                        Column(Modifier.background(AppTheme.colors.surface, shape)) {
                            if (index > 0) RowDivider()
                            ExerciseRow(
                                exercise = exercise,
                                languageTag = languageTag,
                                pickMode = state.pickMode,
                                selected = exercise.id in state.selection,
                                onClick = { if (state.pickMode) onToggle(exercise.id) else onOpenExercise(exercise.id) },
                                onOpenDetails = { onOpenExercise(exercise.id) },
                            )
                        }
                    }
                    item(key = "gap-${section.kind}") { Box(Modifier.padding(bottom = Spacing.l)) }
                }
            }
        }
    }

    when (sheet) {
        FilterSheet.Muscle -> {
            val options = MuscleGroup.entries.map { SheetOption(it, stringResource(it.labelRes)) }.sortedBy { it.label }
            MultiChoiceSheet(
                title = stringResource(Res.string.library_filter_muscle),
                options = options,
                selected = state.filter.muscles,
                onApply = { onMusclesChange(it); sheet = null },
                onDismiss = { sheet = null },
            )
        }
        FilterSheet.Equipment -> {
            val options = Equipment.entries.map { SheetOption(it, stringResource(it.labelRes)) }.sortedBy { it.label }
            MultiChoiceSheet(
                title = stringResource(Res.string.library_filter_equipment),
                options = options,
                selected = state.filter.equipment,
                onApply = { onEquipmentChange(it); sheet = null },
                onDismiss = { sheet = null },
            )
        }
        null -> Unit
    }
}

private fun SectionKind.titleRes() = when (this) {
    SectionKind.RECENT -> Res.string.library_section_recent
    SectionKind.MINE -> Res.string.library_section_mine
    SectionKind.ALL -> Res.string.library_section_all
    SectionKind.HIDDEN -> Res.string.library_section_hidden
    SectionKind.RESULTS -> Res.string.library_section_results
}

/** Rows of one section share a single surface: only the first and last rows are rounded. */
private fun groupShape(index: Int, lastIndex: Int): RoundedCornerShape {
    val top = if (index == 0) 16.dp else 0.dp
    val bottom = if (index == lastIndex) 16.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun NoResults(state: ExerciseBrowserUiState, onCreateExercise: (String?) -> Unit) {
    val query = state.filter.query.trim()
    when {
        query.isNotEmpty() -> EmptyState(
            title = stringResource(Res.string.library_no_results_title),
            message = stringResource(Res.string.library_no_results_query, query),
            actionLabel = stringResource(Res.string.library_create_from_query, query),
            onAction = { onCreateExercise(query) },
            icon = Icons.Outlined.SearchOff,
        )
        state.filter.scope == ExerciseScope.MINE && state.filter.muscles.isEmpty() && state.filter.equipment.isEmpty() -> EmptyState(
            title = stringResource(Res.string.library_mine_empty_title),
            message = stringResource(Res.string.library_mine_empty_message),
            actionLabel = stringResource(Res.string.library_new),
            onAction = { onCreateExercise(null) },
        )
        else -> EmptyState(
            title = stringResource(Res.string.library_no_results_title),
            message = stringResource(Res.string.library_no_results_filters),
            icon = Icons.Outlined.SearchOff,
        )
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    languageTag: String,
    pickMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val name = exercise.name(languageTag)
    ListRow(
        title = name,
        supporting = exercise.muscleAndEquipment(),
        onClick = onClick,
        onClickLabel = stringResource(if (pickMode) Res.string.library_select else Res.string.library_open),
        leading = {
            if (pickMode) {
                // In pick mode the row toggles selection; the thumbnail opens the details.
                Box(
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.library_details, name),
                        onClick = onOpenDetails,
                    ),
                ) { ExerciseThumbnail() }
            } else {
                ExerciseThumbnail()
            }
        },
        badges = if (exercise.isCustom || exercise.isHidden) {
            { ExerciseBadges(exercise) }
        } else {
            null
        },
        trailing = {
            if (pickMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.brandStrong),
                )
            } else {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textSecondary)
            }
        },
    )
}

@Composable
internal fun ExerciseBadges(exercise: Exercise) {
    if (exercise.isCustom) StatusChip(stringResource(Res.string.library_badge_own))
    when (exercise.syncState) {
        SyncState.PENDING -> StatusChip(stringResource(Res.string.library_badge_pending), tone = StatusTone.Warning, icon = Icons.Outlined.CloudOff)
        SyncState.FAILED -> StatusChip(stringResource(Res.string.library_badge_failed), tone = StatusTone.Error, icon = Icons.Outlined.ErrorOutline)
        SyncState.SYNCED -> Unit
    }
    if (exercise.isHidden) StatusChip(stringResource(Res.string.library_badge_hidden))
}
