package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.SetField
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.ExerciseThumbnail
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.SetCell
import com.trainingapp.designsystem.component.SetRow
import com.trainingapp.designsystem.component.SetTableHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.plan.DraftExercise
import com.trainingapp.domain.plan.DraftSet
import com.trainingapp.domain.plan.DraftSetField
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.feature.workoutbuilder.common.labelRes
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.editor_drag_handle
import com.trainingapp.feature.workoutbuilder.resources.editor_exercise_options
import com.trainingapp.feature.workoutbuilder.resources.editor_link_next
import com.trainingapp.feature.workoutbuilder.resources.editor_move_down
import com.trainingapp.feature.workoutbuilder.resources.editor_move_up
import com.trainingapp.feature.workoutbuilder.resources.editor_note
import com.trainingapp.feature.workoutbuilder.resources.editor_remove
import com.trainingapp.feature.workoutbuilder.resources.editor_removed_from_library
import com.trainingapp.feature.workoutbuilder.resources.editor_replace
import com.trainingapp.feature.workoutbuilder.resources.editor_rest
import com.trainingapp.feature.workoutbuilder.resources.editor_settings
import com.trainingapp.feature.workoutbuilder.resources.editor_superset
import com.trainingapp.feature.workoutbuilder.resources.editor_unknown_exercise
import com.trainingapp.feature.workoutbuilder.resources.editor_unlink
import com.trainingapp.feature.workoutbuilder.resources.set_add
import com.trainingapp.feature.workoutbuilder.resources.set_column_kg
import com.trainingapp.feature.workoutbuilder.resources.set_column_km
import com.trainingapp.feature.workoutbuilder.resources.set_column_reps
import com.trainingapp.feature.workoutbuilder.resources.set_column_rpe
import com.trainingapp.feature.workoutbuilder.resources.set_column_set
import com.trainingapp.feature.workoutbuilder.resources.set_column_time
import com.trainingapp.feature.workoutbuilder.resources.set_delete
import com.trainingapp.feature.workoutbuilder.resources.set_options
import com.trainingapp.feature.workoutbuilder.resources.set_type_change
import com.trainingapp.feature.workoutbuilder.resources.set_type_drop
import com.trainingapp.feature.workoutbuilder.resources.set_type_drop_short
import com.trainingapp.feature.workoutbuilder.resources.set_type_warm_up
import com.trainingapp.feature.workoutbuilder.resources.set_type_warm_up_short
import com.trainingapp.feature.workoutbuilder.resources.set_type_working
import com.trainingapp.feature.workoutbuilder.resources.set_value_distance
import com.trainingapp.feature.workoutbuilder.resources.set_value_reps
import com.trainingapp.feature.workoutbuilder.resources.set_value_rpe
import com.trainingapp.feature.workoutbuilder.resources.set_value_time
import com.trainingapp.feature.workoutbuilder.resources.set_value_weight
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Callbacks for one planned exercise; the day and planned-exercise ids are already bound by the caller. */
class PlannedExerciseCallbacks(
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val onLinkWithNext: (() -> Unit)?,
    val onUnlink: (() -> Unit)?,
    val onOpenSettings: () -> Unit,
    val onReplace: () -> Unit,
    val onRemove: () -> Unit,
    val onAddSet: () -> Unit,
    val onRemoveSet: (setId: String) -> Unit,
    val onCycleSetType: (setId: String) -> Unit,
    val onSetValue: (setId: String, field: DraftSetField, text: String) -> Unit,
)

/** The columns a set row has, following the exercise's tracking type (plus RPE when enabled). */
private data class SetColumn(val field: DraftSetField, val header: StringResource, val description: StringResource, val keyboard: KeyboardType)

