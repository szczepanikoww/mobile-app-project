package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.plan.PlanLimits
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.AppTopBar
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.resources.ds_cancel
import com.trainingapp.designsystem.resources.ds_done
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.plan.DraftDay
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.alphabetLabel
import com.trainingapp.domain.plan.supersetLabels
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.editor_add_exercises
import com.trainingapp.feature.workoutbuilder.resources.editor_archive_confirm
import com.trainingapp.feature.workoutbuilder.resources.editor_archive_message
import com.trainingapp.feature.workoutbuilder.resources.editor_archive_title
import com.trainingapp.feature.workoutbuilder.resources.editor_assign
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_load_latest
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_message
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_save_as_new
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_title
import com.trainingapp.feature.workoutbuilder.resources.editor_copy_name
import com.trainingapp.feature.workoutbuilder.resources.editor_day_add
import com.trainingapp.feature.workoutbuilder.resources.editor_day_default
import com.trainingapp.feature.workoutbuilder.resources.editor_day_delete
import com.trainingapp.feature.workoutbuilder.resources.editor_day_duplicate
import com.trainingapp.feature.workoutbuilder.resources.editor_day_empty_message
import com.trainingapp.feature.workoutbuilder.resources.editor_day_empty_title
import com.trainingapp.feature.workoutbuilder.resources.editor_day_has_errors
import com.trainingapp.feature.workoutbuilder.resources.editor_day_name_label
import com.trainingapp.feature.workoutbuilder.resources.editor_day_options
import com.trainingapp.feature.workoutbuilder.resources.editor_day_rename
import com.trainingapp.feature.workoutbuilder.resources.editor_days
import com.trainingapp.feature.workoutbuilder.resources.editor_discard_confirm
import com.trainingapp.feature.workoutbuilder.resources.editor_discard_message
import com.trainingapp.feature.workoutbuilder.resources.editor_discard_title
import com.trainingapp.feature.workoutbuilder.resources.editor_draft_saved
import com.trainingapp.feature.workoutbuilder.resources.editor_exercise_order
import com.trainingapp.feature.workoutbuilder.resources.editor_failed
import com.trainingapp.feature.workoutbuilder.resources.editor_invalid
import com.trainingapp.feature.workoutbuilder.resources.editor_menu_archive
import com.trainingapp.feature.workoutbuilder.resources.editor_menu_discard
import com.trainingapp.feature.workoutbuilder.resources.editor_menu_duplicate
import com.trainingapp.feature.workoutbuilder.resources.editor_name_label
import com.trainingapp.feature.workoutbuilder.resources.editor_not_found
import com.trainingapp.feature.workoutbuilder.resources.editor_offline
import com.trainingapp.feature.workoutbuilder.resources.editor_removed
import com.trainingapp.feature.workoutbuilder.resources.editor_retry
import com.trainingapp.feature.workoutbuilder.resources.editor_save
import com.trainingapp.feature.workoutbuilder.resources.editor_saved_version
import com.trainingapp.feature.workoutbuilder.resources.editor_summary
import com.trainingapp.feature.workoutbuilder.resources.editor_sync_failed
import com.trainingapp.feature.workoutbuilder.resources.editor_title_edit
import com.trainingapp.feature.workoutbuilder.resources.editor_title_new
import com.trainingapp.feature.workoutbuilder.resources.editor_undo
import com.trainingapp.feature.workoutbuilder.resources.exercises_count
import com.trainingapp.feature.workoutbuilder.resources.plans_untitled
import com.trainingapp.feature.workoutbuilder.resources.sets_count
import com.trainingapp.feature.workoutbuilder.resources.days_count
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.trainingapp.designsystem.resources.Res as DsRes

