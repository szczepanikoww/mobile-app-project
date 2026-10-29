package com.trainingapp.contract.exercise

object ExerciseLimits {
    const val NAME_MIN = 2
    const val NAME_MAX = 80
    const val INSTRUCTIONS_MAX = 4_000
    const val SECONDARY_MUSCLES_MAX = 6
}

enum class ExerciseField { NAME, PRIMARY_MUSCLE, EQUIPMENT, TRACKING_TYPE, SECONDARY_MUSCLES, INSTRUCTIONS }

enum class ExerciseProblem { REQUIRED, TOO_SHORT, TOO_LONG, TOO_MANY, INVALID }

data class ExerciseIssue(val field: ExerciseField, val problem: ExerciseProblem)

/** The same rules run in the exercise form and on the server (`PUT /exercises/{id}`). */
object ExerciseValidator {

    fun validate(
        name: String,
        primaryMuscle: MuscleGroup?,
        equipment: Equipment?,
        trackingType: TrackingType?,
        secondaryMuscles: List<MuscleGroup> = emptyList(),
        instructions: String = "",
    ): List<ExerciseIssue> = buildList {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> add(ExerciseIssue(ExerciseField.NAME, ExerciseProblem.REQUIRED))
            trimmed.length < ExerciseLimits.NAME_MIN -> add(ExerciseIssue(ExerciseField.NAME, ExerciseProblem.TOO_SHORT))
            trimmed.length > ExerciseLimits.NAME_MAX -> add(ExerciseIssue(ExerciseField.NAME, ExerciseProblem.TOO_LONG))
        }
        if (primaryMuscle == null) add(ExerciseIssue(ExerciseField.PRIMARY_MUSCLE, ExerciseProblem.REQUIRED))
        if (equipment == null) add(ExerciseIssue(ExerciseField.EQUIPMENT, ExerciseProblem.REQUIRED))
        if (trackingType == null) add(ExerciseIssue(ExerciseField.TRACKING_TYPE, ExerciseProblem.REQUIRED))
        if (secondaryMuscles.size > ExerciseLimits.SECONDARY_MUSCLES_MAX) {
            add(ExerciseIssue(ExerciseField.SECONDARY_MUSCLES, ExerciseProblem.TOO_MANY))
        }
        if (primaryMuscle != null && primaryMuscle in secondaryMuscles) {
            add(ExerciseIssue(ExerciseField.SECONDARY_MUSCLES, ExerciseProblem.INVALID))
        }
        if (instructions.length > ExerciseLimits.INSTRUCTIONS_MAX) {
            add(ExerciseIssue(ExerciseField.INSTRUCTIONS, ExerciseProblem.TOO_LONG))
        }
    }

    fun validate(request: UpsertExerciseRequest): List<ExerciseIssue> {
        val names = request.names.values.ifEmpty { listOf("") }
        return names.flatMap { name ->
            validate(
                name = name,
                primaryMuscle = request.primaryMuscle,
                equipment = request.equipment,
                trackingType = request.trackingType,
                secondaryMuscles = request.secondaryMuscles,
                instructions = request.instructions.values.flatten().joinToString("\n"),
            )
        }.distinct()
    }
}