private fun columnsFor(trackingType: TrackingType?, rpeEnabled: Boolean): List<SetColumn> {
    val reps = SetColumn(DraftSetField.REPS, Res.string.set_column_reps, Res.string.set_value_reps, KeyboardType.Decimal)
    val weight = SetColumn(DraftSetField.WEIGHT, Res.string.set_column_kg, Res.string.set_value_weight, KeyboardType.Decimal)
    val time = SetColumn(DraftSetField.DURATION, Res.string.set_column_time, Res.string.set_value_time, KeyboardType.Decimal)
    val distance = SetColumn(DraftSetField.DISTANCE, Res.string.set_column_km, Res.string.set_value_distance, KeyboardType.Decimal)
    val base = when (trackingType ?: TrackingType.WEIGHT_REPS) {
        TrackingType.WEIGHT_REPS -> listOf(weight, reps)
        TrackingType.REPS -> listOf(reps)
        TrackingType.TIME -> listOf(time)
        TrackingType.DISTANCE_TIME -> listOf(distance, time)
    }
    return if (rpeEnabled) base + SetColumn(DraftSetField.RPE, Res.string.set_column_rpe, Res.string.set_value_rpe, KeyboardType.Decimal) else base
}

private fun DraftSetField.toContractField() = when (this) {
    DraftSetField.REPS -> SetField.REPS
    DraftSetField.WEIGHT -> SetField.WEIGHT
    DraftSetField.DURATION -> SetField.DURATION
    DraftSetField.DISTANCE -> SetField.DISTANCE
    DraftSetField.RPE -> SetField.RPE
}

private fun DraftSet.value(field: DraftSetField) = when (field) {
    DraftSetField.REPS -> reps
    DraftSetField.WEIGHT -> weight
    DraftSetField.DURATION -> duration
    DraftSetField.DISTANCE -> distance
    DraftSetField.RPE -> rpe
}

@Composable
fun PlannedExerciseCard(
    planned: DraftExercise,
    exercise: Exercise?,
    languageTag: String,
    supersetLabel: String?,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val name = exercise?.name(languageTag) ?: stringResource(Res.string.editor_unknown_exercise)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (supersetLabel != null) {
                // The connector: linked exercises share an accent edge and the "Superseria A" label.
                Box(Modifier.width(4.dp).fillMaxHeight().background(colors.brandAccent))
            }
            Column(Modifier.padding(start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.s)) {
                ExerciseHeader(planned, exercise, name, supersetLabel, callbacks, dragHandleModifier)
                planned.note.takeIf { it.isNotBlank() }?.let { note ->
                    Row(
                        Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.editor_note, note), style = AppTheme.type.compactBody, color = colors.textSecondary)
                    }
                }
                SetTable(planned, exercise?.trackingType, issues, callbacks)
                issues.exerciseIssues(planned.id).map { planIssueText(it) }.distinct().forEach { message ->
                    Text(
                        message,
                        style = AppTheme.type.compactBody,
                        color = colors.onErrorContainer,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                }
                AppTextButton(text = stringResource(Res.string.set_add), onClick = callbacks.onAddSet, icon = Icons.Outlined.Add)
            }
        }
    }
}

