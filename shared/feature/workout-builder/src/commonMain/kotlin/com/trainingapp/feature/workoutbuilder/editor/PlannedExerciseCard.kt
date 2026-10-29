package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.SetField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.SetCell
import com.trainingapp.designsystem.component.SetRow
import com.trainingapp.designsystem.component.SetTableHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.plan.DraftExercise
import com.trainingapp.domain.plan.DraftSet
import com.trainingapp.domain.plan.DraftSetField
import com.trainingapp.domain.plan.PlanStatsCalculator
import com.trainingapp.feature.workoutbuilder.common.ExerciseImage
import com.trainingapp.feature.workoutbuilder.common.labelRes
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.creator_accessory
import com.trainingapp.feature.workoutbuilder.resources.creator_exercise_label
import com.trainingapp.feature.workoutbuilder.resources.creator_note
import com.trainingapp.feature.workoutbuilder.resources.creator_superset
import com.trainingapp.feature.workoutbuilder.resources.editor_drag_handle
import com.trainingapp.feature.workoutbuilder.resources.editor_exercise_options
import com.trainingapp.feature.workoutbuilder.resources.editor_link_next
import com.trainingapp.feature.workoutbuilder.resources.editor_move_down
import com.trainingapp.feature.workoutbuilder.resources.editor_move_up
import com.trainingapp.feature.workoutbuilder.resources.editor_remove
import com.trainingapp.feature.workoutbuilder.resources.editor_removed_from_library
import com.trainingapp.feature.workoutbuilder.resources.editor_replace
import com.trainingapp.feature.workoutbuilder.resources.editor_settings
import com.trainingapp.feature.workoutbuilder.resources.editor_superset
import com.trainingapp.feature.workoutbuilder.resources.editor_unknown_exercise
import com.trainingapp.feature.workoutbuilder.resources.editor_unlink
import com.trainingapp.feature.workoutbuilder.resources.set_add
import com.trainingapp.feature.workoutbuilder.resources.set_column_reps
import com.trainingapp.feature.workoutbuilder.resources.set_column_rest
import com.trainingapp.feature.workoutbuilder.resources.set_column_rpe
import com.trainingapp.feature.workoutbuilder.resources.set_column_set
import com.trainingapp.feature.workoutbuilder.resources.set_column_time
import com.trainingapp.feature.workoutbuilder.resources.set_column_weight
import com.trainingapp.feature.workoutbuilder.resources.set_column_km
import com.trainingapp.feature.workoutbuilder.resources.set_delete
import com.trainingapp.feature.workoutbuilder.resources.set_suffix_kg
import com.trainingapp.feature.workoutbuilder.resources.set_suffix_km
import com.trainingapp.feature.workoutbuilder.resources.set_suffix_reps
import com.trainingapp.feature.workoutbuilder.resources.set_suffix_seconds
import com.trainingapp.feature.workoutbuilder.resources.set_type_change
import com.trainingapp.feature.workoutbuilder.resources.set_type_drop
import com.trainingapp.feature.workoutbuilder.resources.set_type_drop_short
import com.trainingapp.feature.workoutbuilder.resources.set_type_warm_up
import com.trainingapp.feature.workoutbuilder.resources.set_type_warm_up_short
import com.trainingapp.feature.workoutbuilder.resources.set_type_working
import com.trainingapp.feature.workoutbuilder.resources.set_value_distance
import com.trainingapp.feature.workoutbuilder.resources.set_value_reps
import com.trainingapp.feature.workoutbuilder.resources.set_value_rest
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

/** One column of the set table. The reference shows CIĘŻAR · POWT. · RPE · PRZERWA for weight × reps. */
private data class SetColumn(
    val field: DraftSetField,
    val header: StringResource,
    val description: StringResource,
    val suffix: StringResource? = null,
    val prefix: String? = null,
    val accent: Boolean = false,
    val large: Boolean = false,
    val weight: Float,
)

