package com.trainingapp.feature.workoutbuilder.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseField
import com.trainingapp.contract.exercise.ExerciseIssue
import com.trainingapp.contract.exercise.ExerciseLimits
import com.trainingapp.contract.exercise.ExerciseProblem
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.PillTextField
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MultiChoiceSheet
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SelectorField
import com.trainingapp.designsystem.component.SheetOption
import com.trainingapp.designsystem.component.SingleChoiceSheet
import com.trainingapp.designsystem.component.TopBarNavigation
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.feature.workoutbuilder.common.ExerciseFormDestination
import com.trainingapp.feature.workoutbuilder.common.labelRes
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.form_create
import com.trainingapp.feature.workoutbuilder.resources.form_equipment
import com.trainingapp.feature.workoutbuilder.resources.form_error_instructions_long
import com.trainingapp.feature.workoutbuilder.resources.form_error_name_long
import com.trainingapp.feature.workoutbuilder.resources.form_error_name_short
import com.trainingapp.feature.workoutbuilder.resources.form_error_required
import com.trainingapp.feature.workoutbuilder.resources.form_error_secondary_many
import com.trainingapp.feature.workoutbuilder.resources.form_error_secondary_same
import com.trainingapp.feature.workoutbuilder.resources.form_instructions
import com.trainingapp.feature.workoutbuilder.resources.form_instructions_supporting
import com.trainingapp.feature.workoutbuilder.resources.form_more
import com.trainingapp.feature.workoutbuilder.resources.form_name
import com.trainingapp.feature.workoutbuilder.resources.form_name_placeholder
import com.trainingapp.feature.workoutbuilder.resources.form_not_found
import com.trainingapp.feature.workoutbuilder.resources.form_not_owner
import com.trainingapp.feature.workoutbuilder.resources.form_primary_muscle
import com.trainingapp.feature.workoutbuilder.resources.form_save
import com.trainingapp.feature.workoutbuilder.resources.form_secondary_muscles
import com.trainingapp.feature.workoutbuilder.resources.form_secondary_none
import com.trainingapp.feature.workoutbuilder.resources.form_select
import com.trainingapp.feature.workoutbuilder.resources.form_similar
import com.trainingapp.feature.workoutbuilder.resources.form_title_edit
import com.trainingapp.feature.workoutbuilder.resources.form_title_new
import com.trainingapp.feature.workoutbuilder.resources.form_tracking
import com.trainingapp.feature.workoutbuilder.resources.form_tracking_locked
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExerciseFormScreen(
    destination: ExerciseFormDestination,
    onClose: () -> Unit,
    onSaved: (exerciseId: String) -> Unit,
    onOpenExercise: (exerciseId: String) -> Unit,
    viewModel: ExerciseFormViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ExerciseFormEffect.Saved -> currentOnSaved(effect.exerciseId)
            }
        }
    }
    ExerciseFormContent(
        state = state,
        languageTag = language.current(),
        onClose = onClose,
        onNameChange = viewModel::onNameChange,
        onPrimaryMuscleChange = viewModel::onPrimaryMuscleChange,
        onEquipmentChange = viewModel::onEquipmentChange,
        onTrackingTypeChange = viewModel::onTrackingTypeChange,
        onSecondaryMusclesChange = viewModel::onSecondaryMusclesChange,
        onInstructionsChange = viewModel::onInstructionsChange,
        onToggleMoreDetails = viewModel::toggleMoreDetails,
        onOpenExercise = onOpenExercise,
        onSubmit = viewModel::submit,
    )
}

private enum class FormSheet { PrimaryMuscle, Equipment, SecondaryMuscles }