@Composable
fun PlanEditorScreen(
    destination: PlanEditorDestination,
    onBack: () -> Unit,
    onOpenPicker: (requestKey: String, singleSelect: Boolean) -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
    onAssignToClient: (templateId: String, versionNumber: Int) -> Unit,
    viewModel: PlanEditorViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val currentOnOpenPicker by rememberUpdatedState(onOpenPicker)
    val currentOnOpenDraft by rememberUpdatedState(onOpenDraft)
    val currentOnAssign by rememberUpdatedState(onAssignToClient)
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlanEditorEffect.Published -> {
                    val result = snackbar.showSnackbar(
                        message = getString(Res.string.editor_saved_version, effect.versionNumber),
                        actionLabel = getString(Res.string.editor_assign),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) currentOnAssign(effect.templateId, effect.versionNumber)
                }
                is PlanEditorEffect.ExerciseRemoved -> {
                    val result = snackbar.showSnackbar(
                        message = getString(Res.string.editor_removed, effect.exerciseName),
                        actionLabel = getString(Res.string.editor_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onAction(effect.undo)
                }
                is PlanEditorEffect.OpenPicker -> currentOnOpenPicker(effect.requestKey, effect.singleSelect)
                is PlanEditorEffect.OpenDraft -> currentOnOpenDraft(effect.draftId)
                PlanEditorEffect.Close -> currentOnBack()
            }
        }
    }

    PlanEditorContent(
        state = state,
        languageTag = language.current(),
        snackbar = snackbar,
        onBack = onBack,
        onAction = viewModel::onAction,
        onSelectDay = viewModel::selectDay,
        onAddExercises = viewModel::addExercises,
        onReplaceExercise = viewModel::replaceExercise,
        onRemoveExercise = viewModel::removeExercise,
        onPublish = viewModel::publish,
        onDismissBanner = viewModel::dismissBanner,
        onDismissConflict = viewModel::dismissConflict,
        onSaveAsNew = viewModel::saveAsNewPlan,
        onLoadLatest = viewModel::loadLatestVersion,
        onDuplicate = viewModel::duplicate,
        onArchive = viewModel::archive,
        onDiscard = viewModel::discardDraft,
    )
}

private enum class EditorDialog { RenameDay, Archive, Discard }