private fun columnsFor(trackingType: TrackingType?): List<SetColumn> {
    val weight = SetColumn(DraftSetField.WEIGHT, Res.string.set_column_weight, Res.string.set_value_weight, Res.string.set_suffix_kg, large = true, weight = 1.35f)
    val reps = SetColumn(DraftSetField.REPS, Res.string.set_column_reps, Res.string.set_value_reps, Res.string.set_suffix_reps, large = true, weight = 1.35f)
    val time = SetColumn(DraftSetField.DURATION, Res.string.set_column_time, Res.string.set_value_time, large = true, weight = 1.35f)
    val distance = SetColumn(DraftSetField.DISTANCE, Res.string.set_column_km, Res.string.set_value_distance, Res.string.set_suffix_km, large = true, weight = 1.35f)
    val rpe = SetColumn(DraftSetField.RPE, Res.string.set_column_rpe, Res.string.set_value_rpe, prefix = "@", accent = true, weight = 1f)
    val rest = SetColumn(DraftSetField.REST, Res.string.set_column_rest, Res.string.set_value_rest, Res.string.set_suffix_seconds, weight = 1f)
    val values = when (trackingType ?: TrackingType.WEIGHT_REPS) {
        TrackingType.WEIGHT_REPS -> listOf(weight, reps)
        TrackingType.REPS -> listOf(reps)
        TrackingType.TIME -> listOf(time)
        TrackingType.DISTANCE_TIME -> listOf(distance, time)
    }
    return values + rpe + rest
}

private fun DraftSetField.toContractField() = when (this) {
    DraftSetField.REPS -> SetField.REPS
    DraftSetField.WEIGHT -> SetField.WEIGHT
    DraftSetField.DURATION -> SetField.DURATION
    DraftSetField.DISTANCE -> SetField.DISTANCE
    DraftSetField.RPE -> SetField.RPE
    DraftSetField.REST -> SetField.REST
}

private fun DraftSet.value(field: DraftSetField) = when (field) {
    DraftSetField.REPS -> reps
    DraftSetField.WEIGHT -> weight
    DraftSetField.DURATION -> duration
    DraftSetField.DISTANCE -> distance
    DraftSetField.RPE -> rpe
    DraftSetField.REST -> rest
}

/**
 * An exercise card of the plan creator: the content of the Stitch "Kreator planu" card, drawn as the flat outlined
 * frame used by the trainer calendar/clients branch.
 */
@Composable
fun PlannedExerciseCard(
    position: Int,
    planned: DraftExercise,
    exercise: Exercise?,
    languageTag: String,
    supersetLabel: String?,
    showSupersetLetter: Boolean,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val name = exercise?.name(languageTag) ?: stringResource(Res.string.editor_unknown_exercise)
    Box(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.tile)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.outline), AppShapes.tile),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ExerciseHeader(position, planned, exercise, name, supersetLabel, showSupersetLetter, callbacks, dragHandleModifier)
            SetTable(planned, exercise?.trackingType, issues, callbacks)
            issues.exerciseIssues(planned.id).map { planIssueText(it) }.distinct().forEach { message ->
                Text(message, style = AppTheme.type.compactBody, color = colors.onErrorContainer)
            }
            CardFooter(name, callbacks)
        }
    }
}

@Composable
private fun ExerciseHeader(
    position: Int,
    planned: DraftExercise,
    exercise: Exercise?,
    name: String,
    supersetLabel: String?,
    showSupersetLetter: Boolean,
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
                .size(width = 32.dp, height = Sizes.minTouchTarget)
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
        ExerciseImage(exercise, size = Sizes.minTouchTarget, shape = CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val muscle = exercise?.primaryMuscle?.let { stringResource(it.labelRes) }.orEmpty()
            Text(
                stringResource(Res.string.creator_exercise_label, position, muscle),
                style = AppTheme.type.label,
                color = colors.brandStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(name, style = AppTheme.type.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (exercise?.isHidden == true) {
                StatusChip(stringResource(Res.string.editor_removed_from_library))
            }
        }
        when {
            supersetLabel != null -> StatusChip(
                if (showSupersetLetter) stringResource(Res.string.editor_superset, supersetLabel) else stringResource(Res.string.creator_superset),
                tone = StatusTone.Accent,
            )
            planned.accessory -> StatusChip(stringResource(Res.string.creator_accessory))
        }
    }
}

@Composable
private fun SetTable(
    planned: DraftExercise,
    trackingType: TrackingType?,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
) {
    val columns = columnsFor(trackingType)
    val defaultRest = "${planned.restSeconds ?: PlanStatsCalculator.DEFAULT_REST_SECONDS}"
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SetTableHeader(
            setColumnLabel = stringResource(Res.string.set_column_set),
            valueColumnLabels = columns.map { stringResource(it.header) to it.weight },
        )
        var workingNumber = 0
        planned.sets.forEach { set ->
            if (set.type == SetType.WORKING) workingNumber++
            val number = workingNumber
            key(set.id) {
                PlannedSetRow(set, number, columns, defaultRest, issues, callbacks)
            }
        }
    }
}