@Composable
fun ExerciseFormContent(
    state: ExerciseFormUiState,
    languageTag: String,
    onClose: () -> Unit,
    onNameChange: (String) -> Unit,
    onPrimaryMuscleChange: (MuscleGroup) -> Unit,
    onEquipmentChange: (Equipment) -> Unit,
    onTrackingTypeChange: (TrackingType) -> Unit,
    onSecondaryMusclesChange: (Set<MuscleGroup>) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onToggleMoreDetails: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf<FormSheet?>(null) }
    val blocked = state.notFound || state.notOwner
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            DetailHeader(
                title = stringResource(if (state.isEdit) Res.string.form_title_edit else Res.string.form_title_new),
                onBack = onClose,
            )
        },
        bottomBar = {
            if (!state.loading && !blocked) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(if (state.isEdit) Res.string.form_save else Res.string.form_create),
                        onClick = onSubmit,
                        loading = state.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    )
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound -> ErrorState(stringResource(Res.string.form_not_found), Modifier.padding(padding))
            state.notOwner -> ErrorState(stringResource(Res.string.form_not_owner), Modifier.padding(padding))
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PillTextField(
                        value = state.name,
                        onValueChange = onNameChange,
                        label = stringResource(Res.string.form_name),
                        placeholder = stringResource(Res.string.form_name_placeholder),
                        icon = Icons.Outlined.FitnessCenter,
                        error = state.issues.messageFor(ExerciseField.NAME),
                    )
                    state.similar?.let { similar ->
                        // A soft warning only: it never blocks saving.
                        Text(
                            stringResource(Res.string.form_similar, similar.name(languageTag)),
                            style = AppTheme.type.compactBody,
                            color = AppTheme.colors.onWarningContainer,
                            modifier = Modifier
                                .heightIn(min = Sizes.minTouchTarget)
                                .clickable(role = Role.Button) { onOpenExercise(similar.id) }
                                .padding(vertical = Spacing.m),
                        )
                    }
                }
                SelectorField(
                    label = stringResource(Res.string.form_primary_muscle),
                    value = state.primaryMuscle?.let { stringResource(it.labelRes) },
                    placeholder = stringResource(Res.string.form_select),
                    onClick = { sheet = FormSheet.PrimaryMuscle },
                    icon = Icons.Outlined.AccessibilityNew,
                    error = state.issues.messageFor(ExerciseField.PRIMARY_MUSCLE),
                )
                SelectorField(
                    label = stringResource(Res.string.form_equipment),
                    value = state.equipment?.let { stringResource(it.labelRes) },
                    placeholder = stringResource(Res.string.form_select),
                    onClick = { sheet = FormSheet.Equipment },
                    icon = Icons.Outlined.FitnessCenter,
                    error = state.issues.messageFor(ExerciseField.EQUIPMENT),
                )
                TrackingTypeChoice(
                    selected = state.trackingType,
                    locked = state.trackingTypeLocked,
                    onSelect = onTrackingTypeChange,
                )
                if (state.trackingLockedAttempt) {
                    InlineMessage(stringResource(Res.string.form_tracking_locked), tone = MessageTone.Error)
                }

                MoreDetailsToggle(expanded = state.moreDetailsExpanded, onToggle = onToggleMoreDetails)
                if (state.moreDetailsExpanded) {
                    SelectorField(
                        label = stringResource(Res.string.form_secondary_muscles),
                        value = state.secondaryMuscles.takeIf { it.isNotEmpty() }?.map { stringResource(it.labelRes) }?.joinToString(),
                        placeholder = stringResource(Res.string.form_secondary_none),
                        onClick = { sheet = FormSheet.SecondaryMuscles },
                        icon = Icons.Outlined.AccessibilityNew,
                        error = state.issues.messageFor(ExerciseField.SECONDARY_MUSCLES),
                    )
                    AppTextField(
                        value = state.instructions,
                        onValueChange = onInstructionsChange,
                        label = stringResource(Res.string.form_instructions),
                        supportingText = stringResource(Res.string.form_instructions_supporting),
                        error = state.issues.messageFor(ExerciseField.INSTRUCTIONS),
                        singleLine = false,
                        minLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
        }
    }

    val muscleOptions = MuscleGroup.entries.map { SheetOption(it, stringResource(it.labelRes)) }.sortedBy { it.label }
    when (sheet) {
        FormSheet.PrimaryMuscle -> SingleChoiceSheet(
            title = stringResource(Res.string.form_primary_muscle),
            options = muscleOptions,
            selected = state.primaryMuscle,
            onSelect = { onPrimaryMuscleChange(it); sheet = null },
            onDismiss = { sheet = null },
        )
        FormSheet.Equipment -> SingleChoiceSheet(
            title = stringResource(Res.string.form_equipment),
            options = Equipment.entries.map { SheetOption(it, stringResource(it.labelRes)) },
            selected = state.equipment,
            onSelect = { onEquipmentChange(it); sheet = null },
            onDismiss = { sheet = null },
        )
        FormSheet.SecondaryMuscles -> MultiChoiceSheet(
            title = stringResource(Res.string.form_secondary_muscles),
            options = muscleOptions.filter { it.value != state.primaryMuscle },
            selected = state.secondaryMuscles.toSet(),
            maxSelected = ExerciseLimits.SECONDARY_MUSCLES_MAX,
            onApply = { onSecondaryMusclesChange(it); sheet = null },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** The four tracking types as a segmented choice that wraps on small screens and large fonts. */
@Composable
private fun TrackingTypeChoice(selected: TrackingType, locked: Boolean, onSelect: (TrackingType) -> Unit) {
    val lockedText = stringResource(Res.string.form_tracking_locked)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(Res.string.form_tracking), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            TrackingType.entries.forEach { type ->
                val isSelected = type == selected
                AppFilterChip(
                    label = stringResource(type.labelRes),
                    selected = isSelected,
                    onClick = { onSelect(type) },
                    leadingIcon = if (locked && isSelected) Icons.Outlined.Lock else null,
                    modifier = Modifier.semantics { if (locked) stateDescription = lockedText },
                )
            }
        }
        if (locked) Text(lockedText, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
    }
}

@Composable
private fun MoreDetailsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.minTouchTarget)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = AppTheme.colors.brandStrong)
        Text(stringResource(Res.string.form_more), style = AppTheme.type.bodyStrong, color = AppTheme.colors.brandStrong)
    }
}

@Composable
private fun List<ExerciseIssue>.messageFor(field: ExerciseField): String? {
    val issue = firstOrNull { it.field == field } ?: return null
    return when (issue.problem) {
        ExerciseProblem.REQUIRED -> stringResource(Res.string.form_error_required)
        ExerciseProblem.TOO_SHORT -> stringResource(Res.string.form_error_name_short, ExerciseLimits.NAME_MIN)
        ExerciseProblem.TOO_LONG -> if (field == ExerciseField.INSTRUCTIONS) {
            stringResource(Res.string.form_error_instructions_long, ExerciseLimits.INSTRUCTIONS_MAX)
        } else {
            stringResource(Res.string.form_error_name_long, ExerciseLimits.NAME_MAX)
        }
        ExerciseProblem.TOO_MANY -> stringResource(Res.string.form_error_secondary_many, ExerciseLimits.SECONDARY_MUSCLES_MAX)
        ExerciseProblem.INVALID -> stringResource(Res.string.form_error_secondary_same)
    }
}