@Composable
fun PlanEditorContent(
    state: PlanEditorUiState,
    languageTag: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (PlanDraftAction) -> Unit,
    onSelectDay: (String) -> Unit,
    onAddExercises: (dayId: String) -> Unit,
    onReplaceExercise: (dayId: String, plannedExerciseId: String) -> Unit,
    onRemoveExercise: (dayId: String, plannedExerciseId: String) -> Unit,
    onPublish: () -> Unit,
    onDismissBanner: () -> Unit,
    onDismissConflict: () -> Unit,
    onSaveAsNew: () -> Unit,
    onLoadLatest: () -> Unit,
    onDuplicate: (copyName: String) -> Unit,
    onArchive: () -> Unit,
    onDiscard: () -> Unit,
) {
    val draft = state.draft
    var dialog by rememberSaveable { mutableStateOf<EditorDialog?>(null) }
    var settingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val untitled = stringResource(Res.string.plans_untitled)
    val copyName = stringResource(Res.string.editor_copy_name, draft?.name?.ifBlank { untitled } ?: untitled)

    val subtitle = draft?.let {
        val counts = stringResource(
            Res.string.editor_summary,
            pluralStringResource(Res.plurals.days_count, it.days.size, it.days.size),
            pluralStringResource(Res.plurals.exercises_count, it.exerciseCount, it.exerciseCount),
            pluralStringResource(Res.plurals.sets_count, it.setCount, it.setCount),
        )
        if (state.savedOnDevice) "${stringResource(Res.string.editor_draft_saved)} · $counts" else counts
    }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            AppTopBar(
                title = stringResource(if (state.isNewPlan) Res.string.editor_title_new else Res.string.editor_title_edit),
                subtitle = subtitle,
                onNavigate = onBack,
                actions = {
                    if (draft != null) {
                        OverflowMenu(
                            actions = listOfNotNull(
                                MenuAction(stringResource(Res.string.editor_menu_duplicate), Icons.Outlined.ContentCopy) { onDuplicate(copyName) },
                                if (!state.isNewPlan) {
                                    MenuAction(stringResource(Res.string.editor_menu_archive), Icons.Outlined.Archive) { dialog = EditorDialog.Archive }
                                } else {
                                    null
                                },
                                if (state.savedOnDevice) {
                                    MenuAction(stringResource(Res.string.editor_menu_discard), Icons.Outlined.DeleteOutline, destructive = true) {
                                        dialog = EditorDialog.Discard
                                    }
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (draft != null) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(Res.string.editor_save),
                        onClick = onPublish,
                        loading = state.publishing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound || draft == null -> ErrorState(stringResource(Res.string.editor_not_found), Modifier.padding(padding))
            else -> {
                val issues = remember(state.issues) { EditorIssues(state.issues) }
                val day = state.selectedDay ?: draft.days.first()
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    state.banner?.let { EditorBannerMessage(it, onPublish, onDismissBanner) }
                    AppTextField(
                        value = draft.name,
                        onValueChange = { onAction(PlanDraftAction.Rename(it)) },
                        label = stringResource(Res.string.editor_name_label),
                        error = issues.name?.let { planIssueText(it) },
                    )
                    DaySelector(
                        days = draft.days,
                        selectedDayId = day.id,
                        issues = issues,
                        onSelect = onSelectDay,
                        onAddDay = { onAction(PlanDraftAction.AddDay) },
                        onRename = { dialog = EditorDialog.RenameDay },
                        onDuplicate = { onAction(PlanDraftAction.DuplicateDay(day.id)) },
                        onDelete = { onAction(PlanDraftAction.DeleteDay(day.id)) },
                    )
                    DayExercises(
                        day = day,
                        state = state,
                        languageTag = languageTag,
                        issues = issues,
                        onAction = onAction,
                        onOpenSettings = { settingsFor = it },
                        onReplace = { onReplaceExercise(day.id, it) },
                        onRemove = { onRemoveExercise(day.id, it) },
                    )
                    SecondaryButton(
                        text = stringResource(Res.string.editor_add_exercises),
                        onClick = { onAddExercises(day.id) },
                        icon = Icons.Outlined.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // The sheet closes by itself if its exercise leaves the selected day.
                settingsFor?.let { plannedId -> day.exercises.firstOrNull { it.id == plannedId } }?.let { planned ->
                    ExerciseSettingsSheet(
                        planned = planned,
                        exerciseName = state.exercises[planned.exerciseId]?.name(languageTag).orEmpty(),
                        onApply = { settings ->
                            onAction(
                                PlanDraftAction.UpdateSettings(
                                    day.id, planned.id, settings.restSeconds, settings.rpeEnabled, settings.tempo, settings.note,
                                ),
                            )
                            settingsFor = null
                        },
                        onDismiss = { settingsFor = null },
                    )
                }

                when (dialog) {
                    EditorDialog.RenameDay -> RenameDayDialog(
                        initial = day.name,
                        onConfirm = { onAction(PlanDraftAction.RenameDay(day.id, it)); dialog = null },
                        onDismiss = { dialog = null },
                    )
                    EditorDialog.Archive -> ConfirmDialog(
                        title = stringResource(Res.string.editor_archive_title),
                        message = stringResource(Res.string.editor_archive_message),
                        confirmLabel = stringResource(Res.string.editor_archive_confirm),
                        onConfirm = { dialog = null; onArchive() },
                        onDismiss = { dialog = null },
                    )
                    EditorDialog.Discard -> ConfirmDialog(
                        title = stringResource(Res.string.editor_discard_title),
                        message = stringResource(Res.string.editor_discard_message),
                        confirmLabel = stringResource(Res.string.editor_discard_confirm),
                        onConfirm = { dialog = null; onDiscard() },
                        onDismiss = { dialog = null },
                    )
                    null -> Unit
                }

                state.conflictLatestVersion?.let { latest ->
                    ConflictDialog(latest, onSaveAsNew = onSaveAsNew, onLoadLatest = onLoadLatest, onDismiss = onDismissConflict)
                }
            }
        }
    }
}

@Composable
private fun EditorBannerMessage(banner: EditorBanner, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val (text, tone) = when (banner) {
        EditorBanner.Invalid -> stringResource(Res.string.editor_invalid) to MessageTone.Error
        EditorBanner.Offline -> stringResource(Res.string.editor_offline) to MessageTone.Offline
        is EditorBanner.SyncFailed -> stringResource(Res.string.editor_sync_failed, banner.exerciseNames.joinToString()) to MessageTone.Error
        EditorBanner.Failed -> stringResource(Res.string.editor_failed) to MessageTone.Error
    }
    InlineMessage(
        text = text,
        tone = tone,
        action = if (banner == EditorBanner.Invalid) {
            null
        } else {
            { AppTextButton(stringResource(Res.string.editor_retry), onClick = onRetry) }
        },
    )
}

@Composable
private fun DaySelector(
    days: List<DraftDay>,
    selectedDayId: String,
    issues: EditorIssues,
    onSelect: (String) -> Unit,
    onAddDay: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val selectedIndex = days.indexOfFirst { it.id == selectedDayId }.coerceAtLeast(0)
    val selectedName = dayLabel(days[selectedIndex], selectedIndex)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.editor_days), style = AppTheme.type.bodyStrong, modifier = Modifier.weight(1f))
            OverflowMenu(
                contentDescription = stringResource(Res.string.editor_day_options, selectedName),
                actions = listOfNotNull(
                    MenuAction(stringResource(Res.string.editor_day_rename), Icons.Outlined.Edit, onClick = onRename),
                    MenuAction(stringResource(Res.string.editor_day_duplicate), Icons.Outlined.ContentCopy, onClick = onDuplicate),
                    if (days.size > 1) {
                        MenuAction(stringResource(Res.string.editor_day_delete), Icons.Outlined.DeleteOutline, destructive = true, onClick = onDelete)
                    } else {
                        null
                    },
                ),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            days.forEachIndexed { index, day ->
                val label = dayLabel(day, index)
                val hasIssues = issues.dayHasIssues(day.id)
                AppFilterChip(
                    label = if (hasIssues) stringResource(Res.string.editor_day_has_errors, label) else label,
                    selected = day.id == selectedDayId,
                    onClick = { onSelect(day.id) },
                    leadingIcon = if (hasIssues) Icons.Outlined.ErrorOutline else null,
                )
            }
            AppFilterChip(
                label = stringResource(Res.string.editor_day_add),
                selected = false,
                onClick = onAddDay,
                leadingIcon = Icons.Outlined.Add,
            )
        }
    }
}

@Composable
private fun dayLabel(day: DraftDay, index: Int): String =
    day.name.ifBlank { stringResource(Res.string.editor_day_default, alphabetLabel(index)) }

/**
 * The exercises of one day, in order. A long press on a drag handle drags the card; the row menu and the
 * handle's accessibility actions move it one step without dragging.
 */
@Composable
private fun DayExercises(
    day: DraftDay,
    state: PlanEditorUiState,
    languageTag: String,
    issues: EditorIssues,
    onAction: (PlanDraftAction) -> Unit,
    onOpenSettings: (plannedExerciseId: String) -> Unit,
    onReplace: (plannedExerciseId: String) -> Unit,
    onRemove: (plannedExerciseId: String) -> Unit,
) {
    SectionHeader(
        title = stringResource(Res.string.editor_exercise_order),
        trailing = {
            Text(
                pluralStringResource(Res.plurals.exercises_count, day.exercises.size, day.exercises.size),
                style = AppTheme.type.compactBody,
                color = AppTheme.colors.textSecondary,
            )
        },
    )
    issues.dayIssues(day.id).forEach { InlineMessage(planIssueText(it), tone = MessageTone.Error) }
    if (day.exercises.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.editor_day_empty_title),
            message = stringResource(Res.string.editor_day_empty_message),
        )
        return
    }

    val labels = remember(day.exercises) { day.supersetLabels() }
    val heights = remember(day.id) { mutableStateMapOf<String, Int>() }
    var draggingId by remember(day.id) { mutableStateOf<String?>(null) }
    var dragOffset by remember(day.id) { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { Spacing.m.toPx() }
    val currentExercises by rememberUpdatedState(day.exercises)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        day.exercises.forEachIndexed { index, planned ->
            key(planned.id) {
                val isDragging = draggingId == planned.id
                val last = day.exercises.lastIndex
                val move = { to: Int -> onAction(PlanDraftAction.MoveExercise(day.id, planned.id, to)) }
                val callbacks = PlannedExerciseCallbacks(
                    onMoveUp = if (index > 0) ({ move(index - 1) }) else null,
                    onMoveDown = if (index < last) ({ move(index + 1) }) else null,
                    onLinkWithNext = if (index < last && labels[planned.id].let { it == null || it != labels[day.exercises[index + 1].id] }) {
                        { onAction(PlanDraftAction.LinkWithNext(day.id, planned.id)) }
                    } else {
                        null
                    },
                    onUnlink = if (planned.supersetGroup != null) ({ onAction(PlanDraftAction.Unlink(day.id, planned.id)) }) else null,
                    onOpenSettings = { onOpenSettings(planned.id) },
                    onReplace = { onReplace(planned.id) },
                    onRemove = { onRemove(planned.id) },
                    onAddSet = { onAction(PlanDraftAction.AddSet(day.id, planned.id)) },
                    onRemoveSet = { setId -> onAction(PlanDraftAction.RemoveSet(day.id, planned.id, setId)) },
                    onCycleSetType = { setId -> onAction(PlanDraftAction.CycleSetType(day.id, planned.id, setId)) },
                    onSetValue = { setId, field, text -> onAction(PlanDraftAction.UpdateSetValue(day.id, planned.id, setId, field, text)) },
                )
                val dragHandle = Modifier.pointerInput(planned.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingId = planned.id
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        },
                        onDragEnd = {
                            val ids = currentExercises.map { it.id }
                            val from = ids.indexOf(planned.id)
                            if (from >= 0) {
                                val to = dropIndex(from, dragOffset, ids, heights, gapPx)
                                if (to != from) onAction(PlanDraftAction.MoveExercise(day.id, planned.id, to))
                            }
                            draggingId = null
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggingId = null
                            dragOffset = 0f
                        },
                    )
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[planned.id] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffset else 0f
                            shadowElevation = if (isDragging) 8f else 0f
                        },
                ) {
                    PlannedExerciseCard(
                        planned = planned,
                        exercise = state.exercises[planned.exerciseId],
                        languageTag = languageTag,
                        supersetLabel = labels[planned.id],
                        issues = issues,
                        callbacks = callbacks,
                        dragHandleModifier = dragHandle,
                    )
                }
            }
        }
    }
}

