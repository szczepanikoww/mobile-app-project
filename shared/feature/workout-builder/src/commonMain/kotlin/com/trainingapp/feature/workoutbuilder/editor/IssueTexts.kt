package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.runtime.Composable
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.contract.plan.PlanLimits
import com.trainingapp.contract.plan.SetField
import com.trainingapp.contract.plan.SetProblem
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.editor_day_empty_error
import com.trainingapp.feature.workoutbuilder.resources.editor_day_name_too_long
import com.trainingapp.feature.workoutbuilder.resources.editor_name_blank
import com.trainingapp.feature.workoutbuilder.resources.editor_name_too_long
import com.trainingapp.feature.workoutbuilder.resources.exercise_error_no_sets
import com.trainingapp.feature.workoutbuilder.resources.exercise_error_settings
import com.trainingapp.feature.workoutbuilder.resources.exercise_error_unknown
import com.trainingapp.feature.workoutbuilder.resources.set_error_distance_invalid
import com.trainingapp.feature.workoutbuilder.resources.set_error_distance_required
import com.trainingapp.feature.workoutbuilder.resources.set_error_reps_invalid
import com.trainingapp.feature.workoutbuilder.resources.set_error_reps_range
import com.trainingapp.feature.workoutbuilder.resources.set_error_reps_required
import com.trainingapp.feature.workoutbuilder.resources.set_error_rpe
import com.trainingapp.feature.workoutbuilder.resources.set_error_time_invalid
import com.trainingapp.feature.workoutbuilder.resources.set_error_time_required
import com.trainingapp.feature.workoutbuilder.resources.set_error_weight_invalid
import com.trainingapp.feature.workoutbuilder.resources.set_error_weight_range
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Issues grouped by where the editor shows them. */
class EditorIssues(issues: List<PlanIssue>) {
    val name: PlanIssue? = issues.firstOrNull { it is PlanIssue.NameBlank || it is PlanIssue.NameTooLong }
    private val byDay = issues.groupBy { it.dayId }
    private val byExercise = issues.groupBy { it.plannedExerciseId }

    fun dayHasIssues(dayId: String): Boolean = byDay[dayId].orEmpty().isNotEmpty()

    fun dayIssues(dayId: String): List<PlanIssue> =
        byDay[dayId].orEmpty().filter { it is PlanIssue.DayHasNoExercises || it is PlanIssue.DayNameTooLong }

    fun exerciseIssues(plannedExerciseId: String): List<PlanIssue> = byExercise[plannedExerciseId].orEmpty()

    fun setFieldHasIssue(setId: String, field: SetField): Boolean =
        byExercise.values.any { list -> list.any { it is PlanIssue.SetValueInvalid && it.setId == setId && it.field == field } }

    private val PlanIssue.dayId: String?
        get() = when (this) {
            is PlanIssue.DayHasNoExercises -> dayId
            is PlanIssue.DayNameTooLong -> dayId
            is PlanIssue.UnknownExercise -> dayId
            is PlanIssue.ExerciseHasNoSets -> dayId
            is PlanIssue.ExerciseSettingInvalid -> dayId
            is PlanIssue.SetValueInvalid -> dayId
            else -> null
        }

    private val PlanIssue.plannedExerciseId: String?
        get() = when (this) {
            is PlanIssue.UnknownExercise -> plannedExerciseId
            is PlanIssue.ExerciseHasNoSets -> plannedExerciseId
            is PlanIssue.ExerciseSettingInvalid -> plannedExerciseId
            is PlanIssue.SetValueInvalid -> plannedExerciseId
            else -> null
        }
}

@Composable
fun planIssueText(issue: PlanIssue): String = when (issue) {
    PlanIssue.NameBlank -> stringResource(Res.string.editor_name_blank)
    PlanIssue.NameTooLong -> stringResource(Res.string.editor_name_too_long, PlanLimits.NAME_MAX)
    PlanIssue.NoDays, is PlanIssue.DayHasNoExercises -> stringResource(Res.string.editor_day_empty_error)
    is PlanIssue.DayNameTooLong -> stringResource(Res.string.editor_day_name_too_long, PlanLimits.DAY_NAME_MAX)
    is PlanIssue.UnknownExercise -> stringResource(Res.string.exercise_error_unknown)
    is PlanIssue.ExerciseHasNoSets -> stringResource(Res.string.exercise_error_no_sets)
    is PlanIssue.ExerciseSettingInvalid -> stringResource(Res.string.exercise_error_settings)
    is PlanIssue.SetValueInvalid -> stringResource(setProblemText(issue.field, issue.problem))
}

private fun setProblemText(field: SetField, problem: SetProblem): StringResource = when (field) {
    SetField.REPS -> when (problem) {
        SetProblem.REQUIRED -> Res.string.set_error_reps_required
        SetProblem.INVALID -> Res.string.set_error_reps_invalid
        SetProblem.OUT_OF_RANGE -> Res.string.set_error_reps_range
    }
    SetField.WEIGHT -> if (problem == SetProblem.OUT_OF_RANGE) Res.string.set_error_weight_range else Res.string.set_error_weight_invalid
    SetField.RPE -> Res.string.set_error_rpe
    SetField.DURATION -> if (problem == SetProblem.REQUIRED) Res.string.set_error_time_required else Res.string.set_error_time_invalid
    SetField.DISTANCE -> if (problem == SetProblem.REQUIRED) Res.string.set_error_distance_required else Res.string.set_error_distance_invalid
}