@Composable
private fun ExerciseHeader(
    planned: DraftExercise,
    exercise: Exercise?,
    name: String,
    supersetLabel: String?,
    callbacks: PlannedExerciseCallbacks,
    dragHandleModifier: Modifier,
) {
    val colors = AppTheme.colors
    val moveUp = stringResource(Res.string.editor_move_up)
    val moveDown = stringResource(Res.string.editor_move_down)
    val handleDescription = stringResource(Res.string.editor_drag_handle, name)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Box(
            dragHandleModifier
                .size(Sizes.minTouchTarget)
                .semantics {
                    contentDescription = handleDescription
                    customActions = listOfNotNull(
                        callbacks.onMoveUp?.let { action -> CustomAccessibilityAction(moveUp) { action(); true } },
                        callbacks.onMoveDown?.let { action -> CustomAccessibilityAction(moveDown) { action(); true } },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = colors.textSecondary)
        }
        ExerciseThumbnail(size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (supersetLabel != null) {
                Text(stringResource(Res.string.editor_superset, supersetLabel), style = AppTheme.type.label, color = colors.brandStrong)
            }
            Text(name, style = AppTheme.type.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                exercise?.primaryMuscle?.let { stringResource(it.labelRes) },
                planned.restSeconds?.let { stringResource(Res.string.editor_rest, SetInput.formatDuration(it)) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = AppTheme.type.compactBody, color = colors.textSecondary)
            if (exercise?.isHidden == true) {
                StatusChip(stringResource(Res.string.editor_removed_from_library), tone = StatusTone.Neutral)
            }
        }
        OverflowMenu(
            contentDescription = stringResource(Res.string.editor_exercise_options, name),
            actions = listOfNotNull(
                callbacks.onMoveUp?.let { MenuAction(moveUp, Icons.Outlined.ArrowUpward, onClick = it) },
                callbacks.onMoveDown?.let { MenuAction(moveDown, Icons.Outlined.ArrowDownward, onClick = it) },
                callbacks.onLinkWithNext?.let { MenuAction(stringResource(Res.string.editor_link_next), Icons.Outlined.Link, onClick = it) },
                callbacks.onUnlink?.let { MenuAction(stringResource(Res.string.editor_unlink), Icons.Outlined.LinkOff, onClick = it) },
                MenuAction(stringResource(Res.string.editor_settings), Icons.Outlined.Tune, onClick = callbacks.onOpenSettings),
                MenuAction(stringResource(Res.string.editor_replace), Icons.Outlined.SwapHoriz, onClick = callbacks.onReplace),
                MenuAction(stringResource(Res.string.editor_remove), Icons.Outlined.DeleteOutline, destructive = true, onClick = callbacks.onRemove),
            ),
        )
    }
}

@Composable
private fun SetTable(
    planned: DraftExercise,
    trackingType: TrackingType?,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
) {
    val columns = columnsFor(trackingType, planned.rpeEnabled)
    Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SetTableHeader(
            setColumnLabel = stringResource(Res.string.set_column_set),
            valueColumnLabels = columns.map { stringResource(it.header) },
            modifier = Modifier.padding(end = Sizes.minTouchTarget + Spacing.s),
        )
        var workingNumber = 0
        planned.sets.forEach { set ->
            if (set.type == SetType.WORKING) workingNumber++
            val number = workingNumber
            key(set.id) {
                PlannedSetRow(set, number, columns, issues, callbacks)
            }
        }
    }
}

@Composable
private fun PlannedSetRow(
    set: DraftSet,
    workingNumber: Int,
    columns: List<SetColumn>,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
) {
    val colors = AppTheme.colors
    val (label, description) = when (set.type) {
        SetType.WORKING -> "$workingNumber" to stringResource(Res.string.set_type_working, workingNumber)
        SetType.WARM_UP -> stringResource(Res.string.set_type_warm_up_short) to stringResource(Res.string.set_type_warm_up)
        SetType.DROP -> stringResource(Res.string.set_type_drop_short) to stringResource(Res.string.set_type_drop)
    }
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) callbacks.onRemoveSet(set.id)
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.errorContainer, AppShapes.input)
                    .padding(horizontal = Spacing.l),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = colors.onErrorContainer)
            }
        },
    ) {
        SetRow(
            modifier = Modifier.background(colors.surface),
            setLabel = label,
            setLabelDescription = description,
            highlightLabel = set.type != SetType.WORKING,
            onSetLabelClick = { callbacks.onCycleSetType(set.id) },
            onSetLabelClickLabel = stringResource(Res.string.set_type_change),
            cells = columns.map { column ->
                SetCell(
                    value = set.value(column.field),
                    onValueChange = { callbacks.onSetValue(set.id, column.field, it) },
                    contentDescription = stringResource(column.description, description),
                    keyboardType = column.keyboard,
                    isError = issues.setFieldHasIssue(set.id, column.field.toContractField()),
                )
            },
            trailing = {
                OverflowMenu(
                    contentDescription = stringResource(Res.string.set_options, description),
                    actions = listOf(
                        MenuAction(stringResource(Res.string.set_type_change), onClick = { callbacks.onCycleSetType(set.id) }),
                        MenuAction(
                            stringResource(Res.string.set_delete),
                            Icons.Outlined.DeleteOutline,
                            destructive = true,
                            onClick = { callbacks.onRemoveSet(set.id) },
                        ),
                    ),
                )
            },
        )
    }
}
