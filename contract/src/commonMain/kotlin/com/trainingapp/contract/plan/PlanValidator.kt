package com.trainingapp.contract.plan

import com.trainingapp.contract.exercise.TrackingType

object PlanLimits {
    const val NAME_MAX = 80
    const val DAY_NAME_MAX = 40
    val REPS = 1..100
    val WEIGHT_GRAMS = 0..1_000_000
    const val RPE_MIN = 1.0
    const val RPE_MAX = 10.0
    val DURATION_SECONDS = 1..36_000
    val DISTANCE_METERS = 1..1_000_000
    val REST_SECONDS = 0..3_600
    const val TEMPO_MAX = 20
    const val NOTE_MAX = 500
}

enum class SetField { REPS, WEIGHT, DURATION, DISTANCE, RPE }

enum class SetProblem {
    /** The value the tracking type needs is missing. */
    REQUIRED,

    /** The value cannot be read, e.g. "8–" or a reps range with min > max. */
    INVALID,
    OUT_OF_RANGE,
}

enum class ExerciseSettingField { REST, TEMPO, NOTE }

/** A problem found by [PlanValidator]. Ids point to the element in the plan body, so the UI can show it inline. */
sealed interface PlanIssue {
    data object NameBlank : PlanIssue
    data object NameTooLong : PlanIssue
    data object NoDays : PlanIssue
    data class DayNameTooLong(val dayId: String) : PlanIssue
    data class DayHasNoExercises(val dayId: String) : PlanIssue
    data class UnknownExercise(val dayId: String, val plannedExerciseId: String) : PlanIssue
    data class ExerciseHasNoSets(val dayId: String, val plannedExerciseId: String) : PlanIssue
    data class ExerciseSettingInvalid(
        val dayId: String,
        val plannedExerciseId: String,
        val field: ExerciseSettingField,
    ) : PlanIssue

    data class SetValueInvalid(
        val dayId: String,
        val plannedExerciseId: String,
        val setId: String,
        val field: SetField,
        val problem: SetProblem,
    ) : PlanIssue
}

/**
 * Validates a plan body. The app and the server run the same rules, so both reject the same input.
 *
 * Which set values are required depends on the exercise's [TrackingType]:
 * reps for [TrackingType.WEIGHT_REPS] and [TrackingType.REPS], duration for [TrackingType.TIME],
 * distance for [TrackingType.DISTANCE_TIME]. Weight and RPE are always optional.
 */
object PlanValidator {

    fun validate(body: PlanBodyDto, trackingTypeOf: (exerciseId: String) -> TrackingType?): List<PlanIssue> {
        val issues = mutableListOf<PlanIssue>()
        val name = body.name.trim()
        when {
            name.isEmpty() -> issues += PlanIssue.NameBlank
            name.length > PlanLimits.NAME_MAX -> issues += PlanIssue.NameTooLong
        }
        if (body.days.isEmpty()) issues += PlanIssue.NoDays

        for (day in body.days) {
            if (day.name.trim().length > PlanLimits.DAY_NAME_MAX) issues += PlanIssue.DayNameTooLong(day.id)
            if (day.exercises.isEmpty()) issues += PlanIssue.DayHasNoExercises(day.id)
            for (exercise in day.exercises) {
                validateExercise(day.id, exercise, trackingTypeOf(exercise.exerciseId), issues)
            }
        }
        return issues
    }

    private fun validateExercise(
        dayId: String,
        exercise: PlannedExerciseDto,
        trackingType: TrackingType?,
        issues: MutableList<PlanIssue>,
    ) {
        if (trackingType == null) {
            issues += PlanIssue.UnknownExercise(dayId, exercise.id)
            return
        }
        fun setting(field: ExerciseSettingField) = PlanIssue.ExerciseSettingInvalid(dayId, exercise.id, field)
        exercise.restSeconds?.let { if (it !in PlanLimits.REST_SECONDS) issues += setting(ExerciseSettingField.REST) }
        exercise.tempo?.let { if (it.length > PlanLimits.TEMPO_MAX) issues += setting(ExerciseSettingField.TEMPO) }
        exercise.note?.let { if (it.length > PlanLimits.NOTE_MAX) issues += setting(ExerciseSettingField.NOTE) }

        if (exercise.sets.isEmpty()) issues += PlanIssue.ExerciseHasNoSets(dayId, exercise.id)
        for (set in exercise.sets) {
            fun issue(field: SetField, problem: SetProblem) =
                PlanIssue.SetValueInvalid(dayId, exercise.id, set.id, field, problem)

            val needsReps = trackingType == TrackingType.WEIGHT_REPS || trackingType == TrackingType.REPS
            if (needsReps) {
                val min = set.repsMin
                val max = set.repsMax ?: min
                when {
                    min == null -> issues += issue(SetField.REPS, SetProblem.REQUIRED)
                    min !in PlanLimits.REPS || max!! !in PlanLimits.REPS -> issues += issue(SetField.REPS, SetProblem.OUT_OF_RANGE)
                    min > max -> issues += issue(SetField.REPS, SetProblem.INVALID)
                }
            }
            if (trackingType == TrackingType.WEIGHT_REPS) {
                set.weightGrams?.let { if (it !in PlanLimits.WEIGHT_GRAMS) issues += issue(SetField.WEIGHT, SetProblem.OUT_OF_RANGE) }
            }
            when (trackingType) {
                TrackingType.TIME -> when (val d = set.durationSeconds) {
                    null -> issues += issue(SetField.DURATION, SetProblem.REQUIRED)
                    !in PlanLimits.DURATION_SECONDS -> issues += issue(SetField.DURATION, SetProblem.OUT_OF_RANGE)
                }
                TrackingType.DISTANCE_TIME -> {
                    when (val m = set.distanceMeters) {
                        null -> issues += issue(SetField.DISTANCE, SetProblem.REQUIRED)
                        !in PlanLimits.DISTANCE_METERS -> issues += issue(SetField.DISTANCE, SetProblem.OUT_OF_RANGE)
                    }
                    set.durationSeconds?.let {
                        if (it !in PlanLimits.DURATION_SECONDS) issues += issue(SetField.DURATION, SetProblem.OUT_OF_RANGE)
                    }
                }
                else -> Unit
            }
            set.rpe?.let { rpe ->
                when {
                    !exercise.rpeEnabled -> Unit // ignored by clients; the server drops it
                    rpe < PlanLimits.RPE_MIN || rpe > PlanLimits.RPE_MAX -> issues += issue(SetField.RPE, SetProblem.OUT_OF_RANGE)
                    (rpe * 2) % 1.0 != 0.0 -> issues += issue(SetField.RPE, SetProblem.INVALID)
                }
            }
        }
    }
}