/** Where a card dragged by [offset] pixels from [from] lands: it passes a neighbour once it covers half of it. */
internal fun dropIndex(from: Int, offset: Float, ids: List<String>, heights: Map<String, Int>, gapPx: Float): Int {
    var target = from
    var remaining = offset
    if (offset > 0) {
        while (target < ids.lastIndex) {
            val next = (heights[ids[target + 1]] ?: 0) + gapPx
            if (remaining > next / 2) {
                remaining -= next
                target++
            } else {
                break
            }
        }
    } else {
        while (target > 0) {
            val previous = (heights[ids[target - 1]] ?: 0) + gapPx
            if (-remaining > previous / 2) {
                remaining += previous
                target--
            } else {
                break
            }
        }
    }
    return target
}

@Composable
private fun RenameDayDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_day_rename)) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it.take(PlanLimits.DAY_NAME_MAX) },
                label = stringResource(Res.string.editor_day_name_label),
            )
        },
        confirmButton = { AppTextButton(stringResource(DsRes.string.ds_done), onClick = { onConfirm(name.trim()) }) },
        dismissButton = { AppTextButton(stringResource(DsRes.string.ds_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = AppTheme.type.body) },
        confirmButton = { AppTextButton(confirmLabel, onClick = onConfirm, destructive = true) },
        dismissButton = { AppTextButton(stringResource(DsRes.string.ds_cancel), onClick = onDismiss) },
    )
}

/** A stale base version: the draft is kept, and the trainer chooses what happens next. */
@Composable
private fun ConflictDialog(latestVersion: Int, onSaveAsNew: () -> Unit, onLoadLatest: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_conflict_title)) },
        text = { Text(stringResource(Res.string.editor_conflict_message, latestVersion), style = AppTheme.type.body) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                AppTextButton(stringResource(Res.string.editor_conflict_save_as_new), onClick = onSaveAsNew)
                AppTextButton(stringResource(Res.string.editor_conflict_load_latest, latestVersion), onClick = onLoadLatest, destructive = true)
                AppTextButton(stringResource(DsRes.string.ds_cancel), onClick = onDismiss)
            }
        },
    )
}