@Composable
private fun PlannedSetRow(
    set: DraftSet,
    workingNumber: Int,
    columns: List<SetColumn>,
    defaultRest: String,
    issues: EditorIssues,
    callbacks: PlannedExerciseCallbacks,
) {
    val colors = AppTheme.colors
    val (label, description) = when (set.type) {
        SetType.WORKING -> "$workingNumber" to stringResource(Res.string.set_type_working, workingNumber)
        SetType.WARM_UP -> stringResource(Res.string.set_type_warm_up_short) to stringResource(Res.string.set_type_warm_up)
        SetType.DROP -> stringResource(Res.string.set_type_drop_short) to stringResource(Res.string.set_type_drop)
    }
    val deleteLabel = stringResource(Res.string.set_delete)
    // Swipe left deletes the set; screen readers get the same action.
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) callbacks.onRemoveSet(set.id)
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { callbacks.onRemoveSet(set.id); true })
        },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.errorContainer)
                    .padding(horizontal = Spacing.l),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = colors.onErrorContainer)
            }
        },
    ) {
        SetRow(
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
                    placeholder = if (column.field == DraftSetField.REST) defaultRest else "",
                    prefix = column.prefix,
                    suffix = column.suffix?.let { stringResource(it) },
                    accent = column.accent,
                    large = column.large,
                    isError = issues.setFieldHasIssue(set.id, column.field.toContractField()),
                    weight = column.weight,
                )
            },
        )
    }
}

@Composable
private fun CardFooter(name: String, callbacks: PlannedExerciseCallbacks) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppTextButton(stringResource(Res.string.set_add), onClick = callbacks.onAddSet, icon = Icons.Outlined.AddCircleOutline)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = callbacks.onOpenSettings) {
            Icon(Icons.Outlined.Description, stringResource(Res.string.creator_note, name), tint = AppTheme.colors.textSecondary)
        }
        ExerciseMenu(name, callbacks)
    }
}

@Composable
private fun ExerciseMenu(name: String, callbacks: PlannedExerciseCallbacks) {
    OverflowMenu(
        contentDescription = stringResource(Res.string.editor_exercise_options, name),
        actions = listOfNotNull(
            callbacks.onMoveUp?.let { MenuAction(stringResource(Res.string.editor_move_up), Icons.Outlined.ArrowUpward, onClick = it) },
            callbacks.onMoveDown?.let { MenuAction(stringResource(Res.string.editor_move_down), Icons.Outlined.ArrowDownward, onClick = it) },
            callbacks.onLinkWithNext?.let { MenuAction(stringResource(Res.string.editor_link_next), Icons.Outlined.Link, onClick = it) },
            callbacks.onUnlink?.let { MenuAction(stringResource(Res.string.editor_unlink), Icons.Outlined.LinkOff, onClick = it) },
            MenuAction(stringResource(Res.string.editor_settings), Icons.Outlined.Tune, onClick = callbacks.onOpenSettings),
            MenuAction(stringResource(Res.string.editor_replace), Icons.Outlined.SwapHoriz, onClick = callbacks.onReplace),
            MenuAction(stringResource(Res.string.editor_remove), Icons.Outlined.DeleteOutline, destructive = true, onClick = callbacks.onRemove),
        ),
    )
}
